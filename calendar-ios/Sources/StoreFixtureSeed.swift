#if DEBUG
import SwiftUI
import TenshuSdk

/// Seeds the store-screenshot fixture (`docs/how-to/store-screenshots.md` §3b), the twin of Drive's
/// and Fit's `StoreFixtureSeed`.
///
/// ⚠⚠ **Data only, never a screen anyone screenshots.** The `Screenshots` UI test launches Calendar
/// once with `-seedFixture <dir>`, waits for the result line, and launches it again without the
/// argument — so every capture is the shipping `CalendarShippingRoot` over the real in-process
/// runtime. The fixture (`store/fixtures/calendar/generate_calendar.py`) is one `.ics` file per
/// calendar, and each goes where a real user's would:
///
/// - the calendars are made with `CalendarRepository.saveCalendar` — the call the Calendars tab's
///   *New calendar* sheet saves through — except the default one (*Personal*), which the app seeds
///   itself on first launch (`ensureDefaultCalendar`) and the fixture only fills;
/// - the events go through `CalendarRepository.importIcs` — the call a calendar row's *Import or
///   export…* sheet makes with the picked file's text — so recurrence, reminders and the raw
///   source rows come out of the shipping parser.
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

    private struct Listing: Decodable {
        struct Calendar: Decodable {
            let name: String
            let colorArgb: String
            let `default`: Bool
            let file: String
        }
        let calendars: [Calendar]
    }

    var body: some View {
        Text(result)
            .font(.system(.footnote, design: .monospaced))
            .padding()
            .accessibilityIdentifier("seed-result")
            .task { await seed() }
    }

    private func seed() async {
        guard CalendarLocalRuntime.shared.isProvisioned() else {
            finish("seed: NOT PROVISIONED — run Control's genesis on this simulator first")
            return
        }
        CalendarLocalRuntime.shared.install()
        let repository = CalendarSurface.shared.repository()
        let root = URL(fileURLWithPath: directory)
        do {
            let listing = try JSONDecoder().decode(
                Listing.self, from: Data(contentsOf: root.appendingPathComponent("calendars.json"))
            )
            // ⚠ Awaited here as well as launched by `install()`: a calendar created before the
            // default exists would make `ensureDefaultCalendar` leave the realm without one.
            try await repository.ensureDefaultCalendar()
            let defaultId = try await repository.defaultCalendar()
            var lines: [String] = []
            for calendar in listing.calendars {
                let id: String
                if calendar.default {
                    guard !defaultId.isEmpty else {
                        finish("seed: FAILED — the app seeded no default calendar")
                        return
                    }
                    id = defaultId
                } else {
                    guard let argb = UInt32(calendar.colorArgb, radix: 16) else {
                        finish("seed: FAILED — bad colour \(calendar.colorArgb)")
                        return
                    }
                    id = try await repository.saveCalendar(
                        calendarId: "", name: calendar.name, colorArgb: Int32(bitPattern: argb), sensitive: false
                    )
                }
                let text = try String(contentsOf: root.appendingPathComponent(calendar.file), encoding: .utf8)
                let imported = try await repository.importIcs(text: text, calendarId: id, origin: calendar.file)
                if imported.imported == 0 || imported.skipped > 0 {
                    finish("seed: FAILED — \(calendar.file): \(imported.message)")
                    return
                }
                lines.append("\(calendar.name) \(imported.imported)")
            }
            finish("seed: OK — " + lines.joined(separator: ", "))
        } catch {
            finish("seed: FAILED — \(error)")
        }
    }

    private func finish(_ line: String) {
        print("TENSHU-SEED \(line)")
        result = line
    }
}
#endif
