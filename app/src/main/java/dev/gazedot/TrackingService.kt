package dev.gazedot

import android.app.*
import android.Manifest
import android.content.pm.PackageManager
import android.content.*
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import android.os.*
import android.provider.Settings
import android.view.Surface
import android.view.WindowManager
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import java.util.concurrent.Executors

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class TrackingService : LifecycleService() {
    @Volatile private var cameraGeometry: DoubleArray? = null
    private val pipeline = GazePipeline()
    private lateinit var voice: VoiceInput
    private var lastModel: GazePredictor? = null
    private val cloud = CloudAssistant {
        val wm=getSystemService(WindowManager::class.java)
        if(Build.VERSION.SDK_INT>=30) wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        else resources.displayMetrics.let { it.widthPixels to it.heightPixels }
    }
    private var armedScreen: String? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closing = false
    private var landmarker: FaceLandmarker? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var overlay: DotOverlay? = null
    private var lastFrame = 0L
    private var lastResult = 0L
    private var initialRotation = Surface.ROTATION_0
    private var initialSize = ""
    private var receiverRegistered = false
    private val displayManager by lazy { getSystemService(DisplayManager::class.java) }
    private fun rotation() = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation ?: 0
    private fun sizeKey(): String {
        val wm = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) return wm.maximumWindowMetrics.bounds.let { "${it.width()}x${it.height()}" }
        return resources.displayMetrics.let { "${it.widthPixels}x${it.heightPixels}" }
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = Unit
        override fun onDisplayRemoved(id: Int) = Unit
        override fun onDisplayChanged(id: Int) {
            if (rotation() != initialRotation || sizeKey() != initialSize) fail("화면 크기·방향 변경 · 앱에서 이 방향의 모델을 선택하고 다시 시작하세요.")
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { fail("화면 꺼짐 · 앱에서 다시 시작해 주세요") }
    }
    private val watchdog = object : Runnable {
        override fun run() {
            if (closing) return
            if (!Settings.canDrawOverlays(this@TrackingService)) { fail("다른 앱 위에 표시 권한이 해제되었습니다"); return }
            if (SystemClock.uptimeMillis() - lastResult > 800) {
                resetTracking()
                TrackerState.publish(null, "카메라 신호 대기 · 다른 카메라 앱을 닫아 주세요")
            }
            main.postDelayed(this, 400)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_PAUSE) {
            CloudSession.disable(); cloud.invalidate()
            TrackerState.controls = false
            resetTracking()
            TrackerState.actionStatus = "조작 일시정지 · 앱에서 다시 켤 수 있습니다"
            return START_NOT_STICKY
        }
        if (TrackerState.running) {
            TrackerState.controls = true
            pipeline.cancelInteraction()
            TrackerState.actionStatus = "눈 조작 준비 · 연습 화면 또는 다른 앱에서 사용하세요"
            return START_NOT_STICKY
        }
        try {
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel("tracking", "시선 추적", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop = PendingIntent.getService(this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val pause = PendingIntent.getService(this, 2, Intent(this, TrackingService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(this, "tracking")
                .setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("NoTouch 시선 추적 중")
                .setContentText("카메라 사용 중 · 눈 조작 / 음성 입력 준비")
                .setContentIntent(open).setOngoing(true)
                .addAction(Notification.Action.Builder(null, "조작 일시정지", pause).build())
                .addAction(Notification.Action.Builder(null, "중지", stop).build()).build()
            TrackerState.microphoneReady = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (Build.VERSION.SDK_INT >= 30) startForeground(1, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or (if (TrackerState.microphoneReady) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0))
            else startForeground(1, notification)
            voice = VoiceInput(this)
            TrackerState.cancelPendingClick = { pipeline.cancelInteraction(); armedScreen=null }
            TrackerState.cancelActions = {
                resetTracking()
                TrackerState.actionStatus = "화면 변경 · 클릭 대기 취소"
            }
            TrackerState.configKey = ModelStore.config(this)
            TrackerState.model = ModelStore.load(this)
            overlay = DotOverlay(this)
            initialRotation = rotation(); initialSize = sizeKey()
            displayManager.registerDisplayListener(displayListener, main)
            ContextCompat.registerReceiver(this, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
            TrackerState.controls = true
            TrackerState.running = true
            TrackerState.publish(null, "카메라·눈 검출 모델 준비 중…")
            TrackerState.actionStatus = "눈 조작 준비 · 접근성 권한을 허용하고 연습 화면에서 확인하세요"
            main.postDelayed(watchdog, 3000)
            worker.execute {
                try {
                    landmarker = FaceLandmarker.createFromOptions(this,
                        FaceLandmarker.FaceLandmarkerOptions.builder()
                            .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build())
                            .setOutputFacialTransformationMatrixes(true)
                            .setRunningMode(RunningMode.VIDEO).setNumFaces(1)
                            .setMinFaceDetectionConfidence(0.6f).setMinFacePresenceConfidence(0.6f)
                            .setMinTrackingConfidence(0.6f).build())
                    main.post { if (!closing) bindCamera() }
                } catch (e: Exception) { main.post { fail("모델 초기화 실패: ${e.message}") } }
            }
        } catch (e: Exception) { fail("시작 실패: ${e.message}") }
        // Camera services must be explicitly started from a visible activity; never auto-restart.
        return START_NOT_STICKY
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (closing) return@addListener
            try {
                val provider = future.get(); cameraProvider = provider
                if (!provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) { fail("전면 카메라가 없습니다"); return@addListener }
                @Suppress("DEPRECATION")
                val analyzer = ImageAnalysis.Builder().setTargetResolution(android.util.Size(1280, 720))
                    .setTargetRotation(initialRotation)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis = analyzer
                analyzer.setAnalyzer(worker) { frame -> process(frame) }
                val camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analyzer)
                val info=Camera2CameraInfo.from(camera.cameraInfo)
                val sensor=info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val pixel=info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                val active=info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                val lenses=info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                cameraGeometry=if(sensor!=null && pixel!=null && active!=null && lenses?.size==1)
                    doubleArrayOf(lenses[0].toDouble(),sensor.width*active.width().toDouble()/pixel.width,
                        sensor.height*active.height().toDouble()/pixel.height) else null
                camera.cameraControl.setZoomRatio(1f)
                camera.cameraInfo.cameraState.observe(this) { state ->
                    if (state.error != null && !closing) {
                        resetTracking()
                        TrackerState.publish(null, "카메라 사용 불가 · 다른 카메라 앱을 닫고 다시 시도해 주세요")
                    }
                }
            } catch (e: Exception) { fail("카메라 시작 실패: ${e.message}") }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun process(frame: ImageProxy) {
        var raw: Bitmap? = null; var upright: Bitmap? = null
        try {
            val now = SystemClock.uptimeMillis()
            if (closing || now - lastFrame < 33) return
            lastFrame = now
            raw = frame.toBitmap()
            val matrix = Matrix().apply { postRotate(frame.imageInfo.rotationDegrees.toFloat()) }
            upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
            val image = BitmapImageBuilder(upright).build()
            val result = try { landmarker?.detectForVideo(image, now) } finally { image.close() }
            val observation = result?.faceLandmarks()?.firstOrNull()?.let { LandmarkFeatures.observe(it, upright.width, upright.height,
                    cameraGeometry?.let { g -> DepthGeometry.focalPixels(g[0],g[1],g[2],raw.width,raw.height,frame.imageInfo.rotationDegrees) },
                    result.facialTransformationMatrixes().orElse(emptyList()).firstOrNull()) }
                ?: EyeObservation(EyeState.LOST, null, 0.0, 0.0)
            cloud.offer(upright, observation.features, now)
            main.post {
                if (closing) return@post
                val at = SystemClock.uptimeMillis()
                if (at - now > 250) {
                    resetTracking()
                    TrackerState.publish(null, "영상 처리가 지연됩니다 · 조작 일시 대기")
                    return@post
                }
                lastResult = at
                TrackerState.frameAt = now
                TrackerState.eyeState = observation.state
                val model = TrackerState.model ?: BasicGazePredictor
                if (model !== lastModel) { cloud.invalidate(); armedScreen = null; lastModel = model }
                val candidate = if (TrackerState.interactionAllowed && !TrackerState.collecting)
                    cloud.target(observation.features, now) else null
                val enabled = TrackerState.controls && TrackerState.interactionAllowed && !TrackerState.collecting &&
                    !TrackerState.speaking && ControlAccessibilityService.instance != null
                val sample = pipeline.process(now, at, observation, model, enabled, candidate?.center)
                TrackerState.sample = sample
                val features = sample.features
                if (sample.quality in listOf(TrackingQuality.LOST, TrackingQuality.OUT_OF_RANGE, TrackingQuality.STALE)) cloud.invalidate()
                sample.actions.forEach(::perform)
                val message = when (sample.quality) {
                    TrackingQuality.LOST -> "얼굴 검출 대기"
                    TrackingQuality.CLOSED -> "눈 감김 감지"
                    TrackingQuality.UNCERTAIN -> "눈 상태 확인 중"
                    TrackingQuality.STALE -> "영상 처리가 지연됩니다 · 조작 일시 대기"
                    TrackingQuality.OUT_OF_RANGE -> "추적 범위 밖 · 학습 때의 얼굴 위치로 돌아오세요"
                    TrackingQuality.TRACKING -> if (model === BasicGazePredictor)
                        "기본 홍채 포인터 작동 중 · 정확도 개선은 선택 보정"
                        else "시선 추적 중 · ${TrackerState.modelLabel}"
                }
                val label = when {
                    TrackerState.collecting -> ""
                    TrackerState.speaking -> TrackerState.actionStatus
                    pipeline.armed != null -> "클릭 대기 · 다시 1초 감으면 확정 / 6초 후 취소"
                    TrackerState.controls -> TrackerState.actionStatus
                    CloudSession.enabled -> "GPT-5.4 실험 · ${CloudSession.calls}/20 호출"
                    else -> ""
                }
                val visible = if (TrackerState.collecting) null else sample.armedPoint ?: sample.point
                overlay?.show(visible, pipeline.armed != null, label, if(TrackerState.collecting) null else candidate?.center)
                TrackerState.publish(features, message + (features?.distanceCm?.takeIf { it.isFinite() }?.let { " · 거리 추정 %.0fcm".format(it) } ?: " · 거리 추정 불가"))
            }
        } catch (e: Exception) {
            main.post { if (!closing) fail("영상 처리 실패: ${e.message}") }
        } finally {
            if (upright !== raw) upright?.recycle()
            raw?.recycle()
            frame.close()
        }
    }

    private fun perform(action: EyeAction) {
        val service = ControlAccessibilityService.instance ?: return
        val wm = getSystemService(WindowManager::class.java)
        val bounds = if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds
            else android.graphics.Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        when(action) {
            is EyeAction.Arm -> {
                armedScreen=service.cloudSnapshot()?.fingerprint
                TrackerState.actionStatus = "클릭 대기"
            }
            is EyeAction.Click -> {
                if(armedScreen!=null && service.cloudSnapshot()?.fingerprint!=armedScreen) {
                    armedScreen=null; TrackerState.actionStatus="화면 후보 변경 · 클릭 취소"; return
                }
                armedScreen=null
                val ok = service.tap((action.point.first * bounds.width()).toFloat(), (action.point.second * bounds.height()).toFloat())
                TrackerState.actionStatus = if(ok) "클릭 요청" else "클릭을 실행하지 못했습니다"
            }
            is EyeAction.Scroll -> {
                val ok = service.scroll(action.down, bounds.width(), bounds.height())
                TrackerState.actionStatus = if(ok) (if(action.down) "아래로 스크롤" else "위로 스크롤") else "스크롤을 실행하지 못했습니다"
            }
            EyeAction.Dictate -> voice.start()
            EyeAction.Cancel -> { armedScreen = null; TrackerState.actionStatus = "클릭 대기 취소" }
        }
    }

    private fun resetTracking() {
        pipeline.reset()
        armedScreen = null
        cloud.invalidate()
        TrackerState.frameAt = 0L
        TrackerState.eyeState = EyeState.LOST
        TrackerState.sample = null
        if (::voice.isInitialized) voice.cancel()
        overlay?.show(null)
    }

    private fun fail(message: String) {
        if (closing) return
        resetTracking()
        TrackerState.publish(null, message)
        stopSelf()
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (rotation() != initialRotation || sizeKey() != initialSize) fail("화면 변경 · 앱에서 다시 보정해 주세요")
    }
    override fun onDestroy() {
        closing = true
        cloud.close(); CloudSession.disable()
        main.removeCallbacksAndMessages(null)
        displayManager.unregisterDisplayListener(displayListener)
        if (receiverRegistered) unregisterReceiver(screenReceiver)
        analysis?.clearAnalyzer()
        analysis?.let { cameraProvider?.unbind(it) }
        pipeline.reset()
        TrackerState.sample = null
        TrackerState.frameAt = 0L
        TrackerState.eyeState = EyeState.LOST
        if (::voice.isInitialized) voice.cancel()
        TrackerState.cancelActions = null
        TrackerState.cancelPendingClick = null
        TrackerState.microphoneReady = false
        TrackerState.controls = false
        overlay?.close()
        worker.execute { landmarker?.close(); landmarker = null }
        worker.shutdown()
        TrackerState.running = false; TrackerState.model = null
        TrackerState.publish(null, if (TrackerState.status.startsWith("추적 중")) "추적 중지됨" else TrackerState.status)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object { const val ACTION_STOP = "dev.gazedot.STOP"; const val ACTION_PAUSE = "dev.gazedot.PAUSE" }
}
