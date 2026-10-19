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
    private lateinit var cameraButton: Button
    private lateinit var calibrationButton: Button
    private lateinit var stopButton: Button
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
            else if (::status.isInitialized) {
                status.text = TrackerState.status
                updateHomeButtons()
            }
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
        val screenWidthDp = resources.displayMetrics.widthPixels / density
        val screenHeightDp = resources.displayMetrics.heightPixels / density
        val scale = minOf(screenWidthDp / 390f, screenHeightDp / 780f).coerceIn(0.55f, 1f)
        fun dp(n: Int) = (n * density * scale).toInt()
        fun sp(n: Float) = n * scale
        val ink = Color.rgb(17, 18, 70)
        val blue = Color.rgb(6, 108, 236)
        val pale = Color.rgb(230, 230, 253)
        val font = resources.getFont(R.font.geologica)
        fun style(view: TextView, weight: Int) {
            view.typeface = font
            view.fontVariationSettings = "'wght' $weight"
        }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), 0, dp(24), dp(16))
        }
        root.addView(content, FrameLayout.LayoutParams(-1, -2))

        val logo = TextView(this).apply {
            text = "NoTouch"; textSize = sp(30f); setTextColor(ink); gravity = Gravity.CENTER
            style(this, 800)
        }
        content.addView(logo, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(104) })
        status = TextView(this).apply {
            text = if (TrackerState.status.startsWith("시작 전")) "" else TrackerState.status
            textSize = sp(12f); setTextColor(ink); gravity = Gravity.CENTER
            maxLines = 2; style(this, 500)
        }
        content.addView(status, LinearLayout.LayoutParams(-1, dp(28)))

        fun pill(label: String, action: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; textSize = sp(16f); setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(28).toFloat(); setColor(blue)
            }
            stateListAnimator = null
            minHeight = 0; minimumHeight = 0; minWidth = 0
            setPadding(dp(16), 0, dp(16), 0)
            style(this, 700)
            setOnClickListener { action() }
        }
        fun addStep(
            number: String,
            heading: String,
            description: String,
            top: Int,
            actionLabel: String? = null,
            action: (() -> Unit)? = null
        ) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }

            val numeral = TextView(this).apply {
                text = number
                textSize = sp(62f)
                setTextColor(pale)
                gravity = Gravity.CENTER
                style(this, 900)
            }
            row.addView(numeral, LinearLayout.LayoutParams(dp(54), dp(64)))

            val title = TextView(this).apply {
                text = heading
                textSize = sp(29f)
                setTextColor(ink)
                gravity = Gravity.CENTER_VERTICAL
                style(this, 800)
            }
            row.addView(title)

            content.addView(
                row,
                LinearLayout.LayoutParams(-1, dp(64)).apply {
                    topMargin = dp(top)
                }
            )

            val detail = TextView(this).apply {
                text = description
                textSize = sp(16f)
                setTextColor(ink)
                gravity = Gravity.CENTER
                style(this, 500)
            }
            content.addView(detail, LinearLayout.LayoutParams(-1, -2))

            if (actionLabel != null && action != null) {
                val button = pill(actionLabel, action)
                content.addView(
                    button,
                    LinearLayout.LayoutParams(dp(186), dp(46)).apply {
                        topMargin = dp(10)
                    }
                )

                if (number == "1") {
                    cameraButton = button
                } else {
                    calibrationButton = button
                }
            }
        }
        addStep("1", "카메라 시작", "카메라 및 오버레이 권한을 허용해 주세요.", 2, "카메라 시작") { startTracking() }
        addStep("2", "시선 보정", "기기를 움직이지 말고 얼굴을 카메라에서 약\n30~50cm 떨어진 위치에 맞춰 주세요.", 26, "보정 시작") {
            if (!TrackerState.running || TrackerState.features == null) {
                status.text = "카메라를 먼저 시작하고 ‘눈 검출됨’ 상태를 기다려 주세요."
            } else {
                TrackerState.model = null
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                calibration = CalibrationView()
                setContentView(calibration)
            }
        }
        addStep("3", "시선 테스트", "회색 점이 시선을 잘 따라오는지 확인해 주세요.\n잘 맞지 않는다면 다시 보정해 주세요.", 26)

        val dwellSettings = TextView(this).apply {
            text = "1초 시선 클릭 설정"
            textSize = sp(14f)
            setTextColor(blue)
            gravity = Gravity.CENTER
            style(this, 700)
            contentDescription = "접근성 설정에서 NoTouch 시선 클릭을 켭니다. 버튼을 1초 바라보면 클릭됩니다."
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        content.addView(dwellSettings, LinearLayout.LayoutParams(-1, dp(34)).apply { topMargin = dp(8) })

        stopButton = pill("추적 중지") {
            TrackerState.status = "추적 중지됨"
            stopService(Intent(this, TrackingService::class.java))
            status.text = TrackerState.status
            updateHomeButtons()
        }
        root.addView(stopButton, FrameLayout.LayoutParams(dp(186), dp(46), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(72)
        })
        setContentView(root)
        updateHomeButtons()

    }

    private fun updateHomeButtons() {
        if (!::cameraButton.isInitialized || !::calibrationButton.isInitialized || !::stopButton.isInitialized) return
        cameraButton.isEnabled = !TrackerState.running
        calibrationButton.isEnabled = TrackerState.running && TrackerState.features != null
        stopButton.isEnabled = TrackerState.running
        val disabled = Color.rgb(153, 158, 165)
        listOf(cameraButton, calibrationButton, stopButton).forEach { button ->
            val drawable = button.background.mutate() as android.graphics.drawable.GradientDrawable
            drawable.setColor(if (button.isEnabled) Color.rgb(6, 108, 236) else disabled)
        }
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
