import SwiftUI
import UIKit
import TenshuSdk

// Control device-pairing — the unified "Add a device" wizard (Control-on-iOS C6 / Track B,
// docs/proposals/ios-control-handover.md §C6). A Scan|Show segmented control switches the two roles:
//   • Scan ("this is the new device") — the joiner wizard: ManualCode → NameDevice → Approval → Success.
//   • Show ("this device invites a new one") — the host's pairing code (selectable text + Copy + Stop),
//     mirrored from the process-lifetime `HostPairingMonitor`.
//
// Binds the shared Compose-free `PairingWizardViewModel` (`:control-shared-logic`) through
// `ControlSurface`, rendered natively in the OnboardingKit visual vocabulary (House Rule #5). It rides
// the **real** RT-3 proxy runtime: `pairingWizardViewModel()` resolves the real `TenshuSyncRepository` +
// process-lifetime `HostPairingMonitor` that `ControlIosApp.init` installed via `installRealRuntime()`,
// so the joiner posts a real relay-backed handshake and the host shows a real pairing code. Only the
// DEBUG screenshot deep-links still run over the B-1 fakes (`ControlIosApp.init` skips the real runtime
// for those), prefilling a sample code to capture the deeper steps without a second device.
//
// Native QR (B-4) replaces the Compose `:ui-core` `QrScanner`/`QrCode` (unusable on iOS): the Scan tab
// shows an AVFoundation camera scanner (`QRScannerView`, with an "Enter code instead" fallback) and the
// Show tab renders the host pairing string as a CoreImage QR image (`qrCodeImage`) above the copyable
// text — see `QRKit.swift`. ⚠ The camera can't run on the simulator (no capture device), so the Scan
// tab falls back to the text-code path there; the generator + the rest run everywhere.

@MainActor
final class PairingModel: ObservableObject {
    @Published var mode: String = "scan"
    @Published var step: String = "scan_qr"
    @Published var sas: String? = nil
    @Published var errorMessage: String? = nil
    @Published var done = false

    // Offer-flow responder (§5.2): this device scanned a *new* device's `tenshu://offer` QR. The
    // device name + derived SAS surface here from the shared VM's uiState; `isLoading` gates the
    // Approve button while the admission POST is in flight (and while the offer is being parsed).
    @Published var responderDeviceName: String? = nil
    @Published var responderSas: String? = nil
    @Published var isLoading = false

    // Show-tab (host) state, mirrored from the HostPairingMonitor via uiState. `hostQrImage` is
    // regenerated only when `hostPairingString` actually changes (CoreImage render is non-trivial and
    // the Show tab re-renders on every monitor tick), not on every `body` evaluation.
    @Published var hostPairingString: String? = nil
    @Published var hostQrImage: UIImage? = nil
    @Published var hostConnectionLost = false
    /// The relay answered and refused THIS device, and the repair could not fix it. Outranks
    /// `hostConnectionLost`, which would blame a network that is working. See
    /// `HostPairingMonitor.deviceUnrecognized`.
    @Published var hostDeviceUnrecognized = false

    // Host-side approval (§3 Step 3), mirrored from `HostPairingMonitor.activeRequest` through the
    // shared VM. Android/desktop render this as an app-global Compose modal, which iOS cannot mount
    // — so it lands here, in the Show tab the user is already looking at, plus the root-level alert
    // (`HostPairingApprovalHost`) for a request that arrives after the sheet is closed. Until this
    // existed the iPhone showed a QR, polled the request, and had nowhere to put it: the joiner sat
    // on "Confirm on your other device" for its whole ~5-min budget and neither side said why.
    @Published var hostRequestDeviceName: String? = nil
    @Published var hostRequestSas: String? = nil
    @Published var hostApprovalError: String? = nil
    /// Terminal success for the current host session. Load-bearing: approving clears
    /// `hostPairingString`, so without this the Show tab falls back to its "no code yet" spinner
    /// and sits there forever (the same trap `HostPairingMonitor.pairingComplete` documents).
    @Published var hostPairingComplete = false

    // Offerer role (`sync-pairing-inverted-direction.md` §5.1) — the OTHER half of the Show tab.
    // ⚠⚠ Not only the unpaired-device case: a paired device the relay no longer recognises is
    // flipped into this role *in place* (`PairingWizardViewModel.onSelectShow` +
    // the `deviceUnrecognized` collector), which is the one way a user reaches it without doing
    // anything unusual. Until these were read, that tab sat on the host branch's "no code yet"
    // spinner under a red "this device isn't recognised" banner — the failure it exists to repair.
    @Published var offerRole = false
    @Published var offerUri: String? = nil
    @Published var offerQrImage: UIImage? = nil
    /// Post-hoc SAS: the offerer only learns `publicKeyA` from the approved response, so the
    /// comparison is necessarily *after* the responder approves (`pairing-flow.md` §4.5) — never
    /// phrase it as a simultaneous check.
    @Published var offerSas: String? = nil
    @Published var offerPaired = false

    /// Mirrors `PairingWizardUiState.offerScanned`: the responder scanned and announced, but has
    /// not decided. ⚠ Derived here for the same reason it is derived there — `offerSas` is set on
    /// both `scanned` and `approved`, and `offerPaired` is what separates them, so a third stored
    /// flag could only disagree with those two.
    var offerScanned: Bool { offerSas != nil && !offerPaired }
    @Published var offerDenied = false
    @Published var offerFailed = false
    /// The relay refused THIS device as a removed identity (F2′): terminal, with its own remedy.
    @Published var offerRemovedMessage: String? = nil

