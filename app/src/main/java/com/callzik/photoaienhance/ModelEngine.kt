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
 * Real-ESRGAN General x4v3 (RGB). Native ONNX resolution ×4,
 * averaged to ×2 output. Small overlapping tiles prevent huge tensors.
 *
 * Input/output: float32 NCHW, channel order RGB, [0..1] input.
 * Original model: xinntao/Real-ESRGAN, BSD-3-Clause.
 */
object ModelEngine {
    private const val CORE = 96
    private const val PAD = 20
    private const val PATCH = CORE + PAD * 2
    private const val NETWORK_SCALE = 4
    private const val EXPORT_SCALE = 2
    private const val OUT_PATCH = PATCH * NETWORK_SCALE

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var session: OrtSession? = null

    private fun getSession(context: Context): OrtSession {
        session?.let { return it }
        val bytes = context.assets.open("realesr-general-x4v3.onnx").use { it.readBytes() }
        val options = OrtSession.SessionOptions()
        try {
            options.setIntraOpNumThreads(2)
            val created = env.createSession(bytes, options)
            session = created
            return created
        } finally {
            options.close()
        }
    }

    @Synchronized
    fun upscale(context: Context, source: Bitmap): Bitmap {
        val network = getSession(context)
        val width = source.width
        val height = source.height
        require(width > 0 && height > 0)
        val result = Bitmap.createBitmap(width * EXPORT_SCALE, height * EXPORT_SCALE, Bitmap.Config.ARGB_8888)

        val floatInput = ByteBuffer.allocateDirect(3 * PATCH * PATCH * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        val tensorDimensions = longArrayOf(1L, 3L, PATCH.toLong(), PATCH.toLong())
        val channelSize = PATCH * PATCH
        val outputPlane = OUT_PATCH * OUT_PATCH

        for (top in 0 until height step CORE) {
            for (left in 0 until width step CORE) {
                // Load only the pixels needed for this tile, with edge replication.
                val startX = (left - PAD).coerceAtLeast(0)
                val startY = (top - PAD).coerceAtLeast(0)
                val endX = (left + CORE + PAD).coerceAtMost(width)
                val endY = (top + CORE + PAD).coerceAtMost(height)
                val regionW = endX - startX
                val regionH = endY - startY
                val region = IntArray(regionW * regionH)
                source.getPixels(region, 0, regionW, startX, startY, regionW, regionH)

                floatInput.clear()
                for (dy in 0 until PATCH) {
                    val sy = (top + dy - PAD).coerceIn(0, height - 1)
                    for (dx in 0 until PATCH) {
                        val sx = (left + dx - PAD).coerceIn(0, width - 1)
                        val px = region[(sy - startY) * regionW + sx - startX]
                        val index = dy * PATCH + dx
                        floatInput.put(index, Color.red(px) / 255f)
                        floatInput.put(channelSize + index, Color.green(px) / 255f)
                        floatInput.put(2 * channelSize + index, Color.blue(px) / 255f)
                    }
                }
                floatInput.rewind()

                OnnxTensor.createTensor(env, floatInput, tensorDimensions).use { inputTensor ->
                    network.run(mapOf("input" to inputTensor)).use { outputs ->
                        val outputTensor = outputs[0] as OnnxTensor
                        val output = outputTensor.floatBuffer
                        val croppedW = minOf(CORE, width - left) * EXPORT_SCALE
                        val croppedH = minOf(CORE, height - top) * EXPORT_SCALE
                        val pixels = IntArray(croppedW * croppedH)

                        for (y in 0 until croppedH) {
                            val oy = PAD * NETWORK_SCALE + y * 2
                            for (x in 0 until croppedW) {
                                val ox = PAD * NETWORK_SCALE + x * 2
                                val pos = oy * OUT_PATCH + ox
                                fun sample(plane: Int): Int {
                                    val i = plane * outputPlane + pos
                                    val average = (output.get(i) + output.get(i + 1) +
                                        output.get(i + OUT_PATCH) + output.get(i + OUT_PATCH + 1)) * 0.25f
                                    return (average * 255f).roundToInt().coerceIn(0, 255)
                                }
                                pixels[y * croppedW + x] = Color.rgb(sample(0), sample(1), sample(2))
                            }
                        }
                        result.setPixels(pixels, 0, croppedW, left * EXPORT_SCALE, top * EXPORT_SCALE, croppedW, croppedH)
                    }
                }
            }
        }
        return result
    }
}