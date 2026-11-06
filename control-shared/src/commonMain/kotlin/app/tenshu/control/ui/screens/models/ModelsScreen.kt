package app.tenshu.control.ui.screens.models

import app.tenshu.control.inference.HeldModel
import app.tenshu.control.inference.IndexCoverage
import app.tenshu.control.inference.ModelRow
import app.tenshu.control.inference.ModelSetup
import app.tenshu.control.inference.ModelsAvailability
import app.tenshu.control.inference.OfferedModel
import app.tenshu.control.inference.download
import app.tenshu.control.inference.offersDownload
import app.tenshu.control.inference.offersRemoval
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import app.tenshu.control.ui.faceClusteringRunsHere
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tenshu.control.ui.screens.offlinemaps.SectionHeader
import app.tenshu.control.ui.screens.offlinemaps.formatBytes
import app.tenshu.ui.chrome.AppListItem
import app.tenshu.ui.chrome.AppTopAppBar
import app.tenshu.ui.layout.appContentWidth
import org.tenshu.inference.InferenceCapability

/**
 * Control → Settings → **Models** (IN-D10). The canonical management surface for on-device
 * inference models — shared by Photos/Mia, so it lives in Control (the store owner), not in any one
 * app. The same shape as Offline maps, and for the same reasons.
 *
 * ⚠⚠ **This is the only route by which a model reaches a device.**
 *
 * ⚠ Strings are inline, matching the Offline maps and Errors entries — this expert surface is not
 * localised. ⚠ `formatBytes` is reused from the Offline maps screen rather than
 * copied: same module, and it already has tests.
 */