    /// The realm has no storage plan, so the relay refuses every write — `enforcement-followup-
    /// tracks.md` Track D. ⚠ Not a pairing failure: the device IS in the realm. It only withdraws
    /// the terminal screen's promise that data will sync.
    @Published var realmUnfunded = false

    /// Mirrors `PairingWizardUiState.autoContinueArmed` — the ONE rule for which terminal may
    /// auto-continue (`control-pairing-ux.md` §9). ⚠ Read, never re-derived here: the settled
    /// funding check, the offerer's pre-approval SAS sighting and the SAS-responder exclusion all
    /// live in the shared VM.
    @Published var autoContinueArmed = false

    // Field state owned locally and forwarded to the VM (the VM stays the validation source of truth),
    // exactly as the genesis/recovery models do. The pairing code starts **empty**: the wizard now rides
    // the real RT-3 runtime, so the joiner enters (or scans) a real host's `tenshu://join` code. DEBUG
    // screenshot deep-links prefill it with a sample over fakes (see `start`).
    @Published var pairingCode: String = ""
    @Published var deviceName: String = UIDevice.current.name

    private let vm = ControlSurface.shared.pairingWizardViewModel()

    func observe() async {
        for await s in vm.uiState {
            mode = ControlSurface.shared.deviceModeKey(state: s)
            step = ControlSurface.shared.pairingStepKey(state: s)
            sas = s.sas
            responderDeviceName = s.responderDeviceName
            responderSas = s.responderSas
            isLoading = s.isLoading
            if s.hostPairingString != hostPairingString {
                hostPairingString = s.hostPairingString
                hostQrImage = s.hostPairingString.flatMap { qrCodeImage(from: $0) }
            }
            hostConnectionLost = s.hostConnectionLost
            hostDeviceUnrecognized = s.hostDeviceUnrecognized
            hostRequestDeviceName = s.hostRequestDeviceName
            hostRequestSas = s.hostRequestSas
            hostApprovalError = ControlSurface.shared.hostApprovalErrorMessage(state: s)
            hostPairingComplete = s.hostPairingComplete
            offerRole = s.offerRole
            // Same caching rule as the host QR: regenerate only when the payload changes, not on
            // every monitor tick (the CoreImage render is non-trivial).
            if s.offerUri != offerUri {
                offerUri = s.offerUri
                offerQrImage = s.offerUri.flatMap { qrCodeImage(from: $0) }
            }
            offerSas = s.offerSas
            offerPaired = s.offerPaired
            offerDenied = s.offerDenied
            offerFailed = s.offerFailed
            offerRemovedMessage = ControlSurface.shared.offerRemovedMessage(state: s)
            realmUnfunded = s.realmUnfunded
            autoContinueArmed = s.autoContinueArmed
            errorMessage = ControlSurface.shared.pairingErrorMessage(state: s)
            done = step == "done"
        }
    }

    /// The Scan tab defaults to the camera scanner (`scan_qr`, the VM's initial step). A DEBUG launch-arg
    /// fast-forwards to a deeper step / the manual entry / the Show tab for screenshot capture; in release
    /// this is a no-op and the camera (or its no-camera fallback) is shown. The screenshot routes run over
    /// fakes (`ControlIosApp.init` skips `installRealRuntime()` for them), so they prefill a sample code.
    func start(debugStep: String?) {
        #if DEBUG
        switch debugStep {
        case "manual":
            pairingCode = ControlSurface.shared.fakeJoinerPairingCode()
            vm.onPickManual()
        case "show":
            vm.onSelectShow()
        case "name", "approval", "success":
            pairingCode = ControlSurface.shared.fakeJoinerPairingCode()
            vm.onQrScanned(data: pairingCode)
            if debugStep != "name" { vm.confirmPairing(deviceName: deviceName) }
        default:
            break
        }
        #endif
    }

    func selectScan() { vm.onSelectScan() }
    func selectShow() { vm.onSelectShow() }
    func stopShowing() { vm.onStopShowing() }
    func pickManual() { vm.onPickManual() }
    func scanned(_ data: String) { vm.onQrScanned(data: data) }
    func submitCode() { vm.onQrScanned(data: pairingCode) }
    func confirm() { vm.confirmPairing(deviceName: deviceName) }
    func cancelToStart() { vm.onBackToScanStart() }
    func successContinue() { vm.onSuccessContinue() }
    func approveOffer() { vm.approveOffer() }
    func denyOffer() { vm.denyOffer() }
    func approveHostRequest() { vm.approveHostRequest() }
    func denyHostRequest() { vm.denyHostRequest() }
    func retryOffer() { vm.onRetryOffer() }
}

struct PairingView: View {
    let onClose: () -> Void
    /// Fired when the wizard reaches **success** (the joiner committed to the realm) — distinct from a
    /// plain `onClose` (toolbar Cancel). The onboarding joiner uses it to finish onboarding + provision
    /// the Drive session; the Settings entry leaves it at the default no-op (its `onClose` already
    /// dismisses the sheet on either outcome). `done` is only ever reached via `onSuccessContinue`.
    var onPaired: () -> Void = {}
    /// Hide the Scan|Show segmented control and pin the wizard to the **joiner** (Scan) role. The
    /// onboarding entry sets this — a device with no realm yet can't host (`onSelectShow` →
    /// `HostPairingMonitor.startPairing` needs a realm). The Settings entry leaves both tabs visible.
    var joinerOnly: Bool = false
    /// "Set up storage" from a terminal that found the realm cannot sync (Track D). Defaults to
    /// dismissing the wizard — Settings → Services is one tap from there — so the button is never
    /// dead. ⚠ A direct deep-link to Services is the better wiring and is still owed.
    var onSetUpStorage: (() -> Void)? = nil
    @StateObject private var model = PairingModel()
    /// One cancel state for whichever terminal is on screen; reset when a new terminal appears.
    @StateObject private var autoContinue = AutoContinueController()

