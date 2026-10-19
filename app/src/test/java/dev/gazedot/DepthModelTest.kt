package dev.gazedot

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class DepthModelTest {
    @Test fun distanceIsResolutionIndependentAndRejectsDisagreement() {
        val a=DepthGeometry.focalPixels(3.0,4.0,3.0,1280,720,0)!!
        val b=DepthGeometry.focalPixels(3.0,4.0,3.0,640,360,90)!!
        val diameter=1.17*a.first/40
        assertEquals(40.0,DepthGeometry.distance(diameter to 0.0,diameter to 0.0,a)!!,1e-8)
        assertEquals(40.0,DepthGeometry.distance(0.0 to diameter/2,0.0 to diameter/2,b)!!,1e-8)
        assertNull(DepthGeometry.distance(diameter to 0.0,diameter*2 to 0.0,a))
        assertNull(DepthGeometry.focalPixels(Double.NaN,4.0,3.0,1280,720,0))
    }
    @Test fun poseUsesColumnMajorTransform() {
        val angle=.2
        val m=floatArrayOf(cos(angle).toFloat(),0f,-sin(angle).toFloat(),0f, 0f,1f,0f,0f,
            sin(angle).toFloat(),0f,cos(angle).toFloat(),0f, 0f,0f,0f,1f)
        val p=DepthGeometry.pose(m)!!
        assertEquals(angle,p.first,1e-6);assertEquals(0.0,p.second,1e-6)
    }
    private fun samples(session:String, offset:Int):List<DepthSample> = List(180) { i ->
        val x=sin((i+offset)*1.7);val y=cos((i+offset)*2.3); val d=.8+(i%3)*.25
        val f=doubleArrayOf(x*.1,y*.1,x*.09,y*.09,.5,.5,.3,0.0,.1,0.0,d,0.0,0.0)
        DepthSample(session,f,.5+.25*x+.15*x*(d-1.05)/.25,.5+.35*y)
    }
    @Test fun learnedDistanceInteractionGeneralizesAndRoundTrips() {
        val train=samples("train",0);val test=samples("test",3)
        val full=DepthModel.fit(train,true);val base=DepthModel.fit(train,false)
        fun error(m:DepthModel)=test.map { r -> val p=m.estimate(r.features)!!;hypot(p.first-r.x,p.second-r.y) }.average()
        assertTrue(error(full)<error(base)*.2)
        val restored=DepthModel.parse(full.json("test-device"),"test-device")
        assertEquals(full.estimate(test[0].features),restored.estimate(test[0].features))
        val far=test[0].features.copyOf();far[10]=3.0
        assertFalse(restored.supported(far))
    }
    @Test fun missingDistanceAndSingleDistanceCannotMasqueradeAsTrained() {
        val rows=samples("train",0)
        assertThrows(IllegalArgumentException::class.java) { DepthModel.fit(rows.map { it.copy(features=it.features.copyOf().also { f->f[10]=1.0 }) },true) }
        val full=DepthModel.fit(rows,true)
        assertNull(full.predict(EyeFeatures(0.0,0.0,.5,.5,.3)))
        assertThrows(IllegalArgumentException::class.java) { DepthModel.parse(full.json("a"),"b") }
    }
    @Test fun smoothingReducesJitterButReleasesForNewTargetAndAfterGap() {
        val s=GazeStabilizer();var p=.5 to .5
        for(i in 0..30) p=s.update((.5+if(i%2==0).01 else -.01) to .5,i*33L)
        assertTrue(abs(p.first-.5)<.005)
        val moved=s.update(.9 to .5,1023)
        assertTrue(moved.first>.75)
        assertEquals(.1 to .2,s.update(.1 to .2,2000))
    }
}
