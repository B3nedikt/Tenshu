package app.tenshu.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.alexzhirkevich.qrose.rememberQrCodePainter

/**
 * Shared QR renderer (moved from `control-shared` for Drive's invite flow —
 * `drive-manage-access-ux.md` §5.1). [contentDescription] is a parameter
 * because `:ui-core` has no string catalogue yet (i18n n=1 deferral) — each
 * app passes its own localised label.
 */
@Composable
fun QrCode(
    data: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    // QR codes require high contrast and a "Quiet Zone" (margin) to be scannable.
    // We force a white background even in dark mode.
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .padding(16.dp)
    ) {
        val painter = rememberQrCodePainter(data)

        Image(
            painter = painter,
            contentDescription = contentDescription,
            modifier = Modifier.matchParentSize()
        )
    }
}
