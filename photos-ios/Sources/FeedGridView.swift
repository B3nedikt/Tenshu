import SwiftUI
import TenshuSdk
import UIKit

// The Library's grid as a UICollectionView (photos-ios.md §8.6).
//
// ⚠⚠ Why UIKit and not a SwiftUI LazyVStack. The feed pages with placeholders, so a page landing
// above the viewport inserts headers and re-cuts every line after it; the re-anchor and the sticky
// header both need the EXACT top line through that. SwiftUI could not give it: `scrollTo` landed by
// height estimates (photo 299 on top for photo 274), `scrollPosition(id:)` went stale when content
// changed above it, and preference-reported frames of cached rows went stale after a re-phase ("2
// January" over a 31 December row). A collection view whose layout knows every line's height has no
// estimates: the layout attributes are the truth, `scrollToItem` lands exactly, and both work the
// same on iOS 16 and 17.
//
// ⚠ Swift decides nothing (PIO-D2): one item per `FeedLayout` line; which lines are headers,
// what each row holds, where to scroll and what the sticky header says are the binding's.
//
// ⚠⚠ A new layout is applied as a DIFFERENCE (photos-ios.md §8.20): `FeedLayout.changesFrom` says
// which lines to delete, insert and redraw, and every other row is left alone. A full reload would
// rebuild every visible row on every photograph a sync delivered.
// ⚠ Not a diffable data source: it wants an identifier per line, and most lines of a long library
// are placeholders — 16 000 of them for 50 000 photographs, named and diffed on every draw.

