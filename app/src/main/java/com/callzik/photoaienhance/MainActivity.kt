package com.callzik.photoaienhance

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.work.*
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var addressField: EditText
    private lateinit var tokenField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 36, 32, 32)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        fun label(value: String, large: Boolean = false): TextView = TextView(this).apply {
            text = value
            textSize = if (large) 23f else 16f
            setPadding(0, 7, 0, 11)
            root.addView(this)
        }
        fun button(value: String, clicked: () -> Unit) {
            root.addView(Button(this).apply {
                text = value
                setAllCaps(false)
                setOnClickListener { clicked() }
            })
        }

        label("Photo AI Enhance v1.3", true)
        label("Обработка на ПК с GTX 1650 · ×2 · через Tailscale (Wi‑Fi или 4G/5G)")
        status = label("")
        label("Адрес ПК в Tailscale")
        addressField = EditText(this).apply {
            hint = "http://100.x.y.z:8765"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString("server_url", "") ?: "")
            root.addView(this)
        }
        label("Ключ подключения (показан в окне сервера)")
        tokenField = EditText(this).apply {
            hint = "Секретный ключ с ПК"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("server_token", "") ?: "")
            root.addView(this)
        }
        button("Сохранить настройки подключения") {
            val url = addressField.text.toString().trim()
            val token = tokenField.text.toString().trim()
            try {
                require(url.isNotBlank()) { "Введи адрес ПК" }
                PcApi.validateAddress(url)
                require(token.length >= 24) { "Введи полный ключ из окна сервера" }
                prefs.edit().putString("server_url", url.removeSuffix("/"))
                    .putString("server_token", token).apply()
                status.text = "Режим ПК сохранён. Нажми «Проверить соединение»."
            } catch (e: Exception) {
                status.text = "Ошибка настройки: " + e.message
            }
        }
        button("Проверить соединение с ПК") {
            status.text = "Проверка соединения с ПК…"
            val url = addressField.text.toString().trim()
            val token = tokenField.text.toString().trim()
            Thread {
                val message = try {
                    val response = PcApi.test(url, token)
                    "Соединение установлено: " + response
                } catch (e: Exception) {
                    "Нет соединения: " + e.message
                }
                runOnUiThread { status.text = message }
            }.start()
        }
        button("Переключить на локальное улучшение (без ПК)") {
            prefs.edit().remove("server_url").remove("server_token").apply()
            addressField.setText("")
            tokenField.setText("")
            status.text = "Выбран локальный Real-ESRGAN на телефоне."
        }

        label("Автоматическое улучшение новых фотографий")
        toggle = Button(this).apply { setAllCaps(false); root.addView(this) }
        toggle.setOnClickListener {
            if (!hasPhotoPermission()) {
                requestPermissions(arrayOf(Manifest.permission.READ_MEDIA_IMAGES), 100)
            } else {
                val enable = !prefs.getBoolean("enabled", false)
                prefs.edit().putBoolean("enabled", enable).apply()
                configure(enable)
                refresh()
            }
        }
        button("Улучшить последнее фото ещё раз") {
            if (!hasPhotoPermission()) {
                requestPermissions(arrayOf(Manifest.permission.READ_MEDIA_IMAGES), 100)
            } else {
                WorkManager.getInstance(this).enqueueUniqueWork(
                    "process-photo-manual",
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<PhotoWorker>()
                        .setInputData(workDataOf("force_latest" to true))
                        .build()
                )
                status.text = "Запрос создан. После обработки фото появится в альбоме AI Upscaled."
            }
        }
        label("Оригиналы остаются нетронутыми. Результат: Pictures/AI Upscaled. " +
            "Для режима ПК оба устройства должны быть в Tailscale, ПК включён. " +
            "Android может задерживать фоновую обработку. Снимки передаются только твоему ПК.")

        setContentView(scroll)
        if (prefs.getBoolean("enabled", false) && hasPhotoPermission()) configure(true)
        refresh()
    }

    private fun hasPhotoPermission(): Boolean =
        checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED

    private fun configure(on: Boolean) {
        val wm = WorkManager.getInstance(this)
        if (on) {
            PhotoWatchWorker.arm(this)
            wm.enqueueUniquePeriodicWork(
                "scan",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<PhotoWorker>(15, TimeUnit.MINUTES).build()
            )
            wm.enqueueUniqueWork(
                "scan-on-enable",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<PhotoWorker>().build()
            )
        } else {
            wm.cancelUniqueWork("watch-new-photos")
            wm.cancelUniqueWork("scan")
            wm.cancelUniqueWork("process-photo-change")
        }
    }

    private fun refresh() {
        val on = prefs.getBoolean("enabled", false)
        toggle.text = if (on) "Выключить автообработку" else "Включить автообработку"
        val mode = if (prefs.getString("server_url", "").isNullOrBlank()) "локально" else "через ПК"
        status.text = "Режим: " + mode + ". Автообработка: " +
            (if (on) "включена" else "выключена") + "." +
            (prefs.getString("last_error", null)?.let { "\nПоследняя ошибка: " + it } ?: "")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && hasPhotoPermission()) {
            prefs.edit().putBoolean("enabled", true).apply()
            configure(true)
            refresh()
        }
    }
}