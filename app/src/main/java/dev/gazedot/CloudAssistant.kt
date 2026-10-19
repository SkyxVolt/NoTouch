package dev.gazedot

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Sampled, optional experiment. Network never blocks the camera or main thread. */
class CloudAssistant(private val dimensions: () -> Pair<Int,Int>) {
    private val main=Handler(Looper.getMainLooper())
    private val io=Executors.newSingleThreadExecutor()
    private val busy=AtomicBoolean(false)
    @Volatile private var closed=false
    @Volatile private var lastStarted=0L
    @Volatile private var connection: HttpURLConnection?=null
    private var result: Result?=null
    @Volatile private var epoch=0L
    private data class Result(val generation: Long,val epoch: Long,val at: Long,val features: EyeFeatures,
        val gaze: CloudGaze?,val screen: UiSnapshot?,val target: UiTarget?)

    fun invalidate() { epoch++; result=null; connection?.disconnect() }
    fun gaze(current: EyeFeatures?, now: Long): Pair<Double,Double>? {
        val r=valid(current,now) ?: return null
        return r.gaze?.takeIf { it.confidence>=.75 }?.point
    }
    fun target(current: EyeFeatures?, now: Long): UiTarget? {
        val r=valid(current,now) ?: return null
        val target=r.target ?: return null
        val fresh=ControlAccessibilityService.instance?.cloudSnapshot() ?: return null
        return target.takeIf { fresh.fingerprint==r.screen?.fingerprint }
    }
    private fun valid(current: EyeFeatures?, now: Long): Result? {
        val r=result ?: return null
        return r.takeIf { !closed && CloudSession.enabled && r.generation==CloudSession.generation &&
            r.epoch==epoch && current!=null && CloudPolicy.usable(r.at,now,r.features,current) }
    }
    // Called only on the camera worker. Encode before its bitmap is released.
    fun offer(bitmap: Bitmap, features: EyeFeatures?, at: Long) {
        if(closed || !CloudSession.enabled || features==null || TrackerState.collecting || TrackerState.speaking) return
        if(CloudSession.calls>=CloudSession.MAX_CALLS) {
            CloudSession.pauseAutomatic(); CloudSession.status="20회 호출 한도 도달 · 앱 프로세스 재시작 필요"; return
        }
        if(!CloudSession.requested && (!CloudSession.automatic || at-lastStarted<10_000)) return
        if(!busy.compareAndSet(false,true)) return
        CloudSession.requested=false; lastStarted=at
        val gen=CloudSession.generation
        val pose=CloudSession.poseEnabled; val context=CloudSession.contextEnabled
        val key=CloudSession.serverToken
        val endpoint=CloudSession.serverUrl
        val jpeg=try {
            if(pose) ByteArrayOutputStream().use { bytes ->
                bitmap.compress(Bitmap.CompressFormat.JPEG,90,bytes)
                Base64.encodeToString(bytes.toByteArray(),Base64.NO_WRAP)
            } else null
        } catch(e: Exception) { busy.set(false); CloudSession.status="프레임 준비 실패"; return }
        main.post {
            if(closed || gen!=CloudSession.generation || TrackerState.collecting) { busy.set(false); return@post }
            val serial=epoch
            val local=(TrackerState.model ?: BasicGazePredictor).predict(features)
            val screen=if(context) ControlAccessibilityService.instance?.cloudSnapshot() else null
            val (width,height)=dimensions()
            CloudSession.status="GPT-5.4 분석 중 · ${if(pose) "눈/머리 " else ""}${if(context) "화면 후보" else ""}"
            io.execute {
                var gaze: CloudGaze?=null; var target: UiTarget?=null
                val notes=mutableListOf<String>()
                try {
                    fun checkActive() { check(!closed && gen==CloudSession.generation && serial==epoch) { "설정/화면 변경 또는 중지" } }
                    if(pose && jpeg!=null) {
                        checkActive()
                        gaze=CloudProtocol.parseGaze(call(endpoint,key,CloudProtocol.gazeRequest(jpeg,features,local,width,height)))
                        notes += "1: ${gaze!!.reason}"
                    }
                    val point=gaze?.takeIf { it.confidence>=.75 }?.point ?: local
                    if(context && screen!=null && screen.targets.isNotEmpty() && point!=null) {
                        checkActive()
                        val choice=CloudProtocol.parseChoice(call(endpoint,key,CloudProtocol.contextRequest(screen,point)))
                        target=CloudPolicy.choose(choice,screen,point)
                        notes += "2: ${choice.reason}"
                    } else if(context) notes += "2: 시선 좌표/접근성 후보 필요"
                    val elapsed=SystemClock.uptimeMillis()-at
                    main.post {
                        if(!closed && gen==CloudSession.generation && serial==epoch) {
                            result=Result(gen,serial,at,features,gaze,screen,target)
                            val current=TrackerState.features
                            val usable=current!=null && CloudPolicy.usable(at,SystemClock.uptimeMillis(),features,current)
                            CloudSession.status="${elapsed}ms · ${if(usable) "샘플 분석" else "오래된/움직인 샘플 · 조작 미적용"}\n"+
                                notes.joinToString("\n")+"\n머리 yaw/pitch: ${gaze?.headYaw ?: "?"}/${gaze?.headPitch ?: "?"}°"+
                                "\n홍채 yaw/pitch: ${gaze?.irisYaw ?: "?"}/${gaze?.irisPitch ?: "?"}° (모두 모델 추정)"
                        }
                    }
                } catch(e: Exception) {
                    main.post { if(!closed && gen==CloudSession.generation && serial==epoch) {
                        CloudSession.pauseAutomatic()
                        result=null; CloudSession.status="GPT 분석 실패: ${e.message?.take(120)}"
                    } }
                } finally { busy.set(false) }
            }
        }
    }
    private fun call(endpoint: String, key: String, request: JSONObject): JSONObject {
        check(CloudSession.reserveCall()) { "20회 호출 한도 도달 · 앱 프로세스 재시작 필요" }
        val c=URL("$endpoint/api/analyze").openConnection() as HttpURLConnection
        connection=c
        try {
            c.requestMethod="POST"; c.connectTimeout=10_000; c.readTimeout=20_000
            c.instanceFollowRedirects=false; c.doOutput=true
            c.setRequestProperty("Authorization","Bearer $key")
            c.setRequestProperty("Content-Type","application/json")
            c.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
            val code=c.responseCode
            check(code in 200..299) { when(code) {
                401 -> "서버 접속 토큰을 확인하세요 (401)"
                403,404 -> "서버 주소/접근 권한 확인 ($code)"
                429 -> "서버 호출 한도 도달 (429)"
                503 -> "서버 환경변수/호출 제한 저장소 설정 필요 (503)"
                502 -> "서버의 OpenAI 연결/키/잔액 확인 (502)"
                else -> "서버 HTTP $code"
            } }
            val text=c.inputStream.bufferedReader().use { reader ->
                val buffer=CharArray(4096); val out=StringBuilder()
                while(true) { val n=reader.read(buffer); if(n<0) break; out.append(buffer,0,n); check(out.length<128_000) { "응답 크기 초과" } }
                out.toString()
            }
            val response=JSONObject(text)
            CloudSession.tokens+=response.optJSONObject("usage")?.optInt("total_tokens",0) ?: 0
            return CloudProtocol.output(response)
        } finally { c.disconnect(); connection=null }
    }
    fun close() { closed=true; invalidate(); main.removeCallbacksAndMessages(null); io.shutdownNow() }
}
