package dev.gazedot

import org.junit.Assert.*
import org.junit.Test

class InteractionEngineTest {
    private class Feed {
        val engine = InteractionEngine()
        val actions = mutableListOf<EyeAction>()
        var now = 1000L
        fun frame(eye: EyeState, point: Pair<Double, Double>? = .4 to .6, step: Long = 50) {
            now += step; actions += engine.update(now, eye, point)
        }
        fun open(frames: Int = 5, point: Pair<Double, Double> = .4 to .6) { repeat(frames) { frame(EyeState.OPEN, point) } }
        fun closed(frames: Int = 22) { repeat(frames) { frame(EyeState.CLOSED, null) } }
    }
    @Test fun longClosureArmsOnlyOnceAndReopenedClosureConfirmsFrozenPoint() {
        val f = Feed(); f.open(); f.closed(50)
        assertEquals(listOf(EyeAction.Arm(.4 to .6)), f.actions)
        f.open(point = .8 to .2); f.closed(30)
        assertEquals(listOf(EyeAction.Arm(.4 to .6), EyeAction.Click(.4 to .6)), f.actions)
        assertNull(f.engine.armed)
    }
    @Test fun ordinaryBlinkDoesNothingButFastDoubleBlinkDictatesOnce() {
        val f = Feed(); f.open(); f.closed(2); f.open(3)
        assertTrue(f.actions.isEmpty())
        f.closed(2); f.open(8)
        assertEquals(listOf(EyeAction.Dictate), f.actions)
        assertNull(f.engine.armed)
    }
    @Test fun slowSeparatedBlinksDoNotDictate() {
        val f = Feed(); f.open(); f.closed(2); f.open(12); f.closed(2); f.open()
        assertTrue(f.actions.isEmpty())
    }
    @Test fun missingFaceAndFrameGapsCannotConfirm() {
        for (lost in listOf(true, false)) {
            val f = Feed(); f.open(); f.closed()
            assertNotNull(f.engine.armed)
            if (lost) f.frame(EyeState.LOST, null) else f.frame(EyeState.CLOSED, null, 500)
            f.closed(25)
            assertNull(f.engine.armed)
            assertEquals(0, f.actions.filterIsInstance<EyeAction.Click>().size)
            assertTrue(f.actions.contains(EyeAction.Cancel))
        }
    }
    @Test fun pendingExpiresAndSuppressesScrolling() {
        val f = Feed(); f.open(); f.closed(); f.open(40, .5 to 1.4)
        assertTrue(f.actions.none { it is EyeAction.Scroll })
        f.open(90)
        assertTrue(f.actions.contains(EyeAction.Cancel))
        assertNull(f.engine.armed)
    }
    @Test fun offscreenDwellScrollsWithRepeatLimitAndCenterStopsIt() {
        val f = Feed(); f.open(12, .5 to 1.3)
        assertTrue(f.actions.isEmpty())
        f.open(3, .5 to 1.3)
        assertEquals(listOf(EyeAction.Scroll(true)), f.actions)
        f.open(10, .5 to 1.3)
        assertEquals(1, f.actions.size)
        f.open(10, .5 to 1.3)
        assertEquals(2, f.actions.size)
        f.open(30)
        assertEquals(2, f.actions.size)
        f.open(15, .5 to -.3)
        assertEquals(EyeAction.Scroll(false), f.actions.last())
    }
    @Test fun justLookingNearEdgeOrOutsideSideDoesNotScroll() {
        val f = Feed(); f.open(30, .5 to 1.05); f.open(30, 1.3 to 1.5)
        assertTrue(f.actions.isEmpty())
    }
    @Test fun doubleBlinkCancelsPendingClickAndNeverConfirmsIt() {
        val f = Feed(); f.open(); f.closed(); f.open()
        f.closed(2); f.open(3); f.closed(2); f.open()
        assertEquals(1, f.actions.filterIsInstance<EyeAction.Arm>().size)
        assertTrue(f.actions.contains(EyeAction.Dictate))
        assertFalse(f.actions.any { it is EyeAction.Click })
        assertNull(f.engine.armed)
    }
    @Test fun prolongedUncertainEyeSignalCancelsPending() {
        val f = Feed(); f.open(); f.closed()
        repeat(6) { f.frame(EyeState.UNCERTAIN, null) }
        f.closed(25)
        assertNull(f.engine.armed)
        assertFalse(f.actions.any { it is EyeAction.Click })
    }
    @Test fun startingWithClosedEyesCannotArm() {
        val f = Feed(); f.closed(50)
        assertTrue(f.actions.isEmpty())
    }
}
