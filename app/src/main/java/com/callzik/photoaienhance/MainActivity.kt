package com.callzik.photoaienhance
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.*
import androidx.work.*
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private lateinit var status: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 60, 36, 24) }
        fun label(s: String) = TextView(this).apply { text = s; textSize = 18f; setPadding(0, 8, 0, 16); root.addView(this) }
        label("Photo AI Enhance v1.0")
        label("Сбалансированный · ×2 · без облака")
        status = label("")
        val toggle = Button(this).apply { root.addView(this) }
        fun schedule(on: Boolean) {
            val manager = WorkManager.getInstance(this)
            if (on) manager.enqueueUniquePeriodicWork("scan", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<PhotoWorker>(15, TimeUnit.MINUTES).build())
            else manager.cancelUniqueWork("scan")
        }
        fun refresh() {
            val on = prefs.getBoolean("enabled", false)
            toggle.text = if (on) "Отключить автообработку" else "Включить автообработку"
            status.text = "Автообработка: " + if (on) "включена" else "выключена"
        }
        toggle.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.READ_MEDIA_IMAGES), 100)
            } else {
                val on = !prefs.getBoolean("enabled", false)
                prefs.edit().putBoolean("enabled", on).apply()
                schedule(on); refresh()
            }
        }
        Button(this).apply {
            text = "Обработать новые фото сейчас"
            setOnClickListener {
                if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED)
                    WorkManager.getInstance(this@MainActivity).enqueue(OneTimeWorkRequestBuilder<PhotoWorker>().build())
                else requestPermissions(arrayOf(Manifest.permission.READ_MEDIA_IMAGES), 100)
            }
            root.addView(this)
        }
        label("Результаты: Pictures/AI Upscaled. Оригиналы сохраняются. Проверка новых фото примерно каждые 15 минут или позже.")
        setContentView(root)
        if (prefs.getBoolean("enabled", false)) schedule(true)
        refresh()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean("enabled", true).apply()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork("scan", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<PhotoWorker>(15, TimeUnit.MINUTES).build())
            status.text = "Автообработка включена"
        }
    }
}