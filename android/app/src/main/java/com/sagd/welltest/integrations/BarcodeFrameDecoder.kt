/*
 * Barcode frame decoder seam.
 *
 * The one-way boundary between "a camera frame" and "a decoded string". The
 * production decoder ([MlKitBarcodeFrameDecoder]) wraps ML Kit's barcode
 * scanner; the core [QrScanController] never sees a frame or an ML Kit type. The
 * [CameraXQrScanner] shell owns the CameraX `ImageAnalysis` pipeline and hands
 * each frame to a decoder, forwarding any decoded value to the controller.
 *
 * Keeping the decode behind this interface means the 2s/10s timing and dispatch
 * logic is tested against the controller with a trivial fake (no ML Kit, no
 * camera), and the ML Kit integration is exercised separately. Mirrors how the
 * sync layer hides its HTTP client behind `SyncPushClient`.
 *
 * Introduced by task 19.1.
 */
package com.sagd.welltest.integrations

/**
 * Decodes barcodes from a single camera frame.
 *
 * The [Frame] type is intentionally opaque to the core: the production decoder
 * accepts a CameraX `ImageProxy` wrapped as a [Frame.CameraImage], while tests
 * (and the controller) never construct one. This keeps CameraX/ML Kit types out
 * of the testable path.
 */
interface BarcodeFrameDecoder {

    /**
     * Attempt to decode the given [frame].
     *
     * @param frame the camera frame to analyze.
     * @param onResult invoked once with the first decoded value, or null if the
     *   frame contained no decodable barcode. Implementations MUST always call
     *   this (even on failure, with null). The implementation OWNS the frame and
     *   MUST close it exactly once as part of completing the decode (on or just
     *   after [onResult]) so the CameraX `ImageAnalysis` backpressure queue keeps
     *   flowing; the caller MUST NOT touch the frame after handing it over.
     */
    fun decode(frame: Frame, onResult: (String?) -> Unit)

    /** An opaque camera frame handed to the decoder by the camera shell. */
    sealed interface Frame {
        /**
         * A CameraX frame. [image] is an `androidx.camera.core.ImageProxy`,
         * typed as [Any] here so this core file carries no CameraX import; the
         * production decoder casts it back. [rotationDegrees] is the frame's
         * rotation as reported by CameraX.
         */
        data class CameraImage(
            val image: Any,
            val rotationDegrees: Int,
        ) : Frame
    }
}