@Composable
fun ModelsScreen(
    viewModel: ModelsViewModel,
    onOpenFeature: (InferenceCapability) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ModelsScreenContent(
        state = state,
        onDownload = { viewModel.download(it) },
        onDownloadOverCellular = { viewModel.download(it, allowsCellular = true) },
        onCancel = viewModel::cancel,
        onConfirmDelete = viewModel::confirmDelete,
        onDelete = viewModel::delete,
        onDismissDelete = viewModel::dismissDelete,
        onRetry = viewModel::retry,
        onMessageShown = viewModel::consumeMessage,
        onSetIndexingPaused = viewModel::setIndexingPaused,
        onIndexNow = viewModel::indexNow,
        onOpenFeature = onOpenFeature,
        onAcceptFeatureOffer = viewModel::acceptFeatureOffer,
        onDismissFeatureOffer = viewModel::dismissFeatureOffer,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreenContent(
    state: ModelsUiState,
    onDownload: (String) -> Unit,
    onDownloadOverCellular: (String) -> Unit = {},
    onCancel: (String) -> Unit = {},
    onConfirmDelete: (String) -> Unit = {},
    onDelete: () -> Unit = {},
    onDismissDelete: () -> Unit = {},
    onRetry: () -> Unit = {},
    onMessageShown: () -> Unit = {},
    onSetIndexingPaused: (Boolean) -> Unit = {},
    onIndexNow: () -> Unit = {},
    onOpenFeature: (InferenceCapability) -> Unit = {},
    onAcceptFeatureOffer: () -> Unit = {},
    onDismissFeatureOffer: () -> Unit = {},
    onBack: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    // ⚠ Keyed on the store's state too: the sentence for a store's refusal is that state's, and
    // it can arrive a moment after the message.
    LaunchedEffect(state.message, state.storeReadOnly) {
        val text = when (state.message) {
            ModelsMessage.DownloadFailed ->
                "Couldn't download that model. Check your connection and try again."
            // ⚠⚠ Deliberately does NOT say "try again": the bytes did not match the signed catalog,
            // and retrying fetches the same wrong bytes from the same place.
            ModelsMessage.IntegrityFailed ->
                "That download didn't match its signature and was discarded. Nothing was installed."
            ModelsMessage.UnknownModel ->
                "That model is no longer published."
            ModelsMessage.CatalogUnavailable ->
                "This device can't reach the model list right now."
            // ⚠⚠ No "try again": it cannot work while this Control runs.
            ModelsMessage.StoreReadOnly -> state.storeReadOnly?.let(::storeReadOnlyNotice) ?: STORE_REFUSED
            ModelsMessage.RemovedSpacePending -> removedText(state.capabilities.spaceReturns)
            ModelsMessage.UpdatedSpacePending -> updatedText(state.capabilities.spaceReturns)
            ModelsMessage.DeleteFailed ->
                "Couldn't remove that model. Try again."
            ModelsMessage.NotConnected -> NOT_CONNECTED
            ModelsMessage.NotEnoughSpace ->
                "There isn't enough free space on this device for that model."
            ModelsMessage.DownloadCouldNotStart ->
                "Couldn't start that download just now. Try again."
            ModelsMessage.OtherCellularChoice ->
                "That download is already running with the other mobile data choice. Stop it to choose again."
            ModelsMessage.CancelFailed ->
                "Couldn't stop that download. Try again."
            ModelsMessage.StillRemoving ->
                "Still removing that model. It's taking longer than usual."
            ModelsMessage.ModelBusy ->
                "Not now: a model is being downloaded or removed. Try again when it has finished."
            ModelsMessage.ConsentNotSaved ->
                "Couldn't save that on this device yet. Try again once it has finished setting up."
            // §34 — each refusal says the one thing that would change it.
            ModelsMessage.IndexingStarted ->
                "Indexing your photos. It keeps going in the background."
            ModelsMessage.IndexNoModel ->
                "No model on this device can index photos yet — download one below."
            ModelsMessage.OtherDeviceConsentNotSaved -> OTHER_DEVICE_NOT_SAVED
            ModelsMessage.IndexNoConsent ->
                "Nothing is switched on for this device. Pick a feature under Features first."
            ModelsMessage.IndexPaused ->
                "Indexing is paused on this device. Resume it first."
            ModelsMessage.IndexNotReady ->
                "Not now — this device's battery is too low to index photos."
            ModelsMessage.IndexFailed ->
                "Couldn't start indexing. Try again."
            null -> null
        }
        if (text != null) {
            snackbarHostState.showSnackbar(text)
            onMessageShown()
        }
    }

    // ⚠ A dialog rather than an instant delete: removing is instant and the undo is a ~383 MB
    // re-download — and IN-D10's "say which one the action does" needs somewhere to be said.
    state.pendingDeletion?.let { model ->
        AlertDialog(
            onDismissRequest = onDismissDelete,
            title = { Text("Remove ${model.name}?") },
            text = {
                Text(removeConfirmationText(model, state.capabilities.spaceReturns))
            },
            confirmButton = { TextButton(onClick = onDelete) { Text("Remove") } },
            dismissButton = { TextButton(onClick = onDismissDelete) { Text("Keep") } },
        )
    }

    // §38.3 — asked once, when a model lands for a feature that is off here. ⚠ Never for a feature
    // this build shows no row for: the answer would have nowhere to be changed.
    val consents = visibleConsents(state.consents)
    state.featureOffer?.takeIf { offer -> consents.any { it.capability == offer } }?.let { offer ->
        AlertDialog(
            onDismissRequest = onDismissFeatureOffer,
            title = { Text("Turn on \"${consentHeadline(offer)}\" on this device?") },
            text = { Text(featureDescription(offer)) },
            confirmButton = { TextButton(onClick = onAcceptFeatureOffer) { Text("Turn on") } },
            dismissButton = { TextButton(onClick = onDismissFeatureOffer) { Text("Not now") } },
        )
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = { AppTopAppBar(title = "Models", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            // ⚠⚠ ONE `LazyColumn` for the whole body, never a fixed header above a list: the switches
            // and the indexing row are taller than a phone can spare, and a list given only the
            // height left over can shrink to nothing — the download buttons then cannot be reached.
            LazyColumn(Modifier.appContentWidth().fillMaxHeight(), state = listState) {
                // ⚠ Every case is named, here and below: a state added to the list is a compile
                // error on this screen, never a row drawn by a default.
                val availability = state.availability
                when (availability) {
                    ModelsAvailability.Loading -> {
                        item(key = ModelsScreenItem.Status) { Message("Loading…") }
                        return@LazyColumn
                    }
                    // ⚠ Nothing else: no model, no indexing and no switch that would change nothing.
                    is ModelsAvailability.NotOffered -> {
                        item(key = ModelsScreenItem.Status) { Message(notOfferedText(availability.why)) }
                        return@LazyColumn
                    }
                    ModelsAvailability.NotConnected,
                    ModelsAvailability.InstalledUnreadable,
                    is ModelsAvailability.Listed -> Unit
                }

                // ⚠ First: downloading a model is what everyone who opens this screen came to do.
                item(key = ModelsScreenItem.ModelsHeader) { SectionHeader("Models") }

                // ⚠⚠ A standing state, so it is said here, before anyone taps, and the download
                // and remove buttons go with it — not after a tap has failed.
                state.storeReadOnly?.let { why ->
                    item(key = ModelsScreenItem.StoreReadOnly) { Message(storeReadOnlyNotice(why)) }
                }

                val retry = onRetry.takeUnless { state.checkingAgain }
                when (availability) {
                    ModelsAvailability.Loading, is ModelsAvailability.NotOffered -> Unit
                    // ⚠⚠ A read that failed: its sentence and the retry, and **no row**. Nothing
                    // is shown as installed or as absent on the strength of a read that did not
                    // answer, and nothing that writes is offered over it.
                    ModelsAvailability.NotConnected ->
                        item(key = ModelsScreenItem.Status) { NoticeWithRetry(NOT_CONNECTED, retry) }
                    ModelsAvailability.InstalledUnreadable ->
                        item(key = ModelsScreenItem.Status) { NoticeWithRetry(INSTALLED_UNREADABLE, retry) }
                    is ModelsAvailability.Listed -> {
                        when (availability.catalog) {
                            // ⚠⚠ Never "no models are available": with no catalog we do not know
                            // what exists. The models below are what this device already holds —
                            // the ones that still work, precisely because nothing is needed from
                            // the network.
                            ModelsAvailability.Catalog.UNAVAILABLE ->
                                item(key = ModelsScreenItem.Status) { NoticeWithRetry(CATALOG_UNAVAILABLE, retry) }
                            ModelsAvailability.Catalog.STALE ->
                                item(key = ModelsScreenItem.Status) { NoticeWithRetry(CATALOG_STALE, retry) }
                            ModelsAvailability.Catalog.READ -> if (availability.models.isEmpty()) {
                                item(key = ModelsScreenItem.Status) {
                                    Message("No models are available for this device yet.")
                                }
                            }
                            ModelsAvailability.Catalog.NOT_READ_YET -> if (availability.models.isEmpty()) {
                                item(key = ModelsScreenItem.Status) { Message("Loading…") }
                            }
                        }
                        when (availability.downloads) {
                            ModelsAvailability.Downloads.KNOWN -> Unit
                            ModelsAvailability.Downloads.UNREADABLE ->
                                item(key = ModelsScreenItem.Downloads) { NoticeWithRetry(DOWNLOADS_UNREADABLE, retry) }
                        }
                        modelRows(state, availability.models, onDownload, onDownloadOverCellular, onCancel, onConfirmDelete)
                    }
                }

                // IN-D6's *visible and pausable*, and it lives HERE rather than on a screen of its
                // own: indexing is what an installed model does, IN-D10 wants a capability's
                // controls beside the model rather than a navigation hop away, and a dedicated
                // destination for one line and one button would be empty of meaning on a device
                // with no model.
                if (state.capabilities.indexing) {
                    item(key = ModelsScreenItem.Divider) { HorizontalDivider() }
                    item(key = ModelsScreenItem.IndexingHeader) { SectionHeader("Indexing") }
                    item(key = ModelsScreenItem.Indexing) {
                        IndexingSection(
                            state = state,
                            onSetIndexingPaused = onSetIndexingPaused,
                            onIndexNow = onIndexNow,
                            // ⚠ The Features rows are the last items, so the end of the list is them.
                            onShowFeatures = {
                                scope.launch {
                                    listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
                                }
                            },
                        )
                    }
                }

                // ⚠ Last (§38.1): which devices do the work is a setting most people answer once,
                // from the question a download asks, and come back to rarely. IN-D10 still holds —
                // it is on this screen, beside the models, not behind another settings entry.
                if (consents.isNotEmpty()) {
                    item(key = ModelsScreenItem.FeaturesDivider) { HorizontalDivider() }
                    item(key = ModelsScreenItem.FeaturesHeader) { SectionHeader("Features") }
                    item(key = ModelsScreenItem.Consents) {
                        // ⚠ Counted only where they can be listed: a summary must not count devices
                        // the feature screen behind it does not show.
                        FeatureRows(consents, state.otherDevices.takeIf { state.capabilities.otherDevices }.orEmpty(), onOpenFeature)
                    }
                }
            }
        }
    }
}

/**
 * The keys of the screen's non-model items.
 *
 * ⚠ An enum rather than strings: model rows are keyed by `modelId`, a published string this screen
 * does not control, and a key of another type cannot equal one.
 */
private enum class ModelsScreenItem {
    FeaturesDivider, FeaturesHeader, Consents, IndexingHeader, Indexing, Divider, ModelsHeader, StoreReadOnly, Status,
    Downloads,
}

/**
 * A read that failed, with the one thing that can change it. ⚠ [onRetry] is `null` while a
 * retry is already running: the button stays, disabled, so a press visibly did something.
 */
@Composable
private fun NoticeWithRetry(text: String, onRetry: (() -> Unit)?) {
    Surface(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onRetry?.invoke() }, enabled = onRetry != null) { Text("Try again") }
        }
    }
}

