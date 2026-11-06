import Foundation
import TenshuSdk

/// Copy for the Data sources surface — the SwiftUI twin of `DataSourceText.kt` (DS-5).
///
/// ⚠⚠ **Localisation only.** Every *decision* — which state a row shows, whether it needs
/// attention, what "last import" means when the newest run failed — was already made by
/// `DataSourceProjection` in `commonMain` and is desktop-tested there (D10). Nothing here may
/// re-derive one; each `switch` is a translation table, not a rule. That split is the whole
/// reason this file is short.

func dataSourceName(_ kind: DataSourceKind) -> String {
    switch kind {
    case .tenshuHealth: return "Tenshu Health"
    case .healthConnect: return "Health Connect"
    case .appleHealth: return "Apple Health"
    @unknown default: return "Health data"
    }
}

func dataSourceHint(_ kind: DataSourceKind) -> String {
    switch kind {
    case .tenshuHealth: return "Workouts you record, enter or that are detected"
    // Both platform stores read the same categories, so the one hint serves both.
    case .healthConnect, .appleHealth: return "Steps, workouts, sleep and vitals"
    @unknown default: return ""
    }
}

/// A source row's right-hand text. Never nil: every state has something honest to say.
func dataSourceStatus(_ state: DataSourceState) -> String {
    switch onEnum(of: state) {
    case .neverRun:
        return "Not imported yet"
    case .running(let s):
        return s.imported > 0 ? "Importing… \(Int(s.imported)) so far" : "Importing…"
    case .imported(let s):
        // ⚠ n = 1 is the common case — a refresh that adds one row is the normal outcome — so
        // "1 items" would be on screen constantly. Compose does the same via one-vs-other keys.
        let items = s.itemCount == 1 ? "1 item" : "\(Int(s.itemCount)) items"
        return "\(items) · \(formatRunDate(s.at))"
    case .noData(let s):
        return "Nothing found · \(formatRunDate(s.at))"
    case .failed(let s):
        return "Import failed · \(formatRunDate(s.at))"
    case .permissionNeeded:
        return "Permission needed"
    case .unavailable:
        return "Not available on this device"
    case .sample:
        return "Sample data, not yours"
    case .recording(let s):
        // ⚠ The setting, not a quantity: a count of Fit's own workouts cannot be produced
        // honestly (see `TenshuHealthInput`), and there is no import here to report on.
        return s.autoDetect ? "Detecting automatically" : "Detection off"
    @unknown default:
        return ""
    }
}

/// One history row's headline — what the run did.
func importRunHeadline(_ entry: ImportRunEntry) -> String {
    switch entry.status {
    case .succeeded:
        return entry.itemCount == 1 ? "1 item" : "\(Int(entry.itemCount)) items"
    case .noData: return "Nothing found"
    case .permissionsMissing: return "Permission needed"
    case .failed: return "Failed"
    @unknown default: return ""
    }
}

/// One history row's supporting line.
///
/// ⚠ Megabytes appear only for a file import — a platform health store hands us objects, not
/// bytes, so `byteCount` is nil there and inventing one would be a fabricated figure (D6).
func importRunDetail(_ entry: ImportRunEntry) -> String {
    var parts = [formatRunDate(entry.startedAt)]
    if entry.window == .fullHistory { parts.append("Full history") }
    if entry.workoutCount == 1 {
        parts.append("1 workout")
    } else if entry.workoutCount > 0 {
        parts.append("\(Int(entry.workoutCount)) workouts")
    }
    if let bytes = entry.byteCount { parts.append(formatBytes(Int64(truncating: bytes))) }
    if let name = entry.fileName { parts.append(name) }
    if let device = entry.deviceLabel { parts.append(device) }
    return parts.joined(separator: " · ")
}

/// `dd.MM.yyyy` in the system zone — the same shape `formatRunDate` produces in Compose, so the
/// two platforms read identically.
func formatRunDate(_ epochMillis: Int64) -> String {
    let date = Date(timeIntervalSince1970: TimeInterval(epochMillis) / 1000)
    let f = DateFormatter()
    // ⚠ A fixed format, not a localised one — deliberately matching Compose rather than the
    // phone's locale. If that becomes wrong it is wrong on both platforms at once, which is the
    // point of pinning it.
    // ⚠ Gregorian + POSIX too: a fixed format alone still follows the system calendar, so a
    // Buddhist phone printed 2569 and a Japanese one the era year (0008).
    f.calendar = isoCalendar
    f.locale = Locale(identifier: "en_US_POSIX")
    f.dateFormat = "dd.MM.yyyy"
    return f.string(from: date)
}

/// ⚠ Decimal MB (10^6), matching Compose and what a file manager shows for the same archive — a
/// figure the user can cross-check beats a binary-exact one.
func formatBytes(_ bytes: Int64) -> String {
    if bytes >= 1_000_000_000 { return "\(Double(bytes / 100_000_000) / 10.0) GB" }
    if bytes >= 1_000_000 { return "\(bytes / 1_000_000) MB" }
    if bytes >= 1_000 { return "\(bytes / 1_000) kB" }
    return "\(bytes) B"
}

/// The SF Symbol for a source row. Mirrors `FitIcons.dataSource`.
func dataSourceSymbol(_ kind: DataSourceKind) -> String {
    switch kind {
    case .tenshuHealth: return "record.circle"
    case .healthConnect, .appleHealth: return "heart.fill"
    @unknown default: return "heart.fill"
    }
}
