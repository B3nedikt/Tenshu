import CoreImage
import SwiftUI
import TenshuSdk

// The photo editor (`photos-editing.md` ED-1, ED-2, ED-4): Transform, Adjust, Markup, Filter and Save
// as copy.
//
// ⚠⚠ Every decision is the shared `PhotoEditorViewModel`'s — the recipe, the aspect lock, the crop
// handles (`dragCrop`, `cropHandleAt`), when ✕ asks (ED-D6), what a save writes (ED-D1). This file
// draws the state and reports gestures, and supplies the Core Image renderer (`EditRendering`).

@MainActor
final class EditorModel: ObservableObject {
    let viewModel: PhotoEditorViewModel
    @Published private(set) var state: PhotoEditorUiState
    /// The upright original at preview size, and the same picture as a `UIImage` for hold-to-compare.
    @Published private(set) var original: UIImage?
    /// The current edit drawn as a whole frame (the crop is an overlay, not applied).
    @Published private(set) var preview: UIImage?
    private var source: CIImage?
    private var renderTask: Task<Void, Never>?
    private var decodeAttempted = false

    init(photo: PhotosPhoto) {
        // ⚠ SKIE bridges the Kotlin `NSData` parameters to `Foundation.Data` here.
        let render: (Foundation.Data, EditRecipe) -> Foundation.Data? = { data, recipe in
            EditRendering.render(original: data, recipe: recipe)
        }
        let editor = PhotosSurface.shared.editor(photo: photo, render: render)
        viewModel = editor
        state = editor.state.value
    }

    func observe() async {
        for await value in viewModel.state {
            // ⚠ The tab too: the Crop tab draws the whole frame, the others the cropped result.
            // ⚠ Not the markup: ink is drawn over the frame by the canvas, live, and a Core Image
            // redraw per stroke point would lag the finger.
            let old = state.recipe
            let editChanged = !value.recipe.transform.isEqual(old.transform) || !value.recipe.adjust.isEqual(old.adjust)
                || value.recipe.filter != old.filter || value.tab != state.tab
            // ⚠ Once: a failed decode must not be retried on every later emission.
            let becameReady = value.source == .ready && !decodeAttempted
            state = value
            if becameReady { decodeAttempted = true; await decodeSource() }
            if editChanged { redraw() }
        }
    }

    private func decodeSource() async {
        guard let data = PhotosSurface.shared.editorOriginal(editor: viewModel) as Foundation.Data? else {
            viewModel.onSourceUndecodable(); return
        }
        let decoded = await Task.detached(priority: .userInitiated) { () -> (CIImage, UIImage?)? in
            // ⚠ The preview's size, not the save's: the save decodes the original again in full.
            guard let image = EditRendering.upright(data, maxEdge: 1600) else { return nil }
            return (image, EditRendering.uiImage(image))
        }.value
        // ⚠ Said, not a spinner for ever: the bytes are here and this device cannot draw them.
        guard let (image, ui) = decoded else { viewModel.onSourceUndecodable(); return }
        source = image
        original = ui
        viewModel.onSourceDecoded(width: Int32(image.extent.width), height: Int32(image.extent.height))
        redraw()
    }

    /// ⚠ Latest wins: a dial dragged fast asks for many frames, and only the last one matters.
    private func redraw() {
        guard let source else { return }
        let recipe = state.recipe
        let cropping = state.tab == .transform
        renderTask?.cancel()
        renderTask = Task {
            let image = await Task.detached(priority: .userInitiated) {
                EditRendering.preview(source, recipe: recipe, cropping: cropping)
            }.value
            if !Task.isCancelled, let image { preview = image }
        }
    }

    var sourceSize: CGSize { source?.extent.size ?? .zero }
}

struct PhotoEditorScreen: View {
    @StateObject private var model: EditorModel
    let onClose: () -> Void
    /// What a save wrote: the photo itself with its edit (`.edit`, ED-3) or a new copy (`.theCopy`).
    let onSaved: (PhotosPhoto, SavedAs) -> Void