internal const val CATALOG_UNAVAILABLE = "Can't reach the model list. Showing what's already on this device."

/** ⚠ Says the list is the last one read: an update it does not show may exist. */
internal const val CATALOG_STALE = "Couldn't refresh the model list. Showing it as it was last read."

/** ⚠ Never "nothing is installed": what is installed is exactly what could not be read. */
internal const val INSTALLED_UNREADABLE = "Couldn't read what's installed on this device."

internal const val NOT_CONNECTED = "Can't reach this device's models right now."

/** A store that refused a download, where nothing says which refusal it was. */
internal const val STORE_REFUSED = "The download couldn't be saved on this device."

internal const val DOWNLOADS_UNREADABLE = "Couldn't read the downloads in progress."

/** Why this device is offered no model at all. */
internal fun notOfferedText(why: ModelsAvailability.NotOffered.Why): String = when (why) {
    ModelsAvailability.NotOffered.Why.NOT_IN_THIS_BUILD -> "On-device models aren't part of this version."
    ModelsAvailability.NotOffered.Why.OS_TOO_OLD -> "On-device models need a newer system version than this device has."
    ModelsAvailability.NotOffered.Why.NOT_ENOUGH_MEMORY -> "This device doesn't have enough memory for on-device models."
}

/**
 * One row per feature, each opening its own screen (§38.1).
 *
 * ⚠⚠ No switch here. IN-D3's two answers, as two switches per feature on one screen, are easily
 * mistaken for each other; the feature screen says which device each answer belongs to.
 */
@Composable
private fun FeatureRows(
    consents: List<CapabilityConsent>,
    otherDevices: Map<InferenceCapability, List<OtherDeviceConsent>>,
    onOpenFeature: (InferenceCapability) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        consents.forEach { consent ->
            AppListItem(
                headline = consentHeadline(consent.capability),
                supportingText = featureSummary(consent, otherDevices[consent.capability].orEmpty()),
                leadingContent = { Icon(consentIcon(consent.capability), contentDescription = null) },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                onClick = { onOpenFeature(consent.capability) },
            )
        }
    }
}

