import Network
import SwiftUI
import TenshuSdk
import UIKit

// Settings → Models, and Models → one feature (ios-on-device-inference.md §14.18.15): the shared
// `ModelsViewModel` on iOS.
//
// ⚠⚠ Swift decides nothing and writes no sentence. What is on the screen is
// `photosModelsScreen(state, device, onWifi)` and `photosFeatureScreen(state, capability, device)`:
// every sentence, whether a button is there, and their order. A press is carried out by
// `photosModelsPress` / `photosFeaturePress`. This file lays the values out and forwards presses.
//
// ⚠⚠ Nothing here moves before the Kotlin state does: a pressed button changes no row, and a
// question is on screen exactly while the state holds one.

/// Bridges one `ModelsViewModel` to SwiftUI, and builds it again when what it was built over is
/// replaced.
@MainActor
final class ModelsModel: ObservableObject {
    enum Screen { case models, feature }

    private let screen: Screen
    private var binding: PhotosModelsBinding
    @Published private(set) var state: ModelsUiState
    /// The path monitor's answer, as Kotlin read it (`photosOnWifi`); `nil` until it has answered.
    @Published private(set) var onWifi: Bool?
    private var observation: Task<Void, Never>?
    private var installs: Task<Void, Never>?
    private let path = NWPathMonitor()
    let device = PhotosSurface.shared.deviceNoun()

    init(screen: Screen) {
        self.screen = screen
        let binding = Self.build(screen)
        self.binding = binding
        state = binding.state.value
        observe(binding)
        // ⚠⚠ A view model built before a teardown holds the retired repository, and its *Try
        // again* can never work (`PhotosSurface.modelsInstalls`): built again over what replaced it.
        installs = Task { [weak self] in
            for await install in PhotosSurface.shared.modelsInstalls {
                guard let self else { return }
                if install.int32Value != self.binding.install { self.rebuild() }
            }
        }
        // Only the Models screen says anything about the network (*Waiting for Wi-Fi*).
        if screen == .models { watchPath() }
    }

    deinit {
        observation?.cancel()
        installs?.cancel()
        path.cancel()
        // ⚠⚠ Nothing on iOS clears a Kotlin view model: without this its collectors run on.
        binding.close()
    }

    private static func build(_ screen: Screen) -> PhotosModelsBinding {
        switch screen {
        case .models: return PhotosSurface.shared.models()
        case .feature: return PhotosSurface.shared.modelsFeature()
        }
    }

    private func observe(_ binding: PhotosModelsBinding) {
        observation = Task { [weak self] in
            for await value in binding.state {
                // ⚠ Of the binding this model holds now: a replaced one says nothing more here.
                guard let self, self.binding === binding else { return }
                self.state = value
            }
        }
    }

    private func rebuild() {
        observation?.cancel()
        binding.close()
        let binding = Self.build(screen)
        self.binding = binding
        state = binding.state.value
        observe(binding)
    }

    private func watchPath() {
        #if DEV_HARNESS
        // The fixture's network, where it names one: the simulator's is the Mac's.
        if let fixed = ModelsProbe.probe?.onWifi {
            onWifi = fixed.boolValue
            return
        }
        #endif
        path.pathUpdateHandler = { [weak self] path in
            let wifi = PhotosModelsCopyKt.photosOnWifi(
                satisfied: path.status == .satisfied,
                usesWifi: path.usesInterfaceType(.wifi),
                usesWired: path.usesInterfaceType(.wiredEthernet)
            )
            Task { @MainActor [weak self] in self?.onWifi = wifi }
        }
        path.start(queue: DispatchQueue.global(qos: .utility))
    }

    /// Forwards a press, then reads the state Kotlin holds NOW.
    /// ⚠ Not an optimistic update: the view model's own change is synchronous, and the stream
    /// delivers it a turn later. A question answered must not be on screen for that turn.
    func send(_ call: (ModelsViewModel) -> Void) {
        call(binding.viewModel)
        state = binding.state.value
    }

