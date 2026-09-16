package dev.gazedot

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.*

object LandmarkFeatures {
    fun extract(p: List<NormalizedLandmark>, width: Int, height: Int): EyeFeatures? {
        if (p.size < 478) return null
        fun point(i: Int) = p[i].x().toDouble() * width to p[i].y().toDouble() * height
        fun eye(a: Int, b: Int, iris: Int, upper: Int, lower: Int): Pair<Double, Double>? {
            val c1 = point(a); val c2 = point(b); val center = point(iris)
            val dx = c2.first - c1.first; val dy = c2.second - c1.second
            val len = hypot(dx, dy)
            if (len < 12) return null
            val top = point(upper); val bottom = point(lower)
            if (hypot(top.first - bottom.first, top.second - bottom.second) / len < 0.10) return null
            val ix = center.first - (c1.first + c2.first) / 2
            val iy = center.second - (c1.second + c2.second) / 2
            return (ix * dx + iy * dy) / (len * len) to (-ix * dy + iy * dx) / (len * len)
        }
        // Both corner pairs have the same image-space direction. Images are NOT mirrored.
        val r = eye(33, 133, 468, 159, 145) ?: return null
        val l = eye(362, 263, 473, 386, 374) ?: return null
        val scale = hypot((p[33].x() - p[263].x()).toDouble(),
            (p[33].y() - p[263].y()).toDouble() * height / width)
        return EyeFeatures((r.first + l.first) / 2, (r.second + l.second) / 2,
            p[1].x().toDouble(), p[1].y().toDouble(), scale)
    }
}
