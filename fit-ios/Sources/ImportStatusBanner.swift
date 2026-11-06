import SwiftUI
import TenshuSdk

// The iOS twin of Compose `ImportStatusBanner` (fit-shared-ui .../components/ImportStatusBanner.kt).
//
// Tells the user what the last import actually did, when that is something they would want to know.
// Every state below otherwise renders as the same blank screen — which is how "the import does not
// work" stays unactionable: an unauthorised source, an empty one, a missing platform and an outright
// failure all look identical from the outside, and none of them says a word.
//
// `ImportStatus.Sample` is the state that earned this component its keep: where no real source is
// reachable a shell may fall back to a fixture, and rendering invented numbers unlabelled is worse
// than rendering nothing. ⚠ iOS no longer produces it — `HealthKitImporter` answers
// `SourceUnavailable` without a health store — but the case stays, labelled, for any shell that does.
//
// Silent for `Idle` and a successful `Imported` — a working import needs no announcement. A
// `Running` import gets a live progress row instead (not an "attention" state, but a minutes-long
// Takeout import that just sits there reads as a hang).
struct ImportStatusBanner: View {
    let status: ImportStatus
    let onRetry: () -> Void

    @ViewBuilder var body: some View {
        switch onEnum(of: status) {
        case .running(let running):
            progressRow(Int(running.imported))
        case .noData:
            attention(
                title: "Nothing to import yet",
                detail: "Apple Health has no health data to share yet. Record a workout, or import a file.",
                isError: false
            )
        case .permissionsMissing:
            attention(
                title: "Fit needs permission to read your health data",
                detail: "Grant Fit read access in Apple Health, then try the import again.",
                isError: false
            )
        case .sourceUnavailable:
            attention(
                title: "Apple Health isn't available on this device",
                detail: "Record a workout or import a file to get started.",
                isError: false
            )
        case .sample:
            attention(
                title: "Showing sample data",
                detail: "These numbers are a built-in example, not your health data. Connect a source to see your own.",
                isError: false
            )
        case .failed:
            attention(
                title: "Import failed",
                detail: "Fit could not read your health data. Try again.",
                isError: true
            )
        default:
            EmptyView()
        }
    }

    // A failed import is an error. The rest are advisory — including sample data, where the app is
    // working exactly as built; it is simply the state the user most needs to not mistake for real.
    private func attention(title: String, detail: String, isError: Bool) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: isError ? "exclamationmark.triangle.fill" : "info.circle.fill")
                .foregroundStyle(isError ? Color(.systemRed) : Color.secondary)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.subheadline.weight(.semibold))
                Text(detail).font(.caption).foregroundStyle(.secondary)
                // Retry suits every attention state: the source may have gained data, the grant may
                // have been given in Health since, and a failure may simply be transient.
                Button("Try again", action: onRetry)
                    .font(.caption.weight(.semibold))
                    .buttonStyle(.borderless)
                    .padding(.top, 4)
            }
            Spacer(minLength: 0)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            (isError ? Color(.systemRed).opacity(0.12) : Color(.secondarySystemGroupedBackground)),
            in: RoundedRectangle(cornerRadius: 16)
        )
    }

    /// A live "Importing… N so far" row while an import runs — the count ticks so a long job reads
    /// as alive. It is a count-so-far, not a percentage: the total is unknown until the whole
    /// archive is parsed.
    private func progressRow(_ imported: Int) -> some View {
        HStack(spacing: 12) {
            ProgressView()
            Text(imported > 0 ? "Importing… \(imported) so far" : "Importing…")
                .font(.subheadline)
            Spacer(minLength: 0)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
    }
}

/// The live source's one-line state, or nil only for `Idle` — no import has run, so there is
/// nothing to say about one. The iOS twin of `ProfileScreen.importStatusDetail`.
///
/// Every other state gets a line, and that is load-bearing: the view model drops the
/// provenance-derived source entry as soon as the import reports on the source itself, so a nil
/// here would delete the row rather than describe it — and the app would claim the source was never
/// connected in exactly the states (failed, sample, unavailable) where the user most needs to know.
func importStatusDetail(_ status: ImportStatus) -> String? {
    switch onEnum(of: status) {
    case .imported(let s): return "\(Int(s.itemCount)) items imported"
    case .noData: return "Connected, no data"
    case .permissionsMissing: return "Permission needed"
    case .running(let s): return s.imported > 0 ? "Importing… \(Int(s.imported)) so far" : "Importing…"
    case .failed: return "Import failed"
    case .sample: return "Sample data, not yours"
    case .sourceUnavailable: return "Not available on this device"
    case .idle: return nil
    @unknown default: return nil
    }
}

/// The Compose `TrustCue` — the one-line reminder of where this data lives. Rendered at the foot of
/// Profile and the workout provenance footer, exactly as on Android.
struct FitTrustCue: View {
    var body: some View {
        Label("Synced to your realm · end-to-end encrypted", systemImage: "lock.fill")
            .font(.caption2)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity)
            .multilineTextAlignment(.center)
    }
}