/**
 * Where a feature stands, in one line: this device first, then how many others.
 *
 * ⚠⚠ Counted only under a realm yes — a device answer does nothing without it (§9.2). ⚠ A yes that
 * is still stored under a realm no is said, because it resumes when the feature is turned back on.
 */
internal fun featureSummary(consent: CapabilityConsent, others: List<OtherDeviceConsent>): String {
    val othersAllowed = others.count { it.allowed }
    if (!consent.realm) {
        val lingering = othersAllowed + if (consent.device) 1 else 0
        return if (lingering == 0) "Off" else "Off. ${devices(lingering)} would start again if it's turned back on."
    }
    return when {
        consent.device && othersAllowed == 0 -> "On for this device"
        consent.device -> "On for this device and ${devices(othersAllowed, other = true)}"
        othersAllowed == 0 -> "Off on every device"
        else -> "On for ${devices(othersAllowed, other = true)}, not this one"
    }
}

private fun devices(count: Int, other: Boolean = false): String {
    val noun = if (count == 1) "device" else "devices"
    return if (other) "$count other $noun" else "$count $noun"
}

/**
 * The switches this build shows.
 *
 * ⚠⚠ A switch that changes nothing is a claim the product cannot keep, so `FACE_CLUSTERING` appears
 * only where [faceClusteringRunsHere] — a build fact, deliberately not inferred from the catalog,
 * which cannot tell "this platform cannot" from "the catalog is unreachable".
 *
 * ⚠ Search is always shown. ⚠⚠ And a switch that is **on** is
 * never hidden, for [showsDeviceRow]'s reason: a live consent must stay visible and withdrawable.
 */
internal fun visibleConsents(
    consents: List<CapabilityConsent>,
    runsFaces: Boolean = faceClusteringRunsHere,
): List<CapabilityConsent> =
    consents.filter { consent ->
        consent.capability != InferenceCapability.FACE_CLUSTERING ||
            runsFaces || consent.realm || consent.device
    }

/**
 * This device's line on the feature screen.
 *
 * ⚠⚠ A yes this device still holds under a realm no says what it will do, so it can be refused: that
 * state is ordinary (a failed clear leaves one) and it resumes the moment the feature is turned back
 * on from any device.
 */
internal fun thisDeviceSupportingText(consent: CapabilityConsent): String = when {
    !consent.realm && consent.device ->
        "Still allowed here. If it's turned back on from another device, this one starts indexing again."
    consent.realm && consent.device -> "On. Indexes while this device is idle and charging."
    else -> "Uses this device's battery and storage to index photos here."
}

/**
 * Another device's line. ⚠⚠ An off one says where to turn it on: its switch here cannot (§38.2).
 */
internal fun otherDeviceSupportingText(realm: Boolean, other: OtherDeviceConsent): String = when {
    !other.allowed -> "Off. Turn it on from that device."
    realm -> "On"
    else -> "Still allowed there. It starts again if this is turned back on."
}

/**
 * What the feature is called — ⚠ named for what the user gets, never for the model behind it.
 * ⚠ Photos and Mia send people here by these names; change them together.
 */
internal fun consentHeadline(capability: InferenceCapability): String = when (capability) {
    InferenceCapability.SEMANTIC_IMAGE_SEARCH -> "Search photos by description"
    InferenceCapability.FACE_CLUSTERING -> "Group photos by people"
    else -> capability.id
}

internal fun consentIcon(capability: InferenceCapability): ImageVector = when (capability) {
    InferenceCapability.FACE_CLUSTERING -> Icons.Default.Face
    else -> Icons.Default.ImageSearch
}

/**
 * What a feature does and what it costs — the feature screen's opening text, and the whole of the
 * question a download asks.
 *
 * ⚠⚠ **Where the work happens and where the result lives are said before the user agrees** — IN-D3
 * asks each switch to say what it does and what it costs first, not after.
 */
internal fun featureDescription(capability: InferenceCapability): String = when (capability) {
    InferenceCapability.SEMANTIC_IMAGE_SEARCH ->
        "Find pictures in Photos and Mia by what's in them. Each device you allow indexes its " +
            "own photos, and the index never leaves that device."
    // ⚠⚠ Biometric data (A40), so the copy says all three things before the user agrees:
    // where the face data lives, that only typed names travel, and what switching off removes.
    InferenceCapability.FACE_CLUSTERING ->
        "Find faces in your photos and group them so you can name people. Face data is made on " +
            "each device you allow and never leaves it. Only the names you type sync to your " +
            "devices. Turning this off deletes the face data and keeps the names."
    else -> ""
}

/** Said when another device's answer could not be switched off from here. */
internal const val OTHER_DEVICE_NOT_SAVED = "Couldn't turn that off for the other device. Try again."

/**
 * IN-D6's progress and pause control.
 *
 * ⚠⚠ A **Pause / Resume button, not a switch**, and that is not a style choice. IN-D3's consent
 * switches are the other control on these screens, and a toggle reading "Index photos on this
 * device" is indistinguishable from one. "Pause" says *not right now*; withdrawing consent says
 * *not at all*, and IN-D5 implies the derived rows would then go.
 *
 * ⚠ The bar is drawn only for a pass with something in it: a determinate bar over `total = 0` is
 * either a division by zero or a full bar, and both are worse than no bar.
 */
