import SwiftUI
import TenshuSdk

// The email-OTP sign-in / sign-up screen (S2-pre,
// docs/proposals/ios-services-status-and-purchase.md §3.6/§3.9).
//
// ⚠⚠ This is not a monetisation screen — it is the only in-app way out of a dead end.
// `StorageSyncGate` holds a personal realm local-only until a subscription is bound, and
// `joinedFundedRealm` is false for a host, so a realm created on iOS never syncs. The documented
// rescue is to sign in from a *desktop* peer, which an iOS-only user does not have (§3.5).
// Verifying here binds this realm's ownerId, the mint writer fetches the signed Free record, and
// the gate opens on this device.
//
// It binds the SHARED `FreeAccountSignupViewModel` through `ControlSurface.signInViewModel()` —
// the same state machine Compose drives — with the step flattened to a String key Kotlin-side, so
// no SKIE-bridged sealed class reaches SwiftUI (the SyncDashboardView discipline).
@MainActor
final class SignInModel: ObservableObject {
    @Published var step = "form"
    @Published var busy = false
    @Published var errorMessage: String? = nil
    @Published var resent = false
    @Published var canVerify = false

    // Field state is owned locally and forwarded to the VM (the NewUserModel pattern): observe()
    // never writes back, which would fight the cursor on every keystroke.
    @Published var email = ""
    @Published var code = ""

    private let vm = ControlSurface.shared.signInViewModel()

    func observe() async {
        for await state in vm.uiState {
            step = ControlSurface.shared.signInStepKey(state: state)
            busy = state.busy
            errorMessage = state.error
            resent = state.resent
            canVerify = ControlSurface.shared.signInCanVerify(state: state)
        }
    }

    func setEmail(_ value: String) {
        email = value
        vm.onEmailChange(value: value)
    }

    /// ⚠ The one field that DOES read back from the view model, unlike the pattern above. The VM
    /// normalises a code (digits only, capped at six) — so a locally-owned field would keep showing
    /// the letters it discarded, and the CTA would stay disabled against a field that looks
    /// complete. The read is synchronous (`MutableStateFlow.update`), so there is no echo loop.
    func setCode(_ value: String) {
        vm.onCodeChange(value: value)
        code = vm.uiState.value.code
    }

    func submit() { vm.onSubmit() }
    func verify() { vm.onVerify() }
    func resend() { vm.onResend() }

    func editEmail() {
        vm.onEditEmail()
        code = ""
    }
}

struct SignInView: View {
    /// Fired once verification succeeds, before this screen pops. Optional because the common case
    /// needs nothing: the caller's own state is driven by the synced entitlement, not by us.
    var onVerified: (() -> Void)? = nil

