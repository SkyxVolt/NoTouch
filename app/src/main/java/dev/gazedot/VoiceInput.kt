package dev.gazedot

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.speech.*
import android.view.accessibility.AccessibilityNodeInfo

/** One explicit dictation gesture -> one utterance. Never presses Send/Enter. */
class VoiceInput(private val context: Context) : RecognitionListener {
    private val handler=Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer?=null
    private var target: AccessibilityNodeInfo?=null
    private var original=""
    private var selectionStart=0
    private var selectionEnd=0
    private var generation=0
    private val timeout=Runnable { cancel("음성 입력 시간 초과") }

    fun start() {
        if(TrackerState.speaking) return
        if(!TrackerState.microphoneReady || context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            TrackerState.actionStatus="마이크 권한을 허용하고 추적을 다시 시작하세요"; return
        }
        val service=ControlAccessibilityService.instance
        val field=service?.focusedEditor()
        if(field==null) { TrackerState.actionStatus="먼저 글을 넣을 입력칸을 선택하세요"; return }
        target=field; original=field.text?.toString().orEmpty()
        selectionStart=field.textSelectionStart.takeIf { it>=0 } ?: original.length
        selectionEnd=field.textSelectionEnd.takeIf { it>=selectionStart } ?: selectionStart
        try {
            val onDevice=Build.VERSION.SDK_INT>=31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            if(!onDevice && !SpeechRecognizer.isRecognitionAvailable(context)) { cancel("설치된 음성 인식 서비스가 없습니다"); return }
            recognizer=if(onDevice && Build.VERSION.SDK_INT>=31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                else SpeechRecognizer.createSpeechRecognizer(context)
            val session=++generation
            // Canceled recognizers may still deliver queued callbacks. Ignore older sessions.
            recognizer!!.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { if(session==generation) this@VoiceInput.onReadyForSpeech(params) }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { if(session==generation) this@VoiceInput.onEndOfSpeech() }
                override fun onError(error: Int) { if(session==generation) this@VoiceInput.onError(error) }
                override fun onResults(results: Bundle?) { if(session==generation) this@VoiceInput.onResults(results) }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            TrackerState.speaking=true
            TrackerState.actionStatus="음성 입력 준비 중…"
            recognizer!!.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE,"ko-KR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1)
            })
            handler.postDelayed(timeout,20000)
        } catch(e: Exception) { cancel("음성 인식 시작 실패: ${e.message}") }
    }
    @Suppress("DEPRECATION")
    fun cancel(message: String?=null) {
        generation++
        TrackerState.speaking=false
        handler.removeCallbacks(timeout)
        val previous=recognizer; recognizer=null
        runCatching { previous?.cancel() }
        runCatching { previous?.destroy() }
        target?.recycle(); target=null
        if(message!=null) TrackerState.actionStatus=message
    }
    override fun onReadyForSpeech(params: Bundle?) { if(TrackerState.speaking) TrackerState.actionStatus="말씀하세요 · 한 문장을 받아적습니다" }
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() { if(TrackerState.speaking) TrackerState.actionStatus="음성을 글자로 바꾸는 중…" }
    override fun onError(error: Int) {
        if(!TrackerState.speaking) return
        val message=when(error) {
            SpeechRecognizer.ERROR_NO_MATCH -> "음성을 알아듣지 못했습니다. 다시 두 번 깜빡여 주세요"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "말소리를 듣지 못했습니다"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "음성 인식 마이크 권한이 필요합니다"
            12,13 -> "한국어 음성 모델을 사용할 수 없습니다. 기기의 음성 인식 언어 설정을 확인하세요"
            else -> "음성 인식 오류 ($error) · 잠시 후 다시 시도하세요"
        }
        cancel(message)
    }
    override fun onResults(results: Bundle?) {
        if(!TrackerState.speaking) return
        val text=results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        val field=target
        val inserted=!text.isNullOrBlank() && field!=null &&
            ControlAccessibilityService.instance?.insert(field,original,selectionStart,selectionEnd,text)==true
        cancel(if(inserted) "음성 입력 완료" else "입력칸이 바뀌었거나 이 앱이 텍스트 입력을 지원하지 않습니다")
    }
    override fun onPartialResults(partialResults: Bundle?) = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit
}
