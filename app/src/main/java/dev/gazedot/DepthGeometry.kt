package dev.gazedot

import kotlin.math.*

/** Approximate axial camera distance, not MediaPipe landmark z or biometric identification. */
object DepthGeometry {
    fun focalPixels(focalMm: Double, sensorWidthMm: Double, sensorHeightMm: Double,
                    width: Int, height: Int, rotation: Int): Pair<Double, Double>? {
        if (listOf(focalMm,sensorWidthMm,sensorHeightMm).any { !it.isFinite() || it<=0 } || width<=0 || height<=0) return null
        // A processed stream center-crops the sensor to the stream aspect ratio.
        val pixelsPerMm=max(width/sensorWidthMm,height/sensorHeightMm)
        val f=focalMm*pixelsPerMm
        require(rotation in listOf(0,90,180,270))
        // Square sensor pixels: rotation swaps equal focal scales.
        return f to f
    }
    fun distance(left: Pair<Double,Double>, right: Pair<Double,Double>, focal: Pair<Double,Double>): Double? {
        if(focal.first<=0 || focal.second<=0) return null
        fun estimate(v: Pair<Double,Double>): Double = 1.17 / hypot(v.first/focal.first,v.second/focal.second)
        if(hypot(left.first,left.second)<4 || hypot(right.first,right.second)<4) return null
        val a=estimate(left); val b=estimate(right)
        if(!a.isFinite() || !b.isFinite() || a !in 15.0..100.0 || b !in 15.0..100.0 || abs(a-b)/min(a,b)>.25) return null
        return (a+b)/2
    }
    /** MediaPipe's canonical-face transform is column-major. Angles are radians. */
    fun pose(m: FloatArray?): Pair<Double,Double>? {
        if(m==null || m.size!=16 || m.any { !it.isFinite() }) return null
        val norm=sqrt((m[0]*m[0]+m[1]*m[1]+m[2]*m[2]).toDouble())
        if(norm<1e-6) return null
        val yaw=asin((-m[2]/norm).coerceIn(-1.0,1.0))
        val pitch=atan2(m[6].toDouble(),m[10].toDouble())
        return yaw to pitch
    }
}

/** Variable-rate smoothing: quiet fixations are steadier; large moves follow quickly. */
class GazeStabilizer {
    private var last: Pair<Double,Double>?=null
    private var at=0L
    fun reset() { last=null; at=0 }
    fun update(p: Pair<Double,Double>, now: Long): Pair<Double,Double> {
        val old=last
        if(old==null || now-at>250 || now<=at) { last=p; at=now; return p }
        val dt=(now-at)/1000.0
        val delta=hypot(p.first-old.first,p.second-old.second)
        val tau=if(delta>.10) .025 else .13
        val alpha=1-exp(-dt/tau)
        return (old.first+alpha*(p.first-old.first) to old.second+alpha*(p.second-old.second)).also { last=it; at=now }
    }
}