struct FeedGridView: UIViewRepresentable {
    @ObservedObject var model: LibraryModel
    /// A tile was tapped — the photograph, and the position it was DRAWN at (the route's last resort);
    /// the screen finds the photograph in the CURRENT list first (`positionOf`).
    let onOpen: (PhotosPhoto, Int32) -> Void
    /// The sticky header's text, or `nil` while a section's own header is fully on screen — and whether
    /// anything has scrolled under the navigation bar, which is when the top edge effect is drawn.
    let onStickyLabel: (String?, Bool) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(model: model, onOpen: onOpen, onStickyLabel: onStickyLabel) }

    func makeUIView(context: Context) -> UICollectionView {
        let lines = FeedLinesLayout()
        lines.measure = { [weak coordinator = context.coordinator] width in coordinator?.measure(width: width) }
        let view = WidthReportingCollectionView(frame: .zero, collectionViewLayout: lines)
        view.backgroundColor = .clear
        // ⚠⚠ The grid lies UNDER both bars (`LibraryScreen` extends it past the safe area), and UIKit
        // insets its content for them — the arrangement the system's glass bars and scroll edge
        // effects are drawn for (photos-ios.md §8.21). Every offset in this file is therefore read
        // against `adjustedContentInset`, never against zero.
        // ⚠ `.always`, not the default: a library too short to scroll must clear the bars as well.
        // ⚠⚠ Never both this AND a grid laid out inside the safe area — that pair was the return hang
        // (§8.11): the large title resized the grid, UIKit moved the offset, for ever.
        view.contentInsetAdjustmentBehavior = .always
        if #available(iOS 26.0, *) {
            // The fade Apple's Photos has under its title: photographs dissolve into the bar instead
            // of meeting a line. ⚠ The system's default here is the firmer boundary.
            view.topEdgeEffect.style = .soft
            // ⚠⚠ The effect dissolves the photographs into the scroll view's `backgroundColor`, so that
            // colour is what the bar's text is read against (photos-ios.md §8.21): black, as in the
            // dark appearance, under a white title. The page itself is the background VIEW, which
            // covers that colour everywhere else.
            view.backgroundColor = .black
            let page = UIView()
            page.backgroundColor = .systemBackground
            view.backgroundView = page
        }
        view.dataSource = context.coordinator
        view.delegate = context.coordinator
        view.onWidthChange = { [weak coordinator = context.coordinator] width in coordinator?.widthChanged(width) }
        context.coordinator.attach(view)
        model.gridNeedsLayoutsNow = { [weak coordinator = context.coordinator] in coordinator?.needsLayoutsNow ?? false }
        model.gridScroller = { [weak coordinator = context.coordinator] line in coordinator?.scroll(toLine: line) }
        model.scrubber.seek = { [weak coordinator = context.coordinator] progress in coordinator?.seek(to: progress) }
        model.scrubber.grabbed = { [weak coordinator = context.coordinator] in coordinator?.scrubberGrabbed() }
        model.scrubber.released = { [weak coordinator = context.coordinator] in coordinator?.reportVisibleVideos() }
        return view
    }

    func updateUIView(_ view: UICollectionView, context: Context) {
        context.coordinator.onOpen = onOpen
        context.coordinator.onStickyLabel = onStickyLabel
        context.coordinator.update(layout: model.layout, offered: model.offeredScroll)
    }

    @MainActor
    final class Coordinator: NSObject, UICollectionViewDataSource, UICollectionViewDelegate {
        static let spacing: CGFloat = 2
        static let headerHeight: CGFloat = 44
        /// Where a header's text sits in its row (`FeedLineContent`): `.headline`, 6 pt from the bottom.
        static let headerTextInset: CGFloat = 6
        static let headerTextHeight: CGFloat = 22
        /// How far below the bar a header has already dissolved: the edge effect blurs past the bar.
        static let headerFadeClearance: CGFloat = 10
        /// How near the top inset still counts as AT the top — the settled offset is not exact.
        static let topSlack: CGFloat = 1

        let model: LibraryModel
        var onOpen: (PhotosPhoto, Int32) -> Void
        var onStickyLabel: (String?, Bool) -> Void
        weak var view: UICollectionView?

        /// The layout the collection view is DRAWING — every line number here indexes it.
        private var layout: FeedLayout
        private var registration: UICollectionView.CellRegistration<UICollectionViewCell, Int>?
        /// The tile edge the rows were drawn at. ⚠ Not something `changesFrom` can see: a width change
        /// at the same column count re-cuts nothing, yet every row is the wrong size.
        private var drawnEdge: CGFloat = 0
        /// The re-anchor serial already PERFORMED, and the one queued for the next turn. ⚠⚠
        /// Separate marking a serial handled when it was merely queued loses it whenever the layout
        /// advances before the hop runs — `scrollTarget` rejects the stale layout, and nothing
        /// retries. Only a performed scroll is handled; anything else is retried on the next
        /// layout.
        private var handledSerial: Int32?
        private var queuedSerial: Int32?

        init(model: LibraryModel, onOpen: @escaping (PhotosPhoto, Int32) -> Void, onStickyLabel: @escaping (String?, Bool) -> Void) {
            self.model = model
            self.onOpen = onOpen
            self.onStickyLabel = onStickyLabel
            self.layout = model.layout
        }

        func attach(_ view: UICollectionView) {
            self.view = view
            registration = UICollectionView.CellRegistration<UICollectionViewCell, Int> { [weak self, weak view] cell, indexPath, _ in
                if let view { self?.configure(cell, at: indexPath, in: view) }
            }
            #if DEV_HARNESS
            FeedGridProbe.top = { [weak self] in self?.probeTop() ?? "-" }
            FeedGridProbe.geometry = { [weak view] in
                guard let view else { return "-" }
                // In the WINDOW's space: an overlay that shifted the grid's host would show here
                // even with the collection view's own frame unchanged.
                let frame = view.convert(view.bounds, to: nil)
                let inset = view.adjustedContentInset
                return "frame=\(Int(frame.minX)),\(Int(frame.minY)),\(Int(frame.width)),\(Int(frame.height)) "
                    + "inset=\(inset.top),\(inset.left),\(inset.bottom),\(inset.right) "
                    + "offset=\(view.contentOffset.x),\(view.contentOffset.y)"
            }
            #endif
        }

        #if DEV_HARNESS
        private func probeTop() -> String {
            guard let view else { return "-" }
            let rest = "offset=\(view.contentOffset.y + view.adjustedContentInset.top)"
            guard let anchor = topAnchor() else { return "none \(rest)" }
            let name = anchor.path?.split(separator: "/").last.map(String.init) ?? "placeholder#\(anchor.photo)"
            return "\(name) ordinal=\(anchor.photo) intoLine=\(anchor.offsetInLine) \(rest)"
        }
        #endif

        // MARK: SwiftUI → UIKit

        /// Draw [newLayout]: the lines that left and arrived as one batch, the kept ones whose content
        /// changed reconfigured in place, every other row untouched.
        private func apply(_ newLayout: FeedLayout, to view: UICollectionView) {
            #if DEV_HARNESS
            let t0 = CACurrentMediaTime()
            let configuredBefore = FeedGridProbe.configured
            #endif
            let edge = Self.tileEdge(columns: newLayout.columns, in: view)
            // ⚠⚠ A batch update is checked against what the view last COUNTED: asked here, while
            // `layout` is still the drawn one, so a view that has not loaded yet loads the old lines
            // and not the new ones (which would then be counted twice, and trap).
            let counted = view.numberOfItems(inSection: 0) == Int(layout.lineCount)
            // ⚠ Reloaded when every row is redrawn anyway — another grouping, column count (both
            // `changesFrom`'s) or tile size — or the change is too wide to be worth a batch.
            var changes: FeedLineChanges? = counted && edge == drawnEdge ? newLayout.changesFrom(old: layout) : nil
            if changes?.reload ?? true { changes = nil }
            #if DEV_HARNESS
            if FeedGridProbe.reloadAll { changes = nil }
            #endif
            layout = newLayout
            drawnEdge = edge
            // ⚠ Called from `updateUIView`: a clamped offset fires `scrollViewDidScroll`, whose report
            // writes SwiftUI state mid-update. The caller schedules the report.
            let wasRestoring = isRestoring
            isRestoring = true
            #if DEV_HARNESS
            let t1 = CACurrentMediaTime()
            var t2 = t1
            #endif
            UIView.performWithoutAnimation {
                if let changes {
                    let deleted = changes.deleted.map { IndexPath(item: $0.intValue, section: 0) }
                    let inserted = changes.inserted.map { IndexPath(item: $0.intValue, section: 0) }
                    if !deleted.isEmpty || !inserted.isEmpty {
                        view.performBatchUpdates {
                            view.deleteItems(at: deleted)
                            view.insertItems(at: inserted)
                        }
                    }
                    if !changes.changed.isEmpty {
                        view.reconfigureItems(at: changes.changed.map { IndexPath(item: $0.intValue, section: 0) })
                    }
                } else {
                    view.reloadData()
                }
                #if DEV_HARNESS
                t2 = CACurrentMediaTime()
                #endif
                // ⚠ A reconfigured line can change HEIGHT (a row becomes a feature): nothing but an
                // invalidation measures it again.
                view.collectionViewLayout.invalidateLayout()
                view.layoutIfNeeded()
            }
            isRestoring = wasRestoring
            #if DEV_HARNESS
            let t3 = CACurrentMediaTime()
            FeedGridProbe.drew(lines: Int(newLayout.lineCount), reload: changes == nil,
                               reconfigured: changes?.changed.count ?? 0,
                               configured: FeedGridProbe.configured - configuredBefore,
                               ms: [(t1 - t0) * 1000, (t2 - t1) * 1000, (t3 - t2) * 1000])
            #endif
        }

        /// ⚠⚠ Whether the grid FOLLOWS the top through the next layout (photos-ios.md §8.20): resting
        /// at the very top, a sync's new photographs — newest first — must come into view, and
        /// keeping the top photograph in place is exactly what pushed them above the viewport.
        /// ⚠ Only at rest: under a finger or the scrubber, or while a kept photograph or the binding's
        /// re-anchor still owns the position, the photograph under the thumb stays where it is.
        private func followsTop(_ view: UICollectionView) -> Bool {
            #if DEV_HARNESS
            if FeedGridProbe.noFollow { return false }
            #endif
            return view.contentOffset.y <= -view.adjustedContentInset.top + Self.topSlack
                && !view.isTracking && !view.isDragging && !view.isDecelerating && !model.scrubber.dragging
                && unresolved == nil && queuedSerial == nil && !model.binding.reanchorPending
        }

        /// Whether the next layout must be drawn at once rather than paced (`FeedLayoutPacer`): the
        /// user is moving the list — the pages are the ones their scroll asked for — or a kept
        /// photograph is still being looked for, and the grid sits on a stand-in until it is found.
        var needsLayoutsNow: Bool {
            guard let view else { return false }
            return view.isDragging || view.isDecelerating || model.scrubber.dragging || unresolved != nil
        }

        func update(layout newLayout: FeedLayout, offered: PhotosFeedBinding.ScrollTarget?) {
            if model.pagingFailed {
                // ⚠ A failed edge load is not retried by more touches, so an unresolved photograph
                // cannot be reached this way: let go of it at once — before any
                // later layout restores the stale ordinal, which may show another photograph. What is
                // on screen stays. ⚠ And the binding's re-anchor with it: its pending
                // anchor would otherwise publish the abandoned jump when a later layout holds the path.
                if unresolved != nil { model.binding.cancelReanchor() }
                unresolved = nil
            }
            if newLayout !== layout, let view {
                // ⚠⚠ An identity not yet found is carried across reloads: capturing
                // afresh would take whatever tile now sits at the fallback ordinal and lose the
                // photograph for good, though a later page may still bring it.
                let following = followsTop(view)
                let kept = following ? nil : unresolved ?? topAnchor()
                apply(newLayout, to: view)
                if let kept { unresolved = restore(kept) ? nil : kept }
                else if following { rest(at: -view.adjustedContentInset.top, in: view) }
                if unresolved != nil {
                    // ⚠ The grid sits still on the fallback ordinal, and its rows may never reach the
                    // prefetch boundary — so page toward BOTH edges of the loaded window until the
                    // kept photograph turns up or the layout is complete.
                    let loaded = model.binding.loadedPhotoPositions()
                    if let first = loaded.first { model.binding.touch(index: first.int32Value) }
                    if let last = loaded.last { model.binding.touch(index: last.int32Value) }
                }
                // Not inside SwiftUI's update pass: reporting writes view state.
                DispatchQueue.main.async { [weak self] in self?.reportViewport() }
            }
            if let offered, offered.serial != handledSerial, offered.serial != queuedSerial {
                queuedSerial = offered.serial
                let serial = offered.serial
                // ⚠ Next run-loop turn, then asked of the binding AGAIN — a drag may have cancelled
                // it and a page replaced the layout in between (`scrollTarget`).
                DispatchQueue.main.async { [weak self] in self?.performReanchor(serial: serial) }
            }
        }

        /// The photograph at the top, whether its line was the section header, and how far into
        /// that line the viewport starts — what a reload must keep in place.
        /// ⚠⚠ [path] is the photograph's identity — the platform's rule (`file-content-addressing.md`
        /// §3.3.1); [photo] (its library ordinal) is only a fallback for a placeholder anchor —
        /// photographs arriving or leaving ahead shift it.
        private struct TopAnchor { let path: String?; let photo: Int32; let onHeader: Bool; let offsetInLine: CGFloat }

        private func topAnchor() -> TopAnchor? {
            guard let view, let (line, frame) = topLine(in: view),
                  let photo = layout.photoAt(line: Int32(line)) else { return nil }
            let top = view.contentOffset.y + view.adjustedContentInset.top
            let feedLine = layout.get(line: Int32(line))
            let onHeader = feedLine is FeedLineHeader
            // The photograph `photoAt(line)` names: the line's first tile, or for a header the one after it.
            let first = feedLine.start + (onHeader ? 1 : 0)
            let path = (layout.itemAt(index: first) as? FeedItemTile)?.photo.path
            return TopAnchor(path: path, photo: photo.int32Value, onHeader: onHeader, offsetInLine: top - frame.minY)
        }

        /// ⚠⚠ Put the kept photograph back where it was — found by its PATH in the new layout. A
        /// page landing above adds headers and re-cuts every line before the viewport, and
        /// `reloadData()` keeps the PIXEL offset — so without this the photograph under the user's
        /// thumb changes as the page lands. The binding re-anchors only a density or width change;
        /// a page is this. A kept photograph whose path the current layout does not hold yet — see
        /// [update].
        private var unresolved: TopAnchor?

        /// Returns whether the kept identity is SETTLED — found, absent, or gone from a loaded region;
        /// `false` means the ordinal stood in over placeholders, and the identity is tried again on the
        /// next reload.
        @discardableResult
        private func restore(_ kept: TopAnchor) -> Bool {
            guard let view, layout.lineCount > 0 else { return false }
            let found = kept.path.flatMap { layout.photoOrdinalOf(path: $0)?.int32Value }
            let ordinal = found ?? kept.photo
            let line = Int(layout.lineOfPhoto(photo: ordinal, header: kept.onHeader))
            guard let frame = view.layoutAttributesForItem(at: IndexPath(item: line, section: 0))?.frame else { return false }
            let inset = view.adjustedContentInset
            let maxY = max(view.contentSize.height - view.bounds.height + inset.bottom, -inset.top)
            let y = min(max(frame.minY + kept.offsetInLine - inset.top, -inset.top), maxY)
            rest(at: y, in: view)
            // ⚠⚠ Expired only when absent from a COMPLETE layout — the whole library loaded — which is
            // the one place absence proves deletion: a photograph pushed pages
            // down by a burst also leaves its old ordinal loaded, occupied by another. Otherwise it is
            // carried until found, or the user drags.
            let gone = found == nil && layout.isComplete
            return found != nil || kept.path == nil || gone
        }

        private func rest(at y: CGFloat, in view: UICollectionView) {
            guard abs(view.contentOffset.y - y) > 0.5 else { return }
            // ⚠ Called from `updateUIView`: the offset write fires `scrollViewDidScroll`, whose
            // report writes SwiftUI state mid-update. Silenced here; the reload
            // already schedules the report for the next turn.
            isRestoring = true
            view.contentOffset.y = y
            isRestoring = false
        }

        private func performReanchor(serial: Int32) {
            if queuedSerial == serial { queuedSerial = nil }
            // Not performable against the layout drawn right now — left unhandled, so the next drawn
            // layout offers it again.
            guard let target = model.binding.scrollTarget(drawn: layout), target.serial == serial else { return }
            handledSerial = serial
            unresolved = nil // the binding's re-anchor owns the position now
            scroll(toLine: Int(target.line))
            // ⚠ Consumed in the BINDING, which outlives this coordinator: a grid
            // recreated after an error and a retry must not see the performed request again.
            model.binding.acknowledgeScroll(serial: serial)
        }

        /// Top-align [line] — exact, since every item's size comes from the delegate.
        func scroll(toLine line: Int) {
            guard let view, line >= 0, line < Int(layout.lineCount) else { return }
            view.scrollToItem(at: IndexPath(item: line, section: 0), at: .top, animated: false)
            view.layoutIfNeeded()
            reportViewport()
        }

        /// The scrubber's seek: the line holding the photograph at [progress] of the library, top-aligned
        /// — `FeedLayout.lineAtProgress`, the inverse of the thumb's `progressAt`.
        func seek(to progress: CGFloat) {
            guard layout.lineCount > 0 else { return }
            scroll(toLine: Int(layout.lineAtProgress(progress: Float(progress))))
        }

        /// ⚠ The user taking the list back — a drag or the scrubber — ends a re-anchor still waiting
        /// for pages (§32). ⚠ The scrubber too: it moves by `scrollToItem`, which no drag callback hears.
        func userTookTheList() {
            model.binding.cancelReanchor()
            unresolved = nil // what is under their thumb is theirs now
        }

        /// The scrubber was grabbed: end a re-anchor as a drag does, and report the viewport at once so
        /// the bubble has the top row's date before the finger moves.
        func scrubberGrabbed() {
            userTookTheList()
            reportViewport()
        }

        func widthChanged(_ width: CGFloat) {
            view?.collectionViewLayout.invalidateLayout()
            model.gridWidthChanged(width)
        }

        // MARK: Viewport

        /// The exact top line and how far it has scrolled past, from the layout's own attributes —
        /// the re-anchor's input and the sticky header's.
        /// The first line whose bottom is below the viewport's top, and its frame — exact, from the
        /// layout's own attributes.
        private func topLine(in view: UICollectionView) -> (Int, CGRect)? {
            guard layout.lineCount > 0 else { return nil }
            let top = view.contentOffset.y + view.adjustedContentInset.top
            // ⚠ Asked of the LAYOUT, not of the visible cells, which are a layout pass behind an offset
            // just written — and a second layout can be drawn before that pass runs.
            let band = CGRect(x: 0, y: top, width: max(view.bounds.width, 1), height: max(view.bounds.height, 1))
            let lines = view.collectionViewLayout.layoutAttributesForElements(in: band) ?? []
            return lines.filter { $0.representedElementCategory == .cell && $0.frame.maxY > top }
                .min { $0.indexPath.item < $1.indexPath.item }
                .map { ($0.indexPath.item, $0.frame) }
        }

        private func reportViewport() {
            fadeHeadersUnderBar()
            guard let view, layout.lineCount > 0 else { onStickyLabel(nil, false); return }
            let top = view.contentOffset.y + view.adjustedContentInset.top
            guard let (line, frame) = topLine(in: view) else { return }
            let scrolledPast = frame.height > 0 ? min(max((top - frame.minY) / frame.height, 0), 1) : 0
            model.viewportChanged(topLine: line, scrolledPast: scrolledPast, measured: layout)
            let inset = view.adjustedContentInset
            let drawn = layout
            // ⚠ At the end of the scroll range the thumb rests at the bottom: the top line there is
            // rows short of the last photograph, so `progressAt` alone would put the thumb above
            // the end the grid is at — and a drag to the bottom would jump up on release.
            let scrollable = view.contentSize.height > view.bounds.height - inset.top - inset.bottom + 0.5
            let maxOffset = view.contentSize.height - view.bounds.height + inset.bottom
            let atEnd = scrollable && view.contentOffset.y >= maxOffset - 0.5
            model.scrubber.viewport(
                progress: atEnd ? 1 : CGFloat(drawn.progressAt(line: Int32(line), withinLine: Float(scrolledPast))),
                scrollable: scrollable,
                bubble: self.model.binding.labelAt(line: Int32(line), layout: drawn)
            )
            // ⚠⚠ While a section's own header is FULLY on screen it IS the header; a copy over it
            // reads as a duplicate (`photos-app.md` §29.2 — the same rule as Compose's overlay). ⚠
            // Half a point of slack: the settled inset is not exactly zero (2.8e-14 on an iPhone 16
            // Pro sim), so an exact `>=` would put the copy over the header at the very top of the
            // feed.
            let headerFullyOnScreen = frame.minY >= top - 0.5 && layout.get(line: Int32(line)) is FeedLineHeader
            onStickyLabel(headerFullyOnScreen ? nil : model.binding.labelAt(line: Int32(line), layout: layout), top > 0.5)
            reportVisibleVideos()
        }

        /// The video tiles on screen, in document order, for the motion rotation — none while the
        /// scrubber is held (nobody reads a moving tile under a scrub; Compose's Q2).
        ///
        /// ⚠⚠ A feature is two rows tall, so its LINE being on screen does not put its small tiles
        /// there: with only the top half showing, the lower one is below the viewport, and a turn spent
        /// on it plays a video nobody sees. `FeedLine.Feature.rowOf` says which row a
        /// small tile is in; the hero spans both.
        func reportVisibleVideos() {
            guard let view else { return }
            if model.scrubber.dragging { model.motion.setVisible([]); return }
            let inset = view.adjustedContentInset
            let top = view.contentOffset.y + inset.top
            let bottom = view.contentOffset.y + view.bounds.height - inset.bottom
            var paths: [String] = []
            for item in view.indexPathsForVisibleItems.map(\.item).sorted() where item < Int(layout.lineCount) {
                guard let frame = view.layoutAttributesForItem(at: IndexPath(item: item, section: 0))?.frame,
                      frame.maxY > top, frame.minY < bottom else { continue }
                let line = layout.get(line: Int32(item))
                if line is FeedLineHeader { continue }
                let feature = line as? FeedLineFeature
                for offset in 0..<Int(line.size) {
                    let index = line.start + Int32(offset)
                    if let row = feature?.rowOf(index: index)?.intValue {
                        let rowHeight = (frame.height - Self.spacing) / 2
                        let rowTop = frame.minY + CGFloat(row) * (rowHeight + Self.spacing)
                        if rowTop >= bottom || rowTop + rowHeight <= top { continue }
                    }
                    if let tile = layout.itemAt(index: index) as? FeedItemTile, tile.photo.isVideo {
                        paths.append(tile.photo.path)
                    }
                }
            }
            model.motion.setVisible(paths)
        }

        private var isRestoring = false

        // MARK: Headers under the bar

        /// How much of a line's row is drawn: a section's own date dissolves as it crosses under the
        /// navigation bar, where the title carries the same date (photos-ios.md §8.21) — blurred by
        /// the edge effect it read as a second, ghosted date behind the first.
        /// ⚠ Gone just before its text reaches the bar: full while the text is well clear of it
        /// (which includes the moment the header is the top line and the title shows no date), so
        /// there is never a point with no date on screen.
        /// ⚠ Before iOS 26 nothing fades: the `.bar` strip covers what passes under it.
        private func headerAlpha(line: Int, frame: CGRect, in view: UICollectionView) -> CGFloat {
            guard #available(iOS 26.0, *), line < Int(layout.lineCount),
                  layout.get(line: Int32(line)) is FeedLineHeader else { return 1 }
            let barBottom = view.contentOffset.y + view.adjustedContentInset.top
            let textBottom = frame.maxY - Self.headerTextInset
            return min(max((textBottom - barBottom - Self.headerFadeClearance) / Self.headerTextHeight, 0), 1)
        }

        /// ⚠ The rows on screen only, and only their alpha: no layout is invalidated at scroll rate
        /// (§8.11). ⚠ `contentView`, not the cell — the layout's attributes own the cell's alpha.
        /// Every row is written, not only headers: a kept row can become another kind of line.
        private func fadeHeadersUnderBar() {
            guard #available(iOS 26.0, *), let view else { return }
            for cell in view.visibleCells {
                guard let line = view.indexPath(for: cell)?.item else { continue }
                cell.contentView.alpha = headerAlpha(line: line, frame: cell.frame, in: view)
            }
        }

        func scrollViewDidScroll(_ scrollView: UIScrollView) {
            fadeHeadersUnderBar()
            guard !isRestoring else { return }
            reportViewport()
            // ⚠ Only the user's own movement wakes the thumb — not a re-anchor or a restore, which is
            // what Compose's `isScrollInProgress` distinguishes too.
            if scrollView.isDragging || scrollView.isDecelerating { model.scrubber.moved() }
        }

        func scrollViewWillBeginDragging(_ scrollView: UIScrollView) {
            userTookTheList()
        }

        // MARK: Rows

        func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
            Int(layout.lineCount)
        }

        func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
            // The registration, not a reuse identifier: `reconfigureItems` runs its handler again.
            collectionView.dequeueConfiguredReusableCell(using: registration!, for: indexPath, item: indexPath.item)
        }

        /// ⚠⚠ A row is drawn from the layout current WHEN it is configured and may outlive it — an
        /// unchanged line is not configured again. So it carries that layout with its line (the pair
        /// stays consistent), and nothing that must be current: a tap is resolved against the layout
        /// drawn at the tap ([open]).
        private func configure(_ cell: UICollectionViewCell, at indexPath: IndexPath, in collectionView: UICollectionView) {
            guard indexPath.item < Int(layout.lineCount) else { return }
            #if DEV_HARNESS
            FeedGridProbe.configured += 1
            #endif
            let line = layout.get(line: Int32(indexPath.item))
            let drawn = layout
            let binding = model.binding
            let edge = tileEdge(in: collectionView)
            let scale = collectionView.traitCollection.displayScale
            let open: (PhotosPhoto, Int32) -> Void = { [weak self] photo, position in self?.open(photo, configuredAt: position) }
            let previews = model.scrubber.previews
            let selection = model.selection
            let motion = model.motion
            cell.contentConfiguration = UIHostingConfiguration {
                FeedLineContent(line: line, lineIndex: indexPath.item, layout: drawn, binding: binding,
                                edge: edge, spacing: Self.spacing, scale: scale, previews: previews,
                                selection: selection, motion: motion, onOpen: open)
            }
            .margins(.all, 0)
        }

        /// The position handed to the viewer is where the photograph is DRAWN now — the row's own is
        /// from the layout it was configured for, which a kept row can be several layouts behind.
        private func open(_ photo: PhotosPhoto, configuredAt position: Int32) {
            onOpen(photo, layout.positionOf(path: photo.path)?.int32Value ?? position)
        }

        /// ⚠⚠ Paging's load trigger: every line that is about to show records an access. Without it
        /// the grid renders the first page and never grows (`TenshuPagingPresenter`'s KDoc).
        func collectionView(_ collectionView: UICollectionView, willDisplay cell: UICollectionViewCell, forItemAt indexPath: IndexPath) {
            // A reused row arrives with whatever alpha its last line left it.
            cell.contentView.alpha = headerAlpha(line: indexPath.item, frame: cell.frame, in: collectionView)
            guard indexPath.item < Int(layout.lineCount) else { return }
            model.binding.touch(index: layout.get(line: Int32(indexPath.item)).start)
        }

        /// What `FeedLinesLayout` places the drawn layout's lines from. ⚠ Only the LOADED lines are
        /// measured one by one; an unloaded line is a row, so the two placeholder runs are a count.
        fileprivate func measure(width: CGFloat) -> FeedLinesLayout.Lines {
            let edge = max(Self.tileEdge(columns: layout.columns, width: width), 1)
            let leading = Int(layout.leadingLineCount)
            let loaded = (0..<Int(layout.loadedLineCount)).map { at -> CGFloat in
                let line = layout.get(line: Int32(leading + at))
                if line is FeedLineHeader { return Self.headerHeight }
                // A feature is two rows tall: its hero spans both (`FeedLine.Feature`).
                if line is FeedLineFeature { return edge * 2 + Self.spacing }
                return edge
            }
            return FeedLinesLayout.Lines(leading: leading, loaded: loaded,
                                         trailing: Int(layout.lineCount) - leading - loaded.count,
                                         row: edge, spacing: Self.spacing)
        }

        private func tileEdge(in view: UICollectionView) -> CGFloat {
            Self.tileEdge(columns: layout.columns, in: view)
        }

        private static func tileEdge(columns: Int32, in view: UICollectionView) -> CGFloat {
            tileEdge(columns: columns, width: view.bounds.width)
        }

        private static func tileEdge(columns: Int32, width: CGFloat) -> CGFloat {
            let columns = CGFloat(max(columns, 1))
            return floor((width - spacing * (columns - 1)) / columns)
        }
    }
}

