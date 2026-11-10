package com.handcats.app.feature.camera

import androidx.camera.core.ImageCapture
import androidx.camera.core.CameraControl
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException

internal enum class CameraFlash(val label: String) {
    OFF("关闭"), AUTO("自动"), ON("开启"), TORCH("常亮");

    fun captureMode(hasFlash: Boolean): Int = when {
        !hasFlash -> ImageCapture.FLASH_MODE_OFF
        this == AUTO -> ImageCapture.FLASH_MODE_AUTO
        this == ON -> ImageCapture.FLASH_MODE_ON
        else -> ImageCapture.FLASH_MODE_OFF
    }

    fun torchEnabled(hasFlash: Boolean): Boolean = hasFlash && this == TORCH
}

/** CameraX cancels superseded controls during lens/lifecycle changes. */
internal class FlashRequestTracker {
    private var generation=0
    fun begin(): Int = ++generation
    fun invalidate() { generation++ }
    // null means an obsolete or normally cancelled request, not a flash failure.
    fun complete(request: Int, error: Throwable?): Boolean? {
        if(request!=generation)return null
        var cause=error
        while(cause is ExecutionException && cause.cause!=null)cause=cause.cause
        if(cause is CameraControl.OperationCanceledException || cause is CancellationException)return null
        return cause==null
    }
}