    /// Identifies the terminal on screen, so a new one starts un-cancelled.
    private var terminalKey: String {
        "\(model.mode)|\(model.step)|\(model.offerRole)|\(model.hostPairingComplete)|\(model.offerPaired)"
    }

    /// DEBUG-only deep-link for screenshots: `-pairingStep manual|name|approval|success`.
    private static func debugStep() -> String? {
        #if DEBUG
        let args = ProcessInfo.processInfo.arguments
        if let i = args.firstIndex(of: "-pairingStep"), i + 1 < args.count { return args[i + 1] }
        #endif
        return nil
    }

    var body: some View {
        NavigationStack {
            OnboardingScaffold {
                if atTabRoot && !joinerOnly {
                    modePicker
                    vGap(24)
                }
                if model.mode == "show" {
                    showContent
                } else {
                    switch model.step {
                    case "scan_qr": scanQrContent
                    case "manual_code": manualContent
                    case "name_device": nameContent
                    case "approval": approvalContent
                    case "success": successContent
                    case "responder_approval": responderApprovalContent
                    case "responder_success": responderSuccessContent
                    default: EmptyView()
                    }
                }
            }
            // §9: a tap anywhere means "I'm reading this" — stop any auto-continue countdown.
            // Simultaneous, so the tapped control (Continue included) still gets its action.
            .simultaneousGesture(TapGesture().onEnded { autoContinue.cancel() })
            .onChange(of: terminalKey) { _ in autoContinue.reset() }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(PairingStrings.close) { onClose() }
                }
            }
            .task { await model.observe() }
            // NT-2b: ask for notification permission *here*, not at launch. Pairing is what produces
            // inbound requests, so the reason for alerts is on screen — and the system prompt is
            // one-shot, so spending it under "Welcome to Tenshu" disabled the background-wake alerts
            // for good. Idempotent: no-ops once the status is no longer NotDetermined.
            .task { ControlSurface.shared.requestNotificationPermission() }
            .onAppear {
                model.start(debugStep: Self.debugStep())
                // Claim the approval while this wizard is up — the root-level alert suppresses
                // itself so the two never stack over the same request.
                PairingSurfacePresence.shared.entered()
            }
            .onDisappear { PairingSurfacePresence.shared.left() }
            .onChange(of: model.done) { done in
                if done {
                    // `done` is reached only via onSuccessContinue → the joiner is in the realm.
                    onPaired()
                    onClose()
                }
            }
        }
        .tint(OnboardingColors.primary)
    }

    /// "tab-root" = a tab landing surface (the whole Show tab, or Scan while still on the entry steps),
    /// where the segmented control + "Add a device" title show. Hidden once the joiner is committed
    /// (NameDevice / Approval / Success). Mirrors the canonical `isTabRoot`.
    private var atTabRoot: Bool {
        if model.mode == "show" {
            // ⚠ Deliberate divergence from the canonical `isTabRoot` (Show is always tab-root
            // there): on Compose the approval is a modal that covers the tabs, here it is rendered
            // *in* the tab. Leaving the segmented control live would put "Scan a code" — which
            // calls `onSelectScan` → `stopPairing()` — one stray tap away from burning the session
            // the user is mid-way through approving.
            //
            // The terminals are excluded for a plainer reason: "Device added" is an outcome, not a
            // tab landing. Keeping the picker there offered a choice that no longer means anything
            // and pushed the content down behind a title + tab strip it had no use for.
            return model.hostRequestDeviceName == nil
                && !model.hostPairingComplete
                && !model.offerPaired
        }
        return model.step == "scan_qr" || model.step == "manual_code"
    }

    private var modePicker: some View {
        Picker(PairingStrings.modePickerLabel, selection: modeBinding) {
            Text(PairingStrings.tabScan).tag("scan")
            Text(PairingStrings.tabShow).tag("show")
        }
        .pickerStyle(.segmented)
        .labelsHidden()
        .accessibilityLabel(PairingStrings.modePickerLabel)
    }

    private var modeBinding: Binding<String> {
        Binding(
            get: { model.mode },
            set: { newMode in newMode == "show" ? model.selectShow() : model.selectScan() }
        )
    }

    private var title: String {
        if model.mode == "show" && model.hostRequestDeviceName != nil {
            return PairingStrings.titleHostApproval
        }
        if model.mode == "show" && model.hostPairingComplete {
            return PairingStrings.titleResponderSuccess
        }
        if model.mode == "show" && model.offerPaired {
            return PairingStrings.titleSuccess
        }
        if atTabRoot { return PairingStrings.addDeviceTitle }
        switch model.step {
        case "name_device": return PairingStrings.titleNameDevice
        case "approval": return PairingStrings.titleApproval
        case "success": return PairingStrings.titleSuccess
        case "responder_approval": return PairingStrings.titleResponderApproval
        case "responder_success": return PairingStrings.titleResponderSuccess
        default: return PairingStrings.addDeviceTitle
        }
    }

    // MARK: - ScanQr (canonical ScanQrStep — camera scanner + "Enter code instead")

    @ViewBuilder private var scanQrContent: some View {
        Text(PairingStrings.scanBody)
            .font(.body)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
        vGap(16)
        if isCameraAvailable {
            QRScannerView(onScanned: { model.scanned($0) })
                .frame(height: 320)
                .frame(maxWidth: .infinity)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            vGap(12)
            Text(PairingStrings.scanHint)
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        } else {
            cameraUnavailableNote
        }
        if let error = model.errorMessage {
            vGap(12)
            Text(error)
                .font(.callout)
                .foregroundStyle(.red)
                .multilineTextAlignment(.center)
        }
        vGap(16)
        Button(PairingStrings.scanEnterCode) { model.pickManual() }
            .buttonStyle(LinkButtonStyle())
    }

    /// Simulator / no-camera fallback: a framed placeholder explaining the camera isn't available and
    /// nudging to the (always-available) manual-code path below.
    @ViewBuilder private var cameraUnavailableNote: some View {
        VStack(spacing: 12) {
            Image(systemName: "qrcode.viewfinder")
                .font(.system(size: 56))
                .foregroundStyle(.secondary)
            Text(PairingStrings.scanNoCamera)
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .frame(height: 320)
        .background(Color(uiColor: .secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    // MARK: - ManualCode (canonical ManualCodeStep)

    @ViewBuilder private var manualContent: some View {
        Text(PairingStrings.manualHeading)
            .font(.onboardingHeadline)
            .multilineTextAlignment(.center)
        vGap(16)
        TextField(PairingStrings.manualPlaceholder, text: $model.pairingCode, axis: .vertical)
            .textFieldStyle(.roundedBorder)
            .font(.system(size: 14, design: .monospaced))
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .lineLimit(1...4)
        if let error = model.errorMessage {
            vGap(12)
            Text(error)
                .font(.callout)
                .foregroundStyle(.red)
                .multilineTextAlignment(.center)
        }
        vGap(20)
        Button(PairingStrings.manualContinue) { model.submitCode() }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(model.pairingCode.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
    }

    // MARK: - NameDevice (canonical NameDeviceStep)

    @ViewBuilder private var nameContent: some View {
        Text(PairingStrings.nameHeading)
            .font(.onboardingHeadline)
            .multilineTextAlignment(.center)
        vGap(12)
        Text(PairingStrings.nameBody)
            .font(.body)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
        vGap(20)
        TextField(PairingStrings.namePlaceholder, text: $model.deviceName)
            .textFieldStyle(.roundedBorder)
            .submitLabel(.go)
            .onSubmit { model.confirm() }
        vGap(8)
        Text(PairingStrings.nameRenameLater)
            .font(.caption)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
        if let error = model.errorMessage {
            vGap(12)
            Text(error)
                .font(.callout)
                .foregroundStyle(.red)
                .multilineTextAlignment(.center)
        }
        vGap(24)
        Button(PairingStrings.nameContinue) { model.confirm() }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(model.deviceName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
    }

    // MARK: - Approval (canonical ApprovalStep — SAS comparison + spinner)

    @ViewBuilder private var approvalContent: some View {
        Text(PairingStrings.approvalHeading)
            .font(.onboardingHeadline)
            .multilineTextAlignment(.center)
        vGap(12)
        Text(PairingStrings.approvalBody)
            .font(.body)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
        vGap(28)
        sasCard
        vGap(24)
        ProgressView()
            .tint(OnboardingColors.primary)
        if let error = model.errorMessage {
            vGap(16)
            Text(error)
                .font(.callout)
                .foregroundStyle(.red)
                .multilineTextAlignment(.center)
        }
        vGap(20)
        Button(PairingStrings.approvalCancel) { model.cancelToStart() }
            .buttonStyle(OutlinedButtonStyle())
    }

    @ViewBuilder private var sasCard: some View {
        VStack(spacing: 8) {
            Text(PairingStrings.approvalMatchPrompt)
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            // Render the SAS "XXX YYY" (3-3 split), matching the canonical Compose ApprovalStep.
            Text(displaySas)
                .font(.system(size: 34, weight: .regular, design: .monospaced))
            Text(PairingStrings.sasVersionHint)
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            if model.sas == nil {
                Text(PairingStrings.approvalComputing)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(20)
        .background(Color(uiColor: .secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private var displaySas: String {
        guard let sas = model.sas, sas.count >= 6 else { return PairingStrings.approvalSasPlaceholder }
        let head = sas.prefix(3)
        let tail = sas.dropFirst(3)
        return "\(head) \(tail)"
    }

    // MARK: - Success (canonical SuccessStep)

    @ViewBuilder private var successContent: some View {
        MarkOutline(dimension: 160)
        vGap(24)
        Text(PairingStrings.successHeading)
            .font(.onboardingHeadline)
            .multilineTextAlignment(.center)
            .accessibilityAddTraits(.isHeader)
        vGap(16)
        if model.realmUnfunded {
            // ⚠⚠ The default body promises "your existing data will sync over the next few
            // minutes". On a realm with no storage plan the relay refuses every write, so that
            // sentence is a promise nothing can keep — printed where the user is paying the most
            // attention. The heading stays: joining really did succeed.
            unfundedRealmBody
        } else {
            Text(PairingStrings.successBody)
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            vGap(8)
            Text(PairingStrings.successNote)
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            vGap(28)
            // §9: arms once the join-race settle has passed (the shared VM's funding check).
            AutoContinueButton(
                title: PairingStrings.successContinue,
                armed: model.autoContinueArmed,
                controller: autoContinue
            ) { model.successContinue() }
        }
    }

    /// The Track D explanation + CTA, shared by the joiner and offerer terminals (both of which
    /// have just put THIS device into the realm).
    @ViewBuilder private var unfundedRealmBody: some View {
        Text(PairingStrings.unfundedHeading)
            .font(.headline)
            .multilineTextAlignment(.center)
        vGap(8)
        Text(PairingStrings.unfundedBody)
            .font(.body)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
        vGap(28)
        Button(PairingStrings.unfundedCta) { (onSetUpStorage ?? onClose)() }
            .buttonStyle(PrimaryButtonStyle())
        vGap(8)
        // ⚠ Never a dead end: joining succeeded, so Continue must still be here.
        Button(PairingStrings.successContinue) { model.successContinue() }
            .buttonStyle(OutlinedButtonStyle())
    }

    // MARK: - ResponderApproval (canonical ResponderApprovalStep — admit a scanned new device)

    @ViewBuilder private var responderApprovalContent: some View {
        // Still parsing the scanned offer (respondToPairingOffer in flight): show a brief reading state.
        if model.responderDeviceName == nil || model.responderSas == nil {
            ProgressView()
                .tint(OnboardingColors.primary)
            vGap(16)
            Text(PairingStrings.responderReading)
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        } else {
            Text(String(format: PairingStrings.responderHeading, model.responderDeviceName ?? ""))
                .font(.onboardingHeadline)
                .multilineTextAlignment(.center)
            vGap(12)
            Text(PairingStrings.responderBody)
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            vGap(28)
            responderSasCard
            vGap(24)
            Button(model.isLoading ? PairingStrings.responderAdding : PairingStrings.responderApprove) {
                model.approveOffer()
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(model.isLoading)
            vGap(8)
            Button(PairingStrings.responderDeny) { model.denyOffer() }
                .buttonStyle(OutlinedButtonStyle())
                .disabled(model.isLoading)
            if let error = model.errorMessage {
                vGap(12)
                Text(error)
                    .font(.callout)
                    .foregroundStyle(.red)
                    .multilineTextAlignment(.center)
            }
        }
    }

    @ViewBuilder private var responderSasCard: some View {
        VStack(spacing: 8) {
            Text(PairingStrings.approvalMatchPrompt)
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Text(responderDisplaySas)
                .font(.system(size: 34, weight: .regular, design: .monospaced))
        }
        .frame(maxWidth: .infinity)
        .padding(20)
        .background(Color(uiColor: .secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private var responderDisplaySas: String {
        guard let sas = model.responderSas, sas.count >= 6 else { return PairingStrings.approvalSasPlaceholder }
        return "\(sas.prefix(3)) \(sas.dropFirst(3))"
    }

    // MARK: - ResponderSuccess (the scanned new device was admitted)

    @ViewBuilder private var responderSuccessContent: some View {
        MarkOutline(dimension: 160)
        vGap(24)
        Text(PairingStrings.responderSuccessHeading)
            .font(.onboardingHeadline)
            .multilineTextAlignment(.center)
            .accessibilityAddTraits(.isHeader)
        vGap(16)
        Text(PairingStrings.responderSuccessBody)
            .font(.body)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
        // ⚠⚠ Parity with the Compose `ResponderSuccessStep`: the code STAYS on screen. The offer
        // flow's SAS is post-approval by construction (pairing-flow.md §4.5), so without it here
        // the user is asked to compare against the new device's screen from memory —
        // `sync-pairing-scanned-announce.md` §5.3.
        if let sas = model.responderSas {
            vGap(24)
            VStack(spacing: 8) {
                Text(PairingStrings.responderSuccessMatchNote)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                Text(sas.count >= 6 ? "\(sas.prefix(3)) \(sas.dropFirst(3))" : sas)
                    .font(.system(size: 34, weight: .regular, design: .monospaced))
            }
            .frame(maxWidth: .infinity)
            .padding(20)
            .background(Color(uiColor: .secondarySystemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
        vGap(28)
        // §9: the SAS responder stays manual — this screen is the side-by-side check. The shared
        // rule already says so; the `responderSas == nil` guard keeps it true whatever it says.
        AutoContinueButton(
            title: PairingStrings.successContinue,
            armed: model.autoContinueArmed && model.responderSas == nil,
            controller: autoContinue
        ) { model.successContinue() }
    }

    // MARK: - Offer (canonical OfferCodeStep — this device shows, an existing device approves)

    @ViewBuilder private var offerContent: some View {
        if model.offerPaired {
            offerPairedContent
        } else if let removed = model.offerRemovedMessage {
            // Before scanned and denied/failed: a refusal can land after SCANNED, and every retry
            // of a removed identity is refused, so no "try again" — the remedy is "Forget this
            // device" here.
            Text(removed)
                .font(.body)
                .foregroundStyle(.red)
                .multilineTextAlignment(.center)
            vGap(24)
            Button(PairingStrings.showStop) { model.stopShowing() }
                .buttonStyle(OutlinedDestructiveButtonStyle())
        } else if model.offerScanned {
            // A3: the other device scanned and announced its key, so the SAS is derivable here
            // while its approval prompt is still up. Replace the QR with the code — the QR has
            // done its job, and the code is now the thing the user must act on.
            Text(PairingStrings.offerScannedHeading)
                .font(.onboardingHeadline)
                .multilineTextAlignment(.center)
            vGap(8)
            Text(PairingStrings.offerScannedBody)
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            vGap(20)
            if let sas = model.offerSas {
                // Same 3-3 split and monospaced face as `offerPairedContent` — the two screens
                // must render the code identically or the comparison is harder than it need be.
                Text(sas.count >= 6 ? "\(sas.prefix(3)) \(sas.dropFirst(3))" : sas)
                    .font(.system(size: 34, weight: .regular, design: .monospaced))
            }
            vGap(24)
            Button(PairingStrings.showStop) { model.stopShowing() }
                .buttonStyle(OutlinedDestructiveButtonStyle())
        } else if model.offerDenied || model.offerFailed {
            Text(model.offerDenied ? PairingStrings.offerDenied : PairingStrings.offerFailed)
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            vGap(24)
            Button(PairingStrings.offerRetry) { model.retryOffer() }
                .buttonStyle(PrimaryButtonStyle())
            vGap(8)
            Button(PairingStrings.showStop) { model.stopShowing() }
                .buttonStyle(OutlinedDestructiveButtonStyle())
        } else {
            Text(PairingStrings.offerHeading)
                .font(.onboardingHeadline)
                .multilineTextAlignment(.center)
            vGap(8)
            Text(PairingStrings.offerBody)
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            vGap(20)
            // ⚠ No connection-lost / not-recognised banner here, deliberately: being refused by
            // the relay is *why* this tab switched to the offer role, and repeating it above the
            // code would report the failure the screen is already repairing.
            if let uri = model.offerUri {
                if let qr = model.offerQrImage {
                    Image(uiImage: qr)
                        .interpolation(.none)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 220, height: 220)
                    vGap(16)
                }
                Text(PairingStrings.manualCodeLabel)
                    .font(.callout.weight(.medium))
                    .frame(maxWidth: .infinity, alignment: .leading)
                vGap(4)
                hostCodeField(uri)
            } else {
                // Still minting the offer session.
                ProgressView()
                    .tint(OnboardingColors.primary)
            }
            vGap(24)
            Button(PairingStrings.showStop) { model.stopShowing() }
                .buttonStyle(OutlinedDestructiveButtonStyle())
        }
    }

    /// Terminal success for the offerer: the responder approved and the proxy installed the keys —
    /// this device is in the realm. The SAS is shown for the §4.5 *post-hoc* comparison against the
    /// prompt the other device answered.
    @ViewBuilder private var offerPairedContent: some View {
        MarkOutline(dimension: 160)
        vGap(24)
        Text(PairingStrings.successHeading)
            .font(.onboardingHeadline)
            .multilineTextAlignment(.center)
            .accessibilityAddTraits(.isHeader)
        vGap(16)
        Text(PairingStrings.successBody)
            .font(.body)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
        if let sas = model.offerSas {
            vGap(20)
            Text(PairingStrings.offerSasLabel)
                .font(.caption)
                .foregroundStyle(.secondary)
            vGap(4)
            Text(sas.count >= 6 ? "\(sas.prefix(3)) \(sas.dropFirst(3))" : sas)
                .font(.system(size: 34, weight: .regular, design: .monospaced))
        }
        // ⚠ No early `return` here: a @ViewBuilder body cannot early-return, so the branch is an
        // if/else. (It compiled as a `return` in the Kotlin twin, which is what led me astray.)
        if model.realmUnfunded {
            vGap(20)
            unfundedRealmBody
        } else {
            vGap(28)
            // Routes through the wizard's normal completion (`onSuccessContinue` → done →
            // `onPaired`): unlike the host terminal, THIS device is the one that just joined.
            // §9: armed only if the SAS was on screen before the responder approved (T-P-07).
            AutoContinueButton(
                title: PairingStrings.successContinue,
                armed: model.autoContinueArmed,
                controller: autoContinue
            ) { model.successContinue() }
        }
    }

    // MARK: - Show (canonical ShowCodeStep — host pairing code + Copy + Stop)

    @ViewBuilder private var showContent: some View {
        if model.offerRole {
            // This device is the one being added: it shows a `tenshu://offer` code for an existing
            // device to scan and approve. Mirrors the canonical `OfferCodeStep`.
            offerContent
        } else if let deviceName = model.hostRequestDeviceName {
            // A device scanned this one's code and is waiting on Approve/Deny — the §3 Step 3
            // "active approval". It supersedes the QR: the code has done its job, and the joiner
            // cannot proceed until this is answered.
            hostApprovalContent(deviceName: deviceName)
        } else if model.hostPairingComplete {
            hostSuccessContent
        } else {
            Text(PairingStrings.showHeading)
                .font(.onboardingHeadline)
                .multilineTextAlignment(.center)
            vGap(8)
            Text(PairingStrings.showBody)
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            vGap(20)

            // ⚠ No "go and scan instead" surface here any more: when the relay refuses this device
            // the shared VM switches this tab to the OFFER role, so the user gets a code to be
            // approved — the same gesture as adding any new device. See
            // `PairingWizardViewModel.onSelectShow`.
            showHostCodeContent
        }
    }

    // MARK: - Host approval (canonical HostPairingApprovalDialog, rendered in-tab)

    /// The joiner's name + the locally-derived SAS + Approve/Deny. Mirrors the Compose
    /// `HostPairingApprovalDialog`, with one structural difference: Compose overlays it on any
    /// destination, iOS renders it where the user already is. Like its twin it offers no passive
    /// dismissal — it is SAS-bearing, so it resolves through Approve or Deny (closing the sheet
    /// leaves the request pending on the monitor, where the root-level alert picks it up).
    @ViewBuilder private func hostApprovalContent(deviceName: String) -> some View {
        Image(systemName: "laptopcomputer.and.iphone")
            .font(.system(size: 56))
            .foregroundStyle(OnboardingColors.primary)
        vGap(20)
        Text(String(format: PairingStrings.hostJoinRequest, deviceName))
            .font(.onboardingHeadline)
            .multilineTextAlignment(.center)
        // The SAS is an enrichment the proxy adds locally (it is optional on the wire), so the
        // prompt renders off the device name and the card only when there is one to compare.
        if let sas = model.hostRequestSas {
            vGap(24)
            VStack(spacing: 8) {
                Text(PairingStrings.hostSasPrompt)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                Text(sas.count >= 6 ? "\(sas.prefix(3)) \(sas.dropFirst(3))" : sas)
                    .font(.system(size: 34, weight: .regular, design: .monospaced))
                Text(PairingStrings.sasVersionHint)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity)
            .padding(20)
            .background(Color(uiColor: .secondarySystemBackground))
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
        vGap(24)
        Button(PairingStrings.hostApprove) { model.approveHostRequest() }
            .buttonStyle(PrimaryButtonStyle())
        vGap(8)
        Button(PairingStrings.hostDeny) { model.denyHostRequest() }
            .buttonStyle(OutlinedDestructiveButtonStyle())
        // Approval failure (most often: this device is a phrase-less joiner and may not admit
        // devices). The request stays up so the message has somewhere to live; Deny closes it.
        if let error = model.hostApprovalError {
            vGap(16)
            Text(error)
                .font(.callout)
                .foregroundStyle(.red)
                .multilineTextAlignment(.center)
        }
    }

    /// Terminal success for a host session — the approval landed and the proxy accepted it.
    @ViewBuilder private var hostSuccessContent: some View {
        MarkOutline(dimension: 160)
        vGap(24)
        Text(PairingStrings.hostSuccessHeading)
            .font(.onboardingHeadline)
            .multilineTextAlignment(.center)
            .accessibilityAddTraits(.isHeader)
        vGap(16)
        // D2: the host knows FIRST — its own pushes have been refused all along — so the device
        // that ran the pairing is where "…but nothing will reach it" belongs.
        Text(model.realmUnfunded ? PairingStrings.unfundedHostNote : PairingStrings.hostSuccessBody)
            .font(.body)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
        vGap(28)
        // This device was already in the realm — the new one joined it — so there is nothing to
        // continue *into*: Done closes the wizard rather than routing through `onSuccessContinue`
        // (which is the joiner's completion, and would fire `onPaired`).
        if model.realmUnfunded {
            Button(PairingStrings.unfundedCta) { (onSetUpStorage ?? onClose)() }
                .buttonStyle(PrimaryButtonStyle())
            vGap(8)
            Button(PairingStrings.successContinue) { onClose() }
                .buttonStyle(OutlinedButtonStyle())
        } else {
            // §9: the host terminal arms with no settle delay (no join race on this side).
            AutoContinueButton(
                title: PairingStrings.successContinue,
                armed: model.autoContinueArmed,
                controller: autoContinue
            ) { onClose() }
        }
    }

    /// The host surface — QR/code, or the reason there is none.
    @ViewBuilder private var showHostCodeContent: some View {
        if model.hostConnectionLost {
            hostFailureBanner
            vGap(16)
        }

        if let code = model.hostPairingString {
            if let qr = model.hostQrImage {
                Image(uiImage: qr)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 220, height: 220)
                vGap(16)
            }
            Text(PairingStrings.manualCodeLabel)
                .font(.callout.weight(.medium))
                .frame(maxWidth: .infinity, alignment: .leading)
            vGap(4)
            hostCodeField(code)
        } else if let error = model.errorMessage {
            // The start produced no session, so nothing is coming: say why and offer another
            // attempt (`onSelectShow` is the idempotent start). Stop (below) still leaves for Scan.
            Text(error)
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            vGap(24)
            Button(PairingStrings.offerRetry) { model.selectShow() }
                .buttonStyle(PrimaryButtonStyle())
        } else if !model.hostConnectionLost {
            // No code yet (the host session is still starting) and no error — show a spinner.
            ProgressView()
                .tint(OnboardingColors.primary)
        } else {
            Text(PairingStrings.pairingError)
                .font(.callout)
                .foregroundStyle(.secondary)
        }

        vGap(24)
        Button(PairingStrings.showStop) { model.stopShowing() }
            .buttonStyle(OutlinedDestructiveButtonStyle())
    }

    /// The pairing code rendered as selectable monospaced text with a Copy affordance — the iOS analogue
    /// of the canonical read-only `AppTextField` + copy `IconButton` (no QR image on iOS; that's B-4).
    @ViewBuilder private func hostCodeField(_ code: String) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Text(code)
                .font(.system(size: 13, design: .monospaced))
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button {
                UIPasteboard.general.string = code
            } label: {
                Image(systemName: "doc.on.doc")
            }
            .accessibilityLabel(PairingStrings.copyClipboard)
        }
        .padding(12)
        .overlay(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .stroke(Color.secondary.opacity(0.4), lineWidth: 1)
        )
    }

    @ViewBuilder private var hostFailureBanner: some View {
        Text(model.hostDeviceUnrecognized ? PairingStrings.deviceNotRecognized : PairingStrings.connectionLost)
            .font(.callout)
            .foregroundStyle(Color(uiColor: .systemRed))
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity)
            .padding(12)
            .background(Color(uiColor: .systemRed).opacity(0.12))
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

/// "Stop" on the Show tab — an outlined button tinted destructive (canonical `OutlinedButton` with
/// `error` content colour).
struct OutlinedDestructiveButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.body.weight(.medium))
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .foregroundStyle(Color(uiColor: .systemRed))
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .stroke(Color(uiColor: .systemRed).opacity(0.5), lineWidth: 1)
            )
            .opacity(configuration.isPressed ? 0.6 : 1)
    }
}

// MARK: - Canonical strings (mirror control-shared composeResources `pairing_*` / `add_device_*`)

enum PairingStrings {
    static let close = "Cancel"
    static let addDeviceTitle = "Add a device"
    static let titleNameDevice = "Name This Device"
    static let titleApproval = "Confirm on Your Other Device"
    static let titleSuccess = "You're In Your Realm"

    static let modePickerLabel = "How to add a device"
    static let tabScan = "Scan a code"
    static let tabShow = "Show a code"

    static let scanBody = "On the new device, scan the code shown on a device that's already signed in."
    static let scanHint = "Hold the QR code from your existing device inside the box."
    static let scanEnterCode = "Enter code instead"
    static let scanNoCamera = "Camera unavailable on this device.\nEnter the pairing code instead."

    static let showHeading = "Show this on your new device"
    static let showBody = "Scan this code with the device you want to add."
    static let showStop = "Stop"
    static let connectionLost = "Can't reach the relay — retrying…"
    static let deviceNotRecognized = "This device isn't recognised by your account any more — its " +
        "access was removed, or the account data was reset. Pair this device again to restore it. " +
        "Your local data is safe."
    static let manualCodeLabel = "Manual Code:"

    // Host approval + host terminal success (mirror the control-shared composeResources
    // `sync_dashboard_dialog_*` / `sync_dashboard_join_request` / `sync_dashboard_sas_prompt` and
    // `add_device_show_success_*`).
    static let titleHostApproval = "Authorization Request"
    static let hostJoinRequest = "Device \"%@\" wants to join your realm."
    static let hostSasPrompt = "Confirm this code matches on the other device:"
    /// Join flow, both screens. Word-for-word the Compose `pairing_sas_version_hint` and the web
    /// `versionHint` keys — SAS v2 is lockstep, so an older Tenshu on either side shows a different code.
    static let sasVersionHint = "If the codes don't match, don't continue — make sure both devices are on the latest version of Tenshu."
    static let hostApprove = "Approve"
    static let hostDeny = "Deny"
    static let offerHeading = "Scan with your other device"
    static let offerBody =
        "On a device that already has Tenshu, open Control, choose Add a device, and scan this code — then approve the request there."
    static let offerSasLabel = "Code shown on both devices"
    static let offerScannedHeading = "Code scanned"
    static let offerScannedBody = "Check this code matches the one on your other device, then approve there."
    static let offerDenied = "The other device declined the request."
    static let offerFailed = "Couldn't create a pairing code. Check your connection and try again."
    static let offerRetry = "Show a new code"
    // Track D — the realm cannot sync (mirror the composeResources `unfunded_realm_*`).
    static let unfundedHeading = "Nothing will sync yet"
    static let unfundedBody =
        "This realm has no storage plan, so the relay is refusing writes. Everything stays on your devices and nothing is lost — but changes won't reach your other devices until storage is set up."
    static let unfundedCta = "Set up storage"
    static let unfundedHostNote = "Nothing will sync to it yet — this realm has no storage plan."
    static let hostSuccessHeading = "Device added"
    static let hostSuccessBody =
        "The new device has been paired. Your data will sync to it over the next few minutes."
    static let copyClipboard = "Copy to clipboard"
    static let pairingError = "Error generating pairing code"

    static let manualHeading = "Enter the pairing code"
    static let manualPlaceholder = "tenshu://join..."
    static let manualContinue = "Continue"

    static let nameHeading = "Name this device"
    static let nameBody =
        "Pick a name for this device. You'll see it in your realm's device list — \"Sarah's MacBook\", \"Living Room Hub\", anything you'll recognise."
    static let namePlaceholder = "e.g. Work Laptop"
    static let nameRenameLater = "You can rename this later in Settings → Realm Devices."
    static let nameContinue = "Continue"

    static let approvalHeading = "Confirm on your other device"
    static let approvalBody = "Your other device received the request. Look there for the approval prompt."
    static let approvalMatchPrompt = "Confirm this code matches on your other device:"
    static let approvalSasPlaceholder = "— — —   — — —"
    static let approvalComputing = "Computing…"
    static let approvalCancel = "Cancel"

    static let successHeading = "You're in your realm"
    static let successBody =
        "This device is now part of your realm. Your existing data will sync over the next few minutes."
    static let successNote = "Don't worry if sync takes a few minutes on slower connections."
    static let successContinue = "Continue"

    // Responder (offer-flow §5.2 — this device admits a scanned new device). Mirrors the
    // control-shared composeResources `pairing_responder_*` / `pairing_title_responder_*`.
    static let titleResponderApproval = "Add This Device"
    static let titleResponderSuccess = "Device Added"
    static let responderReading = "Reading pairing code…"
    static let responderHeading = "Add \"%@\" to your realm?"
    static let responderBody =
        "This device showed a pairing code. Approve only if you're adding it yourself — check the code below matches the one it shows."
    static let responderApprove = "Approve"
    static let responderDeny = "Deny"
    static let responderAdding = "Adding device…"
    static let responderSuccessHeading = "Device added"
    static let responderSuccessBody =
        "The new device has been paired. Your data will sync to it over the next few minutes."
    /// Word-for-word the Compose `pairing_responder_success_match_note`.
    static let responderSuccessMatchNote =
        "Check this code matches the one now shown on the new device. If it doesn't, remove that device and try again."
}
