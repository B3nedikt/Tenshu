import SwiftUI
import TenshuSdk

// Services — the read-only storage/plan status surface (S1,
// docs/proposals/ios-services-status-and-purchase.md). Binds the Kotlin
// `ServicesStatusModel` (ControlSurface.servicesStatusModel()): plan + source +
// used/limit bytes + account email, flattened Kotlin-side to scalars so no
// SKIE-bridged composite leaks into SwiftUI (the SyncDashboardView discipline).
//
// Deliberately NO purchase affordances: signup/checkout are S2, gated on the
// external-purchase-vs-IAP decision (App Store rules) — and no disconnect or
// change-identity either, which Android's card carries beside those buttons. What an
// unfunded device gets is where storage comes from, plus S2-pre's sign-in: the one
// action here, and the only route out of §3.5's dead end.
@MainActor
final class ServicesModel: ObservableObject {
    @Published var planKey = "free"
    @Published var sourceKey = "none"
    @Published var usedBytes: Int64 = 0
    @Published var limitBytes: Int64 = 0
    @Published var accountEmail: String?

    private let model = ControlSurface.shared.servicesStatusModel()

    init() {
        apply(model.snapshot.value)
    }

    func observe() async {
        for await snapshot in model.snapshot {
            apply(snapshot)
        }
    }

    private func apply(_ snapshot: ServicesStatusSnapshot) {
        planKey = snapshot.planKey
        sourceKey = snapshot.sourceKey
        usedBytes = snapshot.usedBytes
        limitBytes = snapshot.limitBytes
        accountEmail = snapshot.accountEmail
    }

    /// Short form for the Settings row value ("Storage    Basic ›") — the long
    /// tier descriptions stay on the detail screen.
    var planShortLabel: String {
        switch planKey {
        case "basic": return "Basic"
        case "premium": return "Premium"
        case "free": return "Free"
        default: return "No plan"
        }
    }

    var planLabel: String {
        switch planKey {
        case "basic": return "Basic — 50 GB"
        case "premium": return "Premium — 2 TB"
        case "free": return "Free"
        default: return "No plan"
        }
    }

    var sourceLabel: String? {
        switch sourceKey {
        case "syncedRealm": return "Storage is active through your paired realm. Manage the plan on the device that owns the account."
        // ⚠⚠ This used to end "…or set up an account on your computer", which is a dead end for an
        // iOS-only user: with StorageSyncGate shut, their realm never syncs and there is no second
        // device to be rescued from (ios-services-status-and-purchase.md §3.5). S2-pre gives this
        // device its own way out; the copy names it rather than pointing off-device.
        case "none": return "No storage plan yet. Sign in to your Tenshu account — or create a free one — to start syncing this device."
        // ⚠⚠ Signed in, but nothing funds this realm. `servicesStatusSnapshot` reports
        // `localAccount` the moment a `PublisherAccount` exists locally, independently of whether
        // any entitlement was found — so this pair is reachable whenever the session outlives its
        // binding (a commerce reset, an account bound to a realm this device has since left, or
        // simply the window before a fresh sign-in syncs back). Left unhandled it fell to `nil`
        // below and rendered as a bare "No plan" beside an email address, explaining nothing.
        case "localAccount" where isUnfunded:
            return "Signed in, but no storage plan is active for this realm yet. Sign in again to link this account to it."
        default: return nil
        }
    }

    /// ⚠⚠ Gated on **unfunded**, not on `sourceKey == "none"`: the old gate assumed a local account
    /// implied funded storage. It does not — `servicesStatusSnapshot` sets `localAccount` from the
    /// account alone — so a device holding a stale session reported `localAccount` with no plan and
    /// lost the only affordance iOS has. `SignInView`'s other entry point is onboarding, so past
    /// onboarding that state had no route left at all: no sign-in, and (S1 being read-only) no
    /// disconnect either. Signing in binds *this* realm's `ownerId`, which is exactly the repair.
    ///
    /// Still silent where it should be: `syncedRealm` and a local account with a plan both carry a
    /// funded `planKey`, so neither offers sign-in.
    var canSignIn: Bool { isUnfunded }

    /// No funded storage was read for this realm. `planKey` is `"none"` exactly when
    /// `servicesStatusSnapshot` found no entitlement — every funded tier maps to its own key.
    private var isUnfunded: Bool { planKey == "none" }

    var usageFraction: Double? {
        guard limitBytes > 0 else { return nil }
        return min(max(Double(usedBytes) / Double(limitBytes), 0), 1)
    }

    // ⚠⚠ The shared Kotlin `formatStorageBytes` (`:storage-service-common`), never
    // `ByteCountFormatter`: that one steps in 1000s, and the plans are binary — Free's 5 GiB read
    // "5.37 GB" here (App Store screenshot run, 2026-10-14). Same formatter as Android's card and
    // every app's storage banner. A top-level Kotlin function: a static on its file facade, no
    // `.shared`.
    static func formatBytes(_ bytes: Int64) -> String {
        StorageBytesFormatKt.formatStorageBytes(bytes: bytes)
    }
}

struct ServicesView: View {
    @StateObject private var model = ServicesModel()

    var body: some View {
        List {
            Section("Plan") {
                LabeledContent("Plan", value: model.planLabel)
                if let email = model.accountEmail {
                    LabeledContent("Account", value: email)
                }
            }
            if let fraction = model.usageFraction {
                Section("Storage") {
                    VStack(alignment: .leading, spacing: 6) {
                        ProgressView(value: fraction)
                        Text("\(ServicesModel.formatBytes(model.usedBytes)) of \(ServicesModel.formatBytes(model.limitBytes)) used")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 4)
                }
            }
            if let hint = model.sourceLabel {
                Section {
                    Text(hint)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            // S2-pre: the way out of §3.5's dead end. Offered only when this device has no plan of
            // its own AND is not already covered by the realm — a device inheriting storage through
            // a paired realm manages the account where it lives, and signing in here would say
            // nothing it needs.
            if model.canSignIn {
                Section {
                    NavigationLink {
                        SignInView()
                    } label: {
                        Label(SignInStrings.title, systemImage: "person.badge.key")
                    }
                }
            }
        }
        .navigationTitle("Storage")
        .task { await model.observe() }
    }
}
