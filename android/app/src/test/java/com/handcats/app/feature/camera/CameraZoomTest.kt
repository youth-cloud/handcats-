package com.handcats.app.feature.camera

import org.junit.Assert.*
import org.junit.Test

class CameraZoomTest {
    @Test fun discoveredOpticalStopsAreKeptAndDuplicateDigitalStopsAreMerged() {
        assertEquals(listOf(.6f,1f,2f,3.2f,5f,10f),cameraZoomStops(listOf(.6f,1f,1f,2f,3.2f),.6f,20f))
        assertEquals(listOf(1f,2f),cameraZoomStops(listOf(1f),1f,4f))
    }
    @Test fun logicalCameraCanExposeUltrawideWithoutPhysicalIds() {
        assertEquals(listOf(.6f,1f,2f,5f),cameraZoomStops(listOf(1f),.6f,8f))
        assertEquals("0.6",zoomLabel(.6f))
        assertEquals("2",zoomLabel(2f))
    }
}
