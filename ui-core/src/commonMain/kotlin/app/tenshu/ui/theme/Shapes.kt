package app.tenshu.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Tenshu shape tokens — the Material 3 shape scale.
 *
 * Consumers should reference `MaterialTheme.shapes.small` etc., not
 * these constants directly. Defining `AppShapes` in `ui-core` means one
 * place to tweak the brand's corner radius language.
 *
 * Don't introduce inline `RoundedCornerShape(n.dp)` literals in feature
 * components — use a token. If a literal is truly needed (e.g., a
 * circular avatar), it's fine; the rule applies to "card-ish rectangles."
 */
val AppShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
