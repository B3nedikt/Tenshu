package app.tenshu.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Tenshu "Amethyst Neutral" color palette — the single source of truth for
 * brand color across Drive, Control, and every target.
 *
 * The hex values come from `docs/adr/0001-design-system-direction.md`. Any
 * change here is an ADR-level change. Do NOT introduce new `Color(0xFF…)`
 * literals outside this file.
 *
 * Contrast validation is a Phase 2 acceptance criterion — run body-text,
 * primary/onPrimary, and primaryContainer/onPrimaryContainer pairs through
 * a WCAG AA checker before this palette ships to users.
 */
object AppColorTokens {

    // --- Light scheme -------------------------------------------------------

    private val LightPrimary = Color(0xFF5A4A96)
    private val LightOnPrimary = Color(0xFFFFFFFF)
    private val LightPrimaryContainer = Color(0xFFE6DEFF)
    private val LightOnPrimaryContainer = Color(0xFF18034F)

    private val LightSecondary = Color(0xFF5F5C71)
    private val LightOnSecondary = Color(0xFFFFFFFF)
    private val LightSecondaryContainer = Color(0xFFE5DFF9)
    private val LightOnSecondaryContainer = Color(0xFF1B1A2C)

    private val LightTertiary = Color(0xFF7B5265)
    private val LightOnTertiary = Color(0xFFFFFFFF)
    private val LightTertiaryContainer = Color(0xFFFFD8E7)
    private val LightOnTertiaryContainer = Color(0xFF301120)

    private val LightError = Color(0xFFBA1A1A)
    private val LightErrorContainer = Color(0xFFFFDAD6)
    private val LightOnError = Color(0xFFFFFFFF)
    private val LightOnErrorContainer = Color(0xFF410002)

    // ⚠⚠ The surface family is ONE tonal ramp — page (tone 98) and the five container steps
    // (100 → 90) must move together. Before 2026-11-01 only `surface` was set (tone 99, the
    // pre-container M3 baseline) and every `surfaceContainer*` fell back to M3's defaults, which
    // are keyed to a tone-98 page in light and a tone-6 page in DARK: on our tone-10 dark page the
    // cards (`surfaceContainerLow`, tone 10) were the page colour and vanished. Same neutral hue as
    // the rest of this palette (`#1C1B1F`/`#E6E1E6` are its tones 10/90). ADR 0001.
    private val LightBackground = Color(0xFFFEF7FF)
    private val LightOnBackground = Color(0xFF1C1B1F)
    private val LightSurface = Color(0xFFFEF7FF)
    private val LightOnSurface = Color(0xFF1C1B1F)
    private val LightSurfaceVariant = Color(0xFFE7E0EC)
    private val LightOnSurfaceVariant = Color(0xFF49454E)
    private val LightSurfaceDim = Color(0xFFDED8E1)
    private val LightSurfaceBright = Color(0xFFFEF7FF)
    private val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
    private val LightSurfaceContainerLow = Color(0xFFF7F2FA)
    private val LightSurfaceContainer = Color(0xFFF3EDF7)
    private val LightSurfaceContainerHigh = Color(0xFFECE6F0)
    private val LightSurfaceContainerHighest = Color(0xFFE6E0E9)

    private val LightOutline = Color(0xFF7A757F)
    private val LightOutlineVariant = Color(0xFFCAC4CF)
    private val LightInverseSurface = Color(0xFF313033)
    private val LightInverseOnSurface = Color(0xFFF4EFF4)
    private val LightInversePrimary = Color(0xFFCBC0FF)
    private val LightSurfaceTint = LightPrimary
    private val LightScrim = Color(0xFF000000)

    // --- Dark scheme --------------------------------------------------------

    private val DarkPrimary = Color(0xFFCBC0FF)
    private val DarkOnPrimary = Color(0xFF2C1E71)
    private val DarkPrimaryContainer = Color(0xFF42347D)
    private val DarkOnPrimaryContainer = Color(0xFFE6DEFF)

    private val DarkSecondary = Color(0xFFC8C3DC)
    private val DarkOnSecondary = Color(0xFF302E42)
    private val DarkSecondaryContainer = Color(0xFF474459)
    private val DarkOnSecondaryContainer = Color(0xFFE5DFF9)

    private val DarkTertiary = Color(0xFFEBB8CB)
    private val DarkOnTertiary = Color(0xFF482535)
    private val DarkTertiaryContainer = Color(0xFF613B4D)
    private val DarkOnTertiaryContainer = Color(0xFFFFD8E7)

    private val DarkError = Color(0xFFFFB4AB)
    private val DarkErrorContainer = Color(0xFF93000A)
    private val DarkOnError = Color(0xFF690005)
    private val DarkOnErrorContainer = Color(0xFFFFDAD6)

