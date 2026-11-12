package com.callzik.photoaienhance

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.work.Worker
import androidx.work.WorkerParameters

class PhotoWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val resolver = applicationContext.contentResolver
        val now = System.currentTimeMillis() / 1000L
        val since = prefs.getLong("last_scan", now - 60L)
        try {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.RELATIVE_PATH),
                "${MediaStore.Images.Media.DATE_ADDED} >= ?",
                arrayOf(since.toString()),
                "${MediaStore.Images.Media.DATE_ADDED} ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (isStopped) return Result.retry()
                    val id = cursor.getLong(0)
                    val path = cursor.getString(2) ?: ""
                    if (!path.contains("DCIM/Camera", ignoreCase = true) || prefs.getBoolean("processed_$id", false)) continue

                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    val orientation = resolver.openInputStream(uri)?.use { input ->
                        val exif = ExifInterface(input)
                        Pair(exif.rotationDegrees, exif.isFlipped)
                    } ?: Pair(0, false)
                    val bitmap = loadBitmap(uri) ?: continue
                    val matrix = Matrix().apply {
                        if (orientation.second) postScale(-1f, 1f)
                        if (orientation.first != 0) postRotate(orientation.first.toFloat())
                    }
                    val source = if (orientation.first != 0 || orientation.second) {
                        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                            if (it !== bitmap) bitmap.recycle()
                        }
                    } else bitmap

                    val enhanced = try {
                        ModelEngine.upscale(applicationContext, source)
                    } finally {
                        source.recycle()
                    }
                    try {
                        saveResult(id, enhanced)
                        prefs.edit().putBoolean("processed_$id", true).apply()
                    } finally {
                        enhanced.recycle()
                    }
                }
            }
            prefs.edit().putLong("last_scan", now - 2L).remove("last_error").apply()
            return Result.success()
        } catch (e: Exception) {
            prefs.edit().putString("last_error", e.message ?: e.javaClass.simpleName).apply()
            return Result.retry()
        }
    }

    private fun loadBitmap(uri: Uri): Bitmap? {
        val resolver = applicationContext.contentResolver
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null

        // Memory limit: high-resolution photos are reduced before neural inference.
        val sampleFactor = (maxOf(options.outWidth, options.outHeight) + 2047) / 2048
        var sample = 1
        while (sample < sampleFactor) sample *= 2
        return resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        }
    }

    private fun saveResult(id: Long, bitmap: Bitmap) {
        val resolver = applicationContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "AI_$id.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AI Upscaled")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val target = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Unable to create output image")
        try {
            val saved = resolver.openOutputStream(target)?.use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it)
            } ?: false
            check(saved) { "JPEG compression failed" }
            resolver.update(target, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(target, null, null)
            throw e
        }
    }
}