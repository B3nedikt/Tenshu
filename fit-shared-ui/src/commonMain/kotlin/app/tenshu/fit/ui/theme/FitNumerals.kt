package app.tenshu.fit.ui.theme

import androidx.compose.ui.text.TextStyle

/**
 * Tabular numerals for every metric/stat (fit-agent.md §2): equal-width digits so
 * ticking values (elapsed, distance, HR) don't jitter horizontally. Fit's only type
 * addition — the scale itself is the shared M3 sans (D3; no serif, ADR 0021).
 */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")
