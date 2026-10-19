package dev.gazedot

import org.junit.Assert.*
import org.junit.Test

class GazePipelineTest {
    private val features = EyeFeatures(0.0, .02, .5, .5, .3)
    private val open = EyeObservation(EyeState.OPEN, features, .2, .2)
    private val closed = EyeObservation(EyeState.CLOSED, null, .05, .05)
    private val local = object : GazePredictor {
        override fun predict(f: EyeFeatures) = .4 to .6
    }
    private fun arm(p: GazePipeline): GazeSample {
        for (t in 1000L..1200L step 50) p.process(t, t, open, local, true)
        var result = p.process(1250, 1250, closed, local, true)
        for (t in 1300L..2250L step 50) result = p.process(t, t, closed, local, true)
        assertNotNull(result.armedPoint)
        return result
    }

    @Test fun candidateDoesNotReplaceLocalGaze() {
        val s = GazePipeline().process(1000, 1000, open, local, true, .9 to .1)
        assertEquals(.4 to .6, s.point)
        assertEquals(TrackingQuality.TRACKING, s.quality)
    }
    @Test fun staleFrameCancelsArmedClick() {
        val p = GazePipeline(); arm(p)
        val s = p.process(2300, 2600, closed, local, true)
        assertEquals(TrackingQuality.STALE, s.quality)
        assertNull(s.point); assertNull(s.armedPoint)
        assertEquals(listOf(EyeAction.Cancel), s.actions)
    }
    @Test fun lossAndRecoveryDoNotReusePreviousPoint() {
        val p = GazePipeline(); arm(p)
        val lost = p.process(2300, 2300, EyeObservation(EyeState.LOST, null, 0.0, 0.0), local, true)
        assertNull(lost.armedPoint); assertTrue(lost.actions.contains(EyeAction.Cancel))
        val recovered = p.process(2350, 2350, open, local, true)
        assertEquals(.4 to .6, recovered.point); assertTrue(recovered.actions.isEmpty())
    }
    @Test fun disabledActionsStillProducePointerAndCancelClick() {
        val p = GazePipeline(); arm(p)
        val s = p.process(2300, 2300, open, local, false)
        assertNotNull(s.point); assertNull(s.armedPoint)
        assertEquals(listOf(EyeAction.Cancel), s.actions)
    }
    @Test fun invalidModelOutputCannotReachPointerOrGestures() {
        val bad = object : GazePredictor { override fun predict(f: EyeFeatures) = Double.NaN to .4 }
        val s = GazePipeline().process(1000, 1000, open, bad, true)
        assertEquals(TrackingQuality.OUT_OF_RANGE, s.quality)
        assertNull(s.point); assertTrue(s.actions.isEmpty())
    }
    @Test fun changingModelCancelsPreviousClick() {
        val p = GazePipeline(); arm(p)
        val s = p.process(2300, 2300, open, BasicGazePredictor, true)
        assertNull(s.armedPoint); assertTrue(s.actions.contains(EyeAction.Cancel))
    }
    @Test fun gapAndResetCannotCompleteOldBlink() {
        val p = GazePipeline(); arm(p)
        val s = p.process(3000, 3000, closed, local, true)
        assertNull(s.armedPoint); assertFalse(s.actions.any { it is EyeAction.Click })
        p.reset()
        assertTrue(p.process(3050, 3050, closed, local, true).actions.isEmpty())
    }
    @Test fun offscreenCoordinatesRemainAvailableForScrolling() {
        val outside = object : GazePredictor { override fun predict(f: EyeFeatures) = .5 to 1.25 }
        val p = GazePipeline()
        val actions = (1000L..1800L step 50).flatMap { p.process(it, it, open, outside, true).actions }
        assertTrue(actions.contains(EyeAction.Scroll(true)))
    }
    @Test fun outOfOrderAndFutureFramesAreRejected() {
        val p = GazePipeline()
        p.process(1000, 1000, open, local, true)
        assertEquals(TrackingQuality.STALE, p.process(999, 1000, open, local, true).quality)
        assertEquals(TrackingQuality.STALE, p.process(999, 1000, open, local, true).quality)
        assertEquals(TrackingQuality.STALE, p.process(2000, 1000, open, local, true).quality)
    }
}
