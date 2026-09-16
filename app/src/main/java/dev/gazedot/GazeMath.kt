package dev.gazedot

import kotlin.math.*

data class EyeFeatures(val x: Double, val y: Double, val faceX: Double, val faceY: Double, val scale: Double)
data class Anchor(val eye: EyeFeatures, val x: Double, val y: Double)

/** Calibration maps iris displacement inside the eye to display coordinates.
 * This is a calibrated approximation, not a pretrained screen-gaze model. */
class GazeModel private constructor(
    private val meanX: Double, private val meanY: Double,
    private val stdX: Double, private val stdY: Double,
    private val wx: DoubleArray, private val wy: DoubleArray,
    private val reference: EyeFeatures,
    val fitError: Double,
) {
    fun predict(f: EyeFeatures): Pair<Double, Double>? {
        if (!listOf(f.x, f.y, f.faceX, f.faceY, f.scale).all { it.isFinite() }) return null
        // A changed pose/distance invalidates this simple calibration. Do not invent a point.
        if (f.scale !in reference.scale * 0.72..reference.scale * 1.28 ||
            abs(f.faceX - reference.faceX) > 0.12 || abs(f.faceY - reference.faceY) > 0.12) return null
        val v = doubleArrayOf(1.0, (f.x - meanX) / stdX, (f.y - meanY) / stdY)
        return dot(wx, v).coerceIn(0.0, 1.0) to dot(wy, v).coerceIn(0.0, 1.0)
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
    fun median(selector: (EyeFeatures) -> Double): Double = samples.map(selector).sorted()[samples.size / 2]
    return EyeFeatures(median { it.x }, median { it.y }, median { it.faceX }, median { it.faceY }, median { it.scale })
}
