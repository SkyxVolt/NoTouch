package dev.gazedot

import kotlin.math.*

data class GazeDirection(val yaw: Double, val pitch: Double) {
    /** Default angle-to-pointer mapping, not personal visual-axis/camera calibration.
     * Horizontal is mirrored into the user's screen; image vertical is unchanged.
     * Preserve offscreen estimates for scrolling. Reject backward-facing gaze.
     */
    fun screenPoint(): Pair<Double,Double>? {
        if (!yaw.isFinite() || !pitch.isFinite() || abs(yaw) > PI/3 || abs(pitch) > PI/3) return null
        return (.5 + 2.5 * tan(yaw)).coerceIn(-.3,1.3) to
            (.5 - 2.5 * tan(pitch)).coerceIn(-.3,1.3)
    }
}

object GazeAngles {
    fun decode(yaw: FloatArray, pitch: FloatArray): GazeDirection? {
        fun angle(logits: FloatArray): Double? {
            if (logits.size != 90 || logits.any { !it.isFinite() }) return null
            val maximum=logits.max().toDouble()
            val weights=logits.map { exp(it.toDouble()-maximum) }
            return Math.toRadians(weights.indices.sumOf { it*weights[it] } / weights.sum() * 4 - 180)
        }
        return GazeDirection(angle(yaw) ?: return null, angle(pitch) ?: return null)
    }
}
