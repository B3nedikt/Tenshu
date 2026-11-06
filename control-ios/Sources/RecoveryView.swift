import SwiftUI
import UIKit
import TenshuSdk

// Recovery from a 24-word phrase (Control-on-iOS C2c), reworked to track the canonical
// Compose RecoveryScreen: centered layout, the 4-column mnemonic grid, the slow-operation
// note, the don't-share warning, and matching copy ("Recover your realm" / "Welcome back").
// Binds the real `RecoveryViewModel` (`:control-shared-logic`) through `ControlSurface`:
// BIP-39 decode runs client-side first (typed `RecoveryError` surfaced before any relay
// round-trip), then `recoveryClaim`. Over fakes the claim always succeeds; the real
// relay-backed claim arrives with the C4 data plane.

@MainActor
final class RecoveryModel: ObservableObject {
    @Published var step: String = "entry"
    @Published var errorMessage: String? = nil
    @Published var wordCount: Int = 0
    @Published var done = false

    /// Per-word field state (24 slots). The VM owns validation; we forward each edit.
    @Published var words: [String] = Array(repeating: "", count: 24)

    private let vm = ControlSurface.shared.recoveryViewModel()

    func observe() async {
        for await s in vm.uiState {
            step = ControlSurface.shared.recoveryStepKey(state: s)
            errorMessage = ControlSurface.shared.recoveryErrorMessage(state: s)
            wordCount = Int(s.wordCount)
            done = step == "done"
        }
    }

    func setWord(_ index: Int, _ value: String) {
        let cleaned = value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        words[index] = cleaned
        vm.onWordChange(index: Int32(index), value: cleaned)
    }

    /// "Paste from clipboard" — split a pasted phrase across all 24 slots.
    func pasteFromClipboard() {
        guard let text = UIPasteboard.general.string else { return }
        let pasted = text
            .split(whereSeparator: { $0 == " " || $0 == "\n" || $0 == "\t" })
            .map { String($0).lowercased() }
        for i in 0..<24 {
            setWord(i, i < pasted.count ? pasted[i] : "")
        }
    }

    func recover() { vm.onRecover() }

    func successContinue() {
        // Route-1 handoff: a recovered device is also a realm device — publish the key material.
        _ = ControlSurface.shared.ensureDriveProvisioned()
        vm.onSuccessContinue()
    }
}

struct RecoveryView: View {
    let onComplete: () -> Void
    @StateObject private var model = RecoveryModel()

    var body: some View {
        OnboardingScaffold {
            switch model.step {
            case "entry": entryContent
            case "processing": processingContent
            case "success": successContent
            default: EmptyView()
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.observe() }
        .onChange(of: model.done) { done in
            if done { onComplete() }
        }
    }

    @ViewBuilder private var entryContent: some View {
        Text(OnboardingStrings.recoveryEntryTitle)
            .font(.onboardingHeadline)
            .multilineTextAlignment(.center)
        vGap(16)
        Text(OnboardingStrings.recoveryEntryBody)
            .font(.body)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
        vGap(12)
        Text(OnboardingStrings.recoveryEntrySlowNote)
            .font(.caption)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
        vGap(24)

        Text(OnboardingStrings.recoveryWordCount(model.wordCount))
            .font(.callout.weight(.medium))
            .foregroundStyle(model.wordCount == 24 ? OnboardingColors.primary : Color.secondary)
        vGap(12)

        MnemonicGrid(words: model.words, onPaste: { model.pasteFromClipboard() }) { index, value in
            model.setWord(index, value)
        }

        vGap(16)
        warningSurface
        if let error = model.errorMessage {
            vGap(12)
            Text(error)
                .font(.callout)
                .foregroundStyle(.red)
                .multilineTextAlignment(.center)
        }
        vGap(24)
        Button(OnboardingStrings.recoveryEntryRecover) { model.recover() }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(model.wordCount != 24)
    }

    @ViewBuilder private var warningSurface: some View {
        Text(OnboardingStrings.recoveryEntryWarning)
            .font(.callout)
            .foregroundStyle(Color(uiColor: .systemRed))
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity)
            .padding(12)
            .background(Color(uiColor: .systemRed).opacity(0.12))
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    @ViewBuilder private var processingContent: some View {
        Text(OnboardingStrings.recoveryProcessingHeading)
            .font(.onboardingHeadline)
            .multilineTextAlignment(.center)
        vGap(16)
        Text(OnboardingStrings.recoveryProcessingBody)
            .font(.body)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
        vGap(24)
        ProgressView()
            .tint(OnboardingColors.primary)
    }

    @ViewBuilder private var successContent: some View {
        MarkOutline(dimension: 160)
        vGap(24)
        Text(OnboardingStrings.recoverySuccessHeading)
            .font(.onboardingHeadline)
            .multilineTextAlignment(.center)
        vGap(16)
        Text(OnboardingStrings.recoverySuccessBody)
            .font(.body)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
        vGap(32)
        Button(OnboardingStrings.recoverySuccessContinue) { model.successContinue() }
            .buttonStyle(PrimaryButtonStyle())
    }
}

// MARK: - Mnemonic grid (canonical MnemonicGrid: "Paste from clipboard" + 4 numbered columns)

struct MnemonicGrid: View {
    let words: [String]
    let onPaste: () -> Void
    let onWordChange: (Int, String) -> Void

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 8), count: 4)

    var body: some View {
        VStack(alignment: .trailing, spacing: 12) {
            Button(OnboardingStrings.recoveryEntryPaste, action: onPaste)
                .buttonStyle(LinkButtonStyle())
                .font(.caption)

            LazyVGrid(columns: columns, spacing: 8) {
                ForEach(0..<24, id: \.self) { i in
                    WordInput(
                        number: i + 1,
                        text: words[i],
                        onChange: { onWordChange(i, $0) }
                    )
                }
            }
        }
    }
}

private struct WordInput: View {
    let number: Int
    let text: String
    let onChange: (String) -> Void

    var body: some View {
        HStack(spacing: 2) {
            Text("\(number)")
                .font(.system(size: 10, weight: .regular, design: .monospaced))
                .foregroundStyle(.secondary)
                .frame(width: 14, alignment: .trailing)
            TextField("", text: Binding(get: { text }, set: onChange))
                .font(.system(size: 13, design: .monospaced))
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.next)
        }
        .padding(.vertical, 6)
        .padding(.horizontal, 4)
        .overlay(
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .stroke(Color.secondary.opacity(0.4), lineWidth: 1)
        )
    }
}
