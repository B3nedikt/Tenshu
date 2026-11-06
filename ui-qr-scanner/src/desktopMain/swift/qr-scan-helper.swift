// qr-scan-helper — macOS camera QR scanner for desktop Control/Drive.
//
// A standalone command-line tool (mirrors control-ios/Sources/QRKit.swift and
// control-desktop's macos/TrayAgent pattern). It owns no window: it streams the
// camera preview back to the JVM, which renders it *inside* the Compose pairing
// screen (true same-window preview — see docs/proposals/desktop-qr-scanning.md
// §8). Vision does the QR decode natively.
//
// Invocation:
//   qr-scan-helper            : stream preview frames + decode (camera session)
//   qr-scan-helper probe      : report camera authorization status and exit
//                               (a pure status check — never touches the camera,
//                               so it can't trigger the first-grant host crash)
//
// Framed stdout protocol (binary):
//   [1-byte type][4-byte big-endian UInt32 length][payload]
//   type 0x01 = FRAME  : JPEG-encoded preview frame
//   type 0x02 = RESULT : UTF-8 text, tab-separated:
//                        "decoded\t<payload>" | "permission_denied"
//                        | "no_camera" | "retry" | "failed\t<reason>"
//                        probe mode: "authorized" | "not_determined"
//                        | "denied" | "no_camera"
// The parent (MacQrCameraScanner) reads one RESULT and then kills the process.
//
// stdin is the liveness line: the parent never writes to it, it only holds the
// pipe open. EOF means nobody is consuming the frames, so the helper exits
// instead of holding the camera until a QR code happens to pass in front of it.

import AVFoundation
import CoreImage
import Foundation
import Vision

private let TYPE_FRAME: UInt8 = 0x01
private let TYPE_RESULT: UInt8 = 0x02

private let out = FileHandle.standardOutput
private let writeLock = NSLock()

private func writeMessage(_ type: UInt8, _ payload: Data) {
    writeLock.lock()
    defer { writeLock.unlock() }
    var header = Data([type])
    var len = UInt32(payload.count).bigEndian
    withUnsafeBytes(of: &len) { header.append(contentsOf: $0) }
    out.write(header)
    out.write(payload)
}

private func emitResult(_ text: String) {
    writeMessage(TYPE_RESULT, Data(text.utf8))
}

private func fail(_ status: String) -> Never {
    emitResult(status)
    exit(0)
}

private final class Scanner: NSObject,
    AVCaptureVideoDataOutputSampleBufferDelegate {

    private let session = AVCaptureSession()
    private let ciContext = CIContext()
    private let rgb = CGColorSpaceCreateDeviceRGB()
    private var lastFrame = Date(timeIntervalSince1970: 0)
    private var done = false

    func start() {
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            fail("no_camera")
        }

        session.beginConfiguration()
        session.sessionPreset = .vga640x480
        session.addInput(input)

        let video = AVCaptureVideoDataOutput()
        video.videoSettings =
            [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        video.alwaysDiscardsLateVideoFrames = true
        if session.canAddOutput(video) {
            session.addOutput(video)
            video.setSampleBufferDelegate(self, queue: DispatchQueue(label: "frames"))
        }

        session.commitConfiguration()
        session.startRunning()
    }

    func captureOutput(_ output: AVCaptureOutput,
                       didOutput sampleBuffer: CMSampleBuffer,
                       from connection: AVCaptureConnection) {
        if done { return }
        let now = Date()
        if now.timeIntervalSince(lastFrame) < 0.08 { return }  // ~12 fps
        lastFrame = now
        guard let pixel = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }

        // QR decode via Vision. `AVCaptureMetadataOutput`'s machine-readable-code
        // detection is an iOS feature that isn't reliably available on macOS
        // (`availableMetadataObjectTypes` is empty there), so we run
        // `VNDetectBarcodesRequest` on each (throttled) frame instead.
        if let payload = detectQr(in: pixel) {
            done = true
            session.stopRunning()
            emitResult("decoded\t" + payload)
            exit(0)
        }

        // Stream the frame as JPEG for the in-window Compose preview.
        let image = CIImage(cvPixelBuffer: pixel)
        guard let jpeg = ciContext.jpegRepresentation(of: image, colorSpace: rgb, options: [:]) else { return }
        writeMessage(TYPE_FRAME, jpeg)
    }

    private func detectQr(in pixel: CVPixelBuffer) -> String? {
        let request = VNDetectBarcodesRequest()
        request.symbologies = [.qr]
        do {
            try VNImageRequestHandler(cvPixelBuffer: pixel, options: [:]).perform([request])
        } catch {
            return nil
        }
        return request.results?
            .first(where: { $0.symbology == .qr && $0.payloadStringValue != nil })?
            .payloadStringValue
    }
}

/// `probe` mode: report the camera authorization status without ever opening a
/// session. Pure status read — safe to call from the UI to decide whether to
/// show the one-time "enable camera" explainer before the (host-crashing) grant.
private func runProbe() -> Never {
    guard AVCaptureDevice.default(for: .video) != nil else { fail("no_camera") }
    switch AVCaptureDevice.authorizationStatus(for: .video) {
    case .authorized: fail("authorized")
    case .notDetermined: fail("not_determined")
    default: fail("denied")
    }
}

private func ensureAuthorized(_ proceed: @escaping () -> Void) {
    switch AVCaptureDevice.authorizationStatus(for: .video) {
    case .authorized:
        proceed()
    case .notDetermined:
        AVCaptureDevice.requestAccess(for: .video) { granted in
            if granted {
                // First-ever grant: do NOT start the session here. macOS
                // SIGKILLs the host app while the permission dialog is up over a
                // live camera preview. Exit and let the parent re-spawn a fresh
                // helper, which finds `.authorized` and starts the preview with
                // no dialog on screen.
                emitResult("retry")
                exit(0)
            } else {
                fail("permission_denied")
            }
        }
    default:
        fail("permission_denied")
    }
}

/// Exits once stdin reaches EOF — the parent died (the kernel closes its end of
/// the pipe even on SIGKILL, where its `destroyForcibly()` never runs) or the
/// helper was started detached on `/dev/null`.
private func exitWhenStdinCloses() {
    Thread {
        var buf = [UInt8](repeating: 0, count: 64)
        while true {
            let n = read(STDIN_FILENO, &buf, buf.count)
            if n > 0 || (n < 0 && errno == EINTR) { continue }
            exit(0)
        }
    }.start()
}

if CommandLine.arguments.dropFirst().first == "probe" {
    runProbe()
}

exitWhenStdinCloses()

private let scanner = Scanner()
ensureAuthorized {
    scanner.start()
}
RunLoop.main.run()