@Composable
private fun IndexingSection(
    state: ModelsUiState,
    onSetIndexingPaused: (Boolean) -> Unit,
    onIndexNow: () -> Unit,
    onShowFeatures: () -> Unit = {},
) {
    val progress = state.indexProgress
    val text = indexingSupportingText(
        progress = progress,
        paused = state.indexingPaused,
        anyModelInstalled = state.anyModelUsable,
        anyAllowedHere = state.consents.any { it.realm && it.device },
        coverage = state.coverage,
    )
    Column(Modifier.fillMaxWidth()) {
        AppListItem(
            headline = "Indexing photos",
            supportingText = text,
            leadingContent = { Icon(Icons.Default.Sync, contentDescription = null) },
            // ⚠ The one line that names a section further down takes you there (§38.3).
            onClick = onShowFeatures.takeIf { text == NOTHING_SWITCHED_ON },
        )
        // ⚠ On a row of their own, under the text and in line with it: beside it, two buttons leave
        // a phone's headline and status a column a few words wide.
        Row(Modifier.fillMaxWidth().padding(start = 44.dp, end = 16.dp)) {
            // ⚠⚠ Absent while paused, never merely disabled: the answer to a paused device is
            // Resume, and two controls where one contradicts the other is the screen arguing
            // with itself (the §25 call).
            if (!state.indexingPaused) {
                TextButton(
                    onClick = onIndexNow,
                    // ⚠ While a pass runs the control is Pause, not a second start: the op
                    // would join the running pass and say "started", which reads as a no-op.
                    enabled = !state.startingIndex && !state.indexProgress.indexing,
                ) {
                    Text("Index now")
                }
            }
            TextButton(onClick = { onSetIndexingPaused(!state.indexingPaused) }) {
                Text(if (state.indexingPaused) "Resume" else "Pause")
            }
        }
        indexBarFraction(progress)?.let { fraction ->
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
    }
}

/**
 * How full the indexing bar is, or `null` when there is no honest bar to draw.
 *
 * ⚠⚠ **A pass over an empty library draws nothing.** `total = 0` is a real state — the walk found
 * no images — and it is the one input for which a fraction is either a division by zero or, once
 * guarded to 1f, a full bar over work that never happened.
 *
 * ⚠ Nothing is drawn when no pass is running either: a bar pinned at 0 reads as a *stalled* pass
 * rather than an absent one, the same call §25.4 makes for the download row.
 *
 * ⚠ Clamped, because [IndexProgressState.done] and `total` come from separate ticks of a live
 * `StateFlow` and a conflated pair can briefly disagree.
 */
internal fun indexBarFraction(progress: IndexProgressState): Float? = when {
    !progress.indexing -> null
    progress.total <= 0 -> null
    else -> (progress.done.toFloat() / progress.total.toFloat()).coerceIn(0f, 1f)
}

/**
 * The indexing section's second line.
 *
 * ⚠⚠ **When it runs is said even when nothing is running**, because that sentence is the whole
 * point of IN-D6: a multi-hour pass over every photograph the user owns, happening silently, is
 * *"the kind of thing this product exists to not do"*. A screen that only spoke while a pass was in
 * flight would be silent on every device that is behaving normally.
 *
 * ⚠ A paused device says so first: it is the state the user put it in, and it outranks a report
 * about a pass that consequently is not running.
 *
 * ⚠ "No model installed" is not the same as "not indexing": with no model every pass is a counted
 * no-op, and telling a user their photos are queued for indexing that cannot happen is a lie the
 * screen must never tell.
 *
 * ⚠⚠ And neither is "nothing switched on here". Consent is read per image, so with no switch on for
 * this device every pass skips every photo — "Runs when idle and charging" above a switch that is
 * off describes work that will never start.
 */
internal fun indexingSupportingText(
    progress: IndexProgressState,
    paused: Boolean,
    anyModelInstalled: Boolean?,
    anyAllowedHere: Boolean,
    coverage: IndexCoverage? = null,
): String = when {
    paused -> "Paused on this device."
    progress.indexing -> "${progress.done} of ${progress.total} photos"
    // ⚠⚠ Not known is not none: over a read that failed, "no model installed" would be a claim
    // about exactly what could not be read.
    anyModelInstalled == null -> INSTALLED_NOT_KNOWN
    !anyModelInstalled -> "No model installed, so nothing is indexed yet."
    !anyAllowedHere -> NOTHING_SWITCHED_ON
    // ⚠⚠ Said, not implied: a photo stored only on the relay cannot be indexed here, and without
    // this line People and search simply look incomplete, with no explanation.
    coverage != null && coverage.notOnDevice > 0 ->
        // ⚠ "can be indexed", not "indexed": the count is reach, not success.
        "${coverage.reachable} of ${coverage.total} photos can be indexed on this device. " +
            "${coverage.notOnDevice} aren't stored here, so they can't be searched or grouped."
    else -> "Runs when this device is idle and charging."
}

internal const val INSTALLED_NOT_KNOWN = "Can't tell whether a model is installed right now."

/** ⚠ Names the section, because that section is the last thing on the screen (§38.1). */
internal const val NOTHING_SWITCHED_ON =
    "Nothing is switched on for this device, so nothing is indexed. Pick a feature under Features."

@Composable
private fun Message(text: String) {
    Surface(Modifier.fillMaxWidth()) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

private fun LazyListScope.modelRows(
    state: ModelsUiState,
    rows: List<ModelRow>,
    onDownload: (String) -> Unit,
    onDownloadOverCellular: (String) -> Unit,
    onCancel: (String) -> Unit,
    onConfirmDelete: (String) -> Unit,
) {
    items(rows, key = { it.modelId }) { row ->
        val removing = row.modelId in state.removing
        val starting = row.modelId in state.starting
        // ⚠ The repository's, never this screen's: a bar is drawn because the row says a download
        // runs, so it is there for a download this screen did not start.
        val download = row.setup.download
        AppListItem(
            headline = row.name,
            supportingText = supportingTextFor(row.setup, removing = removing, starting = starting) +
                // ⚠ A button that cannot be pressed says why: nothing else on the screen would.
                // ⚠ Not over a store that cannot be changed: the other download ending frees nothing here.
                if (waitsForADownload(state, row, removing = removing, starting = starting)) " · $WAITS_FOR_A_DOWNLOAD" else "",
            leadingContent = { Icon(Icons.Default.Memory, contentDescription = null) },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val fraction = download?.let(::downloadFraction)
                    when {
                        // ⚠⚠ Determinate ONLY while bytes are actually reported. A removal
                        // has no byte count, and neither does the window before the first tick —
                        // a bar pinned at 0 there would read as a stalled download rather than as
                        // a starting one, so the spinner is the honest shape until bytes exist.
                        fraction != null && !removing ->
                            CircularProgressIndicator(progress = { fraction }, modifier = Modifier.size(24.dp))
                        removing || starting || download != null -> CircularProgressIndicator(Modifier.size(24.dp))
                    }
                    // ⚠ Nothing below is drawn for an action the repository does not have, and
                    // nothing for one this row could not take: no dead buttons.
                    if (removing || starting) return@Row
                    val enabled = rowActionsEnabled(state, row.modelId)
                    if (download != null && state.capabilities.cancel) {
                        IconButton(onClick = { onCancel(row.modelId) }) {
                            Icon(Icons.Default.Close, contentDescription = "Stop downloading ${row.name}")
                        }
                    }
                    if (row.setup.offersDownload) {
                        // ⚠ Not offered beside a running download where the repository runs one
                        // at a time: the press would be refused.
                        val downloadEnabled = enabled && downloadEnabled(state)
                        IconButton(onClick = { onDownload(row.modelId) }, enabled = downloadEnabled) {
                            Icon(Icons.Default.CloudDownload, contentDescription = "Download ${row.name}")
                        }
                        if (state.capabilities.cellularChoice) {
                            IconButton(onClick = { onDownloadOverCellular(row.modelId) }, enabled = downloadEnabled) {
                                Icon(
                                    Icons.Default.SignalCellularAlt,
                                    contentDescription = "Download ${row.name} using mobile data",
                                )
                            }
                        }
                    }
                    // ⚠ Beside the download where a row has both: sharing one button would leave an
                    // installed model with an update impossible to remove.
                    if (row.setup.offersRemoval(canEndADownload = state.capabilities.cancel)) {
                        IconButton(onClick = { onConfirmDelete(row.modelId) }, enabled = enabled) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove ${row.name}")
                        }
                    }
                }
            },
        )
    }
}