/// The grid's layout: one full-width item per feed line, stacked, `spacing` apart.
///
/// ⚠⚠ Why not a flow layout (photos-ios.md §8.20). A flow layout asks the delegate for EVERY item's
/// size each time it is invalidated — and it is invalidated by every layout drawn. Most lines of a
/// long library are placeholders: 16 700 for 50 000 photographs, ~18 ms a draw on the simulator
/// before a single row was touched, and twice that inside a batch update. Here only the loaded
/// lines are measured; the unloaded runs above and below are `count × row`, so preparing, finding
/// the lines in a rectangle and placing one line are all arithmetic or a search of the loaded window.
///
/// ⚠ Still exact, which is what the grid needs it for: every line's frame is known, never estimated.
final class FeedLinesLayout: UICollectionViewLayout {
    struct Lines {
        var leading = 0
        /// The loaded lines' heights, in order.
        var loaded: [CGFloat] = []
        var trailing = 0
        /// A placeholder line's height — a row of tiles.
        var row: CGFloat = 1
        var spacing: CGFloat = 0
    }

    /// The drawn layout's lines at a width — asked on every `prepare`.
    var measure: ((CGFloat) -> Lines?)?

    private var lines = Lines()
    /// `tops[i]` is the top of loaded line `i` below the first loaded line's; one past the end is their extent.
    private var tops: [CGFloat] = [0]
    private var width: CGFloat = 0