    init(photo: PhotosPhoto, onClose: @escaping () -> Void, onSaved: @escaping (PhotosPhoto, SavedAs) -> Void) {
        _model = StateObject(wrappedValue: EditorModel(photo: photo))
        self.onClose = onClose
        self.onSaved = onSaved
    }

    @State private var labelText = ""

    private var vm: PhotoEditorViewModel { model.viewModel }
    private var state: PhotoEditorUiState { model.state }
    private var ready: Bool { state.source == .ready && model.preview != nil && !state.saving }

    var body: some View {
        VStack(spacing: 0) {
            topBar
            canvasArea.frame(maxWidth: .infinity, maxHeight: .infinity)
            strip
            switch state.tab {
            case .adjust: adjustPanel.frame(height: 96)
            case .filter: filterPanel.frame(height: 96)
            case .markup:
                markupPanel.frame(height: 96)
                markupToolRow
            default:
                toolPanel.frame(height: 96)
                toolRow
            }
            // ⚠ Drawn only for more than one tab — never a lone tab with nothing to switch to.
            if state.tabs.count > 1 { tabRow }
        }
        .background(Color.black.ignoresSafeArea())
        .preferredColorScheme(.dark)
        .task { await model.observe() }
        .onChange(of: state.saved?.path) { _ in reportSaved() }
        // ⚠ An alert, not a confirmation sheet: the sheet hides its cancel button on recent iOS, and
        // "Keep Editing" is the choice that must be impossible to miss (ED-D6).
        .alert(
            "Discard your edits?",
            isPresented: Binding(get: { state.confirmingDiscard }, set: { if !$0 { vm.keepEditing() } })
        ) {
            Button("Discard", role: .destructive) { vm.confirmDiscard(); onClose() }
            Button("Keep Editing", role: .cancel) { vm.keepEditing() }
        } message: {
            Text("Your changes to this photo haven’t been saved.")
        }
        .alert(
            state.labelDraft?.id == nil ? "Add Text" : "Edit Text",
            isPresented: Binding(get: { state.labelDraft != nil }, set: { if !$0 && state.labelDraft != nil { vm.cancelLabel() } })
        ) {
            TextField("Text", text: $labelText)
                .accessibilityIdentifier("editorLabelField")
            Button("Done") { vm.commitLabel(text: labelText) }
            if state.labelDraft?.id != nil {
                Button("Delete", role: .destructive) { vm.deleteLabel() }
            }
            Button("Cancel", role: .cancel) { vm.cancelLabel() }
        }
        // ⚠ Seeded from the label opened, so editing one starts from ITS text.
        .onChange(of: state.labelDraft != nil) { open in
            if open { labelText = state.labelDraft?.text ?? "" }
        }
        .alert(
            "Couldn’t Save",
            isPresented: Binding(get: { state.saveFailed }, set: { if !$0 { vm.dismissSaveFailure() } })
        ) {
            Button("OK") {}
        } message: {
            Text("Tenshu couldn’t save your edit. Your original is unchanged. Try again in a moment.")
        }
    }

    /// The save finished: hand the shell what was written and how (ED-3 `.edit`, ED-1 `.theCopy`).
    private func reportSaved() {
        guard let saved = state.saved else { return }
        // ⚠ `theCopy`: K/N renames `SavedAs.COPY`, because Obj-C reserves `copy`.
        let kind: SavedAs = state.savedAs ?? SavedAs.theCopy
        onSaved(saved, kind)
    }

