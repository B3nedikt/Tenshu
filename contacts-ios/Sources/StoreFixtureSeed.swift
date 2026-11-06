#if DEBUG
import SwiftUI
import TenshuSdk

/// Seeds the store-screenshot fixture (`docs/how-to/store-screenshots.md` §3c), the twin of
/// Drive's and Fit's `StoreFixtureSeed`.
///
/// ⚠⚠ **Data only, never a screen anyone screenshots.** The `Screenshots` UI test launches Contacts
/// once with `-seedFixture <dir>`, waits for the result line, and launches it again without the
/// argument — so every capture is the shipping `ContactsShippingRoot` over the real in-process
/// runtime and the realm Control created.
///
/// The fixture is one vCard file (`store/fixtures/contacts/generate_contacts.py` → `contacts.vcf`),
/// handed to the shared repository's `importVcards` — the op Tenshu Contacts' *Import contacts*
/// calls on Android and desktop. The iOS app has no import screen of its own (its contacts arrive
/// through the realm from the user's other devices), so this is the one place the records can
/// come from that is also a user's: the rows it writes are exactly the ones an import elsewhere
/// syncs in, raw vCard included.
///
/// ⚠ DEBUG-only (the `Debug` configuration the screenshot scheme builds): a Release build does not
/// compile this file's body. It needs the route-1 key material Control provisions at genesis;
/// without it the result line says so and the test fails on it.
enum StoreFixtureSeed {
    /// `<dir>/contacts.vcf` for `-seedFixture <dir>`.
    static var requestedFile: URL? {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-seedFixture"), i + 1 < args.count else { return nil }
        return URL(fileURLWithPath: args[i + 1]).appendingPathComponent("contacts.vcf")
    }
}

struct StoreFixtureSeedView: View {
    let file: URL
    @State private var result = "seeding…"

    var body: some View {
        Text(result)
            .font(.system(.footnote, design: .monospaced))
            .padding()
            .accessibilityIdentifier("seed-result")
            .task { await seed() }
    }

    private func seed() async {
        guard ContactsLocalRuntime.shared.isProvisioned() else {
            finish("seed: NOT PROVISIONED — run Control's genesis on this simulator first")
            return
        }
        ContactsLocalRuntime.shared.install()
        do {
            let text = try String(contentsOf: file, encoding: .utf8)
            let expected = text.components(separatedBy: "BEGIN:VCARD").count - 1
            let outcome = try await ContactsSurface.shared.repository().importVcards(text: text)
            // `Success(value=N)` / `Failure(copyKey=…)` — the Kotlin data classes' own toString.
            let line = String(describing: outcome)
            if line == "Success(value=\(expected))" {
                finish("seed: OK — \(expected) contacts imported")
            } else {
                finish("seed: FAILED — \(line), expected \(expected)")
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
#endif
