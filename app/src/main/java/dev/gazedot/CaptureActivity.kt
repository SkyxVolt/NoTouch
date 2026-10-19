package dev.gazedot

import android.graphics.*
import android.os.*
import android.view.*
import androidx.activity.ComponentActivity
import java.io.File
import java.util.UUID
import kotlin.math.*

/** Deliberate developer data collection. A row is one independent touch/target, not a frame. */
class CaptureActivity : ComponentActivity() {
    private lateinit var capture: CaptureView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if(Build.VERSION.SDK_INT>=30) window.setDecorFitsSystemWindows(false)
        else @Suppress("DEPRECATION") run { window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if(!TrackerState.running) { finish(); return }
        capture=CaptureView(); setContentView(capture)
    }
    override fun onStart() {
        super.onStart()
        TrackerState.collecting=true; TrackerState.interactionAllowed=false
        TrackerState.cancelActions?.invoke()
        TrackerState.listener={ if(::capture.isInitialized) capture.frame() }
    }
    override fun onPause() {
        TrackerState.listener=null; TrackerState.collecting=false
        TrackerState.interactionAllowed=true
        super.onPause()
    }
    override fun onStop() {
        super.onStop()
        // Never resume a partially interrupted fixation/session.
        finish()
    }
    private inner class CaptureView : View(this@CaptureActivity) {
        private val session=UUID.randomUUID().toString()
        private val participant=(intent.getStringExtra("participant") ?: "P01").filter { it.isLetterOrDigit() || it=='_' || it=='-' }.take(32).ifEmpty { "P01" }
        private val folder=DepthTraining.folder(this@CaptureActivity)
        private val file=File(folder,"session-$session.csv")
        private val config=ModelStore.config(this@CaptureActivity)
        private val targets=List(3) { listOf(.12,.31,.5,.69,.88).flatMap { x -> listOf(.15,.325,.5,.675,.85).map { y -> x to y } } }.flatten().shuffled().take(60)
        private val recent=ArrayDeque<Pair<Long,EyeFeatures>>()
        private var index=0
        private var shownAt=SystemClock.uptimeMillis()
        private var hint="목표를 1초 바라본 뒤 누르세요 · 이번 목표 동안 자세 유지"
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        init {
            setBackgroundColor(Color.rgb(15,23,42))
            file.writeText((listOf("schema_version","participant","session","config_key","target_index","frame_count","target_x","target_y","touch_x","touch_y")+DepthModel.names).joinToString(",")+"\n")
        }
        fun frame() {
            val now=TrackerState.frameAt
            val f=TrackerState.features
            if(f!=null && TrackerState.eyeState==EyeState.OPEN && recent.lastOrNull()?.first!=now) recent.addLast(now to f)
            while(recent.isNotEmpty() && now-recent.first().first>600) recent.removeFirst()
            if(!TrackerState.running) { hint="카메라가 중지되었습니다. 뒤로 가서 다시 시작하세요"; recent.clear() }
            invalidate()
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if(event.action!=MotionEvent.ACTION_DOWN) return true
            performClick()
            if(index>=targets.size) { finish(); return true }
            val target=targets[index]; val d=resources.displayMetrics.density
            if(hypot(event.x-width*target.first,event.y-height*target.second)>48*d) { hint="파란 목표를 눌러 주세요"; invalidate(); return true }
            val now=SystemClock.uptimeMillis()
            val selected=recent.filter { now-it.first in 50..450 && it.first>=shownAt+500 }.map { it.second }
            if(now-shownAt<1000 || selected.size<5 || TrackerState.eyeState!=EyeState.OPEN || now-TrackerState.frameAt>200) {
                hint="표본 부족 · 목표를 바라보고 잠시 후 다시 누르세요"; invalidate(); return true
            }
            if(selected.any { !it.depthVector().all { n -> n.isFinite() } }) {
                hint="거리·머리 추정 대기 · 정면과 밝은 조명에서 다시 시도"; invalidate(); return true
            }
            val median=medianFeatures(selected)
            if(selected.any { abs(it.faceX-median.faceX)>.02 || abs(it.faceY-median.faceY)>.02 ||
                    abs(it.distanceCm-median.distanceCm)>3 || abs(it.headYaw-median.headYaw)>.06 || abs(it.headPitch-median.headPitch)>.06 || abs(it.x-median.x)>.035 || abs(it.y-median.y)>.035 }) {
                hint="눈·머리가 움직였습니다 · 목표에 시선을 고정하세요"; invalidate(); return true
            }
            val origin=IntArray(2); getLocationOnScreen(origin)
            val bounds=if(Build.VERSION.SDK_INT>=30) windowManager.maximumWindowMetrics.bounds else Rect(0,0,width,height)
            val row=listOf("3",participant,session,config,index.toString(),selected.size.toString(),
                ((origin[0]+width*target.first)/bounds.width()).toString(),((origin[1]+height*target.second)/bounds.height()).toString(),
                (event.rawX/bounds.width()).toString(),(event.rawY/bounds.height()).toString())+median.depthVector().map { it.toString() }
            file.appendText(row.joinToString(",") { "\""+it.replace("\"","\"\"")+"\"" }+"\n")
            index++; recent.clear(); shownAt=now
            hint=if(index==targets.size) "60개 저장 완료 · 화면을 눌러 돌아간 뒤 CSV 내보내기" else "저장됨 · 다음 목표를 바라보고 누르세요"
            invalidate(); return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
        override fun onDraw(canvas: Canvas) {
            val d=resources.displayMetrics.density
            paint.color=Color.WHITE; paint.textSize=20*d; paint.textAlign=Paint.Align.CENTER
            canvas.drawText("개발 데이터 수집  $index / 60",width/2f,60*d,paint)
            paint.textSize=12*d
            canvas.drawText("참가자 $participant · 거리 추정 ${TrackerState.features?.distanceCm?.takeIf { it.isFinite() }?.let { "%.0fcm".format(it) } ?: "불가"}",width/2f,85*d,paint)
            canvas.drawText(hint,width/2f,height-48*d,paint)
            targets.getOrNull(index)?.let {
                paint.color=Color.rgb(96,165,250)
                canvas.drawCircle((it.first*width).toFloat(),(it.second*height).toFloat(),22*d,paint)
                paint.color=Color.WHITE
                canvas.drawCircle((it.first*width).toFloat(),(it.second*height).toFloat(),4*d,paint)
            }
        }
    }
}