    private var count: Int { lines.leading + lines.loaded.count + lines.trailing }
    private var step: CGFloat { lines.row + lines.spacing }
    private var loadedTop: CGFloat { CGFloat(lines.leading) * step }
    private var trailingTop: CGFloat { loadedTop + tops[lines.loaded.count] }

    override func prepare() {
        super.prepare()
        width = collectionView?.bounds.width ?? 0
        lines = measure?(width) ?? Lines()
        tops = [0]
        tops.reserveCapacity(lines.loaded.count + 1)
        for height in lines.loaded { tops.append(tops[tops.count - 1] + height + lines.spacing) }
    }

    override var collectionViewContentSize: CGSize {
        let height = trailingTop + CGFloat(lines.trailing) * step
        return CGSize(width: width, height: max(height - (count > 0 ? lines.spacing : 0), 0))
    }

    private func frame(of line: Int) -> CGRect {
        let loaded = line - lines.leading
        if loaded < 0 { return CGRect(x: 0, y: CGFloat(line) * step, width: width, height: lines.row) }
        if loaded < lines.loaded.count {
            return CGRect(x: 0, y: loadedTop + tops[loaded], width: width, height: lines.loaded[loaded])
        }
        return CGRect(x: 0, y: trailingTop + CGFloat(loaded - lines.loaded.count) * step, width: width, height: lines.row)
    }

