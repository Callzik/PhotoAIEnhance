package com.callzik.photoaienhance

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * ESPCN x2 (ONNX), trained by clibdev/ESPCN-PyTorch.
 * Inference is on Y (luminance). Chroma is interpolated from the source image.
 * Tiling limits transient tensor allocations and avoids seams using a padded input.
 */
object ModelEngine {
    private const val SCALE = 2
    private const val CORE = 96
    private const val PAD = 8
    private const val PATCH = CORE + 2 * PAD
    private const val OUT_PATCH = PATCH * SCALE
    private val env by lazy { OrtEnvironment.getEnvironment() }
    private var session: OrtSession? = null

    private fun model(context: Context): OrtSession {
        session?.let { return it }
        val bytes = context.assets.open("espcn-x2.onnx").use { it.readBytes() }
        val opts = OrtSession.SessionOptions()
        try {
            opts.setIntraOpNumThreads(2)
            val created = env.createSession(bytes, opts)
            session = created
            return created
        } finally {
            opts.close()
        }
    }

    // Serialize heavy operations to avoid simultaneous large images exhausting the app heap.
    @Synchronized
    fun upscale(context: Context, source: Bitmap): Bitmap {
        require(source.width > 0 && source.height > 0)
        val session = model(context)
        val w = source.width
        val h = source.height
        val src = IntArray(w * h)
        source.getPixels(src, 0, w, 0, 0, w, h)

        val result = Bitmap.createScaledBitmap(source, w * SCALE, h * SCALE, true)
        val input = ByteBuffer.allocateDirect(PATCH * PATCH * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()

        for (y0 in 0 until h step CORE) {
            for (x0 in 0 until w step CORE) {
                input.clear()
                for (py in 0 until PATCH) {
                    val sy = (y0 + py - PAD).coerceIn(0, h - 1)
                    for (px in 0 until PATCH) {
                        val sx = (x0 + px - PAD).coerceIn(0, w - 1)
                        val p = src[sy * w + sx]
                        // ITU-R BT.601 limited-range Y, matching ESPCN's training pipeline.
                        val yy = (16f + 65.481f * Color.red(p) / 255f +
                            128.553f * Color.green(p) / 255f +
                            24.966f * Color.blue(p) / 255f) / 255f
                        input.put(yy)
                    }
                }
                input.rewind()
                OnnxTensor.createTensor(env, input, longArrayOf(1, 1, PATCH.toLong(), PATCH.toLong())).use { t ->
                    session.run(mapOf("input" to t)).use { outputs ->
                        val neural = (outputs[0] as OnnxTensor).floatBuffer
                        val tw = minOf(CORE, w - x0) * SCALE
                        val th = minOf(CORE, h - y0) * SCALE
                        val tile = IntArray(tw * th)
                        result.getPixels(tile, 0, tw, x0 * SCALE, y0 * SCALE, tw, th)
                        for (dy in 0 until th) {
                            for (dx in 0 until tw) {
                                val i = dy * tw + dx
                                val pixel = tile[i]
                                val r = Color.red(pixel)
                                val g = Color.green(pixel)
                                val b = Color.blue(pixel)
                                val interpolatedY = (16f + 65.481f * r / 255f +
                                    128.553f * g / 255f + 24.966f * b / 255f) / 255f
                                val refinedY = neural.get((dy + PAD * SCALE) * OUT_PATCH + dx + PAD * SCALE)
                                val delta = (refinedY - interpolatedY) * 255f * 1.164383f
                                tile[i] = Color.rgb(
                                    (r + delta).roundToInt().coerceIn(0, 255),
                                    (g + delta).roundToInt().coerceIn(0, 255),
                                    (b + delta).roundToInt().coerceIn(0, 255)
                                )
                            }
                        }
                        result.setPixels(tile, 0, tw, x0 * SCALE, y0 * SCALE, tw, th)
                    }
                }
            }
        }
        return result
    }
}