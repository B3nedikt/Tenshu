import CoreLocation
import SwiftUI
import TenshuSdk

// Record (fit-app.md §2 screens 7+8): the pre-start activity picker + sensor rows, and the live
// recording surface. One `RecordViewModel` — over `MockRecordingEngine` in the mock build, the real
// `IosRecordingEngine` (GPS / motion / BLE-HR) on a live client — spans both; the live surface
// observes the same engine state. Finish persists a workout and opens its detail.
//
// Just-in-time permission priming (R-iOS-2, §11.2): entering this tab prompts for nothing. Start
// primes Location + Motion (an in-app rationale precedes the OS sheet); the HR row's Connect action
// primes Bluetooth — so the majority who own no strap are never asked for it.
struct RecordView: View {
    @StateObject private var model = RecordModel()
    @State private var finishedId: String?
    @State private var showMotionRationale = false
    @State private var showHrRationale = false
    @State private var draftTitle = ""
    @State private var draftActivity: ActivityType?
    @State private var confirmDiscard = false

    var body: some View {
        content
            .navigationTitle("Record")
            .navigationBarTitleDisplayMode(.inline)
            .task { await model.observe() }
            .onChange(of: model.finishedWorkoutId) { id in
                if let id { finishedId = id; model.consumeFinished() }
            }
            .sheet(item: Binding(get: { finishedId.map(Identified.init) }, set: { finishedId = $0?.id })) { wrapped in
                NavigationStack { WorkoutDetailView(workoutId: wrapped.id) }
            }
            // Save-outcome notices (§9.13): saved-locally = the draft ended safely (journalled
            // on-device, replays when Control is back); save-failed = the draft is retained, Save
            // retries.
            .alert("Saved on this device", isPresented: Binding(get: { model.savedLocally }, set: { _ in model.consumeSavedLocally() })) {
                Button("OK") { model.consumeSavedLocally() }
            } message: {
                Text("Control was unreachable, so the workout is journalled on this device and will sync when the connection is back.")
            }
            .alert("Could not save", isPresented: Binding(get: { model.saveFailed }, set: { _ in model.dismissSaveFailed() })) {
                Button("OK") { model.dismissSaveFailed() }
            } message: {
                Text("The workout was kept — tap Save to try again.")
            }
    }

    @ViewBuilder private var content: some View {
        switch model.recording.map({ onEnum(of: $0) }) {
        case .some(.active(let active)):
            // A recording already under way keeps going even if Control drops — the live engine
            // captures locally and journals the save (R-iOS-3 outbox).
            liveSurface(active)
        case .some(.reviewing(let reviewing)):
            // RC-2's review surface (fit-app.md §13.2) — also where an RC-5 crash-recovered
            // draft lands, connection or not (the draft exists; a failed save is retained).
            summarySurface(reviewing.draft)
        default:
            if !connected {
                // Not connected → no real recorder (the mock would tick fake numbers and fail to
                // save, fit-app.md §11.7). Gate the flow behind a connect prompt.
                notConnected
            } else {
                preStart
            }
        }
    }

    // MARK: Not connected

    /// Whether Fit has a real data path — the SAME verdict the shell's banner uses
    /// (`FitIosApp.MainShell`): the **route-1** in-process runtime is installed, OR the route-2 EPC
    /// live client is active (only the DEV_HARNESS Pair tab can activate that one now).
    ///
    /// ⚠ This gate used to read `FitLiveClientSession.shared.isActive` alone, so a properly
    /// provisioned route-1 device — which never opens an EPC session by design
    /// (ios-first-party-route-1-migration.md P2) — got the whole app plus a "Not connected" wall on
    /// Record only. Every other tab was live, which made the wall look like a Record-specific bug.
    private var connected: Bool {
        FitLocalRuntime.shared.isInstalled() || FitLiveClientSession.shared.isActive
    }

    /// The SHARED forward-to-Control surface (ADR 0022), not a Fit-local wall.
    ///
    /// ⚠ This used to be a bespoke "Not connected" message telling the user to open Control
    /// — an instruction with **nothing to tap**. Drive had solved the same problem properly
    /// (deep link, and an App Store hand-off when Control isn't installed at all); Fit just
    /// never got it. Sharing the screen is what stops the two drifting again.
    ///
    /// ⚠ Record only, deliberately. Fit's other tabs stay browsable over cached data and the
    /// shell keeps its non-blocking banner — recording is the one thing that genuinely cannot
    /// work without a runtime (the mock would tick fake numbers and fail to save,
    /// fit-app.md §11.7), so it is the one place a full-screen gate is honest.
    private var notConnected: some View {
        ConnectToControlView(
            appName: "Fit",
            purpose: "record and sync your workouts, end-to-end encrypted",
            onRecheck: { NotificationCenter.default.post(name: .liveSessionChanged, object: nil) }
        )
    }

