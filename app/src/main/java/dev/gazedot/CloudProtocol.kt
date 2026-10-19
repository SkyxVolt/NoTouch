package dev.gazedot

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot

data class UiTarget(val id: String, val label: String, val left: Double, val top: Double,
    val right: Double, val bottom: Double, val editable: Boolean) {
    val center get() = (left + right) / 2 to (top + bottom) / 2
    fun json() = JSONObject().put("id", id).put("label", label).put("editable", editable)
        .put("bounds", JSONArray(listOf(left, top, right, bottom)))
}
data class UiSnapshot(val packageName: String, val fingerprint: String, val targets: List<UiTarget>)
data class CloudGaze(val point: Pair<Double, Double>?, val confidence: Double, val reason: String,
    val headYaw: Double?, val headPitch: Double?, val irisYaw: Double?, val irisPitch: Double?)
data class CloudChoice(val targetId: String?, val confidence: Double, val reason: String)

/** Conservative experiment gates, not accuracy/confidence calibration. */
object CloudPolicy {
    const val MAX_AGE_MS = 8_000L
    fun samePose(a: EyeFeatures, b: EyeFeatures): Boolean =
        (a.vector() + b.vector()).all { it.isFinite() } && a.scale > 0 &&
        abs(a.faceX-b.faceX) < .025 && abs(a.faceY-b.faceY) < .025 &&
        abs(b.scale/a.scale-1) < .10 && abs(a.roll-b.roll) < .10 &&
        abs(a.yawProxy-b.yawProxy) < .04 && abs(a.pitchProxy-b.pitchProxy) < .04 &&
        abs(a.leftX-b.leftX) < .035 && abs(a.rightX-b.rightX) < .035 &&
        abs(a.leftY-b.leftY) < .025 && abs(a.rightY-b.rightY) < .025
    fun usable(capturedAt: Long, now: Long, source: EyeFeatures, current: EyeFeatures): Boolean =
        now-capturedAt in 0..MAX_AGE_MS && samePose(source, current)
    fun choose(choice: CloudChoice, snapshot: UiSnapshot, gaze: Pair<Double, Double>): UiTarget? {
        if (choice.confidence < .75 || !choice.confidence.isFinite()) return null
        val target = snapshot.targets.firstOrNull { it.id == choice.targetId } ?: return null
        // Prevent a language prior from pulling a selection across the screen.
        val dx = maxOf(target.left-gaze.first, 0.0, gaze.first-target.right)
        val dy = maxOf(target.top-gaze.second, 0.0, gaze.second-target.bottom)
        return target.takeIf { hypot(dx, dy) <= .12 && hypot(it.center.first-gaze.first, it.center.second-gaze.second) <= .25 }
    }
}

object CloudProtocol {
    const val MODEL = "gpt-5.4"
    fun gazeRequest(jpeg: String, features: EyeFeatures, local: Pair<Double, Double>?, width: Int, height: Int): JSONObject = JSONObject()
        .put("stage", "gaze").put("jpeg", jpeg)
        .put("display_pixels", JSONArray(listOf(width,height)))
        .put("features", JSONObject().also { o -> EyeFeatures.names.zip(features.vector().toList()).forEach { o.put(it.first,it.second) } })
        .put("local_gaze", local?.let { JSONArray(listOf(it.first,it.second)) } ?: JSONObject.NULL)
    fun contextRequest(snapshot: UiSnapshot, point: Pair<Double, Double>): JSONObject = JSONObject()
        .put("stage", "context").put("package",snapshot.packageName)
        .put("gaze",JSONArray(listOf(point.first,point.second)))
        .put("candidates",JSONArray(snapshot.targets.map { it.json() }))

    fun output(response: JSONObject): JSONObject {
        require(response.optString("status") == "completed") { "미완료 응답" }
        val texts = mutableListOf<String>()
        val items = response.getJSONArray("output")
        for(i in 0 until items.length()) {
            val parts = items.getJSONObject(i).optJSONArray("content") ?: continue
            for(j in 0 until parts.length()) {
                val part=parts.getJSONObject(j)
                require(part.optString("type") != "refusal") { "모델이 분석을 보류했습니다" }
                if(part.optString("type")=="output_text") texts += part.getString("text")
            }
        }
        require(texts.size==1) { "분석 결과 형식 오류" }
        return JSONObject(texts.single())
    }
    private fun nullableNumber(o: JSONObject, key: String): Double? = if(o.isNull(key)) null else o.getDouble(key).also { require(it.isFinite()) }
    private fun confidence(o: JSONObject) = o.getDouble("confidence").also { require(it.isFinite() && it in 0.0..1.0) }
    fun parseGaze(o: JSONObject): CloudGaze {
        val x=nullableNumber(o,"x"); val y=nullableNumber(o,"y")
        require((x==null)==(y==null))
        if(x!=null && y!=null) require(x in -.25..1.25 && y in -.25..1.25)
        fun angle(key: String)=nullableNumber(o,key)?.also { require(it in -180.0..180.0) }
        return CloudGaze(if(x!=null && y!=null) x to y else null, confidence(o),o.getString("reason").take(140),
            angle("head_yaw_deg"),angle("head_pitch_deg"),angle("iris_yaw_deg"),angle("iris_pitch_deg"))
    }
    fun parseChoice(o: JSONObject)=CloudChoice(if(o.isNull("target_id")) null else o.getString("target_id"),confidence(o),o.getString("reason").take(140))
}
