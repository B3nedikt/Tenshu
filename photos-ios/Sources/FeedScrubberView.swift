import SwiftUI
import TenshuSdk

// The feed's fast-scroll thumb (photos-ios.md §8.8) — Compose's `FeedScrubber` on iOS.
//
// ⚠⚠ Swift decides nothing (PIO-D2). Where the thumb rests is `FeedLayout.progressAt`, where a seek
// lands is `FeedLayout.lineAtProgress`, the bubble is `labelAt` of the top line and the pills are
// `scrubberPills` — the placement Compose draws. This file measures the track and moves a capsule.
//
// ⚠ In PHOTOGRAPHS, not lines, on both ends: the pills are placed by photographs, and a thumb placed
// in lines points at the wrong year wherever photographs-per-day changes.

/// What the thumb shows, written by the grid on every scroll frame. ⚠ Its own object so that only the
/// scrubber re-renders at scroll rate — publishing this through `LibraryModel` would rebuild the
/// screen, grid representable included, sixty times a second.
@MainActor
final class ScrubberState: ObservableObject {
    /// Where the list rests on the track, 0 (top) to 1 (bottom) — `FeedLayout.progressAt`.
    @Published private(set) var progress: CGFloat = 0
    /// Whether the list is taller than its viewport; a list that fits has nothing to scrub.
    @Published private(set) var scrollable = false
    /// Shown while the list moves or the thumb is held, then hidden after a beat of stillness.
    @Published private(set) var awake = false
    /// The date of the row at the top, while the thumb is held — `nil` over an unloaded row.
    @Published private(set) var bubble: String?
    /// One mark per year in the whole library (`PhotosFeedViewModel.yearMarks`), newest first.
    @Published var yearMarks: [FeedYearMark] = []
    /// While held, the grid's previews wait (`PreviewGate`) and the thumb follows the finger.
    @Published private(set) var dragging = false
    /// The finger's own position while held — the thumb follows it, not the grid, which reports a
    /// frame late and would make the thumb chase its own gesture.
    @Published private(set) var dragProgress: CGFloat = 0

    let previews = PreviewGate()

    /// Set by the grid: seek to a fraction of the track.
    var seek: ((CGFloat) -> Void)?
    /// Set by the grid: the thumb was let go — the grid reports what is on screen again (the motion
    /// tiles rest while a scrub runs).
    var released: (() -> Void)?
    /// Set by the grid: the user took the list back — end a re-anchor, drop a kept identity — and
    /// report the viewport now, so the bubble names the top row before the finger moves.
    var grabbed: (() -> Void)?

    private var fade: Task<Void, Never>?

    /// ⚠ Measured against Google Photos (`FeedScrubber.FADE_AFTER_MS`): the thumb is gone ~1 s after
    /// the list stops.
    private static let fadeAfterNanos: UInt64 = 1_200_000_000

    func viewport(progress: CGFloat, scrollable: Bool, bubble: @autoclosure () -> String?) {
        if abs(self.progress - progress) > 0.0001 { self.progress = progress }
        if self.scrollable != scrollable { self.scrollable = scrollable }
        // ⚠⚠ The row the grid IS SHOWING, not the row the gesture aimed at (`FeedScrubber.dateAt`):
        // the count moves under a held finger as pages land, and a label re-derived from the aim
        // names a photograph no visible row has.
        if dragging {
            let label = bubble()
            if self.bubble != label { self.bubble = label }
        }
    }

    /// The user is moving the list — wake the thumb, and fade it once they stop.
    func moved() {
        guard scrollable else { return }
        if !awake { awake = true }
        scheduleFade()
    }

    func beginDrag() {
        // ⚠ From where the thumb RESTS, so grabbing it never makes it flinch.
        dragProgress = progress
        dragging = true
        previews.paused = true
        fade?.cancel()
        awake = true
        // ⚠ After `dragging`, which is what lets the report fill the bubble: a finger that presses
        // and holds sends no movement, so no seek would report and the bubble would stay empty.
        grabbed?()
    }

    /// ⚠ A seek, not an animation: animating each step makes the list chase the thumb.
    func drag(to next: CGFloat) {
        let clamped = min(max(next, 0), 1)
        guard dragging, clamped != dragProgress else { return }
        dragProgress = clamped
        seek?(clamped)
    }

    /// ⚠ Safe to call twice — a gesture that ends and is then torn down reaches it by both routes.
    func endDrag() {
        guard dragging else { return }
        dragging = false
        bubble = nil
        previews.paused = false
        scheduleFade()
        released?()
    }

    private func scheduleFade() {
        fade?.cancel()
        guard !dragging else { return }
        fade = Task { [weak self] in
            try? await Task.sleep(nanoseconds: Self.fadeAfterNanos)
            guard !Task.isCancelled, let self, !self.dragging else { return }
            self.awake = false
        }
    }
}