    /// The first line whose bottom (its spacing included) is below [y].
    private func line(at y: CGFloat) -> Int {
        if y < loadedTop { return max(Int(y / step), 0) }
        if y < trailingTop {
            // The last loaded line starting at or above y.
            var low = 0
            var high = lines.loaded.count - 1
            while low < high {
                let mid = (low + high + 1) / 2
                if loadedTop + tops[mid] <= y { low = mid } else { high = mid - 1 }
            }
            return lines.leading + low
        }
        return lines.leading + lines.loaded.count + Int((y - trailingTop) / step)
    }

    override func layoutAttributesForElements(in rect: CGRect) -> [UICollectionViewLayoutAttributes]? {
        guard count > 0, rect.maxY >= 0 else { return [] }
        var found: [UICollectionViewLayoutAttributes] = []
        var line = min(line(at: max(rect.minY, 0)), count - 1)
        while line < count {
            let frame = frame(of: line)
            if frame.minY > rect.maxY { break }
            if frame.intersects(rect) { found.append(attributes(line, frame)) }
            line += 1
        }
        return found
    }

    override func layoutAttributesForItem(at indexPath: IndexPath) -> UICollectionViewLayoutAttributes? {
        guard indexPath.item >= 0, indexPath.item < count else { return nil }
        return attributes(indexPath.item, frame(of: indexPath.item))
    }

