package com.handcats.app

import com.handcats.app.data.remote.*
import com.handcats.app.ui.localizedDetections
import org.junit.Assert.*
import org.junit.Test

class ReviewRegressionTest {
    @Test fun bothCatsKeepTheirOwnPositionsAndNumbers() {
        val sameBreed = listOf(
            DetectionItem("布偶猫", .9f, listOf(.05f,.1f,.45f,.9f)),
            DetectionItem("布偶猫", .8f, listOf(.55f,.1f,.95f,.9f)))
        val boxes = localizedDetections(sameBreed)
        assertEquals(listOf(0,1), boxes.map { it.index })
        assertNotEquals(boxes[0].value.boundingBox, boxes[1].value.boundingBox)
    }
    @Test fun missingOrInvalidPositionDoesNotStealAnotherCatsBox() {
        val invalid = listOf(
            DetectionItem("猫", .9f),
            DetectionItem("猫", .9f, listOf(.55f,.1f,.95f,.9f)),
            DetectionItem("猫", .9f, listOf(0f,0f,Float.NaN,1f)),
            DetectionItem("猫", .9f, listOf(-1f,0f,1f,1f)))
        assertEquals(listOf(1),localizedDetections(invalid).map { it.index })
    }
    @Test fun photoQuotaAndLastReviewPersistInContract() {
        val photo = ApiClient.json.decodeFromString<Observation>("""{"id":"1","created_at":"now","image_url":"/photo","status":"processed","review_attempts":3,"review_limit":3,"review_result":{"success":true,"model":"mock","review_id":"last","message":"ok","suggestions":[{"label":"布偶猫","confidence":0.9,"bounding_box":[0.1,0.1,0.4,0.9]}]}}""")
        assertEquals(3,photo.reviewAttempts)
        assertTrue(photo.reviewAttempts >= photo.reviewLimit)
        assertEquals("last",photo.reviewResult?.reviewId)
        assertEquals(4,photo.reviewResult?.suggestions?.single()?.boundingBox?.size)
    }
}