    var models: PhotosModelsScreen {
        PhotosModelsCopyKt.photosModelsScreen(state: state, device: device, onWifi: onWifi.map { KotlinBoolean(bool: $0) })
    }

    func feature(_ capability: InferenceCapability) -> PhotosFeatureScreen? {
        PhotosModelsCopyKt.photosFeatureScreen(state: state, capability: capability, device: device)
    }
}

struct ModelsScreen: View {
    @StateObject private var model = ModelsModel(screen: .models)
    @Environment(\.dismiss) private var dismiss
    /// Navigation only: the feature screen is pushed.
    @State private var showFeature = ModelsScreen.opensFeature
    /// Navigation only: which feature was pressed. ⚠ Kept, so that the pushed screen is built for
    /// it even when the list no longer offers it (a teardown): that screen then closes itself,
    /// where an empty destination left a blank screen pushed.
    @State private var pressedFeature: InferenceCapability?

    private static var opensFeature: Bool {
        #if DEV_HARNESS
        return ModelsProbe.opens == "feature"
        #else
        return false
        #endif
    }

    private func hidden(_ screen: PhotosModelsScreen) -> Bool {
        if case .hidden = onEnum(of: screen.body) { return true }
        return false
    }

    /// How a question's yes is drawn. ⚠ Exhaustive: a kind Kotlin adds does not compile until it is drawn.
    private func role(_ kind: PhotosModelsScreen.AskedKind) -> ButtonRole? {
        switch kind {
        case .removal: return .destructive
        case .featureOffer: return nil
        }
    }