    private func attributes(_ line: Int, _ frame: CGRect) -> UICollectionViewLayoutAttributes {
        let attributes = UICollectionViewLayoutAttributes(forCellWith: IndexPath(item: line, section: 0))
        attributes.frame = frame
        return attributes
    }

    override func shouldInvalidateLayout(forBoundsChange newBounds: CGRect) -> Bool {
        newBounds.width != width
    }
}

#if DEV_HARNESS
/// What the grid did for each layout (`-photosArrivalsDemo`, photos-ios.md §8.20): how many rows it
/// configured — built or rebuilt — which is the cost a diff exists to cut.
/// `-photosReloadAll` draws every layout as a full reload instead, for the same count.
@MainActor
enum FeedGridProbe {
    static let reloadAll = ProcessInfo.processInfo.arguments.contains("-photosReloadAll")
    /// `-photosNoFollow`: keep the top photograph in place even at the top, never following it.
    static let noFollow = ProcessInfo.processInfo.arguments.contains("-photosNoFollow")
    static var configured = 0
    static var draws = 0
    static var configuredByDraws = 0
    /// The photograph on top and where the grid rests — set by the grid.
    static var top: (() -> String)?
    /// The grid's frame in the window, its `adjustedContentInset` and its content offset — what the
    /// sync capsule must leave exactly as they were (`-photosSyncCapsule`).
    static var geometry: (() -> String)?

