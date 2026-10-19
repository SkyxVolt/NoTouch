package dev.gazedot

enum class TrackingQuality { TRACKING, CLOSED, UNCERTAIN, LOST, OUT_OF_RANGE, STALE }

/** Camera and UI-independent output. Points are normalized, deliberately NOT clamped:
 * coordinates outside the screen drive scrolling. Call only from the UI thread. */
data class GazeSample(
    val timestampMs: Long,
    val quality: TrackingQuality,
    val features: EyeFeatures?,
    val rawPoint: Pair<Double, Double>?,
    val point: Pair<Double, Double>?,
    val armedPoint: Pair<Double, Double>?,
    val actions: List<EyeAction>
)

class GazePipeline {
    private val stabilizer = GazeStabilizer()
    private val interaction = InteractionEngine()
    private var model: GazePredictor? = null
    private var lastTimestamp: Long? = null
    val armed get() = interaction.armed

    fun cancelInteraction() = interaction.reset()

    fun reset() {
        interaction.reset()
        stabilizer.reset()
        lastTimestamp = null
    }

    fun process(
        timestampMs: Long,
        nowMs: Long,
        observation: EyeObservation,
        predictor: GazePredictor,
        actionsEnabled: Boolean,
        clickCandidate: Pair<Double, Double>? = null
    ): GazeSample {
        val hadArm = armed != null
        // Reject duplicates, delayed frames, and timestamps from the wrong clock.
        if (timestampMs > nowMs || nowMs - timestampMs > 250 ||
            lastTimestamp?.let { timestampMs <= it } == true) {
            val previous = lastTimestamp
            reset()
            lastTimestamp = previous
            return GazeSample(timestampMs, TrackingQuality.STALE, null, null, null, null,
                if (hadArm) listOf(EyeAction.Cancel) else emptyList())
        }
        var cancelled = false
        if (predictor !== model || lastTimestamp?.let { timestampMs - it > 250 } == true) {
            cancelled = hadArm
            reset()
            model = predictor
        }
        lastTimestamp = timestampMs
        val features = observation.features?.takeIf { observation.state == EyeState.OPEN && it.vector().all(Double::isFinite) }
        val raw = features?.let(predictor::predict)?.takeIf { it.first.isFinite() && it.second.isFinite() }
        val quality = when (observation.state) {
            EyeState.LOST -> TrackingQuality.LOST
            EyeState.CLOSED -> TrackingQuality.CLOSED
            EyeState.UNCERTAIN -> TrackingQuality.UNCERTAIN
            EyeState.OPEN -> if (raw == null) TrackingQuality.OUT_OF_RANGE else TrackingQuality.TRACKING
        }
        val point = raw?.let { stabilizer.update(it, timestampMs) }
        if (quality == TrackingQuality.LOST || quality == TrackingQuality.OUT_OF_RANGE) stabilizer.reset()
        val actions = mutableListOf<EyeAction>()
        if (cancelled) actions += EyeAction.Cancel
        if (!actionsEnabled) {
            if (armed != null) actions += EyeAction.Cancel
            interaction.reset()
        } else {
            val eye = if (quality == TrackingQuality.OUT_OF_RANGE) EyeState.LOST else observation.state
            val candidate = clickCandidate?.takeIf { it.first in 0.0..1.0 && it.second in 0.0..1.0 }
            actions += interaction.update(timestampMs, eye, point, candidate ?: point)
        }
        return GazeSample(timestampMs, quality, features, raw, point, armed, actions)
    }
}
