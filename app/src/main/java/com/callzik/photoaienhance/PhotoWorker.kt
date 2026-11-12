package com.callzik.photoaienhance
import android.content.*
import android.graphics.*
import android.provider.MediaStore
import androidx.work.*

class PhotoWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val resolver = applicationContext.contentResolver
        val now = System.currentTimeMillis() / 1000
        val since = prefs.getLong("last_scan", now - 60)
        try {
            resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.RELATIVE_PATH),
                "${MediaStore.Images.Media.DATE_ADDED} >= ?", arrayOf(since.toString()),
                "${MediaStore.Images.Media.DATE_ADDED} ASC")?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (isStopped) return Result.retry()
                    val id = cursor.getLong(0)
                    val path = cursor.getString(2) ?: ""
                    if (!path.contains("DCIM/Camera", true) || prefs.getBoolean("processed_$id", false)) continue
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                    val sample = maxOf(1, (maxOf(options.outWidth, options.outHeight) + 2047) / 2048)
                    val source = resolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                    } ?: continue
                    val result = try { ModelEngine.upscale(source) } finally { source.recycle() }
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "AI_$id.jpg")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AI Upscaled")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val target = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return Result.retry()
                    try {
                        resolver.openOutputStream(target)?.use { result.compress(Bitmap.CompressFormat.JPEG, 94, it) }
                        resolver.update(target, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                        prefs.edit().putBoolean("processed_$id", true).apply()
                    } catch (e: Exception) { resolver.delete(target, null, null); return Result.retry() }
                    finally { result.recycle() }
                }
            }
            prefs.edit().putLong("last_scan", now - 2).apply()
            return Result.success()
        } catch (e: Exception) { return Result.retry() }
    }
}