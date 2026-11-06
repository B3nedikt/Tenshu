import XCTest

/// The walks' labels in the run's language (docs/how-to/store-screenshots.md §4). Compiled into each
/// app's store-screenshot target beside `ShellTabs.swift`.
///
/// Every walk is written with the ENGLISH label (`app.buttons[l: "New event"]`). A `--lang de` run of
/// `scripts/store-screenshots.sh` hands the runner `SHOTS_STRINGS` (as `TEST_RUNNER_SHOTS_STRINGS`): a
/// JSON object English → [German labels], made by `scripts/lib/ios_shot_strings.py` from the app's own
/// `Localizable.xcstrings` plus the system's labels the walks tap (Apple Health's sheet, alerts). An
/// English run has no table, so every lookup is exactly the English one it always was.
///
/// ⚠⚠ A lookup matches the English label OR its translation, never the translation alone: a string
/// can be in the catalogue and still reach the screen in English — a `String` (not a
/// `LocalizedStringKey`) passed to `Text`/`Label`, e.g. `model.copied ? "Copied" : "Copy Link"`, or
/// a label the Kotlin layer builds. A label with no translation is the English one, which is what
/// such a screen shows.
enum ShotStrings {

    /// English → the run's labels for it: the app's translation first, then any system wording
    /// (Apple's sheets name one action differently from the app — Fit's "Continue" is "Weiter" in the
    /// app and Apple Health's own word on its sheet).
    static let table: [String: [String]] = {
        guard let path = ProcessInfo.processInfo.environment["SHOTS_STRINGS"], !path.isEmpty,
              let data = FileManager.default.contents(atPath: path),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: [String]] else { return [:] }
        return object
    }()

    /// The label in the run's language (the app's translation), or the English one when it has none.
    static func t(_ english: String) -> String { table[english]?.first ?? english }

    /// The English label and every translation of it.
    static func both(_ english: String) -> [String] {
        [english] + (table[english] ?? []).filter { $0 != english }
    }

    /// A format key (`"%lld Selected"`), translated, then filled: `format("%lld Selected", 3)`.
    static func format(_ englishKey: String, _ args: CVarArg...) -> [String] {
        both(englishKey).map { String(format: $0, arguments: args) }
    }

    /// `label == x` for any of the given English labels or their translations.
    static func labelIs(_ english: String...) -> NSPredicate {
        NSPredicate(format: "label IN %@", english.flatMap(both))
    }

    /// `label BEGINSWITH x` for any of the given English prefixes or their translations.
    static func labelBegins(_ english: String...) -> NSPredicate {
        NSCompoundPredicate(orPredicateWithSubpredicates: english.flatMap(both).map {
            NSPredicate(format: "label BEGINSWITH %@", $0)
        })
    }

    /// `label LIKE x` (`*` and `?` wildcards) for any of the given English patterns or their
    /// translations — for a label whose number sits mid-sentence, where the languages put it in
    /// different places ("Select All 11 Topics", "Alle 11 Themen auswählen").
    static func labelLike(_ english: String...) -> NSPredicate {
        NSCompoundPredicate(orPredicateWithSubpredicates: english.flatMap(both).map {
            NSPredicate(format: "label LIKE %@", $0)
        })
    }

    /// `label ENDSWITH x` for any of the given English suffixes or their translations.
    static func labelEnds(_ english: String...) -> NSPredicate {
        NSCompoundPredicate(orPredicateWithSubpredicates: english.flatMap(both).map {
            NSPredicate(format: "label ENDSWITH %@", $0)
        })
    }
}

extension XCUIElementQuery {

    /// The first element whose identifier or label is `english` or its translation — the walks'
    /// `query["Label"]`, in the run's language. `firstMatch`, so a label two elements share (a
    /// button and its nested duplicate) resolves rather than fails.
    subscript(l english: String) -> XCUIElement {
        self[any: ShotStrings.both(english)]
    }

    /// The same for a list of already-resolved names (`ShotStrings.format`'s result). ⚠ The fields a
    /// plain `query["…"]` matches too: a text field is found by its placeholder, a bar by its title.
    subscript(any names: [String]) -> XCUIElement {
        matching(NSPredicate(
            format: "identifier IN %@ OR label IN %@ OR title IN %@ OR placeholderValue IN %@",
            names, names, names, names
        )).firstMatch
    }
}
