package dev.gazedot

import kotlin.math.*

data class EyeFeatures(val x: Double, val y: Double, val faceX: Double, val faceY: Double, val scale: Double,
    val leftX: Double = x, val leftY: Double = y, val rightX: Double = x, val rightY: Double = y,
    val yawProxy: Double = 0.0, val pitchProxy: Double = 0.0, val roll: Double = 0.0,
    val distanceCm: Double = Double.NaN, val headYaw: Double = Double.NaN, val headPitch: Double = Double.NaN) {
    fun depthVector() = vector() + doubleArrayOf(distanceCm / 40.0, headYaw, headPitch)
    fun vector() = doubleArrayOf(leftX, leftY, rightX, rightY, faceX, faceY, scale, yawProxy, pitchProxy, roll)
    companion object {
        val names = listOf("left_x", "left_y", "right_x", "right_y", "face_x", "face_y", "scale", "yaw_proxy", "pitch_proxy", "roll")
        fun from(v: DoubleArray) = EyeFeatures((v[0]+v[2])/2, (v[1]+v[3])/2, v[4], v[5], v[6], v[0], v[1], v[2], v[3], v[7], v[8], v[9])
    }
}
interface GazePredictor { fun predict(f: EyeFeatures): Pair<Double, Double>? }

/** Uncalibrated iris pointer. Fixed gains are a usable starting mapping, not measured gaze accuracy.
 * Eye coordinates already remove face translation, scale and in-plane roll.
 * Front-camera images are unmirrored: image-left corresponds to the user's screen-right.
 */
object BasicGazePredictor : GazePredictor {
    override fun predict(f: EyeFeatures): Pair<Double, Double>? {
        if (!f.vector().all { it.isFinite() } || f.scale <= 0) return null
        val x = (f.leftX + f.rightX) / 2
        val y = (f.leftY + f.rightY) / 2
        return (0.5 - 4.0 * x).coerceIn(-0.3, 1.3) to
            (0.5 + 6.0 * (y - 0.02)).coerceIn(-0.3, 1.3)
    }
}
data class Anchor(val eye: EyeFeatures, val x: Double, val y: Double)

/** Calibration maps iris displacement inside the eye to display coordinates.
 * This is a calibrated approximation, not a pretrained screen-gaze model. */
class GazeModel private constructor(
    private val meanX: Double, private val meanY: Double,
    private val stdX: Double, private val stdY: Double,
    private val wx: DoubleArray, private val wy: DoubleArray,
    private val reference: EyeFeatures,
    val fitError: Double,
) : GazePredictor {
    override fun predict(f: EyeFeatures): Pair<Double, Double>? {
        if (!listOf(f.x, f.y, f.faceX, f.faceY, f.scale).all { it.isFinite() }) return null
        // A changed pose/distance invalidates this simple calibration. Do not invent a point.
        if (f.scale !in reference.scale * 0.72..reference.scale * 1.28 ||
            abs(f.faceX - reference.faceX) > 0.12 || abs(f.faceY - reference.faceY) > 0.12) return null
        val v = doubleArrayOf(1.0, (f.x - meanX) / stdX, (f.y - meanY) / stdY)
        // Preserve outside-screen estimates for scroll intent; clamp only when drawing.
        return dot(wx, v) to dot(wy, v)
    }

    companion object {
        fun fit(anchors: List<Anchor>): GazeModel? {
            if (anchors.size < 5 || anchors.any { a ->
                !listOf(a.eye.x, a.eye.y, a.eye.faceX, a.eye.faceY, a.eye.scale, a.x, a.y).all { it.isFinite() }
            }) return null
            val mx = anchors.map { it.eye.x }.average()
            val my = anchors.map { it.eye.y }.average()
            val sx = sqrt(anchors.map { (it.eye.x - mx).pow(2) }.average())
            val sy = sqrt(anchors.map { (it.eye.y - my).pow(2) }.average())
            if (sx < 0.008 || sy < 0.006) return null
            val rows = anchors.map { doubleArrayOf(1.0, (it.eye.x - mx) / sx, (it.eye.y - my) / sy) }
            val wx = solve(rows, anchors.map { it.x }) ?: return null
            val wy = solve(rows, anchors.map { it.y }) ?: return null
            val error = sqrt(rows.indices.map { i ->
                (dot(wx, rows[i]) - anchors[i].x).pow(2) + (dot(wy, rows[i]) - anchors[i].y).pow(2)
            }.average())
            if (!error.isFinite() || error > 0.18) return null
            val f = EyeFeatures(mx, my, anchors.map { it.eye.faceX }.average(),
                anchors.map { it.eye.faceY }.average(), anchors.map { it.eye.scale }.average())
            return GazeModel(mx, my, sx, sy, wx, wy, f, error)
        }
        private fun dot(a: DoubleArray, b: DoubleArray) = a.indices.sumOf { a[it] * b[it] }
        private fun solve(rows: List<DoubleArray>, targets: List<Double>): DoubleArray? {
            val a = Array(3) { i -> DoubleArray(4) { j ->
                if (j == 3) rows.indices.sumOf { rows[it][i] * targets[it] }
                else rows.sumOf { it[i] * it[j] } + if (i == j && i != 0) 0.03 else 0.0
            } }
            for (col in 0..2) {
                val pivot = (col..2).maxBy { abs(a[it][col]) }
                if (abs(a[pivot][col]) < 1e-9) return null
                val tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp
                val divisor = a[col][col]
                for (j in col..3) a[col][j] /= divisor
                for (i in 0..2) if (i != col) {
                    val factor = a[i][col]
                    for (j in col..3) a[i][j] -= factor * a[col][j]
                }
            }
            return DoubleArray(3) { a[it][3] }
        }
    }
}

fun medianFeatures(samples: List<EyeFeatures>): EyeFeatures {
    require(samples.isNotEmpty())
    fun med(values: List<Double>) = values.filter { it.isFinite() }.sorted().let { if(it.isEmpty()) Double.NaN else it[it.size/2] }
    return EyeFeatures.from(DoubleArray(EyeFeatures.names.size) { i -> med(samples.map { it.vector()[i] }) }).copy(
        distanceCm=med(samples.map { it.distanceCm }), headYaw=med(samples.map { it.headYaw }), headPitch=med(samples.map { it.headPitch }))
}
