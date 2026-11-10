package com.handcats.app.data

import com.handcats.app.data.remote.*

/** One source for the entire result screen, so photo, title and actions agree. */
fun pendingReview(item: Observation, response: ReviewResponse?): ReviewResponse? =
    (response?.takeIf { it.success } ?: item.reviewResult?.takeIf { it.success })?.takeIf {
        it.reviewId != item.appliedReviewId &&
            !(it.automatic && it.suggestions.all { suggestion -> suggestion.catCode != null })
    }

fun presentedObservation(item: Observation, pending: ReviewResponse?): Observation =
    if (pending == null) item else item.copy(
        confirmed = false, confirmedDetectionIds = emptyList(),
        detections = pending.suggestions.map {
            DetectionItem(it.label, it.confidence, it.boundingBox, it.catCode, source = "deepseek")
        },
        message = if (pending.suggestions.isEmpty()) "未能确认清晰的动物类别，请换一张更清晰的照片。"
            else "请核对本次复核结果，勾选正确的猫咪后收进图鉴。"
    )
