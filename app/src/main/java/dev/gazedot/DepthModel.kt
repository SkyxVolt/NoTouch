package dev.gazedot

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

data class DepthSample(val session: String, val features: DoubleArray, val x: Double, val y: Double)

/** Standardized ridge regression with eye x distance interactions. No invented training weights. */
class DepthModel(val withDistance: Boolean, val mean: DoubleArray, val std: DoubleArray,
                 val low: DoubleArray, val high: DoubleArray, val wx: DoubleArray, val wy: DoubleArray) : GazePredictor {
    private val columns get() = (0..12).filter { withDistance || it!=10 }
    private fun basis(raw: DoubleArray): DoubleArray {
        val z=DoubleArray(13) { (raw[it]-mean[it])/std[it] }
        return doubleArrayOf(1.0)+columns.map { z[it] }.toDoubleArray()+
            if(withDistance) DoubleArray(4) { z[it]*z[10] } else doubleArrayOf()
    }
    fun estimate(raw: DoubleArray): Pair<Double,Double>? {
        if(raw.size!=13 || columns.any { !raw[it].isFinite() }) return null
        val b=basis(raw)
        return b.indices.sumOf { b[it]*wx[it] } to b.indices.sumOf { b[it]*wy[it] }
    }
    fun supported(raw: DoubleArray): Boolean = columns.all { i ->
        val margin=max((high[i]-low[i])*.35, if(i==10) .075 else .025)
        raw[i].isFinite() && (i<4 || raw[i] in low[i]-margin..high[i]+margin)
    }
    override fun predict(f: EyeFeatures): Pair<Double,Double>? = f.depthVector().let { if(supported(it)) estimate(it) else null }
    fun json(config: String): String = JSONObject().put("schema_version",3).put("config",config)
        .put("with_distance",withDistance).put("mean",JSONArray(mean.toList())).put("std",JSONArray(std.toList()))
        .put("low",JSONArray(low.toList())).put("high",JSONArray(high.toList()))
        .put("wx",JSONArray(wx.toList())).put("wy",JSONArray(wy.toList())).toString()
    companion object {
        val names=EyeFeatures.names+listOf("distance_40cm","head_yaw_rad","head_pitch_rad")
        fun parse(text: String, config: String): DepthModel {
            val o=JSONObject(text); require(o.getInt("schema_version")==3 && o.getString("config")==config)
            fun a(key:String,n:Int):DoubleArray { val v=o.getJSONArray(key); require(v.length()==n)
                return DoubleArray(n) { v.getDouble(it) }.also { require(it.all { x->x.isFinite() && abs(x)<1e6 }) } }
            val depth=o.getBoolean("with_distance"); val n=if(depth)18 else 13
            return DepthModel(depth,a("mean",13),a("std",13),a("low",13),a("high",13),a("wx",n),a("wy",n)).also {
                require(it.std.all { x->x>0 } && (0..12).all { i->it.low[i]<=it.high[i] })
            }
        }
        fun fit(rows: List<DepthSample>, depth: Boolean): DepthModel {
            require(rows.size>=50) { "학습 표본 50개 이상 필요" }
            require(rows.all { it.features.size==13 && it.features.all { v->v.isFinite() } && it.x in 0.0..1.0 && it.y in 0.0..1.0 })
            require(rows.maxOf { it.x }-rows.minOf { it.x }>.5 && rows.maxOf { it.y }-rows.minOf { it.y }>.5) { "화면 전체 목표를 수집하세요" }
            val mean=DoubleArray(13) { i->rows.map { it.features[i] }.average() }
            val std=DoubleArray(13) { i->max(.01,sqrt(rows.map { (it.features[i]-mean[i]).pow(2) }.average())) }
            val lo=DoubleArray(13) { i->rows.minOf { it.features[i] } }
            val hi=DoubleArray(13) { i->rows.maxOf { it.features[i] } }
            require((0..3).all { hi[it]-lo[it]>.006 }) { "눈 움직임이 충분하지 않습니다" }
            if(depth) require(hi[10]-lo[10]>=.2) { "학습 세션에 최소 8cm 이상의 거리 변화를 포함하세요" }
            val shell=DepthModel(depth,mean,std,lo,hi,doubleArrayOf(),doubleArrayOf())
            val b=rows.map { shell.basis(it.features) }; val n=b[0].size
            fun solve(target: List<Double>): DoubleArray {
                val a=Array(n) { i->DoubleArray(n+1) { j->if(j==n) b.indices.sumOf { b[it][i]*target[it] }
                    else b.sumOf { it[i]*it[j] }+if(i==j && i!=0) 1.0 else 0.0 } }
                for(c in 0 until n) {
                    val p=(c until n).maxBy { abs(a[it][c]) }; require(abs(a[p][c])>1e-10)
                    val temp=a[c];a[c]=a[p];a[p]=temp
                    val d=a[c][c]; for(j in c..n) a[c][j]/=d
                    for(i in 0 until n) if(i!=c) { val k=a[i][c];for(j in c..n) a[i][j]-=k*a[c][j] }
                }
                return DoubleArray(n) { a[it][n] }
            }
            return DepthModel(depth,mean,std,lo,hi,solve(rows.map { it.x }),solve(rows.map { it.y }))
        }
    }
}
