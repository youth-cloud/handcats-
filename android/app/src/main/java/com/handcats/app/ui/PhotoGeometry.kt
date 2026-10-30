package com.handcats.app.ui

import com.handcats.app.data.remote.Observation

data class PhotoRect(val left: Float, val top: Float, val width: Float, val height: Float)

/** Centered ContentScale.Fit transform, including letterboxing. */
fun fittedPhotoRect(imageWidth: Float, imageHeight: Float, viewWidth: Float, viewHeight: Float): PhotoRect {
    if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return PhotoRect(0f, 0f, 0f, 0f)
    val scale = minOf(viewWidth / imageWidth, viewHeight / imageHeight)
    val width = imageWidth * scale
    val height = imageHeight * scale
    return PhotoRect((viewWidth - width) / 2, (viewHeight - height) / 2, width, height)
}

fun observationTitle(item: Observation): String = when (item.detections.size) {
    0 -> "一次新的探索"
    1 -> item.detections.first().let { if (it.catCode == null) "遇见一只猫" else displayName(it.label) }
    else -> "同框相遇 · ${item.detections.size} 个猫咪识别结果"
}