    // MARK: Pre-start

    private var preStart: some View {
        ScrollView {
            VStack(spacing: 20) {
                VStack(spacing: 12) {
                    ForEach([ActivityType.running, .walking, .cycling, .strength, .other], id: \.self) { type in
                        Button { model.select(type) } label: {
                            HStack(spacing: 12) {
                                Image(systemName: type.sfSymbol).foregroundStyle(type.tint).frame(width: 28)
                                Text(type.label).font(.body.weight(.medium)).foregroundStyle(.primary)
                                Spacer()
                                if model.selectedActivity == type {
                                    Image(systemName: "checkmark.circle.fill").foregroundStyle(type.tint)
                                }
                            }
                            .padding(14)
                            .background((model.selectedActivity == type ? type.tint.opacity(0.12) : Color(.secondarySystemGroupedBackground)),
                                        in: RoundedRectangle(cornerRadius: 14))
                        }
                        .buttonStyle(.plain)
                    }
                }

                VStack(spacing: 8) {
                    ForEach(Array(model.sensors.enumerated()), id: \.offset) { _, sensor in
                        HStack {
                            Image(systemName: sensorIcon(sensor.kind))
                            Text(sensorName(sensor.kind))
                            Spacer()
                            // HR is opt-in: while the strap isn't connected, the row offers Connect
                            // (which primes Bluetooth) instead of a status — nobody is asked for
                            // Bluetooth just by opening Record (§11.2).
                            if sensor.kind == .heartRate && sensor.state != .ready {
                                Button("Connect") { showHrRationale = true }
                                    .font(.caption.weight(.semibold))
                                    .buttonStyle(.bordered)
                                    .controlSize(.small)
                            } else {
                                Text(sensorState(sensor.state)).font(.caption).foregroundStyle(sensorColor(sensor.state))
                            }
                        }
                        .font(.subheadline)
                    }
                }
                .padding(14)
                .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 14))

                Button {
                    // Prime Location + Motion behind an in-app rationale the first time; once the OS
                    // has an answer, Start goes straight through.
                    if locationUndetermined() { showMotionRationale = true } else { model.start() }
                } label: {
                    Text("Start").font(.title3.weight(.semibold)).frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .tint(model.selectedActivity.tint)
            }
            .padding(20)
        }
        .alert("Record your workout", isPresented: $showMotionRationale) {
            Button("Not now", role: .cancel) {}
            Button("Continue") { model.start() }
        } message: {
            Text("Fit maps your route and counts your steps on-device while you record. Location and motion are used only during a workout — nothing leaves your device unencrypted.")
        }
        .alert("Connect a heart-rate strap", isPresented: $showHrRationale) {
            Button("Not now", role: .cancel) {}
            Button("Connect") { model.connectHeartRate() }
        } message: {
            Text("Fit pairs with a Bluetooth heart-rate strap, only while you record.")
        }
    }

    /// Whether iOS has yet to record a Location decision — the cue to show the rationale before the
    /// OS sheet. Constructing a `CLLocationManager` to read the status does not prompt.
    private func locationUndetermined() -> Bool {
        CLLocationManager().authorizationStatus == .notDetermined
    }

    // MARK: Live surface

    private func liveSurface(_ a: RecordingStateActive) -> some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 28) {
                Spacer()
                Text(FitUnitFormat.duration(Int(a.elapsedSec)))
                    .font(.system(size: 64, weight: .bold, design: .rounded)).foregroundStyle(.white)
                    .monospacedDigit()
                HStack(spacing: 28) {
                    liveStat(FitUnitFormat.distance(Int(a.distanceM)), "distance")
                    if let pace = a.paceSecPerKm { liveStat(FitUnitFormat.pace(pace.intValue), "pace") }
                    if let hr = a.heartRateBpm { liveStat("\(hr.intValue)", "bpm") }
                }
                Spacer()
                HStack(spacing: 16) {
                    if a.paused {
                        Button { model.resume() } label: { liveButton("Resume", .green) }
                    } else {
                        Button { model.pause() } label: { liveButton("Pause", .orange) }
                    }
                    // RC-2: Stop parks the draft for review — nothing is saved from here.
                    Button { model.stop() } label: { liveButton("Finish", .blue) }
                }
                Button("Discard", role: .destructive) { model.discard() }
                    .foregroundStyle(.red).padding(.bottom, 12)
            }
            .padding(24)
        }
    }

    // MARK: Review & save (RC-2/RC-3/RC-5, fit-app.md §13.2/§13.3/§13.5)

    private func summarySurface(_ draft: RecordingDraft) -> some View {
        // The preview carries the applied trim — sketch + stats update live on Remove/Undo, and
        // Save persists exactly this window (the same fold rides DraftEdits into commit).
        let preview = model.reviewPreview ?? draft
        return ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Workout complete").font(.title2.weight(.semibold))
                if draft.recovered {
                    Text("Recovered from an interrupted recording — the last few seconds may be missing.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                if preview.routeSketch.count >= 2 {
                    RoutePathView(points: preview.routeSketch)
                        .frame(height: 150)
                        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 14))
                }
                summaryStats(preview.workout)
                if let trim = model.trimSuggestion {
                    trimCard(trim, durationSec: Int(draft.workout.durationSec))
                }
                TextField(
                    "Title",
                    text: $draftTitle,
                    prompt: Text((draftActivity ?? draft.workout.activityType).label)
                )
                .textFieldStyle(.roundedBorder)
                Picker("Activity", selection: Binding(
                    get: { draftActivity ?? draft.workout.activityType },
                    set: { draftActivity = $0 }
                )) {
                    ForEach([ActivityType.running, .walking, .cycling, .strength, .other], id: \.self) { type in
                        Label(type.label, systemImage: type.sfSymbol).tag(type)
                    }
                }
                .pickerStyle(.menu)

                Button {
                    model.save(title: draftTitle, activity: draftActivity ?? draft.workout.activityType)
                } label: {
                    Text("Save workout").font(.headline).frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                Button("Discard", role: .destructive) { confirmDiscard = true }
                    .frame(maxWidth: .infinity)
            }
            .padding(20)
        }
        .onAppear { draftTitle = ""; draftActivity = nil }
        .confirmationDialog("Discard workout?", isPresented: $confirmDiscard, titleVisibility: .visible) {
            Button("Discard", role: .destructive) { model.discard() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This recording will be deleted and cannot be recovered.")
        }
    }

    private func summaryStats(_ w: Workout) -> some View {
        let bpm = w.avgHr.map { "\($0.intValue)" } ?? "—"
        let maxBpm = w.maxHr.map { "\($0.intValue)" } ?? "—"
        let steps = w.steps.map { "\($0.intValue)" } ?? "—"
        return VStack(spacing: 12) {
            HStack {
                summaryStat(FitUnitFormat.duration(Int(w.durationSec)), "duration")
                summaryStat(w.distanceM.map { FitUnitFormat.distance($0.intValue) } ?? "—", "distance")
                summaryStat(w.avgPaceSecPerKm.map { FitUnitFormat.pace($0.intValue) } ?? "—", "pace")
            }
            HStack {
                summaryStat(bpm, "avg hr")
                summaryStat(maxBpm, "max hr")
                summaryStat(steps, "steps")
            }
        }
        .padding(14)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 14))
    }

    private func summaryStat(_ value: String, _ label: String) -> some View {
        VStack(spacing: 2) {
            Text(value).font(.headline).monospacedDigit()
            Text(label).font(.caption2).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }

    /// The RC-3 trim card: one row per trimmable end, each with its own Remove/Undo toggle — a
    /// suggestion the user applies, never an automatic cut.
    private func trimCard(_ trim: TrimSuggestion, durationSec: Int) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("TRIM SUGGESTION").font(.caption2.weight(.semibold)).foregroundStyle(.secondary)
            if let start = trim.trimStartSec {
                trimRow(
                    "Remove \(FitUnitFormat.duration(start.intValue)) of idle time at the start?",
                    applied: model.trimStartApplied
                ) { model.setTrimStart(!model.trimStartApplied) }
            }
            if let end = trim.trimEndSec {
                trimRow(
                    "Remove \(FitUnitFormat.duration(durationSec - end.intValue)) of idle time at the end?",
                    applied: model.trimEndApplied
                ) { model.setTrimEnd(!model.trimEndApplied) }
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 14))
    }

    private func trimRow(_ label: String, applied: Bool, onToggle: @escaping () -> Void) -> some View {
        HStack {
            Text(label).font(.subheadline)
            Spacer()
            Button(applied ? "Undo" : "Remove", action: onToggle)
                .font(.caption.weight(.semibold))
                .buttonStyle(.bordered)
                .controlSize(.small)
        }
    }

    private func liveStat(_ value: String, _ label: String) -> some View {
        VStack(spacing: 2) {
            Text(value).font(.title2.weight(.semibold)).foregroundStyle(.white).monospacedDigit()
            Text(label).font(.caption).foregroundStyle(.white.opacity(0.6))
        }
    }

    private func liveButton(_ title: String, _ color: Color) -> some View {
        Text(title).font(.headline).foregroundStyle(.white)
            .frame(maxWidth: .infinity).padding(.vertical, 14)
            .background(color, in: RoundedRectangle(cornerRadius: 14))
    }

    private func sensorIcon(_ k: SensorKind) -> String {
        switch k { case .gps: return "location.fill"; case .heartRate: return "heart.fill"
        case .stepCounter: return "figure.walk"; @unknown default: return "sensor" }
    }
    private func sensorName(_ k: SensorKind) -> String {
        switch k { case .gps: return "GPS"; case .heartRate: return "Heart rate"
        case .stepCounter: return "Step counter"; @unknown default: return "Sensor" }
    }
    private func sensorState(_ s: SensorState) -> String {
        switch s { case .searching: return "Searching…"; case .ready: return "Ready"
        case .unavailable: return "Unavailable"; @unknown default: return "" }
    }
    private func sensorColor(_ s: SensorState) -> Color {
        switch s { case .ready: return .green; case .searching: return .orange
        case .unavailable: return .secondary; @unknown default: return .secondary }
    }
}

