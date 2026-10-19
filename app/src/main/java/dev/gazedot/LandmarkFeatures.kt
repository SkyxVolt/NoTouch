package dev.gazedot

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.*

data class EyeObservation(val state: EyeState, val features: EyeFeatures?, val leftOpen: Double, val rightOpen: Double)

object LandmarkFeatures {
    fun observe(p: List<NormalizedLandmark>, width: Int, height: Int, focal: Pair<Double,Double>? = null, transform: FloatArray? = null): EyeObservation {
        val lost = EyeObservation(EyeState.LOST, null, 0.0, 0.0)
        if (p.size < 478 || width <= 0 || height <= 0 ||
            p.take(478).any { !it.x().isFinite() || !it.y().isFinite() }) return lost
        fun pt(i: Int) = p[i].x().toDouble() * width to p[i].y().toDouble() * height
        fun eye(a: Int, b: Int, iris: Int, top: Int, bottom: Int): DoubleArray? {
            val a0=pt(a); val b0=pt(b); val c=pt(iris); val t=pt(top); val u=pt(bottom)
            val dx=b0.first-a0.first; val dy=b0.second-a0.second; val length=hypot(dx,dy)
            if (length < 12) return null
            val ix=c.first-(a0.first+b0.first)/2; val iy=c.second-(a0.second+b0.second)/2
            return doubleArrayOf((ix*dx+iy*dy)/(length*length),(-ix*dy+iy*dx)/(length*length),hypot(t.first-u.first,t.second-u.second)/length)
        }
        val r=eye(33,133,468,159,145) ?: return lost
        val l=eye(362,263,473,386,374) ?: return lost
        val state=when {
            r[2]<.11 && l[2]<.11 -> EyeState.CLOSED
            r[2]>.15 && l[2]>.15 -> EyeState.OPEN
            else -> EyeState.UNCERTAIN
        }
        val a=pt(33); val b=pt(263); val n=pt(1)
        val span=hypot(b.first-a.first,b.second-a.second)
        if (span < 24) return lost
        val pose=DepthGeometry.pose(transform)
        fun diameter(a: Int,b: Int): Pair<Double,Double> { val v=pt(a); val w=pt(b); return v.first-w.first to v.second-w.second }
        val distance=if(focal!=null && pose!=null && abs(pose.first)<.45 && abs(pose.second)<.45)
            DepthGeometry.distance(diameter(469,471),diameter(474,476),focal) else null
        val f=if (state != EyeState.OPEN) null else EyeFeatures(
            (r[0]+l[0])/2,(r[1]+l[1])/2,p[1].x().toDouble(),p[1].y().toDouble(),span/width,
            l[0],l[1],r[0],r[1],
            (n.first-(a.first+b.first)/2)/span,(n.second-(a.second+b.second)/2)/span,
            atan2(b.second-a.second,b.first-a.first), distance ?: Double.NaN, pose?.first ?: Double.NaN, pose?.second ?: Double.NaN)
        return EyeObservation(state,f,l[2],r[2])
    }
}