/**
 * Whether [modelId]'s buttons can be pressed. ⚠ Off for good in a Control that does not own the
 * model store. ⚠ A row that is busy holds no other row's buttons.
 */
internal fun rowActionsEnabled(state: ModelsUiState, modelId: String): Boolean =
    state.storeReadOnly == null && modelId !in state.starting && modelId !in state.removing

/** Why a row's Download cannot be pressed while another model is being downloaded. */
internal const val WAITS_FOR_A_DOWNLOAD = "Available when the other download has finished."

/** Whether a row says its download waits for another one: only where that other ending would let it start. */
internal fun waitsForADownload(state: ModelsUiState, row: ModelRow, removing: Boolean, starting: Boolean): Boolean =
    !removing && !starting && state.storeReadOnly == null && row.setup.offersDownload && !downloadEnabled(state)

/** Whether a download can be started now, for any row: see [ModelsCapabilities.oneDownloadAtATime]. */
internal fun downloadEnabled(state: ModelsUiState): Boolean =
    !state.capabilities.oneDownloadAtATime || state.models.none { it.setup.download != null }

/** How full a download's bar is, or `null` while there is no honest bar: no byte yet, or no total. */
internal fun downloadFraction(download: ModelSetup.Download): Float? {
    val written = download.writtenBytes ?: return null
    if (download.totalBytes <= 0L) return null
    return (written.toFloat() / download.totalBytes.toFloat()).coerceIn(0f, 1f)
}

/**
 * The one sentence for each reason this Control may not change models. Desktop-only states.
 *
 * ⚠⚠ Only the first names another Control, because only then is there one to quit. The second says
 * what failed and where and offers no remedy: none is known here, and an invented one is worse.
 */