// `sheet(item:)` needs an Identifiable wrapper for the finished-workout id.
private struct Identified: Identifiable { let id: String }

@MainActor
final class RecordModel: ObservableObject {
    @Published var selectedActivity: ActivityType = .running
    @Published var sensors: [SensorStatus] = []
    @Published var recording: RecordingState?
    @Published var finishedWorkoutId: String?
    // Review surface (RC-2/RC-3): the trim-previewed draft + suggestion + applied toggles.
    @Published var reviewPreview: RecordingDraft?
    @Published var trimSuggestion: TrimSuggestion?
    @Published var trimStartApplied = false
    @Published var trimEndApplied = false
    @Published var savedLocally = false
    @Published var saveFailed = false

    private let vm = FitSurface.shared.recordViewModel()

    func observe() async {
        for await s in vm.uiState {
            selectedActivity = s.selectedActivity
            sensors = s.sensors
            recording = s.recording
            finishedWorkoutId = s.finishedWorkoutId
            reviewPreview = s.reviewPreview
            trimSuggestion = s.trimSuggestion
            trimStartApplied = s.trimStartApplied
            trimEndApplied = s.trimEndApplied
            savedLocally = s.savedLocally
            saveFailed = s.saveFailed
        }
    }

    func select(_ type: ActivityType) { vm.selectActivity(type: type) }
    func connectHeartRate() { vm.connectHeartRate() }
    func start() { vm.start() }
    func pause() { vm.pause() }
    func resume() { vm.resume() }
    /// RC-2: park the session as a Reviewing draft — the review surface takes it from there.
    func stop() { vm.stop() }
    func discard() { vm.discard() }
    func setTrimStart(_ applied: Bool) { vm.setTrimStartApplied(applied: applied) }
    func setTrimEnd(_ applied: Bool) { vm.setTrimEndApplied(applied: applied) }
    /// Batches the review edits into the VM, then commits — what the preview showed is what saves.
    func save(title: String, activity: ActivityType) {
        vm.setDraftTitle(value: title)
        vm.setDraftActivity(type: activity)
        vm.save()
    }
    func consumeFinished() { vm.consumeFinished() }
    func consumeSavedLocally() { vm.consumeSavedLocally() }
    func dismissSaveFailed() { vm.dismissSaveFailed() }
}
