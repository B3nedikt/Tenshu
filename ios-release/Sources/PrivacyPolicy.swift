import SwiftUI

/// Where Tenshu's privacy policy lives, and which translation a reader gets: German for a German
/// device or app language, French for French, English otherwise.
///
/// App Store guideline 5.1.1(i) wants the policy reachable inside the app, so every app's settings /
/// profile screen shows a `PrivacyPolicyLink` (`docs/how-to/app-store-release.md`).
///
/// ⚠⚠ The Swift copy of `org.tenshu.core.common.legal.PrivacyPolicy` (`:core-common`), which is the
/// source of truth; `PrivacyPolicyParityTest` (`:ui-core`) pins this file's base URL and languages
/// to it. Change both together. Compiled into every app through the `ReleaseApp` template
/// (`ios-release/release.yml`), the one template all ten iOS apps apply.
enum PrivacyPolicy {
    static let baseURL = "https://tenshu.app/legal/privacy"
    static let languages: Set<String> = ["de", "en", "fr"]
    static let fallbackLanguage = "en"

    /// The published language for a BCP 47 tag (`de-AT`), a POSIX locale (`de_CH`) or a bare code.
    static func language(for tag: String?) -> String {
        let code = (tag ?? "")
            .trimmingCharacters(in: .whitespaces)
            .split(whereSeparator: { $0 == "-" || $0 == "_" })
            .first
            .map { $0.lowercased() } ?? ""
        return languages.contains(code) ? code : fallbackLanguage
    }

    static func url(for tag: String?) -> URL {
        URL(string: "\(baseURL)/\(language(for: tag))")!
    }

    /// For this reader. `Locale.preferredLanguages` carries the per-app language from Settings when
    /// the user picked one, and the device's language list otherwise.
    static var current: URL { url(for: Locale.preferredLanguages.first) }
}

/// Where Tenshu's terms of use live: the same languages and the same choice as `PrivacyPolicy`.
/// Control's sign-in screen links both.
///
/// ⚠⚠ The Swift copy of `org.tenshu.core.common.legal.TermsOfUse`; `PrivacyPolicyParityTest`
/// (`:ui-core`) pins `termsBaseURL` to it.
enum TermsOfUse {
    static let termsBaseURL = "https://tenshu.app/legal/terms"

    static func url(for tag: String?) -> URL {
        URL(string: "\(termsBaseURL)/\(PrivacyPolicy.language(for: tag))")!
    }

    /// For this reader, by the same rule as `PrivacyPolicy.current`.
    static var current: URL { url(for: Locale.preferredLanguages.first) }
}

/// A row that opens the privacy policy in the browser (`Link` hands it to the system).
struct PrivacyPolicyLink: View {
    var body: some View {
        Link(destination: PrivacyPolicy.current) {
            Label("Privacy policy", systemImage: "hand.raised")
        }
    }
}