    @StateObject private var model = SignInModel()
    @FocusState private var codeFocused: Bool
    // ⚠ The POPPED view owns the pop. A `dismiss` captured in the presenting view dismisses the
    // presenter — here that would close the Storage screen the user came to see, rather than
    // returning them to it with their new plan on it.
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        OnboardingScaffold {
            switch model.step {
            case "form": formContent
            case "code": codeContent
            default: EmptyView()
            }
        }
        .navigationTitle(SignInStrings.title)
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.observe() }
        .onChange(of: model.step) { step in
            if step == "done" {
                onVerified?()
                dismiss()
            }
            codeFocused = step == "code"
        }
    }

    // MARK: - Email entry

    @ViewBuilder private var formContent: some View {
        Text(SignInStrings.heading)
            .font(.onboardingHeadline)
            .multilineTextAlignment(.center)
        vGap(16)
        Text(SignInStrings.body)
            .font(.body)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
        vGap(24)

        TextField(SignInStrings.emailLabel, text: Binding(
            get: { model.email },
            set: { model.setEmail($0) }))
            .textFieldStyle(.roundedBorder)
            .textContentType(.emailAddress)
            .keyboardType(.emailAddress)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .submitLabel(.go)
            .onSubmit { model.submit() }

        errorLine

        // Above the button: the acceptance must be read before the tap that gives it.
        // `register` sends TOS_VERSION and PRIVACY_VERSION (CommercePublisherClient), each the date
        // of the page linked here. Continuing accepts the terms and confirms the minimum age (16,
        // acceptable-use-and-limits.md §7). ⚠⚠ The privacy half informs, never "you accept": a
        // privacy policy is information under the GDPR, and the legal bases are Art. 6(1)(b)/(f).
        vGap(16)
        Text(SignInStrings.legalNotice)
            .font(.footnote)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
        HStack(spacing: 16) {
            Link(SignInStrings.termsLink, destination: TermsOfUse.current)
            PrivacyPolicyLink()
        }
        .font(.footnote)
        vGap(24)
        Button(action: { model.submit() }) {
            Text(model.busy ? SignInStrings.sending : SignInStrings.submit)
        }
        .buttonStyle(PrimaryButtonStyle())
        // The service rejects a blank or malformed address anyway; gating here keeps the user
        // from spending a round trip to be told so.
        .disabled(model.busy || !model.email.contains("@"))
    }

    // MARK: - Code entry

    @ViewBuilder private var codeContent: some View {
        Text(SignInStrings.codeHeading)
            .font(.onboardingHeadline)
            .multilineTextAlignment(.center)
        vGap(16)
        Text(String(format: SignInStrings.codeBody, model.email))
            .font(.body)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
        vGap(24)

        TextField(SignInStrings.codeLabel, text: Binding(
            get: { model.code },
            set: { model.setCode($0) }))
            .textFieldStyle(.roundedBorder)
            // ⚠ `.oneTimeCode` is what lets iOS offer the code from the Messages/Mail banner
            // instead of making the user leave the app to read it.
            .textContentType(.oneTimeCode)
            .keyboardType(.numberPad)
            .font(.system(size: 20, design: .monospaced))
            .multilineTextAlignment(.center)
            .focused($codeFocused)

        if model.resent {
            vGap(8)
            Text(SignInStrings.resent)
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }

        errorLine

        vGap(24)
        Button(action: { model.verify() }) {
            Text(model.busy ? SignInStrings.verifying : SignInStrings.verify)
        }
        .buttonStyle(PrimaryButtonStyle())
        // Kotlin-side gate, so the length lives with the state machine that enforces it — and so a
        // tap can never hit `onVerify`'s silent early return, which reads as a dead button.
        .disabled(!model.canVerify)

        vGap(8)
        Button(SignInStrings.resend) { model.resend() }
            .buttonStyle(LinkButtonStyle())
            .disabled(model.busy)
        Button(SignInStrings.editEmail) { model.editEmail() }
            .buttonStyle(LinkButtonStyle())
            .disabled(model.busy)
    }

    @ViewBuilder private var errorLine: some View {
        if let error = model.errorMessage {
            vGap(12)
            Text(error)
                .font(.callout)
                .foregroundStyle(.red)
                .multilineTextAlignment(.center)
        }
    }
}

// Mirrors the CMP `free_signup_*` resources (control-shared strings.xml). ⚠⚠ "Sign in **or**
// create": the same screen serves both, only the service knows which (`findAccountByEmail`), and
// `verify` calls `bindOwner` — which supersedes the account's live owner rather than creating a
// second account. Copy that promised "create" hid the one fact that reassures someone re-binding
// after a lost device: same account, same plan, no second charge (§3.7).
enum SignInStrings {
    static let legalNotice =
        "By continuing you accept our terms of use and confirm that you are 16 or older. "
        + "How we handle your data is explained in our privacy policy."
    static let termsLink = "Terms of use"
    static let title = "Sign in or create"
    static let heading = "Sign in or create your free Tenshu account"
    static let body =
        "Already have a Tenshu account? Enter the same email — you'll be signed back in on this device, with your plan and storage unchanged. We use your email only to verify you're a real person and to recover your account if you lose your devices. We never see your files."
    static let emailLabel = "Email address"
    static let submit = "Email me a code"
    static let sending = "Sending…"
    static let codeHeading = "Enter your code"
    static let codeBody = "We emailed a 6-digit code to %@. Enter it below to finish."
    static let codeLabel = "6-digit code"
    static let verify = "Verify"
    static let verifying = "Verifying…"
    static let resend = "Send a new code"
    static let resent = "A new code is on its way."
    static let editEmail = "Use a different email"
}
