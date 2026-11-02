/*
 * ML Kit barcode frame decoder (production).
 *
 * The production [BarcodeFrameDecoder]: wraps Google ML Kit's on-device barcode
 * scanner. All ML Kit types live here, isolated from the testable
 * [QrScanController]. Configured to read QR codes plus the common 1-D formats a
 * wellhead tag might carry (Code 128 / Code 39), matching the iOS scanner's
 * `.qr`/barcode object types (design.md "QR Scanning Flow": "Android uses
 * CameraX `ImageAnalysis` feeding ML Kit's barcode scanner").
 *
 * This class is not exercised by the deterministic scanner-timing tests (those
 * run against the controller with a fake decoder); it is the thin ML Kit
 * adapter the camera shell uses at runtime.
 *
 * Introduced by task 19.1.
 */
package com.sagd.welltest.integrations

import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * Decodes barcodes from CameraX frames using ML Kit.
 *
 * @param scanner the ML Kit [BarcodeScanner]. Defaults to a scanner restricted
 *   to QR plus Code 128 / Code 39; restricting the format set lets ML Kit skip
 *   detectors it does not need, which tightens the decode latency the 2-second
 *   budget (Req 6.1) depends on.
 */
class MlKitBarcodeFrameDecoder(
    private val scanner: BarcodeScanner = defaultScanner(),
) : BarcodeFrameDecoder {

    @androidx.camera.core.ExperimentalGetImage
    override fun decode(frame: BarcodeFrameDecoder.Frame, onResult: (String?) -> Unit) {
        val cameraImage = frame as? BarcodeFrameDecoder.Frame.CameraImage
        if (cameraImage == null) {
            onResult(null)
            return
        }

        val proxy = cameraImage.image as ImageProxy
        val media = proxy.image
        if (media == null) {
            // No backing image to analyze; close the proxy so backpressure
            // keeps flowing and report "no decode".
            proxy.close()
            onResult(null)
            return
        }

        val input = InputImage.fromMediaImage(media, cameraImage.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                // Take the first non-empty decoded value, if any.
                val value = barcodes.firstNotNullOfOrNull { it.rawValue?.takeIf(String::isNotEmpty) }
                onResult(value)
            }
            .addOnFailureListener {
                // A decode failure on one frame is not fatal: report "no decode"
                // so the controller keeps scanning until the next frame or the
                // unreadable timeout (Req 6.4).
                onResult(null)
            }
            .addOnCompleteListener {
                // Always release the frame so the ImageAnalysis queue advances.
                proxy.close()
            }
    }

    companion object {
        /** A scanner restricted to the formats a wellhead tag is expected to use. */
        fun defaultScanner(): BarcodeScanner {
            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_QR_CODE,
                    Barcode.FORMAT_CODE_128,
                    Barcode.FORMAT_CODE_39,
                )
                .build()
            return BarcodeScanning.getClient(options)
        }
    }
}
