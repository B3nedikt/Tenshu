package app.tenshu.mia.ui.screens.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tenshu.mia.data.MiaCapabilities
import app.tenshu.mia.model.Hit
import app.tenshu.mia.model.MessageBody
import app.tenshu.mia.model.MiaMessage
import app.tenshu.mia.shared.resources.Res
import app.tenshu.mia.shared.resources.mia_chat_greeting_body
import app.tenshu.mia.shared.resources.mia_chat_greeting_title
import app.tenshu.mia.shared.resources.mia_chat_input_hint
import app.tenshu.mia.shared.resources.mia_chat_send
import app.tenshu.mia.shared.resources.mia_chat_typing
import app.tenshu.mia.shared.resources.mia_chat_listening
import app.tenshu.mia.shared.resources.mia_chat_voice
import app.tenshu.mia.shared.resources.mia_chat_voice_stop
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.getKoin

/**
 * The chat surface (docs/proposals/home-and-mia-apps.md §4, M3) — used identically by the
 * Mia app's Chat tab and Home's embedded "Ask Mia" destination (D1). Text-first: the mic
 * affordance arrives with the voice pipeline, behind a capability flag (D4).
 *
 * Rich results render as dedicated cells ([MessageBody.PhotoGrid] / [PlaceCard] /
 * [DeviceAck]) so the realm-data assistant story is visible from the first mock reply.
 */
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    // D4 capability flag (audited M5): the mic renders only where a platform binds
    // voiceInput = true — constant false until the voice pipeline lands.
    val koin = getKoin()
    val capabilities = remember(koin) { koin.getOrNull<MiaCapabilities>() ?: MiaCapabilities() }

    // Keep the newest message in view as the thread grows.
    LaunchedEffect(state.messages.size, state.replying) {
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    // ⚠⚠ **`imePadding` is what makes the answer visible while the keyboard is open**, and its
    // absence is §14.3: the window panned the whole column up by the keyboard's height, so the
    // reply to the question you just typed sat off the top of the screen until you dismissed the
    // IME. ⚠ The auto-scroll above is not the fix and never was — with a short thread the list is
    // smaller than its viewport, so there is nothing to scroll; the column was simply displaced by
    // an inset nothing consumed.
    //
    // ⚠ First use of this modifier in the repo: Mia's chat is the platform's first screen with a
    // text field parked at the bottom of a full-height layout. Any surface that grows one owes the
    // same modifier — a dialog does not, because the system centres it above the IME.
    Column(Modifier.fillMaxSize().imePadding()) {
        if (state.messages.isEmpty()) {
            Greeting(Modifier.weight(1f))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.messages, key = { it.id }) { message ->
                    MessageRow(
                        message,
                        onConfirm = viewModel::confirm,
                        onOpen = viewModel::openHit,
                    )
                }
                if (state.replying) {
                    item(key = "typing") {
                        Text(
                            stringResource(Res.string.mia_chat_typing),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.input,
                onValueChange = viewModel::setInput,
                placeholder = {
                    Text(
                        stringResource(
                            if (state.listening) Res.string.mia_chat_listening
                            else Res.string.mia_chat_input_hint,
                        ),
                    )
                },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            // AV-1: shown only where a platform bound an ON-DEVICE recogniser (IN-D11). The
            // capability flag and the binding move together, so a mic can never appear without
            // something behind it.
            if (capabilities.voiceInput && viewModel.canListen) {
                IconButton(onClick = viewModel::toggleListening) {
                    Icon(
                        if (state.listening) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = stringResource(
                            if (state.listening) Res.string.mia_chat_voice_stop
                            else Res.string.mia_chat_voice,
                        ),
                        tint = if (state.listening) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    )
                }
            }
            IconButton(onClick = viewModel::send, enabled = state.canSend) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(Res.string.mia_chat_send),
                    tint = if (state.canSend) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Greeting(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(Res.string.mia_chat_greeting_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(Res.string.mia_chat_greeting_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** One message: user bubbles trail, Mia's lead; rich bodies get their own cells. */
@Composable
fun MessageRow(
    message: MiaMessage,
    onConfirm: (String) -> Unit = {},
    onOpen: (Hit) -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(Modifier.widthIn(max = 320.dp)) {
            when (val body = message.body) {
                is MessageBody.Text -> TextBubble(body.text, message.fromUser)
                is MessageBody.PhotoGrid -> PhotoGridCell(body)
                is MessageBody.PlaceCard -> PlaceCardCell(body)
                is MessageBody.DeviceAck -> DeviceAckCell(body)
                is MessageBody.Citations -> CitationsCell(body, onOpen)
                is MessageBody.Confirmation -> ConfirmationCell(body, onConfirm)
            }
        }
    }
}

/**
 * MQ-D7's cell: what will happen, and a button that is the only thing that makes it happen.
 *
 * ⚠⚠ Shows the **resolved** action — the device Mia actually found. Rendering the utterance
 * and letting the resolution land unseen is the failure this exists to prevent, and it matters
 * far more once voice lands: a spoken interface has no undo and no hover state.
 */
@Composable
private fun ConfirmationCell(body: MessageBody.Confirmation, onConfirm: (String) -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(body.title, style = MaterialTheme.typography.bodyLarge)
            if (body.detail.isNotEmpty()) {
                Text(
                    body.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = { onConfirm(body.token) },
                // ⚠ Spent confirmations cannot be re-tapped. The proxy refuses a used token
                // too, so this is politeness rather than the control.
                enabled = !body.done,
            ) {
                Text(if (body.done) "Done" else "Confirm")
            }
        }
    }
}

@Composable
private fun TextBubble(text: String, fromUser: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (fromUser) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/** Mock photo grid: labelled placeholder tiles until real Drive thumbnails ride here. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhotoGridCell(body: MessageBody.PhotoGrid) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(body.caption, style = MaterialTheme.typography.bodyMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                body.photoLabels.forEach { label ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                Icons.Default.Image,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceCardCell(body: MessageBody.PlaceCard) {
    Card {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Place,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    body.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    body.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Mia's citations (`mia-query-layer.md` MQ-D1): real rows, not prose.
 *
 * ⚠ The caption is the planner's *explanation* — what was actually searched — so the user
 * can check the question Mia answered against the one they asked. It is the visible half
 * of MQ-D3's explainability argument, and dropping it would leave an answer with no way
 * to tell a right result from a right-looking one.
 */
@Composable
private fun CitationsCell(body: MessageBody.Citations, onOpen: (Hit) -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                body.caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            body.hits.forEach { hit ->
                Row(
                    // ⭐ TT-1c: the citation stops being inert. ⚠⚠ §14.7's device pass measured
                    // `clickable="false"` on exactly these rows and named it the thing to fix —
                    // so the accessibility tree is where the guard lives, not a screenshot.
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(hit) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        glyphFor(hit.typeId),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(hit.title, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            hit.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The glyph for a cited row, by the owning schema's type id.
 *
 * ⚠ Until the calendar read landed the cell drew a **file icon for everything**, so a place, a
 * workout and a device all cited as documents. It became worth fixing at the point an *appointment*
 * would have.
 *
 * ⚠⚠ **Switches on `DataId.type` since TT-1c**, not on a `kind` string Mia used to mint. Same six
 * outcomes, one fewer vocabulary — and the constants now come from the services that own the rows,
 * so a type that is renamed cannot leave this switch quietly matching nothing.
 *
 * ⚠ The default is the file glyph rather than a question mark: a kind this build does not know is a
 * newer proxy citing a source that did not exist when this app was built, and a neutral document is
 * a better guess than an error icon on a row that is otherwise perfectly readable.
 */
private fun glyphFor(typeId: String): ImageVector = when (typeId) {
    TYPE_PLACE -> Icons.Default.Place
    TYPE_MOMENT -> Icons.Default.Timeline
    TYPE_WORKOUT -> Icons.Default.DirectionsRun
    TYPE_DEVICE -> Icons.Default.Lightbulb
    TYPE_EVENT_OCCURRENCE -> Icons.Default.Event
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

// ⚠⚠ **Literals, not the owning services' `ID` constants, and that is MQ-D2 not laziness.** Mia's
// client depends on `:core-common` and `:mia-service-*` and nothing else — the cross-service reads
// all happen on the PROXY — so importing `Place.ID` here would drag five feature services into the
// app whose whole design is not to have them. (They resolve today only by transitive leak, which
// is worse than a literal: it is a dependency nobody declared and nobody can see.)
//
// ⭐ Duplicating a type id for **presentation** is the sanctioned shape — `core-realm-data.md`'s
// rule is that presentation lives in each consumer's registry, never on the type — and
// `HandlerTable.OCCURRENCE_TYPE` is the same duplication one layer down, for the same reason.
// ⚠ A stale spelling here costs a generic document icon on an otherwise correct row, which is
// exactly the failure the `else` branch is already designed to absorb.
private const val TYPE_PLACE = "tenshu/place/place"
private const val TYPE_MOMENT = "tenshu/timeline/moment"
private const val TYPE_WORKOUT = "tenshu/health/workout"
private const val TYPE_DEVICE = "tenshu/home/device/homeDevice"
private const val TYPE_EVENT_OCCURRENCE = "tenshu/calendar/eventOccurrence"

@Composable
private fun DeviceAckCell(body: MessageBody.DeviceAck) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(body.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
