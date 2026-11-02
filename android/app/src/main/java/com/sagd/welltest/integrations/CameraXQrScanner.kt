/*
 * CameraX QR scanner shell (production).
 *
 * The thin platform shell around the testable [QrScanController]: it owns the
 * CameraX `ImageAnalysis` pipeline, binds it to a lifecycle, runs each frame
 * through a [BarcodeFrameDecoder] (ML Kit in production), and forwards decoded
 * values plus periodic timeout checks into the controller. All CameraX and
 * lifecycle types live here; the timing/dispatch logic and the outcome
 * branching live in [QrScanController] and `WellLookupUseCase` respectively, so
 * nothing in this file is on the deterministic-test path (task 19.5 tests the
 * controller directly).
 *
 * Responsibilities (design.md "QR Scanning Flow"):
 *   - Build an `ImageAnalysis` use case with STRATEGY_KEEP_ONLY_LATEST
 *     backpressure (analyze the freshest frame; the decode budget cares about
 *     latency, not throughput) and bind it to the provided lifecycle alongside
 *     the camera selector.
 *   - On every analyzed frame: run [onTimeoutCheck] first (so the 10s unreadable
 *     deadline (Req 6.4) is enforced even under a steady frame stream), then, if
 *     still scanning, hand the frame to the decoder and, on a decode, call
 *     [QrScanController.onDecoded].
 *   - Also schedule a lightweight periodic timeout tick so an idle camera (no
 *     frames arriving) still trips the unreadable timeout.
 *
 * The controller's work is suspending (it calls the `WellLookupUseCase`), so the
 * shell hops onto the provided [scope] to invoke it rather than blocking the
 * analysis executor.
 *
 * Introduced by task 19.1.
 */
package com.sagd.welltest.integrations

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import com.sagd.welltest.domain.WellLookupUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

/**
 * Binds a CameraX QR-scanning session to a lifecycle and drives a
 * [QrScanController].
 *
 * @param cameraProvider the bound [ProcessCameraProvider] (obtained by the
 *   caller via `ProcessCameraProvider.getInstance(context)`).
 * @param lifecycleOwner the lifecycle the camera use cases are bound to.
 * @param analysisExecutor the executor ML Kit/analysis callbacks run on. Must be
 *   single-threaded so frame callbacks and timeout checks stay serialized
 *   (matching [QrScanController]'s single-sequence requirement).
 * @param scope coroutine scope used to invoke the controller's suspending
 *   methods (lookup/outcome) and to run the periodic timeout tick.
 * @param decoder the barcode decoder; defaults to the ML Kit production decoder.
 * @param clock monotonic time source; defaults to the production system clock.
 * @param lookupUseCase resolves a decoded identifier against the local store.
 * @param onOutcome invoked once per session with the terminal [ScanOutcome].
 * @param cameraSelector which camera to use; defaults to the back camera.
 */
class CameraXQrScanner(
    private val cameraProvider: ProcessCameraProvider,
    private val lifecycleOwner: LifecycleOwner,
    private val analysisExecutor: Executor,
    private val scope: CoroutineScope,
    lookupUseCase: WellLookupUseCase,
    onOutcome: suspend (ScanOutcome) -> Unit,
    private val decoder: BarcodeFrameDecoder = MlKitBarcodeFrameDecoder(),
    clock: ScannerClock = SystemScannerClock(),
    private val cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
) {

    private val controller = QrScanController(
        lookupUseCase = lookupUseCase,
        onOutcome = { outcome ->
            // The session is over: stop analyzing and surface the outcome.
            stop()
            onOutcome(outcome)
        },
        clock = clock,
    )

    private var analysis: ImageAnalysis? = null
    private var timeoutTickJob: Job? = null

    /**
     * Start a scan session: build and bind the `ImageAnalysis` use case and
     * begin the decode/timeout clocks. Idempotent while already scanning.
     */
    @androidx.camera.core.ExperimentalGetImage
    fun start() {
        if (controller.isScanning) return

        controller.start()
        bindAnalysis()
        startTimeoutTicker()
    }

    /**
     * Restart after a terminal outcome (the rescan control, Req 6.3/6.4):
     * re-arm the controller and rebind the analysis pipeline.
     */
    @androidx.camera.core.ExperimentalGetImage
    fun rescan() {
        controller.rescan()
        bindAnalysis()
        startTimeoutTicker()
    }

    /**
     * Stop the session: unbind the analysis use case and cancel the timeout
     * ticker. Called automatically when the controller reaches a terminal
     * outcome, and by the caller on teardown.
     */
    fun stop() {
        timeoutTickJob?.cancel()
        timeoutTickJob = null
        analysis?.let { cameraProvider.unbind(it) }
        analysis?.clearAnalyzer()
        analysis = null
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun bindAnalysis() {
        // Rebuild fresh so a rescan starts from a clean analyzer.
        analysis?.let { cameraProvider.unbind(it) }

        val imageAnalysis = ImageAnalysis.Builder()
            // Latency, not throughput: always analyze the newest frame so a code
            // entering view is decoded as fast as possible (Req 6.1).
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalysis.setAnalyzer(analysisExecutor) { proxy ->
            val rotation = proxy.imageInfo.rotationDegrees
            val frame = BarcodeFrameDecoder.Frame.CameraImage(
                image = proxy,
                rotationDegrees = rotation,
            )
            // Enforce the unreadable timeout on each frame, then attempt decode.
            // Frame-ownership contract: whichever path consumes the frame closes
            // it exactly once. If we do NOT hand the frame to the decoder (the
            // session already ended, or the timeout just tripped), the shell
            // closes it here; if we DO hand it over, the decoder closes it (see
            // [BarcodeFrameDecoder.decode]). This guarantees one close and keeps
            // the KEEP_ONLY_LATEST backpressure queue flowing.
            scope.launch {
                val terminatedByTimeout = controller.onTimeoutCheck()
                if (terminatedByTimeout || !controller.isScanning) {
                    proxy.close()
                    return@launch
                }
                decoder.decode(frame) { decoded ->
                    if (decoded != null) {
                        scope.launch { controller.onDecoded(decoded) }
                    }
                }
            }
        }

        cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalysis)
        analysis = imageAnalysis
    }

    /**
     * A low-frequency timer that trips the unreadable timeout even when no
     * frames arrive (e.g. a covered or frozen camera). The on-frame check is the
     * common path; this is the safety net for an idle stream.
     */
    private fun startTimeoutTicker() {
        timeoutTickJob?.cancel()
        timeoutTickJob = scope.launch {
            while (isActive && controller.isScanning) {
                delay(TIMEOUT_TICK_MILLIS)
                if (controller.onTimeoutCheck()) break
            }
        }
    }

    companion object {
        /** Cadence of the idle-stream timeout safety-net tick. */
        private const val TIMEOUT_TICK_MILLIS: Long = 250L
    }
}
