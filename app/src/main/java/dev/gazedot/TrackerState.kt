package dev.gazedot

/** Accessed on the main thread. Camera worker posts immutable results here. */
object TrackerState {
    var running = false
    var model: GazePredictor? = null
    var modelLabel = "기본 홍채 포인터 · 보정 없이 시작"
    var controls = false
    var collecting = false
    var interactionAllowed = false
    private var settingsScreens = 0
    fun settingsStarted() { settingsScreens++; interactionAllowed=false }
    fun settingsStopped() { settingsScreens=(settingsScreens-1).coerceAtLeast(0); interactionAllowed=settingsScreens==0 }
    var speaking = false
    var microphoneReady = false
    var actionStatus = "조작 꺼짐"
    var frameAt = 0L
    var eyeState = EyeState.LOST
    var configKey = ""
    var cancelActions: (() -> Unit)? = null
    var cancelPendingClick: (() -> Unit)? = null
    var features: EyeFeatures? = null
    var sample: GazeSample? = null
    var status = "시작 전 · 기기를 거치대에 고정해 주세요"
    var listener: (() -> Unit)? = null
    fun publish(f: EyeFeatures?, message: String) {
        features = f
        status = message
        listener?.invoke()
    }
}
