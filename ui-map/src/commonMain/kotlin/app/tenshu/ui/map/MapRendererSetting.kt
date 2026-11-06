package app.tenshu.ui.map

import androidx.compose.runtime.Composable

/**
 * The map-renderer election, as a settings row — **empty on every platform that has no second
 * renderer to elect** (`ios-apple-maps-option.md` AM-5).
 *
 * It is an `expect` rather than a `if (isMacOs)` at each call site so a shared settings screen can
 * host it unconditionally: Android renders nothing (D1 and §9 Q4 — the Google Maps SDK is
 * explicitly not being extended to), desktop renders the toggle on macOS and nothing elsewhere, and
 * iOS has its own SwiftUI `MapRendererSection` because its settings screens are not Compose.
 *
 * ⚠⚠ The *empty* actuals are the point. A toggle that renders nowhere useful is invisible rather
 * than misleading, the failure mode AM-2 exists to avoid — a switch that claims to change a
 * renderer that does not exist on this platform would be fabricated.
 */
@Composable
expect fun MapRendererSetting()
