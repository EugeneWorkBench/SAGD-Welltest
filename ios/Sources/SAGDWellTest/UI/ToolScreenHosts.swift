//
//  UI layer — hosts that make the tool screens reachable
//
//  The subcool, QR-lookup and escalation screens existed but nothing navigated
//  to them. These small host views are the navigation destinations used by the
//  well-selection screen's bottom toolbar. Each host owns its view model in a
//  `@StateObject`, because the screens take `@ObservedObject` and would
//  otherwise lose their state whenever the parent re-rendered.
//
//  Also here: the live camera preview for QR scanning and the camera/photo
//  picker that supplies escalation photos (the escalation screen takes photo
//  capture as an injected closure).

#if os(iOS) && canImport(SwiftUI) && canImport(UIKit) && canImport(AVFoundation)
import SwiftUI
import UIKit
import AVFoundation

// MARK: - Subcool

struct SubcoolHost: View {
    @StateObject private var viewModel = SubcoolEstimatorViewModel()

    var body: some View {
        SubcoolEstimatorScreen(viewModel: viewModel)
    }
}

// MARK: - QR scan

/// Live camera feed for aiming at a code.
struct QRCameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        // swiftlint:disable:next force_cast
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {}
}

struct QRScanHost: View {
    @StateObject private var viewModel: QRResultViewModel
    private let previewSession: AVCaptureSession?

    @MainActor
    init(graph: AppGraph) {
        let scan = graph.makeQRScanSession()
        _viewModel = StateObject(wrappedValue: scan.viewModel)
        previewSession = (scan.source as? AVFoundationQRCodeSource)?.previewSession
    }

    var body: some View {
        QRResultScreen(
            viewModel: viewModel,
            scanningPreview: previewSession.map { session in
                AnyView(
                    ZStack(alignment: .bottom) {
                        QRCameraPreview(session: session)
                        Text("Point the camera at the well's QR code")
                            .font(.subheadline)
                            .padding(8)
                            .background(.ultraThinMaterial, in: Capsule())
                            .padding(.bottom, 24)
                    }
                    .ignoresSafeArea(edges: .bottom)
                )
            }
        )
    }
}

// MARK: - Escalation

/// Presents the system camera (or the photo library where no camera exists,
/// e.g. the simulator) and returns JPEG bytes, or `nil` on cancel.
struct CameraPicker: UIViewControllerRepresentable {
    let onFinish: (Data?) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onFinish: onFinish) }

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = UIImagePickerController.isSourceTypeAvailable(.camera) ? .camera : .photoLibrary
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        private let onFinish: (Data?) -> Void
        init(onFinish: @escaping (Data?) -> Void) { self.onFinish = onFinish }

        func imagePickerController(
            _ picker: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
        ) {
            let image = info[.originalImage] as? UIImage
            onFinish(image?.jpegData(compressionQuality: 0.8))
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            onFinish(nil)
        }
    }
}

/// Owns the escalation view model and bridges the picker's callback to the
/// screen's `async` photo-capture closure.
@MainActor
final class EscalationHolder: ObservableObject {
    let viewModel: EscalationCaptureViewModel
    @Published var isPickerPresented = false
    private var continuation: CheckedContinuation<Data?, Never>?

    init(graph: AppGraph, wells: [Well]) {
        viewModel = graph.makeEscalationCaptureViewModel(availableWells: wells)
    }

    func capturePhoto() async -> Data? {
        // A previous request that was never answered must not be left hanging.
        finish(nil)
        return await withCheckedContinuation { continuation in
            self.continuation = continuation
            isPickerPresented = true
        }
    }

    /// Resume the waiting request exactly once, then close the picker.
    func finish(_ data: Data?) {
        isPickerPresented = false
        continuation?.resume(returning: data)
        continuation = nil
    }
}

struct EscalationHost: View {
    @StateObject private var holder: EscalationHolder

    @MainActor
    init(graph: AppGraph, wells: [Well]) {
        _holder = StateObject(wrappedValue: EscalationHolder(graph: graph, wells: wells))
    }

    var body: some View {
        EscalationCaptureScreen(
            viewModel: holder.viewModel,
            capturePhoto: { await holder.capturePhoto() }
        )
        // Swiping the picker away dismisses without a callback; resume with
        // `nil` so the pending request is never left hanging.
        .fullScreenCover(isPresented: $holder.isPickerPresented, onDismiss: { holder.finish(nil) }) {
            CameraPicker { holder.finish($0) }
                .ignoresSafeArea()
        }
    }
}
#endif
