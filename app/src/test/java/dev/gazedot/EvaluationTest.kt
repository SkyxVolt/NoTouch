package dev.gazedot

import android.content.pm.ApplicationInfo
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class EvaluationTest {
    private val fullScreen = EvaluationGeometry(1080, 2400, 0, 0, 1080, 2400, 1080 to 2400)
    @After fun cleanup() = EvaluationState.end()

    @Test fun gridIsFourByFourAndAvoidsCalibrationPoints() {
        val targets = EvaluationProtocol.targets
        assertEquals(16, targets.size)
        assertEquals(16, targets.map { it.id }.toSet().size)
        assertEquals(16, targets.map { it.x to it.y }.toSet().size)
        assertEquals(setOf(.2, .4, .6, .8), targets.map { it.x }.toSet())
        assertEquals(setOf(.2, .4, .6, .8), targets.map { it.y }.toSet())
        val calibration = setOf(.15, .5, .85)
        assertTrue(targets.none { it.x in calibration || it.y in calibration })
        assertEquals(EvaluationTarget("r1c3", .8, .4), targets.single { it.id == "r1c3" })
    }

    @Test fun firstPassIsSerpentineFromTopLeft() {
        val expected = listOf(
            .2 to .2, .4 to .2, .6 to .2, .8 to .2,
            .8 to .4, .6 to .4, .4 to .4, .2 to .4,
            .2 to .6, .4 to .6, .6 to .6, .8 to .6,
            .8 to .8, .6 to .8, .4 to .8, .2 to .8)
        assertEquals(expected, EvaluationProtocol.firstPass.map { it.x to it.y })
    }

    @Test fun secondPassIsExactReverseAndPresentationsAreLabelled() {
        assertEquals(EvaluationProtocol.firstPass.reversed(), EvaluationProtocol.secondPass)
        val p = EvaluationProtocol.presentations
        assertEquals(32, p.size)
        assertEquals((0 until 32).toList(), p.map { it.trialIndex })
        assertEquals(List(16) { 1 } + List(16) { 2 }, p.map { it.pass })
        assertEquals((0 until 16).toList() + (0 until 16).toList(), p.map { it.indexInPass })
        assertTrue(p.groupBy { it.target.id }.values.all { it.size == 2 })
        // Pass boundary: the last first-pass target is shown again first in pass two.
        assertEquals(p[15].target, p[16].target)
        assertEquals(80_000L, EvaluationProtocol.TOTAL_MS)
    }

    @Test fun timingPhasesHaveExactBoundaries() {
        val run = EvaluationRun(EvaluationMode.BLIND_MEASUREMENT, 10_000, fullScreen)
        assertNull(run.presentationAt(9_999)); assertNull(run.phaseAt(9_999))
        assertEquals(EvaluationPhase.SETTLING, run.phaseAt(10_000))
        assertEquals(EvaluationPhase.SETTLING, run.phaseAt(10_999))
        assertEquals(EvaluationPhase.MEASUREMENT, run.phaseAt(11_000))
        assertEquals(EvaluationPhase.MEASUREMENT, run.phaseAt(12_499))
        assertEquals(0, run.presentationAt(12_499)!!.trialIndex)
        assertEquals(1, run.presentationAt(12_500)!!.trialIndex)
        assertEquals(EvaluationPhase.SETTLING, run.phaseAt(12_500))
        val last = run.presentationAt(89_999)!!
        assertEquals(31, last.trialIndex); assertEquals(2, last.pass)
        assertEquals(87_500L, run.onsetMs(last))
        assertEquals(EvaluationPhase.MEASUREMENT, run.phaseAt(89_999))
        assertFalse(run.isFinished(89_999))
        assertTrue(run.isFinished(90_000))
        assertNull(run.presentationAt(90_000)); assertNull(run.phaseAt(90_000))
    }

    @Test fun targetsInvertTheCalibrationMapping() {
        // API 30+: display bounds basis, view offset by e.g. a status bar.
        val offset = EvaluationGeometry(1080, 2400, 0, 63, 1080, 2337, null)
        // API <30: CalibrationView divides by its own size, so the basis equals the view size.
        val legacy = EvaluationGeometry(1080, 2280, 0, 40, 1080, 2280, null)
        for (g in listOf(fullScreen, offset, legacy)) for (t in EvaluationProtocol.targets) {
            val (lx, ly) = g.toView(t.x, t.y)
            // Same expression CalibrationView uses to store anchors.
            assertEquals(t.x, (g.viewLeft + lx) / g.basisWidth, 1e-9)
            assertEquals(t.y, (g.viewTop + ly) / g.basisHeight, 1e-9)
            val (sx, sy) = g.toScreen(t.x, t.y)
            assertEquals(g.viewLeft + lx, sx, 1e-9); assertEquals(g.viewTop + ly, sy, 1e-9)
        }
        val (ox, oy) = offset.toView(.8, .8)
        assertEquals(864.0, ox, 1e-9); assertEquals(1857.0, oy, 1e-9)
    }

    @Test fun rejectsGeometryThatPushesTargetsOffTheView() {
        assertTrue(fullScreen.containsAllTargets(48.0))
        assertFalse(EvaluationGeometry(1080, 2400, 0, 0, 1080, 1000, null).containsAllTargets(48.0))
        assertFalse(EvaluationGeometry(1080, 2400, 900, 0, 1080, 2400, null).containsAllTargets(48.0))
    }

    @Test fun entryIsOnlyEnabledForDebuggableBuilds() {
        assertTrue(evaluationEntryEnabled(ApplicationInfo.FLAG_DEBUGGABLE))
        assertTrue(evaluationEntryEnabled(ApplicationInfo.FLAG_DEBUGGABLE or ApplicationInfo.FLAG_HAS_CODE))
        assertFalse(evaluationEntryEnabled(0))
        assertFalse(evaluationEntryEnabled(ApplicationInfo.FLAG_HAS_CODE))
    }

    @Test fun stateSuppressesDwellPerModeAndCleansUp() {
        assertFalse(EvaluationState.active); assertFalse(EvaluationState.hidesCursor)
        EvaluationState.begin(EvaluationMode.BLIND_MEASUREMENT)
        assertTrue(EvaluationState.active); assertTrue(EvaluationState.hidesCursor)
        val run = EvaluationRun(EvaluationMode.BLIND_MEASUREMENT, 0, fullScreen)
        EvaluationState.attach(run)
        assertSame(run, EvaluationState.run)
        EvaluationState.end()
        assertFalse(EvaluationState.active); assertFalse(EvaluationState.hidesCursor); assertNull(EvaluationState.run)
        EvaluationState.end()
        assertFalse(EvaluationState.active)

        EvaluationState.begin(EvaluationMode.LIVE_PREVIEW)
        assertTrue(EvaluationState.active); assertFalse(EvaluationState.hidesCursor)
        // A run from a different mode is never attached to the live state.
        EvaluationState.attach(run)
        assertNull(EvaluationState.run)
        EvaluationState.end()
        assertFalse(EvaluationState.active)
    }

    @Test fun modeIdsMatchSchemaValues() {
        assertEquals("blind_measurement", EvaluationMode.BLIND_MEASUREMENT.id)
        assertEquals("live_preview", EvaluationMode.LIVE_PREVIEW.id)
        assertFalse(EvaluationMode.BLIND_MEASUREMENT.showsCursor)
        assertTrue(EvaluationMode.LIVE_PREVIEW.showsCursor)
    }
}
