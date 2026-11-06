import SwiftUI
import Photos
import UIKit
import TenshuSdk

// P6b (ios-drive-parity.md): the Synced-Folders reframe as an iOS Settings section — "Back up my
// Photos". The backup ENGINE + the PhotoKit source live in Kotlin (`DriveBackupSurface` over the
// shared `SyncedFolderController`); this owns the iOS-side story: photo-library consent, the
// enable toggle, a live status footer, and a foreground "Back up now" catch-up — the reliable core
// of the honest "open the app to finish" UX (iOS grants no guaranteed background wake).
struct PhotoBackupSection: View {
    @StateObject private var model = PhotoBackupModel()

    var body: some View {
        Section {
            Toggle(isOn: Binding(get: { model.enabled }, set: { model.setEnabled($0) })) {
                Label("Back up my Photos", systemImage: "photo.on.rectangle.angled")
            }
            .disabled(model.busy)

            if model.enabled {
                // The per-album selection entry point — narrows backup below the whole
                // library, and is what makes the new-album prompt able to fire at all.
                NavigationLink {
                    BackupScopeView()
                } label: {
                    HStack {
                        Label("Photos to back up", systemImage: "rectangle.stack")
                        Spacer()
                        Text(model.scopeSummary)
                            .foregroundStyle(.secondary)
                    }
                }

                // The new-album prompt is its OWN switch, independent of the master
                // toggle — same split as Android's "Ask about new folders". Turning it
                // off stops the notification, never the backup.
                //
                // Disabled in All Photos mode because there is genuinely nothing to
                // announce: a new album's contents are already being backed up. Same
                // reasoning as Android disabling it without full media access — an
                // active-looking switch would promise something that cannot happen.
                Toggle(isOn: Binding(
                    get: { model.askAboutNewAlbums },
                    set: { model.setAskAboutNewAlbums($0) }
                )) {
                    Label("Ask about new albums", systemImage: "bell.badge")
                }
                .disabled(model.scopeIsAllPhotos)

                if model.scopeIsAllPhotos {
                    // Say WHY it is inert rather than leaving a greyed switch unexplained.
                    Text("Applies when you back up selected albums — with All Photos, a new album is already included.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Button {
                    model.backupNow()
                } label: {
                    Label("Back up now", systemImage: "arrow.clockwise")
                }
                .disabled(model.busy)
            }

            // Limited-access notice — the iOS twin of Android's partial-access notice.
            // Advisory, not a gate: under "limited" the photos the user picked genuinely
            // do back up. Without it the shortfall is invisible, because PhotoKit answers
            // every fetch happily and the library just looks smaller than it is.
            if model.enabled, model.mediaAccess != "full" {
                VStack(alignment: .leading, spacing: 8) {
                    Text(model.mediaAccess == "limited"
                         ? "Drive can only see selected photos"
                         : "Drive cannot see your photos")
                        .font(.subheadline.weight(.semibold))
                    Text(model.mediaAccess == "limited"
                         ? "You chose to share specific photos, so Drive cannot see your albums or notice new ones. Only the photos you picked are backed up."
                         : "Photo backup needs access to your photo library. Without it, nothing is backed up.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    Button("Open Settings") { model.openSystemSettings() }
                        .font(.footnote)
                }
                .padding(.vertical, 4)
            }
        } header: {
            Text("Photo Backup")
        } footer: {
            Text(model.footer)
        }
        .task { await model.start() }
        .onAppear {
            model.refreshMediaAccess()
            model.refreshScope()
        }
    }
}

@MainActor
final class PhotoBackupModel: ObservableObject {
    @Published var enabled = false
    @Published var status = ""
    @Published var busy = false
    /// "full" | "limited" | "denied", read live from Kotlin (never cached — the grant
    /// can change in Settings while Drive is backgrounded).
    @Published var mediaAccess = "full"
    /// Right-hand summary on the "Photos to back up" row.
    @Published var scopeSummary = "All Photos"
    /// Whether the new-album prompt is on (its own preference, not the master switch).
    @Published var askAboutNewAlbums = true
    /// In All Photos mode the prompt has nothing to announce, so its switch is inert.
    @Published var scopeIsAllPhotos = true

    var footer: String {
        if !enabled {
            return "Automatically back up your photos and videos to Drive, end-to-end encrypted. "
                + "iOS backs up in the background when it can — open Drive to catch up."
        }
        return status.isEmpty ? "Backing up your photo library." : status
    }

    private var started = false

    /// Observe the durable enabled pref + the live status text (both Kotlin Flows via SKIE).
    func start() async {
        if started { return }
        started = true
        Task { for await on in DriveBackupSurface.shared.observeEnabled() { self.enabled = on.boolValue } }
        Task { for await text in DriveBackupSurface.shared.observeStatusText() { self.status = text } }
        Task {
            for await on in DriveBackupSurface.shared.observeAskAboutNewAlbums() {
                self.askAboutNewAlbums = on.boolValue
            }
        }
        refreshMediaAccess()
    }

    /// Re-read the photo-library grant. Called on appear and after the consent dialog;
    /// iOS offers no change notification for it, so it is polled at the points where it
    /// can plausibly have changed rather than cached.
    func refreshMediaAccess() {
        mediaAccess = DriveBackupSurface.shared.mediaAccessState()
    }

    /// Re-read the backup scope for the row summary. Called on appear so it
    /// reflects a change made in the picker.
    func refreshScope() {
        Task {
            let all = (try? await DriveBackupSurface.shared.isAllPhotosScope())?.boolValue ?? true
            scopeIsAllPhotos = all
            if all {
                scopeSummary = "All Photos"
            } else {
                let count = ((try? await DriveBackupSurface.shared.listAlbums()) ?? [])
                    .filter(\.selected).count
                scopeSummary = count == 0 ? "None" : "\(count) album\(count == 1 ? "" : "s")"
            }
        }
    }

    func openSystemSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }

    /// Enabling first requests photo-library authorization; a denial leaves the toggle off.
    func setEnabled(_ on: Bool) {
        Task {
            if on {
                let granted = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
                guard granted == .authorized || granted == .limited else {
                    refreshMediaAccess()
                    return
                }
                refreshMediaAccess()
            }
            busy = true
            try? await DriveBackupSurface.shared.setEnabled(enabled: on)
            busy = false
        }
    }

    /// Toggle the new-album prompt. Notification permission is asked for HERE — the
    /// moment the user opts into being notified — rather than when backup is enabled,
    /// which is a different decision.
    func setAskAboutNewAlbums(_ on: Bool) {
        askAboutNewAlbums = on
        Task {
            if on { await NewFolderPrompt.requestAuthorizationIfNeeded() }
            try? await DriveBackupSurface.shared.setAskAboutNewAlbums(enabled: on)
        }
    }

    /// Foreground catch-up — run one drain pass now ("open the app to finish").
    func backupNow() {
        Task {
            busy = true
            _ = try? await DriveBackupSurface.shared.backupNow()
            busy = false
        }
    }
}
