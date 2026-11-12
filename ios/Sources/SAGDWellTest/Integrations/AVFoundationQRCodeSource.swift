//
//  AVFoundation-backed QR / barcode code source
//
//  The production `QRCodeSource`: an `AVCaptureSession` with a video-device
//  input feeding an `AVCaptureMetadataOutput` configured for `.qr` and common
//  1-D barcode symbologies. Each decoded `AVMetadataMachineReadableCodeObject`
//  with a non-empty `stringValue` is forwarded onto the `decodedStrings()`
//  stream the `QRScannerCoordinator` consumes; the coordinator owns the 2- and
//  10-second budgets and the well lookup.
//
//  The whole file is guarded by `#if canImport(AVFoundation)` so the package
//  still builds on platforms without AVFoundation (the coordinator and seam in
//  `QRScanner.swift` are platform-agnostic and remain available there).
//
//  Requirements: 6.1 (decode), 6.3/6.5/6.6 (string delivered to lookup).

#if canImport(AVFoundation)

import Foundation
import AVFoundation

/// Production `QRCodeSource` driven by `AVCaptureMetadataOutput`.
///
/// Configuration happens lazily in `start()`: the session is built, a camera
/// input added, and a metadata output attached for `.qr` plus the barcode
/// object types the device reports as available. Decoded strings are delivered
/// through an `AsyncStream` whose continuation is fed by the capture delegate.
///
/// The capture-session mutation and delegate callbacks are serialized onto a
/// dedicated session queue; the small amount of shared state (continuation,
/// started flag) is guarded by a lock so `start()` / `stop()` are safe to call
/// from any isolation domain.
public final class AVFoundationQRCodeSource: NSObject, QRCodeSource, @unchecked Sendable {

    /// Metadata object types to request. `.qr` is the primary target (Req 6.1);
    /// the common retail/industrial 1-D barcode symbologies are added when the
    /// metadata output reports them as available so pad-manifold barcodes decode
    /// too (design: "`.qr`/barcode object types").
    private static let desiredCodeTypes: [AVMetadataObject.ObjectType] = [
        .qr,
        .code128,
        .code39,
        .code93,
        .ean13,
        .ean8,
        .upce,
        .pdf417,
        .aztec,
        .dataMatrix,
        .interleaved2of5,
        .itf14
    ]

    private let sessionQueue = DispatchQueue(label: "com.sagd.welltest.qrscanner.session")
    private let captureSession = AVCaptureSession()
    private let metadataOutput = AVCaptureMetadataOutput()

    private let lock = NSLock()
    private var continuation: AsyncStream<String>.Continuation?
    private var configured = false

    public override init() {
        super.init()
    }

    // MARK: - QRCodeSource

    public func decodedStrings() -> AsyncStream<String> {
        AsyncStream { continuation in
            lock.lock()
            self.continuation = continuation
            lock.unlock()

            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                self.lock.lock()
                self.continuation = nil
                self.lock.unlock()
            }
        }
    }

    /// The capture session, exposed so a preview layer can show the camera
    /// feed while scanning. The session is configured lazily by `start()`.
    public var previewSession: AVCaptureSession { captureSession }

    public func start() async {
        // Ask for camera access up front. Without this the first scan builds
        // its input before permission is granted, fails silently, and reports
        // "unreadable". Returns immediately once the user has already decided.
        _ = await AVCaptureDevice.requestAccess(for: .video)
        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            sessionQueue.async { [weak self] in
                guard let self else { cont.resume(); return }
                self.configureIfNeeded()
                if !self.captureSession.isRunning {
                    self.captureSession.startRunning()
                }
                cont.resume()
            }
        }
    }

    public func stop() async {
        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            sessionQueue.async { [weak self] in
                guard let self else { cont.resume(); return }
                if self.captureSession.isRunning {
                    self.captureSession.stopRunning()
                }
                cont.resume()
            }
        }
    }

    // MARK: - Session configuration

    /// Build the capture graph once: camera input + metadata output configured
    /// for the desired code types. Any failure leaves the source unconfigured
    /// so `decodedStrings()` simply never emits, which the coordinator surfaces
    /// as `unreadable` after the 10-second budget (Req 6.4).
    private func configureIfNeeded() {
        guard !configured else { return }

        captureSession.beginConfiguration()
        defer { captureSession.commitConfiguration() }

        guard
            let device = Self.defaultVideoDevice(),
            let input = try? AVCaptureDeviceInput(device: device),
            captureSession.canAddInput(input)
        else {
            return
        }
        captureSession.addInput(input)

        guard captureSession.canAddOutput(metadataOutput) else { return }
        captureSession.addOutput(metadataOutput)

        metadataOutput.setMetadataObjectsDelegate(self, queue: sessionQueue)
        // Only request the types the output actually supports on this device;
        // requesting an unavailable type raises an exception.
        let available = Set(metadataOutput.availableMetadataObjectTypes)
        metadataOutput.metadataObjectTypes = Self.desiredCodeTypes.filter { available.contains($0) }

        configured = true
    }

    /// The default capture device for scanning: the back wide-angle camera when
    /// present, otherwise any available video device.
    private static func defaultVideoDevice() -> AVCaptureDevice? {
        if let back = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) {
            return back
        }
        return AVCaptureDevice.default(for: .video)
    }
}

// MARK: - AVCaptureMetadataOutputObjectsDelegate

extension AVFoundationQRCodeSource: AVCaptureMetadataOutputObjectsDelegate {

    public func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        // Forward the first machine-readable code with a non-empty string value.
        // The coordinator consumes only the first decode, so later objects in
        // the same callback are redundant; yielding them is harmless.
        for object in metadataObjects {
            guard
                let code = object as? AVMetadataMachineReadableCodeObject,
                let value = code.stringValue,
                !value.isEmpty
            else {
                continue
            }
            lock.lock()
            let continuation = self.continuation
            lock.unlock()
            continuation?.yield(value)
        }
    }
}

#endif
