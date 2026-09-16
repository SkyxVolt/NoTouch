package dev.gazedot

import org.junit.Assert.*
import org.junit.Test

class GazeMathTest {
    private fun eye(x: Double, y: Double) = EyeFeatures(x, y, .5, .5, .25)
    @Test fun recoversMappingAtUnseenPoint() {
        val anchors = listOf(.15, .5, .85).flatMap { x -> listOf(.15, .5, .85).map { y ->
            Anchor(eye((x - .5) * .3, (y - .5) * .2), x, y)
        } }
        val model = GazeModel.fit(anchors)!!
        val p = model.predict(eye((.32 - .5) * .3, (.71 - .5) * .2))!!
        assertEquals(.32, p.first, .01); assertEquals(.71, p.second, .01)
        assertNull(model.predict(eye(0.0, 0.0).copy(faceX = .9)))
        assertNull(model.predict(eye(Double.NaN, 0.0)))
    }
    @Test fun rejectsStationaryEyesAndContradictoryCalibration() {
        assertNull(GazeModel.fit(List(9) { Anchor(eye(0.0, 0.0), it / 9.0, .5) }))
        val a = (0..8).map { Anchor(eye((it % 3) * .1, (it / 3) * .1), if (it % 2 == 0) .1 else .9, .5) }
        assertNull(GazeModel.fit(a))
    }
    @Test fun medianRejectsOutlier() {
        val result = medianFeatures(listOf(eye(.1, .2), eye(.11, .21), eye(9.0, 9.0)))
        assertEquals(.11, result.x, 0.00001)
    }
}
