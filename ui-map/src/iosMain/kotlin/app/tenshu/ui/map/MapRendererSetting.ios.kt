package app.tenshu.ui.map

import androidx.compose.runtime.Composable

/**
 * No election here, by decision rather than by omission: MapLibre over our own offline tiles is the
 * only renderer on this platform. Android is explicitly out of scope (`ios-apple-maps-option.md`
 * §9 **Q4** — the Play-services dependency, the account linkage and the `INTERNET`-stripped
 * manifest posture make Google Maps a different and worse trade), and Compose does not draw iOS's
 * settings screens, which carry their own SwiftUI `MapRendererSection`.
 */
@Composable
actual fun MapRendererSetting() = Unit
