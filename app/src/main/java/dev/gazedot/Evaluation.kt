package dev.gazedot

import android.content.pm.ApplicationInfo

/** Evaluation-only fixation protocol. Target coordinates use the gaze model's display-normalized frame. */
enum class EvaluationMode(val id: String, val label: String, val showsCursor: Boolean) {
    BLIND_MEASUREMENT("blind_measurement", "블라인드 측정", false),
    LIVE_PREVIEW("live_preview", "라이브 미리보기", true),
}

enum class EvaluationPhase(val id: String) { SETTLING("settling"), MEASUREMENT("measurement") }

data class EvaluationTarget(val id: String, val x: Double, val y: Double)

/** One target presentation; [trialIndex] runs 0..31 across both passes, [pass] is 1 or 2. */
data class Presentation(val trialIndex: Int, val pass: Int, val indexInPass: Int, val target: EvaluationTarget)

object EvaluationProtocol {
    val GRID = listOf(0.20, 0.40, 0.60, 0.80)
    const val TARGET_MS = 2500L
    const val SETTLING_MS = 1000L

    /** Row-major: id "r{row}c{col}", x from the column, y from the row. */
    val targets: List<EvaluationTarget> = GRID.indices.flatMap { r ->
        GRID.indices.map { c -> EvaluationTarget("r${r}c$c", GRID[c], GRID[r]) }
    }
    /** Serpentine from top-left: even rows left to right, odd rows right to left. */
    val firstPass: List<EvaluationTarget> = GRID.indices.flatMap { r ->
        val columns = if (r % 2 == 0) GRID.indices else GRID.indices.reversed()
        columns.map { c -> targets[r * GRID.size + c] }
    }
    val secondPass: List<EvaluationTarget> = firstPass.reversed()
    val presentations: List<Presentation> =
        firstPass.mapIndexed { i, t -> Presentation(i, 1, i, t) } +
            secondPass.mapIndexed { i, t -> Presentation(firstPass.size + i, 2, i, t) }
    val TOTAL_MS = presentations.size * TARGET_MS
}

/**
 * Screen geometry captured when a run starts. [basisWidth]/[basisHeight] must be the same bounds
 * CalibrationView divides by, so a normalized target lands where the model maps that gaze.
 */
data class EvaluationGeometry(
    val basisWidth: Int, val basisHeight: Int,
    val viewLeft: Int, val viewTop: Int, val viewWidth: Int, val viewHeight: Int,
    val overlaySize: Pair<Int, Int>?,
) {
    fun toScreen(x: Double, y: Double) = x * basisWidth to y * basisHeight
    /** Inverse of the calibration mapping (viewLocation + local) / basis. */
    fun toView(x: Double, y: Double) = x * basisWidth - viewLeft to y * basisHeight - viewTop
    fun containsAllTargets(marginPx: Double) = EvaluationProtocol.targets.all { t ->
        val (x, y) = toView(t.x, t.y)
        x in marginPx..viewWidth - marginPx && y in marginPx..viewHeight - marginPx
    }
}

class EvaluationRun(val mode: EvaluationMode, val startMs: Long, val geometry: EvaluationGeometry) {
    fun presentationAt(now: Long): Presentation? {
        if (now < startMs) return null
        return EvaluationProtocol.presentations.getOrNull(((now - startMs) / EvaluationProtocol.TARGET_MS).toInt())
    }
    fun onsetMs(p: Presentation) = startMs + p.trialIndex * EvaluationProtocol.TARGET_MS
    fun phaseAt(now: Long): EvaluationPhase? {
        val p = presentationAt(now) ?: return null
        return if (now - onsetMs(p) < EvaluationProtocol.SETTLING_MS) EvaluationPhase.SETTLING else EvaluationPhase.MEASUREMENT
    }
    fun isFinished(now: Long) = now - startMs >= EvaluationProtocol.TOTAL_MS
}

/** Accessed on the main thread. Non-null [mode] suppresses dwell taps; blind mode also hides the cursor. */
object EvaluationState {
    var mode: EvaluationMode? = null
        private set
    var run: EvaluationRun? = null
        private set
    val active get() = mode != null
    val hidesCursor get() = mode?.showsCursor == false
    fun begin(m: EvaluationMode) { mode = m; run = null }
    fun attach(r: EvaluationRun) { if (r.mode == mode) run = r }
    fun end() { mode = null; run = null }
}

fun evaluationEntryEnabled(appFlags: Int) = (appFlags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
