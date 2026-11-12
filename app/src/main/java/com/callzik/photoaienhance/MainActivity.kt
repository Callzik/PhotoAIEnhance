package com.callzik.photoaienhance

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.work.*
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private lateinit var status: TextView
    private lateinit var toggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 60, 36, 24)
        }
        fun label(s: String): TextView = TextView(this).apply {
            text = s
            textSize = 18f
            setPadding(0, 8, 0, 16)
            root.addView(this)
        }
        label("Photo AI Enhance v1.2")
        label("Real-ESRGAN · ×2 · без интернета")
        status = label("")
        toggle = Button(this).apply { root.addView(this) }
        toggle.setOnClickListener {
            if (!hasPhotoPermission()) requestPermissions(arrayOf(Manifest.permission.READ_MEDIA_IMAGES), 100)
            else {
                val enabled = !prefs.getBoolean("enabled", false)
                prefs.edit().putBoolean("enabled", enabled).apply()
                configure(enabled)
                refresh()
            }
        }
        Button(this).apply {
            text = "Улучшить последнее фото ещё раз"
            setOnClickListener {
                if (hasPhotoPermission()) {
                    WorkManager.getInstance(this@MainActivity).enqueueUniqueWork(
                        "process-photo-manual",
                        ExistingWorkPolicy.KEEP,
                        OneTimeWorkRequestBuilder<PhotoWorker>()
                            .setInputData(workDataOf("force_latest" to true)).build()
                    )
                    status.text = "Повторная обработка последнего фото запрошена. Открой приложение позже для проверки результата."
                } else requestPermissions(arrayOf(Manifest.permission.READ_MEDIA_IMAGES), 100)
            }
            root.addView(this)
        }
        label("Новые снимки отслеживаются автоматически. Нейросеть Real-ESRGAN может работать несколько минут, Android может задерживать фоновый запуск. " +
            "Результаты: Pictures/AI Upscaled. Оригиналы остаются в галерее.")
        setContentView(root)
        if (prefs.getBoolean("enabled", false) && hasPhotoPermission()) configure(true)
        refresh()
    }

    private fun hasPhotoPermission() =
        checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED

    private fun configure(enabled: Boolean) {
        val wm = WorkManager.getInstance(this)
        if (enabled) {
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
        val enabled = prefs.getBoolean("enabled", false)
        toggle.text = if (enabled) "Отключить автообработку" else "Включить автообработку"
        val lastError = prefs.getString("last_error", null)
        status.text = (if (enabled) "Автообработка включена" else "Автообработка выключена") +
            (if (lastError != null) "\nПоследняя ошибка: $lastError" else "")
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