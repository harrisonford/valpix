import AVFoundation
import SwiftUI

/// The camera, looking for a valpix code. Anything else is ignored.
struct ScanView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        ZStack(alignment: .bottom) {
            CameraPreview { model.open(text: $0) }
                .ignoresSafeArea()
                .accessibilityLabel("Cámara")
            // A code can also arrive as text: a link someone sent.
            GlassButton(icon: "doc.on.clipboard", label: "Pegar código", size: 52) {
                if let text = UIPasteboard.general.string { model.open(text: text) }
            }
            .padding(.bottom, 36)
        }
    }
}

/// A live camera view that reports each QR code it reads.
struct CameraPreview: UIViewRepresentable {
    let onCode: (String) -> Bool

    func makeCoordinator() -> Coordinator { Coordinator(onCode: onCode) }

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.backgroundColor = .black
        context.coordinator.start(in: view)
        return view
    }

    func updateUIView(_ view: PreviewView, context: Context) {}

    static func dismantleUIView(_ view: PreviewView, coordinator: Coordinator) { coordinator.stop() }

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }

    /// AVCaptureSession is safe to start and stop from any one thread.
    private nonisolated struct SessionBox: @unchecked Sendable { let session: AVCaptureSession }

    final class Coordinator: NSObject, AVCaptureMetadataOutputObjectsDelegate {
        private let session = AVCaptureSession()
        private let onCode: (String) -> Bool
        private var finished = false

        init(onCode: @escaping (String) -> Bool) { self.onCode = onCode }

        func start(in view: PreviewView) {
            // The simulator has no camera; the view simply stays black.
            guard let camera = AVCaptureDevice.default(for: .video),
                  let input = try? AVCaptureDeviceInput(device: camera),
                  session.canAddInput(input)
            else { return }
            session.addInput(input)
            let output = AVCaptureMetadataOutput()
            guard session.canAddOutput(output) else { return }
            session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: .main)
            output.metadataObjectTypes = [.qr]
            view.previewLayer.session = session
            view.previewLayer.videoGravity = .resizeAspectFill
            // Starting the camera blocks, so it runs off the main thread.
            let box = SessionBox(session: session)
            DispatchQueue.global(qos: .userInitiated).async { box.session.startRunning() }
        }

        func stop() {
            let box = SessionBox(session: session)
            DispatchQueue.global(qos: .userInitiated).async { box.session.stopRunning() }
        }

        nonisolated func metadataOutput(
            _ output: AVCaptureMetadataOutput,
            didOutput objects: [AVMetadataObject],
            from connection: AVCaptureConnection
        ) {
            let codes = objects.compactMap { ($0 as? AVMetadataMachineReadableCodeObject)?.stringValue }
            MainActor.assumeIsolated {
                guard !finished else { return }
                for code in codes where onCode(code) {
                    finished = true
                    UINotificationFeedbackGenerator().notificationOccurred(.success)
                    break
                }
            }
        }
    }
}
