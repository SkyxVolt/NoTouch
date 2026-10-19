package dev.gazedot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudProtocolTest {
    private val features=EyeFeatures(.01,.02,.5,.4,.3,roll=.01)
    private fun gaze(x: Any=.4,y: Any=.6)=JSONObject().put("x",x).put("y",y).put("confidence",.8)
        .put("head_yaw_deg",JSONObject.NULL).put("head_pitch_deg",JSONObject.NULL)
        .put("iris_yaw_deg",JSONObject.NULL).put("iris_pitch_deg",JSONObject.NULL).put("reason","test")
    @Test fun sendsOnlyDomainDataToServer() {
        val gaze=CloudProtocol.gazeRequest("jpeg",features,null,1080,2340)
        val context=CloudProtocol.contextRequest(UiSnapshot("test","fingerprint", emptyList()),.5 to .5)
        assertEquals("gaze",gaze.getString("stage"))
        assertEquals("context",context.getString("stage"))
        listOf(gaze,context).forEach {
            assertFalse(it.has("model")); assertFalse(it.has("instructions")); assertFalse(it.has("api_key"))
        }
    }
    @Test fun abstentionDoesNotInventCoordinateOrAngle() {
        val parsed=CloudProtocol.parseGaze(gaze(JSONObject.NULL,JSONObject.NULL))
        assertNull(parsed.point); assertNull(parsed.irisYaw)
    }
    @Test fun invalidCoordinatesAndConfidenceRejected() {
        listOf(gaze(5.0),gaze(.5,JSONObject.NULL),gaze().put("confidence",1.1)).forEach {
            assertTrue(runCatching { CloudProtocol.parseGaze(it) }.isFailure)
        }
    }
    @Test fun refusesPartialOrRefusalApiResponse() {
        val partial=JSONObject().put("status","incomplete").put("output",JSONArray())
        assertTrue(runCatching { CloudProtocol.output(partial) }.isFailure)
        val refusal=JSONObject().put("status","completed").put("output",JSONArray().put(JSONObject()
            .put("content",JSONArray().put(JSONObject().put("type","refusal")))))
        assertTrue(runCatching { CloudProtocol.output(refusal) }.isFailure)
    }
    @Test fun extractsCompletedResponseIgnoringReasoningItems() {
        val response=JSONObject().put("status","completed").put("output",JSONArray()
            .put(JSONObject().put("type","reasoning"))
            .put(JSONObject().put("content",JSONArray().put(JSONObject().put("type","output_text").put("text",gaze().toString())))))
        assertEquals(.4,CloudProtocol.parseGaze(CloudProtocol.output(response)).point!!.first,0.0)
    }
    @Test fun staleFutureAndChangedHeadOrEyesAreRejected() {
        assertTrue(CloudPolicy.usable(100,500,features,features))
        assertFalse(CloudPolicy.usable(100,9000,features,features))
        assertFalse(CloudPolicy.usable(100,50,features,features))
        assertFalse(CloudPolicy.usable(100,500,features,features.copy(faceX=.7)))
        assertFalse(CloudPolicy.usable(100,500,features,features.copy(leftX=.2)))
        assertFalse(CloudPolicy.usable(100,500,features,features.copy(scale=.6)))
    }
    @Test fun inventedFarAwayOrUncertainTargetsAreRejected() {
        val near=UiTarget("near","search",.4,.4,.6,.6,false)
        val far=UiTarget("far","send",.9,.9,1.0,1.0,false)
        val screen=UiSnapshot("test","fingerprint",listOf(near,far))
        assertEquals(near,CloudPolicy.choose(CloudChoice("near",.8,""),screen,.5 to .5))
        assertNull(CloudPolicy.choose(CloudChoice("invented",.99,""),screen,.5 to .5))
        assertNull(CloudPolicy.choose(CloudChoice("far",.99,""),screen,.5 to .5))
        assertNull(CloudPolicy.choose(CloudChoice("near",.1,""),screen,.5 to .5))
    }
    @Test fun clickUsesFrozenSuggestionButScrollUsesOriginalGaze() {
        val engine=InteractionEngine()
        var actions: List<EyeAction> = emptyList()
        for(t in 0L..300L step 50L) engine.update(t,EyeState.OPEN,.5 to .5,.6 to .5)
        for(t in 350L..1400L step 50L) {
            val next=engine.update(t,EyeState.CLOSED,null)
            if(next.isNotEmpty()) actions=next
        }
        assertEquals(EyeAction.Arm(.6 to .5),actions.single())
        for(t in 1450L..1700L step 50L) engine.update(t,EyeState.OPEN,.1 to .1,.2 to .2)
        for(t in 1750L..2800L step 50L) {
            val next=engine.update(t,EyeState.CLOSED,null)
            if(next.isNotEmpty()) actions=next
        }
        assertEquals(EyeAction.Click(.6 to .5),actions.single())
        engine.reset()
        val scroll=mutableListOf<EyeAction>()
        for(t in 3000L..4100L step 50L) scroll+=engine.update(t,EyeState.OPEN,.5 to 1.2,.5 to .5)
        assertTrue(scroll.any { it is EyeAction.Scroll && it.down })
    }
}