    private var topBar: some View {
        HStack {
            Button { if vm.requestClose() { onClose() } } label: {
                Image(systemName: "xmark").font(.title3).foregroundStyle(.white).padding(12)
            }
            .disabled(state.saving)
            .accessibilityLabel("Close Editor")
            Spacer()
            if state.saving {
                ProgressView().tint(.white).padding(.trailing, 16)
            } else {
                // ⚠⚠ *Save* is the non-destructive edit (ED-3, ED-D11) — the original's bytes and id
                // never move — offered once something changed. *Save as Copy* (a new file) is in ⋯.
                Button("Save") { vm.save() }
                    .accessibilityIdentifier("editorSave")
                    .buttonStyle(.borderedProminent)
                    .disabled(!state.canSave)
                Menu {
                    Button("Save as Copy") { vm.saveAsCopy() }
                        .accessibilityIdentifier("editorSaveCopy")
                        .disabled(!state.canSaveAsCopy)
                } label: {
                    Image(systemName: "ellipsis.circle").font(.title3).foregroundStyle(.white).padding(12)
                }
                .accessibilityIdentifier("editorMore")
                .accessibilityLabel("More")
            }
        }
        .overlay(alignment: .bottom) {
            if state.storedEditUnreadable {
                // ED-D9: the edit came from a newer version — said; the original is what is open.
                Text("This photo was edited in a newer version of Photos. You’re editing the original; saving replaces that edit.")
                    .font(.footnote)
                    .foregroundStyle(.white.opacity(0.85))
                    .padding(.horizontal, 16)
                    .offset(y: 28)
            }
        }
    }

    @ViewBuilder
    private var canvasArea: some View {
        if state.source == .unreadable {
            Text("This photo can't be edited on this device.")
                .foregroundStyle(.white.opacity(0.85))
                .padding(32)
        } else if state.source == .editUnknown {
            // ED-3: never opened as "no edit" — a save would replace edits the user never saw.
            Text("Your edits to this photo couldn’t be loaded, so it can’t be edited right now. Try again in a moment.")
                .foregroundStyle(.white.opacity(0.85))
                .multilineTextAlignment(.center)
                .padding(32)
        } else if state.source == .unavailable {
            Text("This photo hasn't been downloaded to this device, so it can't be edited here yet.")
                .foregroundStyle(.white.opacity(0.85))
                .multilineTextAlignment(.center)
                .padding(32)
        } else if let preview = model.preview {
            CropCanvas(
                preview: preview,
                original: model.original,
                sourceSize: model.sourceSize,
                state: state,
                viewModel: vm,
                cropping: state.tab == .transform,
                marking: state.tab == .markup && state.labelDraft == nil
            )
            .padding(8)
            .accessibilityElement(children: .ignore)
            .accessibilityIdentifier("editorCanvas")
            .accessibilityLabel("Photo being edited")
        } else {
            ProgressView().tint(.white)
        }
    }

    private var strip: some View {
        HStack(spacing: 4) {
            Button { vm.rotate() } label: { Image(systemName: "rotate.left").padding(10) }
                .accessibilityLabel("Rotate")
            Button { vm.mirror() } label: { Image(systemName: "arrow.left.and.right.righttriangle.left.righttriangle.right").padding(10) }
                .accessibilityLabel("Mirror")
            Spacer()
            if state.dirty { Button("Reset") { vm.reset() } }
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 8)
        .disabled(!ready)
    }

