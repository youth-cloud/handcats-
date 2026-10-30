package com.handcats.app.feature.camera

import java.util.Locale
import kotlin.math.abs

/** Ratios are relative to the default camera on the same side of the phone. */
internal fun cameraZoomStops(opticalRatios: List<Float>, min: Float, max: Float): List<Float> =
    (opticalRatios + listOf(1f, 2f, 5f, 10f) + if(min < .9f) listOf(min) else emptyList()).filter { it.isFinite() && it >= min && it <= max }
        .sorted().fold(emptyList()) { stops, ratio ->
            if (stops.lastOrNull()?.let { abs(it - ratio) < .12f } == true) stops else stops + ratio
        }

internal fun zoomLabel(ratio: Float): String =
    if (abs(ratio - ratio.toInt()) < .04f) "${ratio.toInt()}" else String.format(Locale.US, "%.1f", ratio)
