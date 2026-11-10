package com.handcats.app.ui

import com.handcats.app.data.remote.DetectionItem

// Keep the original list index even when some results have no reliable position.
fun localizedDetections(detections: List<DetectionItem>): List<IndexedValue<DetectionItem>> =
    detections.withIndex().filter { (_, d) ->
        val b = d.boundingBox
        b.size == 4 && b.all { it.isFinite() && it in 0f..1f } && b[2] > b[0] && b[3] > b[1]
    }
