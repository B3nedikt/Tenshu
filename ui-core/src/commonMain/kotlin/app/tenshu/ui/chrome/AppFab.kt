package app.tenshu.ui.chrome

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.tenshu.ui.preview.PreviewBox
import app.tenshu.ui.theme.spacing
import androidx.compose.ui.tooling.preview.Preview

/**
 * The app-styled floating action button — a screen's **one** primary action.
 *
 * ### When to use
 *
 * Exactly one per screen, for the action the screen exists to enable: add a file, add a
 * contact, invite someone, pair a device. If a screen has two candidate actions, the FAB opens
 * a sheet holding both (Drive's `AddFileActionsFloatingActionButton` and Contacts'
 * `AddContactFab` are that pattern) — two FABs is not a layout this design system has.
 *
 * ### Placement
 *
 * Two shapes, both already in use and both correct:
 *
 *  - inside a `Scaffold`'s `floatingActionButton` slot, which owns placement and inset — pass
 *    no modifier;
 *  - aligned by hand over a `Box`, for a screen with no `Scaffold`:
 *    `Modifier.align(Alignment.BottomEnd).appFabInset()`.
 *
 * ### Accessibility
 *
 * ⚠ [contentDescription] is **required**, not nullable. A FAB is an icon and nothing else, so
 * without one it is an unlabelled button — the single most important control on the screen,
 * invisible to a screen reader. Every call site already passed one; making it required is what
 * stops the next one from forgetting. Use [AppExtendedFab] when the action needs a visible
 * label, and the label becomes the accessible name.
 *
 * @param onClick            invoked on tap.
 * @param icon               the action's glyph.
 * @param contentDescription the button's accessible name — what the action *does*
 *                           ("Add a contact"), not what the icon looks like.
 */
@Composable
fun AppFab(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    FloatingActionButton(modifier = modifier, onClick = onClick) {
        Icon(imageVector = icon, contentDescription = contentDescription)
    }
}

/**
 * A FAB that carries its label (Material's extended FAB).
 *
 * ### When to use
 *
 * When the action's glyph is not self-evident — Home's *Add device* and *Add automation* both
 * use a bare `+`, which says nothing about what is being added on a screen that already lists
 * several kinds of thing. Prefer [AppFab] where the icon speaks for itself; an extended FAB
 * takes noticeably more of the content it floats over.
 *
 * ⚠ The icon's `contentDescription` is `null` **by construction**: [label] is the accessible
 * name, and describing the icon as well would make a screen reader announce the action twice.
 * That is the opposite of [AppFab]'s rule, and it is why the two are separate functions rather
 * than one with a nullable label.
 */
@Composable
fun AppExtendedFab(
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = { Icon(imageVector = icon, contentDescription = null) },
        text = { Text(label) },
        modifier = modifier,
    )
}

/**
 * The design system's inset between a hand-placed FAB and the content edge.
 *
 * Only for the `Box`-aligned shape — a `Scaffold` applies its own inset, and adding this on top
 * would double it. It exists because the four hand-placed call sites had drifted into writing
 * the value two ways (`MaterialTheme.spacing.l` in Drive and Contacts, a raw `16.dp` in Home's
 * two screens): the same number today, and two things to keep in step forever otherwise.
 */
@Composable
fun Modifier.appFabInset(): Modifier = padding(MaterialTheme.spacing.l)

@Preview
@Composable
private fun AppFabPreview() = PreviewBox {
    Box(Modifier.fillMaxSize()) {
        AppFab(
            onClick = {},
            icon = Icons.Default.PersonAdd,
            contentDescription = "Invite someone",
            modifier = Modifier.align(Alignment.BottomEnd).appFabInset(),
        )
    }
}

@Preview
@Composable
private fun AppExtendedFabPreview() = PreviewBox {
    Box(Modifier.fillMaxSize()) {
        AppExtendedFab(
            onClick = {},
            icon = Icons.Default.Add,
            label = "Add device",
            modifier = Modifier.align(Alignment.BottomEnd).appFabInset(),
        )
    }
}

@Preview
@Composable
private fun AppFabDarkPreview() = PreviewBox(useDarkTheme = true) {
    Box(Modifier.fillMaxSize().padding(8.dp)) {
        AppFab(
            onClick = {},
            icon = Icons.Default.Add,
            contentDescription = "Add",
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }
}