    /// [ms]: working out what changed, applying it, and the layout pass that follows.
    static func drew(lines: Int, reload: Bool, reconfigured: Int, configured: Int, ms: [Double]) {
        draws += 1
        configuredByDraws += configured
        let cost = ms.map { String(format: "%.2f", $0) }.joined(separator: "/")
        print("[photos-probe] draw #\(draws) lines=\(lines) mode=\(reload ? "reload" : "diff") reconfigured=\(reconfigured) rowsConfigured=\(configured) ms(changes/apply/layout)=\(cost)")
    }
}
#endif

/// Reports its width as it changes — the column count follows the grid's width (`columnsAt`).
private final class WidthReportingCollectionView: UICollectionView {
    var onWidthChange: ((CGFloat) -> Void)?
    private var lastWidth: CGFloat = 0

    override func layoutSubviews() {
        if bounds.width != lastWidth, bounds.width > 0 {
            lastWidth = bounds.width
            onWidthChange?(bounds.width)
        }
        super.layoutSubviews()
    }
}

/// One feed line — a section header, or a row of tiles — drawn from the layout the grid is DRAWING.
/// ⚠ Items are read off [layout] (`itemAt`), never the binding's newest: a line number means nothing
/// against another layout.
private struct FeedLineContent: View {
    let line: FeedLine
    let lineIndex: Int
    let layout: FeedLayout
    let binding: PhotosFeedBinding
    let edge: CGFloat
    let spacing: CGFloat
    let scale: CGFloat
    let previews: PreviewGate
    let selection: SelectionState
    let motion: MotionState
    let onOpen: (PhotosPhoto, Int32) -> Void