internal fun storeReadOnlyNotice(why: StoreReadOnly): String = when (why) {
    StoreReadOnly.OwnedElsewhere ->
        "Another Control is running on this computer and manages its models. " +
            "Quit the other one and restart this one to download or remove models here."
    is StoreReadOnly.LockUnavailable ->
        "Control couldn't lock its models folder, so it can't tell whether another Control is " +
            "using it and won't download or remove models here." +
            if (why.detail.isBlank()) "" else " (${why.detail})"
}

/**
 * What the remove dialog says.
 *
 * ⚠⚠ Not "this frees N MB" where the bytes stay on disk until Control next starts (F2v —
 * `photos-people-and-pets.md` §12.12, *reclamation waits for the next process*): removing stops the
 * model being used at once and frees nothing yet. ⚠⚠ IN-D10: deleting a model must not silently
 * disable the capability, and the user has to be told that is what is happening.
 */
internal fun removeConfirmationText(model: ModelRow, spaceReturns: ModelsCapabilities.SpaceReturns): String {
    // ⚠ The record's own size: what is on this device, whatever the catalog now offers.
    val size = formatBytes((model.setup as? ModelSetup.Held)?.model?.onDeviceBytes ?: 0L)
    val space = when (spaceReturns) {
        ModelsCapabilities.SpaceReturns.AT_NEXT_START ->
            "Its $size is released the next time Control starts."
        ModelsCapabilities.SpaceReturns.TOWERS_AT_ONCE ->
            "Most of its $size is released now, and the rest the next time the app starts."
    }
    return "The model stops being used right away. $space The features that use it stay switched " +
        "on, and stop working until you download it again."
}

/** Said after a removal, for the same reason: nothing else on the screen says the bytes are still there. */
internal const val REMOVED_SPACE_PENDING =
    "Removed. Its space is released the next time Control starts."

/** Said after an update: the version it replaced is still on disk until then. */
internal const val UPDATED_SPACE_PENDING =
    "Updated. The previous version's space is released the next time Control starts."

internal fun removedText(spaceReturns: ModelsCapabilities.SpaceReturns): String = when (spaceReturns) {
    ModelsCapabilities.SpaceReturns.AT_NEXT_START -> REMOVED_SPACE_PENDING
    ModelsCapabilities.SpaceReturns.TOWERS_AT_ONCE -> "Removed."
}

internal fun updatedText(spaceReturns: ModelsCapabilities.SpaceReturns): String = when (spaceReturns) {
    ModelsCapabilities.SpaceReturns.AT_NEXT_START -> UPDATED_SPACE_PENDING
    ModelsCapabilities.SpaceReturns.TOWERS_AT_ONCE -> "Updated."
}

/**
 * The row's second line.
 *
 * ⚠⚠ The size is always there, on every state, because it is the number the decision turns on — a
 * ~383 MB checkpoint is not something to discover after tapping. ⚠ An installed row shows it too:
 * that is the disk removing it would release.
 *
 * ⚠ While downloading it becomes "X of Y", which is the only form that stays honest across the
 * checkpoint's three artifacts: a per-file count would visibly reset twice.
 *
 * @param removing a removal of this row is in flight: the one state with no size, which is about
 *   to stop being true.
 * @param starting a download of this row was pressed and not answered yet.
 */
internal fun supportingTextFor(setup: ModelSetup, removing: Boolean = false, starting: Boolean = false): String {
    if (removing) return "Removing…"
    return when (setup) {
        is ModelSetup.Absent -> {
            val size = formatBytes(setup.model.downloadBytes)
            if (starting) "Starting… · $size" else size.with(setup.last?.let(::downloadSaidText))
        }
        is ModelSetup.Fetching -> downloadingText(setup.download)
        is ModelSetup.Held -> {
            // ⚠ A download the repository reports is said first: *starting* is only the moment
            // before it does. Then whatever the row offered a download of: an update, or a file
            // that is missing.
            (setup.update as? ModelSetup.Update.Fetching)?.let { return downloadingText(it.download) }
            if (starting) return "Starting… · ${formatBytes(setup.model.onDeviceBytes)}"
            val standing = standingText(setup.standing, formatBytes(setup.model.onDeviceBytes))
            when (val update = setup.update) {
                ModelSetup.Update.None, ModelSetup.Update.Unknown -> standing
                is ModelSetup.Update.Fetching -> downloadingText(update.download)
                is ModelSetup.Update.Available -> {
                    val size = formatBytes(update.model.downloadBytes)
                    val offer = "Update available · $size".with(update.last?.let(::downloadSaidText))
                    // ⚠ A model that is simply installed says only that there is an update; one
                    // that stands anywhere else says where first.
                    when (setup.standing) {
                        ModelSetup.Standing.Ready -> offer
                        ModelSetup.Standing.NotReadYet,
                        ModelSetup.Standing.Unreadable,
                        ModelSetup.Standing.Unsuitable,
                        ModelSetup.Standing.StoppedLoading,
                        is ModelSetup.Standing.Preparing,
                        is ModelSetup.Standing.Checking -> "$standing · $offer"
                    }
                }
            }
        }
    }
}

private fun String.with(more: String?): String = if (more == null) this else "$this · $more"

private fun downloadingText(download: ModelSetup.Download): String {
    val total = formatBytes(download.totalBytes)
    val progress = download.writtenBytes?.let { "${formatBytes(it)} of $total" } ?: "Downloading… · $total"
    return progress.with(download.held?.let(::downloadSaidText))
}

