package com.handcats.app

import com.handcats.app.data.*
import com.handcats.app.data.remote.*
import com.handcats.app.ui.observationTitle
import org.junit.Assert.*
import org.junit.Test

class ObservationPresentationTest {
    private val photo=Observation("photo","now","/image","processed",confirmed=true,
        detections=listOf(DetectionItem("波斯猫",.58f,catCode="persian",id=1,confirmed=true)))
    private val result=ReviewResponse(true,"mock",listOf(
        ReviewSuggestion("中华田园猫(白)","domestic_white",.9f),
        ReviewSuggestion("中华田园猫(狸花)","domestic_tabby",.9f)),message="ok",reviewId="review")

    @Test fun reviewReplacesEntirePresentationWhenOneCatBecomesTwo() {
        val pending=pendingReview(photo,result)
        val presented=presentedObservation(photo,pending)
        assertEquals(2,presented.detections.size)
        assertFalse(observationTitle(presented).contains("波斯"))
        assertFalse(presented.confirmed)
        assertEquals(emptyList<Long>(),presented.confirmedDetectionIds)
        assertTrue(presented.detections.all { it.source=="deepseek" })
    }
    @Test fun failedRetryKeepsLastSuccessfulPresentation() {
        val saved=photo.copy(reviewResult=result)
        assertEquals(result,pendingReview(saved,ReviewResponse(false,"mock",message="failed")))
        assertEquals(result,pendingReview(saved,null))
    }
    @Test fun automaticCatalogUsesNormalResultButOutsideSpeciesUsesReview() {
        assertNull(pendingReview(photo.copy(reviewResult=result.copy(automatic=true)),null))
        val outside=result.copy(automatic=true,suggestions=listOf(ReviewSuggestion("土耳其安哥拉猫",confidence=.9f)))
        assertEquals(outside,pendingReview(photo.copy(reviewResult=outside),null))
    }
    @Test fun acceptedReviewDoesNotReappearAsPending() {
        assertNull(pendingReview(photo.copy(reviewResult=result,appliedReviewId="review"),null))
    }
    @Test fun emptyReviewDoesNotShowOldCatOrCollectAction() {
        val empty=result.copy(suggestions=emptyList())
        val presented=presentedObservation(photo,pendingReview(photo,empty))
        assertTrue(presented.detections.isEmpty())
        assertFalse(presented.confirmed)
    }
}
