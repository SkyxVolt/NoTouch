package dev.gazedot

import android.content.Context
import org.json.JSONObject
import java.io.File
import kotlin.math.*

object DepthTraining {
    fun folder(c:Context)=File(c.filesDir,"datasets-v3").apply { mkdirs() }
    fun load(c:Context):DepthModel? = runCatching {
        DepthModel.parse(File(c.filesDir,"depth-model.json").readText(),ModelStore.config(c))
    }.getOrNull()
    fun train(c:Context):String {
        val files=folder(c).listFiles().orEmpty().filter { it.extension=="csv" }.sortedBy { it.lastModified() }
        val sessions=files.mapNotNull { file ->
            val lines=file.readLines(); if(lines.size<61) return@mapNotNull null
            // Writer quotes every field; these numeric/session fields cannot contain commas.
            val rows=lines.drop(1).map { line ->
                val v=line.split(',').map { it.removeSurrounding("\"") }
                require(v.size==23 && v[0]=="3") { "수집 형식 불일치" }
                require(v[3]==ModelStore.config(c)) { "현재 기기·화면 방향의 데이터만 학습하세요" }
                DepthSample(v[2],DoubleArray(13) { v[10+it].toDouble() },v[6].toDouble(),v[7].toDouble())
            }
            rows
        }
        require(sessions.size>=3) { "완료된 세션이 3개 이상 필요합니다. 서로 다른 거리에서 학습 2회, 별도 평가 1회를 수집하세요." }
        val test=sessions.last(); val train=sessions.dropLast(1).flatten()
        require(train.map { it.session }.toSet().intersect(test.map { it.session }.toSet()).isEmpty())
        val full=DepthModel.fit(train,true); val baseline=DepthModel.fit(train,false)
        fun report(m:DepthModel):JSONObject {
            val accepted=test.filter { m.supported(it.features) }
            fun metrics(rows:List<DepthSample>):JSONObject? {
                if(rows.isEmpty())return null
                val errors=rows.map { r-> val p=m.estimate(r.features)!!; hypot(p.first-r.x,p.second-r.y) }.sorted()
                return JSONObject().put("mean_normalized",errors.average()).put("median_normalized",errors[errors.size/2])
                    .put("p95_normalized",errors[ceil(errors.size*.95).toInt().coerceAtMost(errors.size)-1])
            }
            return JSONObject().put("all_predictions",metrics(test)).put("accepted_predictions",metrics(accepted) ?: JSONObject.NULL)
                .put("coverage",accepted.size.toDouble()/test.size)
        }
        val a=report(full);val b=report(baseline)
        val report=JSONObject().put("schema_version",3).put("test_session",test.first().session)
            .put("train_samples",train.size).put("test_samples",test.size).put("with_distance",a).put("without_distance",b)
            .put("note","Same last-session holdout for both models. Unclamped normalized x/y errors; not cm or degrees. No new-person accuracy claim. Baseline still includes face scale. No held-out refit.")
        File(c.filesDir,"depth-evaluation.json").writeText(report.toString(2))
        // Keep the held-out session out of the installed model, too.
        val destination=File(c.filesDir,"depth-model.json");val temp=File(c.filesDir,"depth-model.tmp")
        temp.writeText(full.json(ModelStore.config(c)));check(temp.renameTo(destination))
        fun error(o:JSONObject)=o.getJSONObject("all_predictions").getDouble("mean_normalized")*100
        return "학습 완료 · 별도 세션 ${test.size}점 평가\n평균 오차(정규화 좌표 ×100): 거리 포함 %.2f / 제외 %.2f\n거리 포함 유효 예측 %.0f%% · cm·각도 아님\n정확도 개선 여부는 평가 결과를 확인하세요".format(error(a),error(b),a.getDouble("coverage")*100)
    }
}
