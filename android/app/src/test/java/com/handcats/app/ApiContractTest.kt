package com.handcats.app

import com.handcats.app.data.remote.*
import com.handcats.app.ui.displayName
import com.handcats.app.ui.fittedPhotoRect
import com.handcats.app.ui.observationTitle
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ApiContractTest {
    @Test fun detectionOverlaysFollowFittedImageRatherThanLetterbox() {
        val wide = fittedPhotoRect(1000f, 500f, 400f, 400f)
        assertEquals(0f, wide.left, .01f); assertEquals(100f, wide.top, .01f)
        assertEquals(400f, wide.width, .01f); assertEquals(200f, wide.height, .01f)
        val tall = fittedPhotoRect(500f, 1000f, 400f, 400f)
        assertEquals(100f, tall.left, .01f); assertEquals(0f, tall.top, .01f)
        assertEquals(200f, tall.width, .01f); assertEquals(400f, tall.height, .01f)
    }
    @Test fun multicatResponsePreservesIndividualSelectionAndSummary() {
        val item=ApiClient.json.decodeFromString<Observation>("""{"id":"1","created_at":"2026-10-28T10:00:00Z","image_url":"/photo","status":"processed","confirmed":true,"confirmed_detection_ids":[2],"detections":[{"id":1,"label":"中华田园猫(白)","confidence":0.96,"cat_code":"domestic_white","confirmed":false},{"id":2,"label":"中华田园猫(狸花)","confidence":0.93,"cat_code":"domestic_tabby","confirmed":true}]}""")
        assertEquals(listOf(2L), item.confirmedDetectionIds)
        assertFalse(item.detections[0].confirmed); assertTrue(item.detections[1].confirmed)
        assertTrue(observationTitle(item).contains("2 个"))
        assertEquals("{\"confirmed_detection_ids\":[2]}", ApiClient.json.encodeToString(ObservationEdit(confirmedDetectionIds=listOf(2L))))
    }
    @Test fun domesticCatNamesStayConsistentAcrossOldAndNewRecords() {
        assertEquals("中华田园猫(狸花)", displayName("狸花猫"))
        assertEquals("中华田园猫(橘)", displayName("中华田园猫（橘）"))
        assertEquals("中华田园猫(白)", displayName("中华田园猫(白)"))
        assertEquals("中华田园猫(三花)", displayName("三花猫"))
        assertEquals("布偶猫", displayName("布偶猫"))
    }
    @Test fun localNetworkPermissionAppliesToLocalAddresses() {
        for (address in listOf("http://192.168.1.10:8000", "http://10.0.2.2:8000", "http://172.16.0.1", "http://169.254.1.2", "http://localhost", "http://cats.local", "http://[fd00::1]:8000")) {
            assertTrue(address, ApiClient.isLocalServer(address))
        }
        for (address in listOf("https://example.com", "https://8.8.8.8", "http://172.32.0.1", "https://[2606:4700:4700::1111]")) {
            assertFalse(address, ApiClient.isLocalServer(address))
        }
    }
    @Test fun partialEditsDoNotOverwriteUnrelatedState() {
        assertEquals("{\"favorite\":true}", ApiClient.json.encodeToString(ObservationEdit(favorite = true)))
        assertEquals("{\"confirmed\":false}", ApiClient.json.encodeToString(ObservationEdit(confirmed = false)))
    }
    @Test fun observationAcceptsServerMetadataAndUnknownCategory() {
        val item = ApiClient.json.decodeFromString<Observation>("""{"id":"1","created_at":"2026-10-09T10:00:00Z","image_url":"/api/v1/observations/1/image","status":"processed","model_version":"yolo26n","detections":[{"label":"cat","confidence":0.9,"cat_code":null}]}""")
        assertNull(item.detections.single().catCode)
        assertFalse(item.confirmed)
    }
    @Test fun addressValidationKeepsCredentialsOutOfServerConfiguration() {
        assertEquals("http://192.168.1.10:8000/", ApiClient.normalize(" http://192.168.1.10:8000 "))
        for (invalid in listOf("http://user:secret@localhost/", "http://localhost/api/", "http://localhost/?token=a", "ftp://localhost")) {
            assertThrows(IllegalArgumentException::class.java) { ApiClient.normalize(invalid) }
        }
    }
}
