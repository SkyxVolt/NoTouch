package dev.gazedot

import org.junit.Assert.*
import org.junit.Test

class BasicGazePredictorTest {
    private fun eye(x: Double, y: Double) = EyeFeatures(x,y,.5,.5,.3)
    @Test fun neutralIrisStartsAtCenterWithoutModelOrCalibration() {
        val p=BasicGazePredictor.predict(eye(0.0,.02))!!
        assertEquals(.5,p.first,1e-9); assertEquals(.5,p.second,1e-9)
    }
    @Test fun unmirroredHorizontalAndVerticalMotionHaveExpectedDirections() {
        assertTrue(BasicGazePredictor.predict(eye(-.1,.02))!!.first>.5)
        assertTrue(BasicGazePredictor.predict(eye(.1,.02))!!.first<.5)
        assertTrue(BasicGazePredictor.predict(eye(0.0,.10))!!.second>.5)
        assertTrue(BasicGazePredictor.predict(eye(0.0,-.06))!!.second<.5)
    }
    @Test fun preservesOffscreenScrollAndRejectsInvalidDetection() {
        assertTrue(BasicGazePredictor.predict(eye(0.0,.15))!!.second>1.12)
        assertTrue(BasicGazePredictor.predict(eye(0.0,-.10))!!.second<-.12)
        assertNull(BasicGazePredictor.predict(eye(Double.NaN,0.0)))
        assertNull(BasicGazePredictor.predict(eye(0.0,0.0).copy(scale=0.0)))
    }
}
