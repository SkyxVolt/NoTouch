package dev.gazedot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/** MobileGaze MobileOne S0, Gaze360-trained weights, bundled in the APK.
 * RGB / ImageNet normalization and 90-bin decoding match upstream ONNX inference.
 * Called and closed only on the camera worker; no camera data leaves the device.
 */
class PretrainedGaze(context: Context) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(2)
        options.setInterOpNumThreads(1)
        environment.createSession(context.assets.open("mobileone_s0_gaze.onnx").use { it.readBytes() }, options)
    }
    private val pixels = IntArray(448 * 448)
    private val input = FloatBuffer.allocate(3 * 448 * 448)

    fun estimate(bitmap: Bitmap, bounds: Rect): GazeDirection? {
        if (bounds.width() < 32 || bounds.height() < 32) return null
        val crop = Bitmap.createBitmap(bitmap, bounds.left, bounds.top, bounds.width(), bounds.height())
        val resized = Bitmap.createScaledBitmap(crop, 448, 448, true)
        try {
            resized.getPixels(pixels, 0, 448, 0, 0, 448, 448)
            input.clear()
            val means = floatArrayOf(.485f,.456f,.406f)
            val deviations = floatArrayOf(.229f,.224f,.225f)
            for (channel in 0..2) for (pixel in pixels) {
                val value = (pixel shr (16 - channel * 8)) and 255
                input.put((value / 255f - means[channel]) / deviations[channel])
            }
            input.flip()
            OnnxTensor.createTensor(environment, input, longArrayOf(1,3,448,448)).use { tensor ->
                session.run(mapOf("input" to tensor)).use { outputs ->
                    @Suppress("UNCHECKED_CAST")
                    val yaw = (outputs.get("yaw").get().value as Array<FloatArray>)[0]
                    @Suppress("UNCHECKED_CAST")
                    val pitch = (outputs.get("pitch").get().value as Array<FloatArray>)[0]
                    return GazeAngles.decode(yaw, pitch)
                }
            }
        } finally {
            if (resized !== crop && resized !== bitmap) resized.recycle()
            if (crop !== bitmap) crop.recycle()
        }
    }
    override fun close() { session.close() }
}
