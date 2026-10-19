package dev.gazedot

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject

object ModelStore {
    fun config(context: Context): String {
        val wm=context.getSystemService(WindowManager::class.java)
        val size=if(Build.VERSION.SDK_INT>=30) wm.maximumWindowMetrics.bounds.let { "${it.width()}x${it.height()}" }
            else context.resources.displayMetrics.let { "${it.widthPixels}x${it.heightPixels}" }
        val rotation=context.getSystemService(DisplayManager::class.java).getDisplay(0)?.rotation ?: 0
        return "${Build.MANUFACTURER}|${Build.MODEL}|$size|$rotation|features-v2"
    }
    private fun prefs(c: Context)=c.getSharedPreferences("models",Context.MODE_PRIVATE)
    fun import(c: Context, text: String): LearnedModel {
        require(text.length<=200_000) { "모델 파일이 너무 큽니다" }
        val model=LearnedModel.parse(text,config(c))
        prefs(c).edit().putString("trained",text).apply()
        java.io.File(c.filesDir,"depth-model.json").delete()
        TrackerState.modelLabel="개발 데이터 학습 모델"
        return model
    }
    fun saveCalibration(c: Context, anchors: List<Anchor>) {
        java.io.File(c.filesDir,"depth-model.json").delete()
        val rows=JSONArray()
        anchors.forEach { rows.put(JSONObject().put("features",JSONArray(it.eye.vector().toList())).put("x",it.x).put("y",it.y)) }
        prefs(c).edit().putString("calibration",JSONObject().put("config",config(c)).put("anchors",rows).toString()).apply()
        // Explicit fallback calibration supersedes an imported model until another model import.
        prefs(c).edit().remove("trained").apply()
        TrackerState.modelLabel="저장된 개인 보정"
    }
    fun load(c: Context): GazePredictor? {
        DepthTraining.load(c)?.let { TrackerState.modelLabel="거리·머리 보정 학습 모델"; return it }
        TrackerState.modelLabel="기본 홍채 포인터 · 보정 없이 시작"
        val pref=prefs(c)
        pref.getString("trained",null)?.let { text ->
            runCatching { LearnedModel.parse(text,config(c)) }.getOrNull()?.let {
                TrackerState.modelLabel="개발 데이터 학습 모델"; return it
            }
        }
        return runCatching {
            val obj=JSONObject(pref.getString("calibration",null) ?: return null)
            if(obj.getString("config")!=config(c)) return null
            val a=obj.getJSONArray("anchors")
            val model=GazeModel.fit((0 until a.length()).map { i ->
                val row=a.getJSONObject(i); val v=row.getJSONArray("features")
                Anchor(EyeFeatures.from(DoubleArray(10) { v.getDouble(it) }),row.getDouble("x"),row.getDouble("y"))
            })
            if(model!=null) TrackerState.modelLabel="저장된 개인 보정"
            model
        }.getOrNull()
    }
}

/** PC-trained quadratic ridge regression, evaluated locally without a Python runtime. */
class LearnedModel private constructor(
    private val mean: DoubleArray, private val std: DoubleArray,
    private val weights: Array<DoubleArray>, private val min: DoubleArray, private val max: DoubleArray,
) : GazePredictor {
    override fun predict(f: EyeFeatures): Pair<Double,Double>? {
        val raw=f.vector()
        if(raw.any { !it.isFinite() }) return null
        // Reject unsupported head positions/distances; permit eye extrapolation for edge scrolling.
        for(i in 4..9) {
            val margin=kotlin.math.max((max[i]-min[i])*.35,.025)
            if(raw[i]<min[i]-margin || raw[i]>max[i]+margin) return null
        }
        val z=DoubleArray(10) { (raw[it]-mean[it])/std[it] }
        if(z.any { kotlin.math.abs(it)>12 }) return null
        val basis=doubleArrayOf(1.0)+z+DoubleArray(10) { z[it]*z[it] }
        val x=basis.indices.sumOf { basis[it]*weights[it][0] }
        val y=basis.indices.sumOf { basis[it]*weights[it][1] }
        return if(x.isFinite() && y.isFinite()) x to y else null
    }
    companion object {
        fun parse(text: String, config: String): LearnedModel {
            val o=JSONObject(text)
            require(o.getInt("schema_version")==2 && o.getString("kind")=="quadratic_ridge") { "지원하지 않는 모델 형식" }
            require(o.getString("config_key")==config) { "기기·화면 방향이 학습 데이터와 다릅니다" }
            val names=o.getJSONArray("feature_names")
            require(names.length()==10 && (0..9).all { names.getString(it)==EyeFeatures.names[it] }) { "특징 순서 불일치" }
            fun vector(key: String): DoubleArray {
                val a=o.getJSONArray(key); require(a.length()==10)
                return DoubleArray(10) { a.getDouble(it) }.also { require(it.all { n -> n.isFinite() && kotlin.math.abs(n)<1e6 }) }
            }
            val mean=vector("mean"); val std=vector("std"); val min=vector("min"); val max=vector("max")
            require(std.all { it>0 } && (0..9).all { min[it]<=max[it] })
            val w=o.getJSONArray("weights"); require(w.length()==21)
            val weights=Array(21) { i -> val row=w.getJSONArray(i); require(row.length()==2)
                DoubleArray(2) { row.getDouble(it) }.also { require(it.all { n -> n.isFinite() && kotlin.math.abs(n)<1e6 }) } }
            return LearnedModel(mean,std,weights,min,max)
        }
    }
}
