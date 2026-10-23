package dev.gazedot

/** Accessed on the main thread. Camera worker posts immutable results here. */
object TrackerState {
    var running = false
    var model: GazeModel? = null
    var features: EyeFeatures? = null
    var status = "시작 전 · 기기를 거치대에 고정해 주세요"
    var listener: (() -> Unit)? = null
    /** Laid-out size of the cursor overlay window, recorded for evaluation geometry. */
    var overlaySize: Pair<Int, Int>? = null
    fun publish(f: EyeFeatures?, message: String) {
        features = f
        status = message
        listener?.invoke()
    }
}