    @ViewBuilder
    private var toolPanel: some View {
        switch state.tool {
        case .crop:
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(AspectPreset.allCases, id: \.self) { preset in
                        let selected = state.edit.aspect == preset
                        Button(preset.label) { vm.setAspect(preset: preset) }
                            .font(.subheadline)
                            .padding(.horizontal, 12).padding(.vertical, 6)
                            .background(Capsule().fill(selected ? Color.white : Color.white.opacity(0.12)))
                            .foregroundStyle(selected ? Color.black : Color.white)
                            .disabled(!ready || (!state.sourceKnown && preset != .freeform))
                    }
                }
                .padding(.horizontal, 16)
            }
        case .straighten:
            AngleDialView(
                label: "Straighten",
                value: state.edit.viewStraightenDegrees,
                range: TransformEdit.companion.MAX_STRAIGHTEN_DEGREES,
                format: { String(format: "%.1f°", abs($0) < 0.05 ? 0 : $0) },
                compact: false
            ) { vm.setStraighten(degrees: $0) }
            .disabled(!ready)
        case .perspective:
            let pair = state.edit.viewPerspective
            let x = (pair.first as? KotlinFloat)?.floatValue ?? 0
            let y = (pair.second as? KotlinFloat)?.floatValue ?? 0
            VStack(spacing: 4) {
                AngleDialView(label: "Vertical", value: y * 100, range: 100, format: { String(format: "%.0f", abs($0) < 0.5 ? 0 : $0) }, compact: true) {
                    vm.setPerspective(x: x, y: $0 / 100)
                }
                AngleDialView(label: "Horizontal", value: x * 100, range: 100, format: { String(format: "%.0f", abs($0) < 0.5 ? 0 : $0) }, compact: true) {
                    vm.setPerspective(x: $0 / 100, y: y)
                }
            }
            .disabled(!ready)
        default:
            EmptyView()
        }
    }

    private var toolRow: some View {
        HStack {
            tool("Crop", "crop", .crop)
            tool("Straighten", "level", .straighten)
            tool("Perspective", "perspective", .perspective)
        }
        .padding(.vertical, 8)
        .disabled(!ready)
    }

    /// Crop · Adjust · Filters — Google's tab row, at the bottom.
    private var tabRow: some View {
        HStack {
            ForEach(state.tabs, id: \.self) { tab in
                let selected = state.tab == tab
                Button(tab.label) { vm.selectTab(tab: tab) }
                    .font(.subheadline.weight(selected ? .semibold : .regular))
                    .foregroundStyle(selected ? Color.accentColor : Color.white)
                    .frame(maxWidth: .infinity)
                    .accessibilityIdentifier("editorTab-\(String(describing: tab))")
                    .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
        .padding(.bottom, 6)
        .disabled(!ready)
    }

    /// The Adjust tab: the eleven sliders as a row; one picked opens the dial with Reset and Done.
    @ViewBuilder
    private var adjustPanel: some View {
        if let open = state.adjustTool {
            let value = Float(state.recipe.adjust.get(tool: open))
            VStack(spacing: 4) {
                AngleDialView(
                    label: open.label,
                    value: value,
                    range: Float(AdjustEdit.companion.MAX),
                    format: { signed(Int(($0).rounded())) },
                    compact: true
                ) { vm.setAdjust(tool: open, value: $0) }
                HStack {
                    Button("Reset") { vm.resetAdjust(tool: open) }
                        .disabled(value == 0)
                    Spacer()
                    Button("Done") { vm.selectAdjustTool(tool: nil) }
                        .accessibilityIdentifier("editorAdjustDone")
                }
                .foregroundStyle(.white)
                .padding(.horizontal, 16)
            }
            .disabled(!ready)
        } else {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(AdjustTool.allCases, id: \.self) { tool in
                        let value = Int(state.recipe.adjust.get(tool: tool))
                        chip(value == 0 ? tool.label : "\(tool.label)  \(signed(value))", selected: value != 0) {
                            vm.selectAdjustTool(tool: tool)
                        }
                        .accessibilityIdentifier("editorAdjust-\(String(describing: tool))")
                    }
                }
                .padding(.horizontal, 16)
            }
            .disabled(!ready)
        }
    }

    /// The Filter tab: Original and our own looks (ED-D5), and the chosen look's intensity.
    private var filterPanel: some View {
        VStack(spacing: 6) {
            if let filter = state.recipe.filter {
                AngleDialView(
                    label: "Intensity",
                    value: Float(filter.intensity),
                    range: Float(FilterEdit.companion.FULL),
                    format: { String(Int(($0).rounded())) },
                    compact: true,
                    low: 0
                ) { vm.setFilterIntensity(value: $0) }
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    chip("Original", selected: state.recipe.filter == nil) { vm.selectLook(look: nil) }
                        .accessibilityIdentifier("editorLook-original")
                    ForEach(FilterLook.allCases, id: \.self) { look in
                        chip(look.label, selected: state.recipe.filter?.look == look) { vm.selectLook(look: look) }
                            .accessibilityIdentifier("editorLook-\(look.id)")
                    }
                }
                .padding(.horizontal, 16)
            }
        }
        .disabled(!ready)
    }

    /// The Markup tab (ED-4): the tool's colours and widths (Redact has neither — always black), its
    /// hint, and markup's undo and redo.
    private var markupPanel: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let brush = state.brush {
                HStack(spacing: 8) {
                    ForEach(InkColor.allCases, id: \.self) { color in
                        let on = brush.color == color
                        Button { vm.setInkColor(color: color) } label: {
                            // ⚠ A faint ring, or the black swatch vanishes into the black panel.
                            Circle().fill(swatch(color)).frame(width: 22, height: 22)
                                .overlay(Circle().stroke(Color.white.opacity(0.35), lineWidth: 1))
                                .padding(3)
                                .overlay(Circle().stroke(on ? Color.white : Color.clear, lineWidth: 2))
                        }
                        .accessibilityLabel(color.label)
                        .accessibilityIdentifier("editorInk-\(color.id)")
                        .accessibilityAddTraits(on ? .isSelected : [])
                    }
                    Spacer()
                    ForEach(InkWidth.allCases, id: \.self) { width in
                        let on = brush.width == width
                        let dot: CGFloat = width == .thin ? 6 : (width == .medium ? 11 : 17)
                        Button { vm.setInkWidth(width: width) } label: {
                            Circle().fill(Color.white.opacity(on ? 1 : 0.4)).frame(width: dot, height: dot).frame(width: 28, height: 28)
                        }
                        .accessibilityLabel(width.label)
                        .accessibilityAddTraits(on ? .isSelected : [])
                    }
                }
            }
            HStack {
                Text(markupHint).font(.caption).foregroundStyle(.white.opacity(0.75))
                Spacer()
                Button { vm.undo() } label: { Image(systemName: "arrow.uturn.backward").padding(8) }
                    .disabled(!state.canUndo)
                    .accessibilityLabel("Undo")
                Button { vm.redo() } label: { Image(systemName: "arrow.uturn.forward").padding(8) }
                    .disabled(!state.canRedo)
                    .accessibilityLabel("Redo")
            }
            .foregroundStyle(.white)
        }
        .padding(.horizontal, 16)
        .disabled(!ready)
    }

    private var markupHint: String {
        switch state.markupTool {
        case .redact: return "Drag a box over what to hide. It’s blacked out in the saved copy."
        case .text: return "Tap to add text. Tap a label to edit it, drag to move it."
        default: return "Draw on the photo."
        }
    }

    private func swatch(_ color: InkColor) -> Color {
        let v = UInt32(bitPattern: color.argb)
        return Color(.sRGB, red: Double((v >> 16) & 0xFF) / 255, green: Double((v >> 8) & 0xFF) / 255, blue: Double(v & 0xFF) / 255)
    }

    /// Pen · Highlighter · Redact · Text — §1.2's order.
    private var markupToolRow: some View {
        HStack {
            ForEach(MarkupTool.allCases, id: \.self) { tool in
                let selected = state.markupTool == tool
                let symbol: String = {
                    switch tool {
                    case .pen: return "pencil.tip"
                    case .highlighter: return "highlighter"
                    case .redact: return "eye.slash"
                    default: return "textformat"
                    }
                }()
                Button { vm.selectMarkupTool(tool: tool) } label: {
                    VStack(spacing: 2) {
                        Image(systemName: symbol)
                        Text(tool.label).font(.caption)
                    }
                    .frame(maxWidth: .infinity)
                    .foregroundStyle(selected ? Color.accentColor : Color.white)
                }
                .accessibilityLabel(tool.label)
                .accessibilityIdentifier("editorMarkup-\(String(describing: tool))")
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
        .padding(.vertical, 8)
        .disabled(!ready)
    }

    private func chip(_ title: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(title, action: action)
            .font(.subheadline)
            .padding(.horizontal, 12).padding(.vertical, 6)
            .background(Capsule().fill(selected ? Color.white : Color.white.opacity(0.12)))
            .foregroundStyle(selected ? Color.black : Color.white)
            .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private func signed(_ v: Int) -> String { v > 0 ? "+\(v)" : "\(v)" }

    private func tool(_ label: String, _ symbol: String, _ tool: TransformTool) -> some View {
        let selected = state.tool == tool
        return Button { vm.selectTool(tool: tool) } label: {
            VStack(spacing: 2) {
                Image(systemName: symbol)
                Text(label).font(.caption)
            }
            .frame(maxWidth: .infinity)
            .foregroundStyle(selected ? Color.accentColor : Color.white)
        }
        .accessibilityLabel(label)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// The picture with the crop box over it, and the gestures: drag a handle, drag inside to move, hold
/// still to compare with the original.
private struct CropCanvas: View {
    let preview: UIImage
    let original: UIImage?
    let sourceSize: CGSize
    let state: PhotoEditorUiState
    let viewModel: PhotoEditorViewModel
    /// The Crop tab: the box and its handles. Elsewhere the picture is the cropped result and a
    /// press can only compare.
    let cropping: Bool
    /// The Markup tab with no label open: a press draws (or picks a label), never compares.
    let marking: Bool

    /// The start of the markup drag in progress, or nil. ⚠ Compared with each event's start rather
    /// than trusted as a flag: SwiftUI never calls `onEnded` for a CANCELLED gesture (an edge swipe,
    /// a call), and a stale flag would join the next stroke onto the last one.
    @State private var markStart: CGPoint?
    @State private var drag: (start: NormRect, handle: CropHandle?)?
    @State private var moved = false
    @State private var hold: Task<Void, Never>?

    var body: some View {
        GeometryReader { geo in
            let frame = fit(preview.size, in: geo.size, pad: 20)
            let crop = state.edit.viewCrop
            let cropRect = CGRect(
                x: frame.minX + CGFloat(crop.left) * frame.width,
                y: frame.minY + CGFloat(crop.top) * frame.height,
                width: CGFloat(crop.width) * frame.width,
                height: CGFloat(crop.height) * frame.height
            )
            ZStack(alignment: .topLeading) {
                if state.comparing, let original {
                    let o = fit(original.size, in: geo.size, pad: 20)
                    Image(uiImage: original).resizable().frame(width: o.width, height: o.height).offset(x: o.minX, y: o.minY)
                } else {
                    Image(uiImage: preview).resizable().frame(width: frame.width, height: frame.height).offset(x: frame.minX, y: frame.minY)
                    markupLayer(frame: frame)
                    if cropping { overlay(frame: frame, crop: cropRect, locked: state.edit.aspect != .freeform) }
                }
            }
            .frame(width: geo.size.width, height: geo.size.height, alignment: .topLeading)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { g in changed(g, frame: frame) }
                    .onEnded { _ in ended() }
            )
        }
    }

    /// The markup as the shared scene for the plan the preview shows — the save draws the same ops at
    /// full size (`MarkupRender.scene`), over the coloured picture.
    private func markupLayer(frame: CGRect) -> some View {
        Canvas { ctx, _ in
            let w = Int32(sourceSize.width.rounded())
            let h = Int32(sourceSize.height.rounded())
            guard w > 0, h > 0, !state.recipe.markup.isEmpty else { return }
            let plan = state.recipe.transform.plan(sourceWidth: w, sourceHeight: h, includeCrop: !cropping)
            let ops = MarkupRender.shared.scene(markup: state.recipe.markup, plan: plan, sourceWidth: w, sourceHeight: h)
            ctx.withCGContext { cg in
                cg.translateBy(x: frame.minX, y: frame.minY)
                cg.scaleBy(x: frame.width / CGFloat(plan.outputWidth), y: frame.height / CGFloat(plan.outputHeight))
                cg.clip(to: CGRect(x: 0, y: 0, width: CGFloat(plan.outputWidth), height: CGFloat(plan.outputHeight)))
                MarkupDrawing.draw(ops, in: cg)
            }
        }
        .allowsHitTesting(false)
    }

    private func changed(_ g: DragGesture.Value, frame: CGRect) {
        if marking {
            // ⚠ Every move is ink (or a label drag) from the first one: no hold-to-compare here.
            func u(_ p: CGPoint) -> Float { Float(min(1, max(0, (p.x - frame.minX) / frame.width))) }
            func v(_ p: CGPoint) -> Float { Float(min(1, max(0, (p.y - frame.minY) / frame.height))) }
            if markStart != g.startLocation {
                // A new drag — or one that began after a cancelled one, which never ended.
                if markStart != nil { viewModel.markupUp() }
                markStart = nil
                guard frame.contains(g.startLocation) else { return }
                markStart = g.startLocation
                viewModel.markupDown(u: u(g.startLocation), v: v(g.startLocation))
            }
            viewModel.markupMove(u: u(g.location), v: v(g.location))
            return
        }
        if drag == nil {
            let start = state.edit.viewCrop
            let handle = !cropping ? nil : CropHandlesKt.cropHandleAt(
                crop: start,
                frameWidth: Float(frame.width),
                frameHeight: Float(frame.height),
                x: Float(g.startLocation.x - frame.minX),
                y: Float(g.startLocation.y - frame.minY),
                slop: 28
            )
            drag = (start, handle)
            moved = false
            // Held still for 0.3 s: show the original (Google's "hold to compare"). ⚠ Only off the
            // box's edges and corners — press, pause, drag is how a corner is grabbed. The inside
            // (MOVE) still compares: on an uncropped photo it is the whole picture.
            if handle == nil || handle == .move { hold = Task { @MainActor in
                try? await Task.sleep(nanoseconds: 300_000_000)
                if !Task.isCancelled && !moved { viewModel.setComparing(comparing: true) }
            } }
        }
        let t = g.translation
        if !moved && hypot(t.width, t.height) > 6 {
            moved = true
            hold?.cancel()
        }
        guard moved, !state.comparing, let current = drag, let handle = current.handle else { return }
        viewModel.dragCrop(
            start: current.start, handle: handle,
            dx: Float(t.width / frame.width), dy: Float(t.height / frame.height)
        )
    }

    private func ended() {
        if markStart != nil { viewModel.markupUp(); markStart = nil }
        hold?.cancel()
        if state.comparing { viewModel.setComparing(comparing: false) }
        drag = nil
        moved = false
    }

    private func overlay(frame: CGRect, crop: CGRect, locked: Bool) -> some View {
        Canvas { ctx, _ in
            var dim = Path(frame)
            dim.addRect(crop)
            ctx.fill(dim, with: .color(.black.opacity(0.55)), style: FillStyle(eoFill: true))
            for i in 1...2 {
                let x = crop.minX + crop.width * CGFloat(i) / 3
                let y = crop.minY + crop.height * CGFloat(i) / 3
                var p = Path()
                p.move(to: CGPoint(x: x, y: crop.minY)); p.addLine(to: CGPoint(x: x, y: crop.maxY))
                p.move(to: CGPoint(x: crop.minX, y: y)); p.addLine(to: CGPoint(x: crop.maxX, y: y))
                ctx.stroke(p, with: .color(.white.opacity(0.35)), lineWidth: 0.5)
            }
            ctx.stroke(Path(crop), with: .color(.white), lineWidth: 1)
            let len = min(24, min(crop.width, crop.height) / 3)
            var h = Path()
            for (p, dx, dy) in [
                (CGPoint(x: crop.minX, y: crop.minY), 1.0, 1.0), (CGPoint(x: crop.maxX, y: crop.minY), -1.0, 1.0),
                (CGPoint(x: crop.maxX, y: crop.maxY), -1.0, -1.0), (CGPoint(x: crop.minX, y: crop.maxY), 1.0, -1.0),
            ] {
                h.move(to: CGPoint(x: p.x + dx * len, y: p.y)); h.addLine(to: p); h.addLine(to: CGPoint(x: p.x, y: p.y + dy * len))
            }
            if !locked {
                let c = CGPoint(x: crop.midX, y: crop.midY)
                h.move(to: CGPoint(x: c.x - len / 2, y: crop.minY)); h.addLine(to: CGPoint(x: c.x + len / 2, y: crop.minY))
                h.move(to: CGPoint(x: c.x - len / 2, y: crop.maxY)); h.addLine(to: CGPoint(x: c.x + len / 2, y: crop.maxY))
                h.move(to: CGPoint(x: crop.minX, y: c.y - len / 2)); h.addLine(to: CGPoint(x: crop.minX, y: c.y + len / 2))
                h.move(to: CGPoint(x: crop.maxX, y: c.y - len / 2)); h.addLine(to: CGPoint(x: crop.maxX, y: c.y + len / 2))
            }
            ctx.stroke(h, with: .color(.white), lineWidth: 4)
        }
        .allowsHitTesting(false)
    }

    private func fit(_ size: CGSize, in box: CGSize, pad: CGFloat) -> CGRect {
        let aw = max(1, box.width - 2 * pad)
        let ah = max(1, box.height - 2 * pad)
        let s = min(aw / max(size.width, 1), ah / max(size.height, 1))
        let w = size.width * s
        let h = size.height * s
        return CGRect(x: (box.width - w) / 2, y: (box.height - h) / 2, width: w, height: h)
    }
}

/// Google's fine-angle dial: a ruler that slides under a fixed centre mark. Double-tap resets.
struct AngleDialView: View {
    let label: String
    let value: Float
    let range: Float
    let format: (Float) -> String
    let compact: Bool
    /// The low end: `-range` (nil) for a centred dial, `0` for an intensity.
    var low: Float? = nil
    let onChange: (Float) -> Void
    @State private var dragBase: Float?

    private var lowEnd: Float { low ?? -range }

    var body: some View {
        let pxPerUnit: Float = compact ? 2 : 4
        VStack(spacing: 2) {
            Text(compact ? "\(label)  \(format(value))" : format(value))
                .font(.subheadline).foregroundStyle(.white)
            Canvas { ctx, size in
                let mid = size.width / 2
                let step = range > 50 ? 5 : 1
                for unit in stride(from: Int(lowEnd), through: Int(range), by: step) {
                    let x = mid + CGFloat((Float(unit) - value) * pxPerUnit)
                    guard x >= 0 && x <= size.width else { continue }
                    let major = unit % (step * 5) == 0
                    let h = size.height * (major ? 0.7 : 0.4)
                    var p = Path()
                    p.move(to: CGPoint(x: x, y: (size.height - h) / 2)); p.addLine(to: CGPoint(x: x, y: (size.height + h) / 2))
                    ctx.stroke(p, with: .color(.white.opacity(major ? 0.9 : 0.5)), lineWidth: major ? 1.5 : 1)
                }
                var c = Path()
                c.move(to: CGPoint(x: mid, y: 0)); c.addLine(to: CGPoint(x: mid, y: size.height))
                ctx.stroke(c, with: .color(.accentColor), lineWidth: 2)
            }
            .frame(height: compact ? 22 : 36)
            .contentShape(Rectangle())
            .gesture(
                DragGesture()
                    .onChanged { g in
                        let base = dragBase ?? value
                        if dragBase == nil { dragBase = value }
                        onChange(min(range, max(lowEnd, base - Float(g.translation.width) / pxPerUnit)))
                    }
                    .onEnded { _ in dragBase = nil }
            )
            .onTapGesture(count: 2) { onChange(lowEnd < 0 ? 0 : range) }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
        .accessibilityValue(format(value))
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: onChange(min(range, value + 1))
            case .decrement: onChange(max(lowEnd, value - 1))
            @unknown default: break
            }
        }
    }
}
