#if DEBUG
import HealthKit
import SwiftUI
import TenshuSdk

/// Seeds the store-screenshot fixture (`docs/how-to/store-screenshots.md` §3a), the twin of
/// Drive's `StoreFixtureSeed`.
///
/// ⚠⚠ **Data only, never a screen anyone screenshots.** The `Screenshots` UI test launches Fit once
/// with `-seedFixture <dir>`, waits for the result line, and launches it again without the argument —
/// so every capture is the shipping `FitShippingRoot` over the real in-process runtime. The fixture
/// (`store/fixtures/fit/generate_fit.py`) has two halves, and each goes where a real user's would be:
///
/// - `health.json` — the daily figures (steps, sleep, weight, resting heart rate, …) are written
///   into this simulator's **Apple Health**. Nothing reaches the realm here: the next launch
///   brings them in through the shipping Apple Health import (Profile → Import), by taps.
/// - `workouts/*.gpx` — each workout goes through `FitSurface.importPickedFile`, the call the
///   Takeout screen's file picker makes, so routes and heart rate come from the shipping parser.
///
/// ⚠ Writing to Apple Health asks for write consent on the seed launch (the test answers the sheet).
/// The samples are then Fit's own in Apple Health; the importer skips only Fit's own *workouts*
/// (R-iOS-6), and the fixture's workouts never go there.
///
/// ⚠ DEBUG-only (the `Debug` configuration the screenshot scheme builds): a Release build does not
/// compile this file's body. It needs the route-1 key material Control provisions at genesis;
/// without it the result line says so and the test fails on it.
enum StoreFixtureSeed {
    static var requestedDirectory: String? {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-seedFixture"), i + 1 < args.count else { return nil }
        return args[i + 1]
    }
}

struct StoreFixtureSeedView: View {
    let directory: String
    @State private var result = "seeding…"

    var body: some View {
        Text(result)
            .font(.system(.footnote, design: .monospaced))
            .padding()
            .accessibilityIdentifier("seed-result")
            .task { await seed() }
    }

    private func seed() async {
        guard FitLocalRuntime.shared.isProvisioned() else {
            finish("seed: NOT PROVISIONED — run Control's genesis on this simulator first")
            return
        }
        FitLocalRuntime.shared.install()
        do {
            let health = try await HealthFixture.write(from: URL(fileURLWithPath: directory).appendingPathComponent("health.json"))
            let dir = URL(fileURLWithPath: directory).appendingPathComponent("workouts")
            let files = try FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)
                .filter { $0.pathExtension == "gpx" }
                .sorted { $0.lastPathComponent < $1.lastPathComponent }
            var failed: [String] = []
            for file in files {
                let outcome = (try? await FitSurface.shared.importPickedFile(fileUrl: file.absoluteString))
                    ?? "file import: threw"
                if !outcome.contains("done") { failed.append("\(file.lastPathComponent): \(outcome)") }
            }
            if failed.isEmpty {
                finish("seed: OK — \(health) health samples, \(files.count) workouts")
            } else {
                finish("seed: FAILED — \(failed.joined(separator: "; "))")
            }
        } catch {
            finish("seed: FAILED — \(error)")
        }
    }

    private func finish(_ line: String) {
        print("TENSHU-SEED \(line)")
        result = line
    }
}

/// `health.json` → Apple Health. The file names HealthKit's own identifiers and units, so this is
/// a translation table, not a model.
private enum HealthFixture {
    struct File: Decodable {
        struct Quantity: Decodable { let type: String; let unit: String; let value: Double; let start: Date; let end: Date }
        struct Sleep: Decodable { let stage: String; let start: Date; let end: Date }
        let quantities: [Quantity]
        let sleep: [Sleep]
    }

    struct Failure: Error, CustomStringConvertible { let description: String }

    static func write(from url: URL) async throws -> Int {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let file = try decoder.decode(File.self, from: Data(contentsOf: url))

        var samples: [HKSample] = []
        for q in file.quantities {
            let id = HKQuantityTypeIdentifier(rawValue: "HKQuantityTypeIdentifier" + q.type.prefix(1).uppercased() + q.type.dropFirst())
            guard let type = HKQuantityType.quantityType(forIdentifier: id) else {
                throw Failure(description: "no quantity type \(q.type)")
            }
            samples.append(HKQuantitySample(type: type, quantity: HKQuantity(unit: HKUnit(from: q.unit), doubleValue: q.value),
                                            start: q.start, end: q.end))
        }
        let sleepType = HKCategoryType(.sleepAnalysis)
        for s in file.sleep {
            let value: HKCategoryValueSleepAnalysis
            switch s.stage {
            case "inBed": value = .inBed
            case "core": value = .asleepCore
            case "deep": value = .asleepDeep
            case "rem": value = .asleepREM
            case "awake": value = .awake
            default: throw Failure(description: "no sleep stage \(s.stage)")
            }
            samples.append(HKCategorySample(type: sleepType, value: value.rawValue, start: s.start, end: s.end))
        }

        let store = HKHealthStore()
        let types = Set(samples.map(\.sampleType))
        try await store.requestAuthorization(toShare: types, read: [])
        // ⚠ Saving needs every type granted: a sheet answered "Don't Allow" fails here, loudly.
        for chunk in stride(from: 0, to: samples.count, by: 200) {
            try await store.save(Array(samples[chunk..<min(chunk + 200, samples.count)]))
        }
        return samples.count
    }
}
#endif
