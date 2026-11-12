package com.callzik.photoaienhance

import android.content.Context
import android.provider.MediaStore
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * One-shot ContentUri-triggered worker. Reschedules its own observation after each
 * change, while the periodic job remains a fallback when Android batches events.
 */
class PhotoWatchWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", false)) return Result.success()

        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "process-photo-change",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<PhotoWorker>().build()
        )
        arm(applicationContext, append = true)
        return Result.success()
    }

    companion object {
        fun arm(context: Context, append: Boolean = false) {
            val constraints = Constraints.Builder()
                .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                .setTriggerContentUpdateDelay(1, TimeUnit.SECONDS)
                .setTriggerContentMaxDelay(10, TimeUnit.SECONDS)
                .build()
            val watch = OneTimeWorkRequestBuilder<PhotoWatchWorker>()
                .setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "watch-new-photos",
                if (append) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
                watch
            )
        }
    }
}