/// Whether the grid's tiles may fetch and decode previews. ⚠ Paused while the thumb is held: a scrub
/// sweeps thousands of rows past in seconds, and decoding each would queue work for tiles gone a
/// frame later (Compose's `previewsEnabled = !scrubbing`). A cached tile still shows.
/// ⚠ Its own object for the reason `ScrubberState` is: the tiles must not re-render at scroll rate.
@MainActor
final class PreviewGate: ObservableObject {
    @Published var paused = false
}

struct FeedScrubberView: View {
    @ObservedObject var state: ScrubberState

    /// Where the thumb rested when the finger went down.
    @State private var dragStart: CGFloat?
    /// Whether a finger is on the thumb. ⚠⚠ The ONE signal of a cancelled touch: when iOS takes the
    /// touch away — the app backgrounds, Control Center opens, the view goes — `DragGesture` never
    /// calls `onEnded`, and without this the scrub stays held with every preview paused until the
    /// next scrub (Compose has `onDragCancel`). SwiftUI resets a `@GestureState` on
    /// cancellation as well as on the end.
    @GestureState private var touching = false

    private static let thumbHeight: CGFloat = 56
    private static let thumbWidth: CGFloat = 40
    private static let pillHeight: CGFloat = 20
    /// Clear of the thumb, which is 40 pt wide plus its 6 pt inset.
    private static let pillInset: CGFloat = 52

    var body: some View {
        GeometryReader { geometry in
            let track = geometry.size.height
            let travel = max(track - Self.thumbHeight, 0)
            let progress = state.dragging ? state.dragProgress : state.progress
            ZStack(alignment: .topTrailing) {
                // ⚠ Only while held: a permanent column of years sits on the photographs for a
                // control nobody is touching.
                if state.dragging {
                    ForEach(pills(track: track, progress: progress), id: \.year) { pill in
                        Text(String(pill.year))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 1)
                            .frame(height: Self.pillHeight)
                            .background(Capsule().fill(.regularMaterial))
                            .padding(.trailing, Self.pillInset)
                            .offset(y: CGFloat(pill.top))
                    }
                }

                HStack(spacing: 4) {
                    if let bubble = state.bubble, state.dragging {
                        // Left of the thumb and centred on it, so the dragging finger never covers it.
                        Text(bubble)
                            .font(.subheadline.weight(.medium))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(Capsule().fill(.thickMaterial).shadow(color: .black.opacity(0.2), radius: 3, y: 1))
                    }
                    thumb(travel: travel)
                }
                .offset(y: progress * travel)
                .opacity(state.awake ? 1 : 0)
                .animation(.easeInOut(duration: 0.2), value: state.awake)
                // ⚠ A faded thumb still hit-tests; it must not swallow a scroll that starts under it.
                .allowsHitTesting(state.awake && state.scrollable)
            }
            .frame(width: geometry.size.width, height: track, alignment: .topTrailing)
            .coordinateSpace(name: Self.space)
        }
        .padding(.vertical, 8)
        .onChange(of: touching) { held in if !held { finishDrag() } }
        .onDisappear { finishDrag() }
    }

    private func finishDrag() {
        dragStart = nil
        state.endDrag()
    }

    private func thumb(travel: CGFloat) -> some View {
        // ⚠ Two chevrons, as the reference draws: the thumb must read as "drag me up or down", not as
        // a position marker. ⚠ Not overlapped — at this size a negative spacing fuses them into a
        // diamond (seen on the simulator).
        VStack(spacing: 2) {
            Image(systemName: "chevron.up")
            Image(systemName: "chevron.down")
        }
        .font(.footnote.weight(.bold))
        .foregroundStyle(.secondary)
        .frame(width: Self.thumbWidth, height: Self.thumbHeight)
        .background(Capsule().fill(.thickMaterial).shadow(color: .black.opacity(0.2), radius: 3, y: 1))
        .padding(.trailing, 6)
        .contentShape(Rectangle())
        .accessibilityLabel("Scroll through the library")
        .gesture(
            // ⚠ In the track's own space: the thumb moves under the finger, so its local space would
            // shift with every step of the drag it is measuring.
            DragGesture(minimumDistance: 0, coordinateSpace: .named(Self.space))
                .updating($touching) { _, touching, _ in touching = true }
                .onChanged { value in
                    if dragStart == nil {
                        dragStart = state.progress
                        state.beginDrag()
                    }
                    guard travel > 0, let start = dragStart else { return }
                    state.drag(to: start + value.translation.height / travel)
                }
                .onEnded { _ in finishDrag() }
        )
    }

    private func pills(track: CGFloat, progress: CGFloat) -> [ScrubberPill] {
        FeedYearIndexKt.scrubberPills(
            marks: state.yearMarks,
            track: Float(track),
            thumb: Float(Self.thumbHeight),
            pill: Float(Self.pillHeight),
            thumbFraction: Float(progress)
        )
    }

    /// The track the drag is measured in.
    private static let space = "feedScrubberTrack"
}