    var body: some View {
        let screen = model.models
        let asked = screen.question
        List {
            ModelsMessageSection(message: screen.message) {
                if let shown = screen.messageShown { model.send { PhotosModelsCopyKt.photosModelsMessageSeen(viewModel: $0, shown: shown) } }
            }
            if let checking = screen.checking {
                Section {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text(checking)
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("models-checking")
                }
            }
            switch onEnum(of: screen.body) {
            case .hidden:
                EmptyView()
            case .notice(let notice):
                notices(text: notice.text, detail: notice.detail, tryAgain: notice.tryAgain)
            case .rows(let rows):
                if let notice = rows.notice { notices(text: notice, detail: nil, tryAgain: rows.tryAgain) }
                ForEach(rows.rows, id: \.modelId) { row in ModelRowSection(row: row, model: model) }
            }
            if let feature = screen.feature {
                Section {
                    Button {
                        pressedFeature = feature.capability
                        showFeature = true
                    } label: {
                        PushRow(title: feature.title, status: feature.status, detail: feature.detail)
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("models-feature")
                }
            }
            // ⚠ Under the switch it depends on: its "turn on … above" names that row.
            if let indexing = screen.indexing { IndexingSection(indexing: indexing, model: model) }
        }
        .navigationTitle(PhotosModelsCopy.shared.MODELS)
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(isPresented: $showFeature) {
            if let capability = pressedFeature ?? screen.feature?.capability {
                ModelFeatureScreen(capability: capability)
            } else {
                // Opened by the harness before the list offered a feature, and none came.
                Color.clear.onAppear { showFeature = false }
            }
        }
        // This build has no Models screen (the button is not drawn either): there is nothing to stay for.
        .task(id: hidden(screen)) { if hidden(screen) { dismiss() } }
        // ⚠⚠ Presented by the STATE's question and nothing else, which Kotlin chose: a removal
        // before an offer. The setter is empty on purpose: each button says what it means to the
        // view model, and a dismissal written here as well would withdraw the question before
        // *Remove* read it.
        .alert(
            asked?.question.title ?? "",
            isPresented: Binding(get: { asked != nil }, set: { _ in }),
            presenting: asked
        ) { asked in
            Button(asked.question.yes, role: role(asked.kind)) {
                model.send { PhotosModelsCopyKt.photosModelsAnswer(viewModel: $0, kind: asked.kind, yes: true) }
            }
            Button(asked.question.no, role: .cancel) {
                model.send { PhotosModelsCopyKt.photosModelsAnswer(viewModel: $0, kind: asked.kind, yes: false) }
            }
        } message: { asked in
            Text(asked.question.text)
        }
    }

    /// What is wrong with the list as a whole, and the one thing to do about it where there is one.
    @ViewBuilder
    private func notices(text: String, detail: String?, tryAgain: String?) -> some View {
        Section {
            VStack(alignment: .leading, spacing: 4) {
                Text(text)
                if let detail { Text(detail).font(.subheadline).foregroundStyle(.secondary) }
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("models-notice")
            if let tryAgain {
                Button(tryAgain) { model.send { $0.retry() } }
                    .accessibilityIdentifier("models-try-again")
            }
        }
    }
}

/// A one-shot message, said until it is acknowledged: the press consumes it in the view model, so
/// it is not said again on a return to the screen, a rotation or a new collection.
/// ⚠ In the list, not an alert: a second alert beside a question is dropped by SwiftUI, silently.
private struct ModelsMessageSection: View {
    let message: String?
    let consume: () -> Void

    var body: some View {
        if let message {
            Section {
                Text(message)
                    .accessibilityIdentifier("models-message")
                Button(PhotosModelsCopy.shared.OK, action: consume)
                    .accessibilityIdentifier("models-message-dismiss")
            }
            // VoiceOver hears it when it arrives, wherever its cursor is, and hears the one that
            // replaces it too (the section stays, so `onAppear` alone said only the first).
            .onAppear { UIAccessibility.post(notification: .announcement, argument: message) }
            .onChange(of: message) { replaced in UIAccessibility.post(notification: .announcement, argument: replaced) }
        }
    }
}

/// One model: what it is and where it stands, then a row per action, in Kotlin's order.
private struct ModelRowSection: View {
    let row: PhotosModelsScreen.Row
    @ObservedObject var model: ModelsModel

    var body: some View {
        Section {
            VStack(alignment: .leading, spacing: 4) {
                Text(row.title).font(.headline)
                Text(row.facts).font(.footnote).foregroundStyle(.secondary)
                if let status = row.status { Text(status).padding(.top, 2) }
                if let detail = row.detail { Text(detail).font(.subheadline).foregroundStyle(.secondary) }
                if let progress = row.progress {
                    switch onEnum(of: progress) {
                    case .bar(let bar): ProgressView(value: bar.fraction).padding(.top, 4)
                    case .busy: ProgressView().padding(.top, 4)
                    }
                }
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("models-row-\(row.modelId)")
            ForEach(row.actions, id: \.kind) { action in
                Button(action.label, role: role(action.kind)) {
                    model.send { PhotosModelsCopyKt.photosModelsPress(viewModel: $0, modelId: row.modelId, action: action) }
                }
                .accessibilityIdentifier("models-action-\(action.kind.name)-\(row.modelId)")
            }
        }
    }

    /// How a kind is drawn. ⚠ Exhaustive: a kind Kotlin adds does not compile until it is drawn.
    private func role(_ kind: PhotosModelsScreen.ActionKind) -> ButtonRole? {
        switch kind {
        case .remove: return .destructive
        case .download, .downloadWithMobileData, .cancel, .tryAgain, .loadAgain: return nil
        }
    }
}

/// The indexing section (§14.19.6): where this iPhone's index stands, then a row per action, in
/// Kotlin's order. ⚠ Kotlin chose every sentence and every button; this lays them out.
private struct IndexingSection: View {
    let indexing: PhotosModelsScreen.Indexing
    @ObservedObject var model: ModelsModel

    var body: some View {
        Section(indexing.title) {
            VStack(alignment: .leading, spacing: 4) {
                Text(indexing.status)
                if let detail = indexing.detail { Text(detail).font(.subheadline).foregroundStyle(.secondary) }
                if let progress = indexing.progress {
                    switch onEnum(of: progress) {
                    case .bar(let bar): ProgressView(value: bar.fraction).padding(.top, 4)
                    case .busy: ProgressView().padding(.top, 4)
                    }
                }
                if let coverage = indexing.coverage {
                    Text(coverage).font(.footnote).foregroundStyle(.secondary).padding(.top, 2)
                }
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("models-indexing")
            ForEach(indexing.actions, id: \.kind) { action in
                Button(action.label) {
                    model.send { PhotosModelsCopyKt.photosIndexingPress(viewModel: $0, action: action) }
                }
                .accessibilityIdentifier("models-index-\(action.kind.name)")
            }
        }
    }
}

/// Models → one feature: what it does, and which of the user's devices do the work (§38's shape).
struct ModelFeatureScreen: View {
    let capability: InferenceCapability
    @StateObject private var model = ModelsModel(screen: .feature)
    @Environment(\.dismiss) private var dismiss
    /// Presentation only: *Turn off everywhere* was pressed and its question is up. ⚠ The question
    /// itself, and whether there is one to ask, are the screen value's (`everywhere`).
    @State private var asking = false

    var body: some View {
        if let screen = model.feature(capability) {
            content(screen)
        } else {
            // No switch is offered for it any more (the runtime behind the screen was replaced).
            Color.clear.onAppear { dismiss() }
        }
    }

    // ⚠ In pieces: as one expression the list outruns the type checker.
    private func content(_ screen: PhotosFeatureScreen) -> some View {
        List {
            ModelsMessageSection(message: screen.message) {
                if let shown = screen.messageShown { model.send { PhotosModelsCopyKt.photosModelsMessageSeen(viewModel: $0, shown: shown) } }
            }
            about(screen)
            devices(screen)
            everywhere(screen)
        }
        .navigationTitle(screen.title)
        .navigationBarTitleDisplayMode(.inline)
        // ⚠ A question whose button has gone is withdrawn with it, and does not come back by itself.
        .onChange(of: screen.everywhere == nil) { gone in if gone { asking = false } }
        .alert(
            screen.everywhere?.question.title ?? "",
            isPresented: Binding(get: { asking && screen.everywhere != nil }, set: { if !$0 { asking = false } }),
            presenting: screen.everywhere
        ) { everywhere in
            Button(everywhere.question.yes, role: .destructive) { press(everywhere.press) }
            Button(everywhere.question.no, role: .cancel) {}
        } message: { everywhere in
            Text(everywhere.question.text)
        }
    }

    private func about(_ screen: PhotosFeatureScreen) -> some View {
        Section {
            Text(screen.about)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .accessibilityIdentifier("feature-description")
        }
    }

    private func devices(_ screen: PhotosFeatureScreen) -> some View {
        Section(screen.devicesHeader) {
            device(screen.thisDevice)
            ForEach(screen.others, id: \.key) { other in device(other) }
            if let notice = screen.othersNotice {
                Text(notice)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier("feature-others-notice")
            }
        }
    }

    @ViewBuilder
    private func everywhere(_ screen: PhotosFeatureScreen) -> some View {
        if let everywhere = screen.everywhere {
            Section {
                Button(everywhere.label, role: .destructive) { asking = true }
                    .accessibilityIdentifier("feature-everywhere")
            }
        }
    }

    /// A device and its switch. ⚠ The switch shows what the state says and never its own press:
    /// it moves when the view model moves it, and a device with no press has none to make.
    private func device(_ device: PhotosFeatureScreen.Device) -> some View {
        Toggle(isOn: Binding(get: { device.on }, set: { _ in if let press = device.press { self.press(press) } })) {
            VStack(alignment: .leading, spacing: 3) {
                Text(device.name)
                Text(device.status).font(.subheadline).foregroundStyle(.secondary)
            }
        }
        .disabled(device.press == nil)
        .accessibilityIdentifier("feature-device-\(device.key)")
    }

    private func press(_ press: PhotosFeatureScreenPress) {
        model.send { PhotosModelsCopyKt.photosFeaturePress(viewModel: $0, capability: capability, press: press) }
    }
}
