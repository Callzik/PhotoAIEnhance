package com.callzik.photoaienhance
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

object ModelEngine {
    // v1.0 fallback: standard interpolation, not neural inference.
    fun upscale(source: Bitmap): Bitmap {
        val width = source.width * 2
        val height = source.height * 2
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(source, null, android.graphics.Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
        return output
    }
}