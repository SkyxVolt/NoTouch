package dev.gazedot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private var calibration: CalibrationView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val overlayPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this)) startTracking()
        else status.text = "다른 앱 위에 표시 권한을 허용한 뒤 다시 시작하세요."
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startTracking()
        else { status.text = "시선 추적에는 카메라 권한이 필요합니다. 권한을 허용한 뒤 다시 시작하세요." }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else @Suppress("DEPRECATION") run {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        showHome()
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (calibration != null) {
                    calibration = null
                    TrackerState.status = "보정 취소됨"
                    showHome()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }
    override fun onStart() {
        super.onStart()
        TrackerState.listener = {
            if (calibration != null && !TrackerState.running) {
                calibration = null; showHome()
            } else if (calibration != null) calibration?.onFrame(TrackerState.features)
            else if (::status.isInitialized) status.text = TrackerState.status
        }
    }
    override fun onPause() {
        super.onPause()
        if (calibration != null) {
            calibration = null
            TrackerState.model = null
            TrackerState.status = "보정이 중단되었습니다. 다시 9점 보정을 시작하세요."
            showHome()
        }
    }
    override fun onStop() { TrackerState.listener = null; super.onStop() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun showHome() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val density = resources.displayMetrics.density
        fun dp(n: Int) = (n * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(26), dp(52), dp(26), dp(36))
            setBackgroundColor(Color.rgb(245, 247, 251))
        }
        fun text(value: String, size: Float, color: Int = Color.rgb(28, 37, 53)) = TextView(this).apply {
            this.text = value; textSize = size; setTextColor(color)
            setPadding(0, dp(10), 0, dp(10)); root.addView(this)
        }
        text("GAZE DOT", 14f, Color.rgb(213, 41, 59))
        text("눈으로 움직이는\n빨간 점", 32f)
        text("안드로이드 시선 추정 실험 · v0.1", 15f)
        status = text(TrackerState.status, 17f)
        fun button(label: String, action: () -> Unit) {
            root.addView(Button(this).apply {
                this.text = label; isAllCaps = false
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(8) })
        }
        button("1. 권한 허용 및 카메라 시작") { startTracking() }
        button("2. 9점 시선 보정") {
            if (!TrackerState.running || TrackerState.features == null) {
                status.text = "카메라를 먼저 시작하고 ‘눈 검출됨’ 상태를 기다려 주세요."
            } else {
                TrackerState.model = null
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                calibration = CalibrationView()
                setContentView(calibration)
            }
        }
        button("3. 홈 화면에서 빨간 점 확인") {
            if (TrackerState.model == null) status.text = "9점 보정을 먼저 완료해 주세요."
            else startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
        }
        button("추적 중지 · 카메라 끄기") {
            TrackerState.status = "추적 중지됨"
            val serviceIntent = Intent(this, TrackingService::class.java)
            stopService(serviceIntent)
            status.text = "추적 중지됨"
        }
        text("사용 방법", 20f)
        text("기기를 고정하고 얼굴을 약 30~50cm 앞에 두세요. 보정 중에는 머리를 움직이지 말고 파란 점만 눈으로 따라보세요. 각 점에서 자동으로 표본을 모읍니다.", 15f)
        text("보정 후 앱을 나가면 빨간 점이 다른 앱 위에도 표시됩니다. 알림의 ‘중지’로 끌 수 있습니다. 화면을 끄거나 회전하면 재보정이 필요합니다.", 15f)
        text("실험용 추정치입니다. 기기·조명·안경·머리 각도에 따라 오차가 큽니다. 모든 영상 처리는 기기 안에서 수행하며 촬영 영상을 저장하거나 전송하지 않습니다.", 14f)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun startTracking() {
        if (TrackerState.running) { status.text = TrackerState.status; return }
        if (!Settings.canDrawOverlays(this)) {
            overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            val requested = mutableListOf(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) requested += Manifest.permission.POST_NOTIFICATIONS
            permissions.launch(requested.toTypedArray())
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // Camera foreground service still works if notification permission is declined.
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
        try {
            startForegroundService(Intent(this, TrackingService::class.java))
            status.text = "시작 중…"
        } catch (e: Exception) { status.text = "시작 실패: ${e.message}" }
    }

    private inner class CalibrationView : View(this@MainActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val targets = listOf(.5 to .5, .15 to .15, .5 to .15, .85 to .15,
            .85 to .5, .85 to .85, .5 to .85, .15 to .85, .15 to .5)
        private var index = 0
        private var since = SystemClock.uptimeMillis()
        private val samples = mutableListOf<EyeFeatures>()
        private val anchors = mutableListOf<Anchor>()
        private var hint = "머리는 고정하고 파란 점을 바라보세요"
        private val tick = object : Runnable {
            override fun run() {
                if (calibration !== this@CalibrationView) return
                if (SystemClock.uptimeMillis() - since > 9000) {
                    finish(null, "눈 표본이 부족합니다. 조명·거리·안경 반사를 확인하고 다시 보정하세요.")
                    return
                }
                invalidate(); postDelayed(this, 100)
            }
        }
        init { setBackgroundColor(Color.rgb(15, 23, 42)); post(tick) }
        override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }
        fun onFrame(f: EyeFeatures?) {
            if (index >= targets.size || width == 0 || height == 0) return
            val elapsed = SystemClock.uptimeMillis() - since
            if (f == null) { hint = "눈 검출 대기 · 눈을 뜨고 얼굴을 카메라로 향해 주세요"; return }
            hint = "${index + 1} / 9 · 파란 점을 계속 바라보세요"
            if (elapsed < 1000) return
            samples += f
            if (samples.size >= 22 && elapsed >= 2500) {
                val eye = medianFeatures(samples)
                // Reject motion within a fixation, rather than fitting mismatched samples.
                if (samples.any { kotlin.math.abs(it.faceX - eye.faceX) > .045 || kotlin.math.abs(it.faceY - eye.faceY) > .045 }) {
                    samples.clear(); since = SystemClock.uptimeMillis()
                    hint = "머리가 움직였습니다 · 같은 점에서 다시 측정합니다"
                    return
                }
                val xy = targets[index]
                val location = IntArray(2); getLocationOnScreen(location)
                val bounds = if (Build.VERSION.SDK_INT >= 30) windowManager.maximumWindowMetrics.bounds else Rect(0, 0, width, height)
                anchors += Anchor(eye, (location[0] + width * xy.first) / bounds.width(),
                    (location[1] + height * xy.second) / bounds.height())
                index++; samples.clear(); since = SystemClock.uptimeMillis()
                if (index == targets.size) {
                    val model = GazeModel.fit(anchors)
                    finish(model, if (model == null) "보정 실패 · 시선 변화가 불충분하거나 오차가 큽니다. 기기를 고정하고 다시 시도하세요."
                        else "보정 완료 · 빨간 점과 실제 시선이 맞는지 확인하세요. 홈으로 나가도 동작합니다.")
                }
            }
            invalidate()
        }
        private fun finish(model: GazeModel?, message: String) {
            TrackerState.model = model; TrackerState.status = message
            calibration = null; showHome()
        }
        override fun onDraw(canvas: Canvas) {
            val target = targets.getOrNull(index) ?: return
            val d = resources.displayMetrics.density
            paint.color = Color.WHITE; paint.textSize = 15 * d; paint.textAlign = Paint.Align.CENTER
            canvas.drawText("시선 보정 ${index + 1}/9", width / 2f, 55 * d, paint)
            paint.textSize = 12 * d
            canvas.drawText(hint, width / 2f, height - 70 * d, paint)
            canvas.drawText("중단하려면 뒤로 가기", width / 2f, height - 45 * d, paint)
            val x = (target.first * width).toFloat(); val y = (target.second * height).toFloat()
            paint.color = Color.rgb(96, 165, 250)
            canvas.drawCircle(x, y, 13 * d, paint)
            paint.color = Color.WHITE; canvas.drawCircle(x, y, 3 * d, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 3 * d
            canvas.drawArc(x - 23*d, y - 23*d, x + 23*d, y + 23*d, -90f,
                360f * (samples.size / 22f).coerceAtMost(1f), false, paint)
            paint.style = Paint.Style.FILL
        }
    }
}
