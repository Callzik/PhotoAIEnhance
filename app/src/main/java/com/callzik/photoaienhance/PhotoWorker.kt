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
        val forceLatest = inputData.getBoolean("force_latest", false)
        val now = System.currentTimeMillis() / 1000L
        val since = if (forceLatest) now - 60L * 60L * 24L * 365L else prefs.getLong("last_scan", now - 60L)
        try {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.RELATIVE_PATH),
                "${MediaStore.Images.Media.DATE_ADDED} >= ?",
                arrayOf(since.toString()),
                if (forceLatest) "${MediaStore.Images.Media.DATE_ADDED} DESC" else "${MediaStore.Images.Media.DATE_ADDED} ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (isStopped) return Result.retry()
                    val id = cursor.getLong(0)
                    val path = cursor.getString(2) ?: ""
                    if (!path.contains("DCIM/Camera", ignoreCase = true)) continue
                    if (!forceLatest && prefs.getBoolean("processed_$id", false)) continue

                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    val pcAddress = prefs.getString("server_url", "")?.trim().orEmpty()
                    if (pcAddress.isNotEmpty()) {
                        val token = prefs.getString("server_token", "").orEmpty()
                        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: continue
                        val enhanced = PcApi.enhance(pcAddress, token, bytes)
                        saveNetworkResult(id, enhanced)
                    } else {
                        // Offline fallback: same Real-ESRGAN model as v1.2, on phone CPU.
                        val orientation = resolver.openInputStream(uri)?.use { input ->
                            val exif = ExifInterface(input)
                            Pair(exif.rotationDegrees, exif.isFlipped)
                        } ?: Pair(0, false)
                        val bitmap = loadBitmap(uri) ?: continue
                        val transform = Matrix().apply {
                            if (orientation.second) postScale(-1f, 1f)
                            if (orientation.first != 0) postRotate(orientation.first.toFloat())
                        }
                        val source = if (orientation.first != 0 || orientation.second) {
                            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true)
                                .also { if (it !== bitmap) bitmap.recycle() }
                        } else bitmap
                        val enhanced = try {
                            ModelEngine.upscale(applicationContext, source)
                        } finally {
                            source.recycle()
                        }
                        try {
                            saveResult(id, enhanced)
                        } finally {
                            enhanced.recycle()
                        }
                    }
                    prefs.edit()
                        .putBoolean("processed_$id", true)
                        .putLong("last_success", System.currentTimeMillis())
                        .putLong("last_processed_id", id)
                        .apply()
                    if (forceLatest) break
                }
            }
            if (!forceLatest) prefs.edit().putLong("last_scan", now - 2L).apply()
            prefs.edit().remove("last_error").apply()
            return Result.success()
        } catch (e: Exception) {
            prefs.edit().putString("last_error", e.message ?: e.javaClass.simpleName).apply()
            return Result.retry()
        }
    }

    private fun saveNetworkResult(id: Long, jpeg: ByteArray) {
        val resolver = applicationContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "AI_PC_v13_${id}_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AI Upscaled")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val target = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Не удалось создать фото")
        try {
            resolver.openOutputStream(target)?.use { it.write(jpeg) }
                ?: throw IllegalStateException("Не удалось открыть файл для сохранения")
            resolver.update(target, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null)
        } catch (e: Exception) {
            resolver.delete(target, null, null)
            throw e
        }
    }

    private fun loadBitmap(uri: Uri): Bitmap? {
        val resolver = applicationContext.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // 3072 px long side ×2 output gives up to 6144 px; more pixels than
        // the previous 2048 px / power-of-two downsample of v1.1.
        // Cap input to avoid excessive memory use on the A56.
        val maxSide = 3072
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2

        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } ?: return null
        val longest = maxOf(decoded.width, decoded.height)
        if (longest <= maxSide) return decoded
        val ratio = maxSide.toFloat() / longest
        val resized = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * ratio).toInt().coerceAtLeast(1),
            (decoded.height * ratio).toInt().coerceAtLeast(1),
            true
        )
        if (resized !== decoded) decoded.recycle()
        return resized
    }

    private fun saveResult(id: Long, bitmap: Bitmap) {
        val resolver = applicationContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "AI_local_v13_${id}_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AI Upscaled")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val target = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Не удалось создать файл изображения")
        try {
            val saved = resolver.openOutputStream(target)?.use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)
            } ?: false
            check(saved) { "Не удалось сохранить JPEG" }
            resolver.update(target, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(target, null, null)
            throw e
        }
    }
}