    var body: some View {
        if line is FeedLineHeader {
            Text(binding.labelAt(line: Int32(lineIndex), layout: layout) ?? "")
                .font(.headline)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
                .padding(.horizontal, 16)
                .padding(.bottom, FeedGridView.Coordinator.headerTextInset)
        } else if let feature = line as? FeedLineFeature {
            featureBlock(feature)
        } else {
            HStack(spacing: spacing) {
                ForEach(0..<Int(line.size), id: \.self) { offset in
                    tile(at: line.start + Int32(offset))
                }
                Spacer(minLength: 0)
            }
        }
    }

    /// A feature's blocks — each a hero across 2×2 cells beside the two that stack — placed on the
    /// column grid exactly as Compose's `FeedLineCells` places them. ⚠ The shared `isHero` /
    /// `heroLeftIn` decide which child is a hero and which side it takes; only the arithmetic of
    /// positions is here, and it is Compose's: column `child / 3 * 3 + (hero left ? (0 | 2) : (1 | 0))`,
    /// and a block's last child is always the lower small cell.
    @ViewBuilder
    private func featureBlock(_ feature: FeedLineFeature) -> some View {
        let step = edge + spacing
        let block = Int(FeedLineFeature.companion.BLOCK)
        ZStack(alignment: .topLeading) {
            Color.clear
            ForEach(0..<Int(feature.size), id: \.self) { child in
                let index = feature.start + Int32(child)
                let isHero = feature.isHero(index: index)
                let offset = child % block
                let heroLeft = feature.heroLeftIn(block: Int32(child / block))
                let column = child / block * block + (heroLeft ? (offset == 0 ? 0 : 2) : (offset == 1 ? 1 : 0))
                let row = offset == 2 ? 1 : 0
                tile(at: index, size: isHero ? edge * 2 + spacing : edge)
                    .offset(x: CGFloat(column) * step, y: CGFloat(row) * step)
            }
        }
    }

    @ViewBuilder
    private func tile(at position: Int32, size: CGFloat? = nil) -> some View {
        let edge = size ?? self.edge
        if let item = layout.itemAt(index: position) as? FeedItemTile {
            // ⚠⚠ The photograph, not `position`: that index is this layout's, and the viewer opens on
            // the list as it is NOW.
            SelectableTile(photo: item.photo, position: position, edge: edge, scale: scale,
                           previews: previews, selection: selection, motion: motion, onOpen: onOpen)
        } else {
            // An unloaded slot: the grid keeps its shape while the page arrives.
            Rectangle()
                .fill(Color(uiColor: .secondarySystemFill))
                .frame(width: edge, height: edge)
        }
    }
}
