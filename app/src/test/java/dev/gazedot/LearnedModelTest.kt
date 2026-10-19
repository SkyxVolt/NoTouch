package dev.gazedot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LearnedModelTest {
    private fun fixture() = JSONObject(javaClass.getResource("/synthetic-trained-fixture.json")!!.readText())
    private val config = "test|phone|1080x2400|0|features-v2"
    @Test fun androidPredictionsMatchPythonTrainerIncludingOffscreenValues() {
        val fixture=fixture()
        val model=LearnedModel.parse(fixture.getJSONObject("model").toString(),config)
        val cases=fixture.getJSONArray("cases")
        for(i in 0 until cases.length()) {
            val item=cases.getJSONObject(i)
            val a=item.getJSONArray("features")
            val predicted=model.predict(EyeFeatures.from(DoubleArray(10) { a.getDouble(it) }))!!
            val expected=item.getJSONArray("expected")
            assertEquals(expected.getDouble(0),predicted.first,1e-10)
            assertEquals(expected.getDouble(1),predicted.second,1e-10)
        }
    }
    @Test fun incompatibleAndMalformedModelsFailImport() {
        val original=fixture().getJSONObject("model").toString()
        for(configKey in listOf("different-device",config)) {
            val o=JSONObject(original)
            if(configKey==config) o.getJSONArray("std").put(0,0)
            assertThrows(IllegalArgumentException::class.java) { LearnedModel.parse(o.toString(),configKey) }
        }
        val wrongOrder=JSONObject(original)
        wrongOrder.getJSONArray("feature_names").put(0,"right_x")
        assertThrows(IllegalArgumentException::class.java) { LearnedModel.parse(wrongOrder.toString(),config) }
    }
    @Test fun unsupportedPoseAndNonfiniteInputHidePrediction() {
        val f=fixture()
        val model=LearnedModel.parse(f.getJSONObject("model").toString(),config)
        val a=f.getJSONArray("cases").getJSONObject(0).getJSONArray("features")
        val eye=EyeFeatures.from(DoubleArray(10) { a.getDouble(it) })
        assertNull(model.predict(eye.copy(faceX=.8)))
        assertNull(model.predict(eye.copy(leftX=Double.NaN)))
    }
}