    // Tone 6 page, containers stepping UP from it (4 → 22) — see the light ramp's note.
    private val DarkBackground = Color(0xFF141218)
    private val DarkOnBackground = Color(0xFFE6E1E6)
    private val DarkSurface = Color(0xFF141218)
    private val DarkOnSurface = Color(0xFFE6E1E6)
    private val DarkSurfaceVariant = Color(0xFF49454E)
    private val DarkOnSurfaceVariant = Color(0xFFCAC4CF)
    private val DarkSurfaceDim = Color(0xFF141218)
    private val DarkSurfaceBright = Color(0xFF3B383E)
    private val DarkSurfaceContainerLowest = Color(0xFF0F0D13)
    private val DarkSurfaceContainerLow = Color(0xFF1D1B20)
    private val DarkSurfaceContainer = Color(0xFF211F26)
    private val DarkSurfaceContainerHigh = Color(0xFF2B2930)
    private val DarkSurfaceContainerHighest = Color(0xFF36343B)

    private val DarkOutline = Color(0xFF948F99)
    private val DarkOutlineVariant = Color(0xFF49454E)
    private val DarkInverseSurface = Color(0xFFE6E1E6)
    private val DarkInverseOnSurface = Color(0xFF313033)
    private val DarkInversePrimary = Color(0xFF5A4A96)
    private val DarkSurfaceTint = DarkPrimary
    private val DarkScrim = Color(0xFF000000)

    // --- Schemes ------------------------------------------------------------

    val LightScheme: ColorScheme = lightColorScheme(
        primary = LightPrimary,
        onPrimary = LightOnPrimary,
        primaryContainer = LightPrimaryContainer,
        onPrimaryContainer = LightOnPrimaryContainer,
        secondary = LightSecondary,
        onSecondary = LightOnSecondary,
        secondaryContainer = LightSecondaryContainer,
        onSecondaryContainer = LightOnSecondaryContainer,
        tertiary = LightTertiary,
        onTertiary = LightOnTertiary,
        tertiaryContainer = LightTertiaryContainer,
        onTertiaryContainer = LightOnTertiaryContainer,
        error = LightError,
        errorContainer = LightErrorContainer,
        onError = LightOnError,
        onErrorContainer = LightOnErrorContainer,
        background = LightBackground,
        onBackground = LightOnBackground,
        surface = LightSurface,
        onSurface = LightOnSurface,
        surfaceVariant = LightSurfaceVariant,
        onSurfaceVariant = LightOnSurfaceVariant,
        outline = LightOutline,
        outlineVariant = LightOutlineVariant,
        inverseSurface = LightInverseSurface,
        inverseOnSurface = LightInverseOnSurface,
        inversePrimary = LightInversePrimary,
        surfaceTint = LightSurfaceTint,
        surfaceDim = LightSurfaceDim,
        surfaceBright = LightSurfaceBright,
        surfaceContainerLowest = LightSurfaceContainerLowest,
        surfaceContainerLow = LightSurfaceContainerLow,
        surfaceContainer = LightSurfaceContainer,
        surfaceContainerHigh = LightSurfaceContainerHigh,
        surfaceContainerHighest = LightSurfaceContainerHighest,
        scrim = LightScrim,
    )

    val DarkScheme: ColorScheme = darkColorScheme(
        primary = DarkPrimary,
        onPrimary = DarkOnPrimary,
        primaryContainer = DarkPrimaryContainer,
        onPrimaryContainer = DarkOnPrimaryContainer,
        secondary = DarkSecondary,
        onSecondary = DarkOnSecondary,
        secondaryContainer = DarkSecondaryContainer,
        onSecondaryContainer = DarkOnSecondaryContainer,
        tertiary = DarkTertiary,
        onTertiary = DarkOnTertiary,
        tertiaryContainer = DarkTertiaryContainer,
        onTertiaryContainer = DarkOnTertiaryContainer,
        error = DarkError,
        errorContainer = DarkErrorContainer,
        onError = DarkOnError,
        onErrorContainer = DarkOnErrorContainer,
        background = DarkBackground,
        onBackground = DarkOnBackground,
        surface = DarkSurface,
        onSurface = DarkOnSurface,
        surfaceVariant = DarkSurfaceVariant,
        onSurfaceVariant = DarkOnSurfaceVariant,
        outline = DarkOutline,
        outlineVariant = DarkOutlineVariant,
        inverseSurface = DarkInverseSurface,
        inverseOnSurface = DarkInverseOnSurface,
        inversePrimary = DarkInversePrimary,
        surfaceTint = DarkSurfaceTint,
        surfaceDim = DarkSurfaceDim,
        surfaceBright = DarkSurfaceBright,
        surfaceContainerLowest = DarkSurfaceContainerLowest,
        surfaceContainerLow = DarkSurfaceContainerLow,
        surfaceContainer = DarkSurfaceContainer,
        surfaceContainerHigh = DarkSurfaceContainerHigh,
        surfaceContainerHighest = DarkSurfaceContainerHighest,
        scrim = DarkScrim,
    )
}
