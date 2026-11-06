import SwiftUI
import UIKit
import AVFoundation
import CoreImage.CIFilterBuiltins

// Native QR for the Control "Add a device" wizard (Control-on-iOS C6 / Track B, B-4) — the iOS
// replacement for `:ui-core`'s Compose `QrScanner`/`QrCode`, which can't render on iOS (House Rule #5).
//  • Scan (joiner): an AVFoundation camera scanner that emits the decoded `tenshu://join` string.
//  • Show (host):   a CoreImage `CIQRCodeGenerator` image of the host pairing string.
//
// ⚠ The camera path can't be exercised on the simulator (no capture device); `isCameraAvailable` is
// false there, so the Scan tab falls back to the text-code entry. The generator works everywhere.

/// Whether a usable video capture device exists — false on the simulator.
var isCameraAvailable: Bool {
    AVCaptureDevice.default(for: .video) != nil
}

/// Render `string` as a QR `UIImage` via CoreImage (works on the simulator + device).
func qrCodeImage(from string: String) -> UIImage? {
    let context = CIContext()
    let filter = CIFilter.qrCodeGenerator()
    filter.message = Data(string.utf8)
    filter.correctionLevel = "M"
    guard let output = filter.outputImage else { return nil }
    // Upscale the tiny native output with nearest-neighbour so the modules stay crisp.
    let scaled = output.transformed(by: CGAffineTransform(scaleX: 10, y: 10))
    guard let cg = context.createCGImage(scaled, from: scaled.extent) else { return nil }
    return UIImage(cgImage: cg)
}

/// A full-bleed camera QR scanner. Calls `onScanned` with each decoded payload, throttled to one
/// detection per `rescanCooldown` so a *rejected* code (the VM stays on ScanQr with an error) can be
/// retried without freezing the preview; a *valid* scan advances the VM, which unmounts this view and
/// stops the session via `dismantleUIViewController`. The VM owns parsing — `onQrScanned` reads the
/// `tenshu://join` link.
struct QRScannerView: UIViewControllerRepresentable {
    let onScanned: (String) -> Void

    func makeUIViewController(context: Context) -> ScannerViewController {
        let vc = ScannerViewController()
        vc.onScanned = onScanned
        return vc
    }

    func updateUIViewController(_ uiViewController: ScannerViewController, context: Context) {
        uiViewController.onScanned = onScanned
    }

    /// SwiftUI removed the scanner (e.g. a valid scan advanced the wizard off ScanQr) — stop capture.
    static func dismantleUIViewController(_ uiViewController: ScannerViewController, coordinator: ()) {
        uiViewController.stop()
    }
}

final class ScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onScanned: ((String) -> Void)?

    private let session = AVCaptureSession()
    private var preview: AVCaptureVideoPreviewLayer?
    private var didScan = false

    /// Gap before the scanner re-arms after a detection, so a rejected code doesn't tight-loop.
    private let rescanCooldown: TimeInterval = 2.0

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
            guard granted else { return }
            DispatchQueue.main.async { self?.configureSession() }
        }
    }

    private func configureSession() {
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else { return }
        session.addInput(input)

        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else { return }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        output.metadataObjectTypes = [.qr]

        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.frame = view.bounds
        view.layer.addSublayer(layer)
        preview = layer

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            self?.session.startRunning()
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview?.frame = view.bounds
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        stop()
    }

    /// Stop capture off the main thread (`stopRunning` blocks). Safe to call repeatedly.
    func stop() {
        let session = self.session
        DispatchQueue.global(qos: .userInitiated).async {
            if session.isRunning { session.stopRunning() }
        }
    }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard !didScan,
              let object = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              let value = object.stringValue else { return }
        // Gate further detections during the cooldown, but keep the session running so a rejected code
        // can be retried (a valid scan unmounts this view → `dismantleUIViewController` stops capture).
        didScan = true
        onScanned?(value)
        DispatchQueue.main.asyncAfter(deadline: .now() + rescanCooldown) { [weak self] in
            self?.didScan = false
        }
    }
}
