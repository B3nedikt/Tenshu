// IN-3m-iOS — the shell around `In3mHarness` (docs/proposals/ios-on-device-inference.md §4).
//
// One Run button and a log. Everything it measures is Kotlin, in `:inference-service-proxy`; this
// file only picks the row, keeps the screen awake and gets the work off the main thread.
//
// Launched with `IN3M_AUTORUN=1` (what `run-in3m.sh run` does) it runs the row named by
// `IN3M_ROW` / `IN3M_TOWER` / `IN3M_RECOMPILE`, prints every line to stdout and exits, so
// `devicectl … launch --console` returns when the row is done. One process per row: a footprint
// peak is a process-lifetime high-water mark.
import SwiftUI
import In3mHarnessKit

@main
struct In3mHarnessApp: App {
    @StateObject private var runner = In3mRunner()

    init() {
        setvbuf(stdout, nil, _IOLBF, 0)
    }

    var body: some Scene {
        WindowGroup {
            In3mView(runner: runner)
                .onAppear {
                    // A pass is minutes long, and a locked phone is a different thermal state.
                    UIApplication.shared.isIdleTimerDisabled = true
                    runner.autorunIfAsked()
                }
        }
    }
}

final class In3mRunner: ObservableObject {
    static let rows = ["coreml-ane", "coreml-all", "coreml-cpu"]
    static let towers = ["vision", "text"]

    @Published var row = In3mRunner.rows[0]
    @Published var tower = In3mRunner.towers[0]
    @Published var recompile = false
    @Published var running = false
    @Published var lines: [String] = []

    private var autorunChecked = false

    func autorunIfAsked() {
        guard !autorunChecked else { return }
        autorunChecked = true
        let env = ProcessInfo.processInfo.environment
        guard env["IN3M_AUTORUN"] == "1" else { return }
        row = env["IN3M_ROW"] ?? row
        tower = env["IN3M_TOWER"] ?? tower
        recompile = env["IN3M_RECOMPILE"] == "1"
        run(exitWhenDone: true)
    }

    func run(exitWhenDone: Bool = false) {
        guard !running else { return }
        running = true
        lines = []
        let (row, tower, recompile) = (self.row, self.tower, self.recompile)
        let documents = NSSearchPathForDirectoriesInDomains(.documentDirectory, .userDomainMask, true)[0]
        // Off the main thread: the first compile is seconds and a pass is minutes, against a
        // launch watchdog of ~20 s.
        let thread = Thread {
            let harness = In3mHarness(documents: documents) { line in
                print(line)
                DispatchQueue.main.async { self.lines.append(line) }
            }
            let outcome = harness.run(row: row, tower: tower, recompile: recompile)
            print("IN3M_DONE \(outcome.name)")
            fflush(stdout)
            if exitWhenDone { exit(0) }
            DispatchQueue.main.async { self.running = false }
        }
        thread.stackSize = 16 << 20
        thread.qualityOfService = .userInitiated
        thread.start()
    }
}

struct In3mView: View {
    @ObservedObject var runner: In3mRunner

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("IN-3m-iOS").font(.headline)
            Picker("Row", selection: $runner.row) {
                ForEach(In3mRunner.rows, id: \.self) { Text($0) }
            }
            .pickerStyle(.segmented)
            Picker("Tower", selection: $runner.tower) {
                ForEach(In3mRunner.towers, id: \.self) { Text($0) }
            }
            .pickerStyle(.segmented)
            Toggle("Recompile first", isOn: $runner.recompile)
            Button(runner.running ? "Running…" : "Run") { runner.run() }
                .buttonStyle(.borderedProminent)
                .disabled(runner.running)
            ScrollView {
                Text(runner.lines.joined(separator: "\n"))
                    .font(.system(size: 11, design: .monospaced))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
            }
            Text("Quit and relaunch between rows: one process per row.")
                .font(.footnote)
                .foregroundColor(.secondary)
        }
        .padding()
    }
}
