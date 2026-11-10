package com.handcats.app.feature.camera

import androidx.camera.core.ImageCapture
import org.junit.Assert.*
import org.junit.Test
import androidx.camera.core.CameraControl
import java.util.concurrent.ExecutionException
import java.util.concurrent.CancellationException

class CameraFlashTest {
    @Test fun torchAndCaptureFlashAreMutuallyExclusive() {
        assertEquals(ImageCapture.FLASH_MODE_OFF, CameraFlash.TORCH.captureMode(true))
        assertTrue(CameraFlash.TORCH.torchEnabled(true))
        assertEquals(ImageCapture.FLASH_MODE_AUTO, CameraFlash.AUTO.captureMode(true))
        assertEquals(ImageCapture.FLASH_MODE_ON, CameraFlash.ON.captureMode(true))
        assertEquals(ImageCapture.FLASH_MODE_OFF, CameraFlash.OFF.captureMode(true))
        for (mode in listOf(CameraFlash.OFF, CameraFlash.AUTO, CameraFlash.ON)) assertFalse(mode.torchEnabled(true))
    }
    @Test fun unsupportedLensNeverEnablesFlashOrTorch() {
        for (mode in CameraFlash.entries) {
            assertEquals(ImageCapture.FLASH_MODE_OFF, mode.captureMode(false))
            assertFalse(mode.torchEnabled(false))
        }
    }
    @Test fun normalCameraCancellationDoesNotShowFailure() {
        val tracker=FlashRequestTracker()
        assertNull(tracker.complete(tracker.begin(),ExecutionException(CameraControl.OperationCanceledException("Camera closed"))))
        assertNull(tracker.complete(tracker.begin(),CancellationException()))
    }
    @Test fun oldCallbacksCannotOverwriteNewModeOrSurviveLeavingCamera() {
        val tracker=FlashRequestTracker()
        val old=tracker.begin()
        val current=tracker.begin()
        assertNull(tracker.complete(old,IllegalStateException("old failure")))
        assertEquals(true,tracker.complete(current,null))
        tracker.invalidate()
        assertNull(tracker.complete(current,IllegalStateException("camera unbound")))
    }
    @Test fun actualCurrentFailureIsReportedAndSuccessfulRetryClearsIt() {
        val tracker=FlashRequestTracker()
        assertEquals(false,tracker.complete(tracker.begin(),ExecutionException(IllegalStateException("hardware failure"))))
        assertEquals(true,tracker.complete(tracker.begin(),null))
    }
}