/**
 * What was last said of a download, as a row says it. `null` where there is nothing to add.
 *
 * ⚠⚠ An integrity failure never reads as "try again": asking again fetches the same file.
 */
internal fun downloadSaidText(said: ModelSetup.DownloadSaid): String? = when (said) {
    is ModelSetup.DownloadSaid.NeedsSpace ->
        "Needs ${formatBytes(said.neededBytes)} free. This device has ${formatBytes(said.freeBytes)}."
    ModelSetup.DownloadSaid.FreeSpaceUnknown -> "Couldn't check the free space."
    ModelSetup.DownloadSaid.NoTransport -> "Couldn't start the download just now."
    ModelSetup.DownloadSaid.TransfersUnknown -> "Couldn't check on the download just now."
    ModelSetup.DownloadSaid.JournalUnreadable -> "Couldn't read the download's record."
    ModelSetup.DownloadSaid.StoreRefused -> "The download couldn't be saved."
    is ModelSetup.DownloadSaid.Failed ->
        if (said.integrity) {
            "The last download didn't match its signature and was discarded."
        } else {
            "The last download didn't finish."
        }
    // The user ended it: the row is as it was before.
    ModelSetup.DownloadSaid.Cancelled -> null
}

/**
 * Where an installed model stands. ⚠ On Android and desktop every installed model is
 * [ModelSetup.Standing.Ready]: there is no prepare step and no check there, so the other lines are
 * reached only where a repository reports them.
 */
internal fun standingText(standing: ModelSetup.Standing, size: String): String = when (standing) {
    ModelSetup.Standing.Ready -> "Installed · $size"
    ModelSetup.Standing.NotReadYet -> "Installed · $size"
    ModelSetup.Standing.Unreadable -> "Couldn't read where this model stands. · $size"
    ModelSetup.Standing.Unsuitable -> "This device ran the model too slowly, so it stays off. · $size"
    // ⚠ Not reached here: no repository of Android or desktop reports it (§14.5 item 0).
    ModelSetup.Standing.StoppedLoading -> "The app stopped loading this model after it was closed while loading it. · $size"
    is ModelSetup.Standing.Preparing -> when (val how = standing.how) {
        ModelSetup.Standing.Preparing.How.Now -> "Preparing… Keep the app open."
        ModelSetup.Standing.Preparing.How.Pending -> "Preparing…"
        ModelSetup.Standing.Preparing.How.NotInForeground -> "Preparing goes on when the app is open."
        is ModelSetup.Standing.Preparing.How.Failed -> "Couldn't prepare the model.".let { failed ->
            how.space?.let { "$failed Needs ${formatBytes(it.neededBytes)} free; this device has ${formatBytes(it.freeBytes)}." }
                ?: failed
        }
        ModelSetup.Standing.Preparing.How.Incomplete -> "Part of the model is missing. Download it again."
        ModelSetup.Standing.Preparing.How.Unreadable -> "Couldn't read the model's files."
    }
    is ModelSetup.Standing.Checking -> when (val how = standing.how) {
        ModelSetup.Standing.Checking.How.Running, ModelSetup.Standing.Checking.How.Pending -> "Checking this device…"
        is ModelSetup.Standing.Checking.How.Paused -> "Checking paused. It is tried again the next time the app opens." +
            when (how.why) {
                ModelSetup.Standing.Checking.Why.HOT -> " This device is warm."
                ModelSetup.Standing.Checking.Why.ALREADY_TRIED_THIS_TIME,
                ModelSetup.Standing.Checking.Why.BACKING_OFF,
                ModelSetup.Standing.Checking.Why.MEMORY_PRESSURE,
                ModelSetup.Standing.Checking.Why.SLOW -> ""
            }
        ModelSetup.Standing.Checking.How.InstallFailed ->
            "The model didn't work on this device after it was installed. Remove it and download it again."
        ModelSetup.Standing.Checking.How.NoProbe -> "This version of the app can't check this model. Update the app."
        ModelSetup.Standing.Checking.How.Unreadable -> "Couldn't read the result of the check."
    }
}

@Preview
@Composable
private fun ModelsScreenPreview() {
    val offered = OfferedModel(
        modelId = "siglip-base-patch16-256-multilingual",
        name = "SigLIP (multilingual)",
        modelVersion = 1,
        downloadBytes = 401_604_608,
        onDeviceBytes = 401_604_608,
        licence = "Apache-2.0",
    )
    val held = HeldModel("installed-example", "Already installed", 1, onDeviceBytes = 104_857_600, licence = "")
    ModelsScreenContent(
        state = ModelsUiState(
            capabilities = ControlModelsCapabilities,
            availability = ModelsAvailability.Listed(
                catalog = ModelsAvailability.Catalog.READ,
                downloads = ModelsAvailability.Downloads.KNOWN,
                models = listOf(
                    ModelRow(offered.modelId, offered.name, ModelSetup.Absent(offered, last = null)),
                    ModelRow(held.modelId, held.name, ModelSetup.Held(held, ModelSetup.Standing.Ready, ModelSetup.Update.None)),
                ),
            ),
        ),
        onDownload = {},
        onBack = {},
    )
}
