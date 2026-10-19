package dev.gazedot

enum class EyeState { OPEN, CLOSED, UNCERTAIN, LOST }
sealed class EyeAction {
    data class Arm(val point: Pair<Double, Double>) : EyeAction()
    data class Click(val point: Pair<Double, Double>) : EyeAction()
    data class Scroll(val down: Boolean) : EyeAction()
    data object Dictate : EyeAction()
    data object Cancel : EyeAction()
}

/** Pure, timestamp-driven state machine. LOST never counts as closed eyes. */
class InteractionEngine {
    var armed: Pair<Double, Double>? = null
        private set
    private var armedAt = 0L
    private var closedAt: Long? = null
    private var closurePoint: Pair<Double, Double>? = null
    private var longHandled = false
    private var openedAt = 0L
    private var lastShort: Long? = null
    private var lastValid = 0L
    private var lastPoint: Pair<Double, Double>? = null
    private var previousFrame: Long? = null
    private var uncertainAt: Long? = null
    private var scrollDirection = 0
    private var scrollSince = 0L
    private var lastScroll = 0L
    private var cooldownUntil = 0L

    fun reset() {
        armed = null; closedAt = null; closurePoint = null; longHandled = false
        lastShort = null; lastPoint = null; previousFrame = null; uncertainAt = null
        scrollDirection = 0; openedAt = 0; lastScroll = 0; cooldownUntil = 0
    }
    fun update(now: Long, eye: EyeState, point: Pair<Double, Double>?, clickPoint: Pair<Double, Double>? = point): List<EyeAction> {
        if (eye == EyeState.LOST || previousFrame?.let { now - it > 250 } == true) {
            val hadArm = armed != null
            reset(); previousFrame = now
            return if (hadArm) listOf(EyeAction.Cancel) else emptyList()
        }
        previousFrame = now
        if (armed != null && now - armedAt > 6000) {
            reset(); previousFrame = now
            return listOf(EyeAction.Cancel)
        }
        if (eye == EyeState.UNCERTAIN) {
            if (uncertainAt == null) uncertainAt = now
            if (now - uncertainAt!! > 150) {
                val hadArm = armed != null; reset(); previousFrame = now
                return if (hadArm) listOf(EyeAction.Cancel) else emptyList()
            }
            return emptyList()
        }
        uncertainAt = null
        if (now < cooldownUntil) return emptyList()
        if (eye == EyeState.CLOSED) {
            scrollDirection = 0
            if (closedAt == null) {
                closedAt = now; longHandled = false
                closurePoint = if (now - lastValid <= 250 && now - openedAt >= 120) lastPoint else null
            }
            if (!longHandled && now - closedAt!! >= 1000) {
                longHandled = true; lastShort = null
                val pending = armed
                if (pending != null) {
                    armed = null; cooldownUntil = now + 400
                    return listOf(EyeAction.Click(pending))
                }
                closurePoint?.takeIf { it.first in 0.0..1.0 && it.second in 0.0..1.0 }?.let {
                    armed = it; armedAt = now
                    return listOf(EyeAction.Arm(it))
                }
            }
            return emptyList()
        }
        val closed = closedAt
        if (closed != null) {
            val duration = now - closed
            closedAt = null; openedAt = now
            if (!longHandled && duration in 70..350) {
                val prior = lastShort
                if (prior != null && closed - prior in 70..450) {
                    armed = null; lastShort = null; scrollDirection = 0; cooldownUntil = now + 700
                    return listOf(EyeAction.Dictate)
                }
                lastShort = now
            } else lastShort = null
            longHandled = false
        }
        if (point != null && point.first.isFinite() && point.second.isFinite()) {
            lastPoint = clickPoint?.takeIf { it.first.isFinite() && it.second.isFinite() }; lastValid = now
        } else {
            lastPoint = null; scrollDirection = 0
            return emptyList()
        }
        if (armed != null) return emptyList()
        val direction = when {
            point.first !in 0.0..1.0 -> 0
            point.second < -0.12 -> -1
            point.second > 1.12 -> 1
            else -> 0
        }
        if (direction != scrollDirection) { scrollDirection = direction; scrollSince = now }
        if (direction != 0 && now - scrollSince >= 650 && now - lastScroll >= 900) {
            lastScroll = now
            return listOf(EyeAction.Scroll(direction > 0))
        }
        return emptyList()
    }
}
