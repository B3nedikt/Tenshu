package app.tenshu.photos.ui.screens.albumdetail

import androidx.compose.foundation.layout.Arrangement
import app.tenshu.photos.ui.components.FaceAvatar
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import app.tenshu.photos.ui.components.WidthScaledGridCells
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import app.tenshu.photos.model.NamingOutcome
import app.tenshu.photos.ui.screens.collections.SmartAlbum
import app.tenshu.photos.model.Photo
import app.tenshu.photos.ui.components.PhotoTile
import app.tenshu.photos.ui.components.PreviewLoader
import app.tenshu.photos.ui.screens.feed.FeedError
import app.tenshu.photos.ui.screens.collections.photoCount

/**
 * One album's photos (`photos-app.md` S5), and the Favourites smart album, which is the same
 * screen with [AlbumDetailUiState.editable] false.
 *
 * ⚠ **Long-press removes from the album, and says so.** It is the one destructive-looking gesture
 * here, and the thing it does not do — delete the photograph — is the distinction the whole
 * service is shaped around: `RemoveFromAlbum` cannot reach `:file-service` at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    state: AlbumDetailUiState,
    loadPreview: PreviewLoader,
    onPhotoClick: (Photo) -> Unit,
    onRemove: (Photo) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** The column count on a phone-width grid; a wider one multiplies it (`WidthScaledGridCells`). */
    columns: Int = 3,
    /**
     * Name the person these photographs show — `null` for every collection that is not
     * a person, which is all of them but one.
     *
     * ⚠⚠ Nullable rather than a `no-op` default: the action is **absent** for an album or a place
     * rather than present and inert, because a button that does nothing is the thing this repo
     * keeps deciding not to ship.
     */
    onName: ((String) -> Unit)? = null,
    /**
     * A name the user typed that did NOT save — today only for want of consent.
     *
     * ⚠⚠ **Rendering this is not optional.** The sheet closes on confirm, so without a
     * surface the person is simply still unnamed and the user reasonably concludes the save worked.
     * State that nothing reads is the same as state that was never set.
     */
    nameFailure: NamingOutcome? = null,
    /**
     * The NAME an interrupted naming was writing — `null` when none was.
     *
     * ⚠⚠ **The name, not a flag.** "Add another person" means the interrupted write can be
     * for someone this card is NOT titled after: adding Bob from Anna's card and stopping part way
     * leaves Bob half labelled. Retrying with the card's title would rewrite ANNA, report success
     * and clear the retry, while Bob stays exactly as broken and nothing says so.
     */
    unfinishedName: String? = null,
    onDismissNameRefusal: () -> Unit = {},
    /**
     * Open a photograph WITH the list it was tapped in — `null` keeps [onPhotoClick].
     *
     * ⚠ Needed once a person's grid can be filtered: the viewer swipes through the list it is
     * handed, and handing it the unfiltered one would page from an "Only them" grid into group
     * photos the user just filtered away.
     */
    onPhotoInListClick: ((Photo, List<Photo>) -> Unit)? = null,
    /**
     * *"Is this Anna?"* on an unnamed person, or a display fold's photographs on a named one
     * (IN-6 F6c name suggestions; `PersonPage.suggestedName` / `unconfirmedCount`). `null` shows
     * nothing — every collection that is not a person, and every person with nothing to ask.
     */
    suggestion: NameSuggestionPrompt? = null,
) {
    var naming by remember { mutableStateOf(false) }
    // The second person, by id. ⚠ Keyed on the person's route so opening someone else
    // starts un-narrowed; and resolved against the CURRENT companions, so a refresh that drops or
    // re-keys them falls back to the whole grid rather than a stale intersection.
    // ⚠ SAVEABLE: opening a shared photo takes this screen out of composition, and a plain
    // `remember` comes back un-narrowed — the user returns to a grid they had not asked for.
    var withId by rememberSaveable(state.person?.key) { mutableStateOf<String?>(null) }
    var choosing by remember(state.person?.key) { mutableStateOf(false) }
    // ⚠ Through the aliases too (`PersonHeader.chosenCompanion`).
    val second: SmartAlbum? = state.person?.chosenCompanion(withId)
    val visiblePhotos = if (second != null) photosWithBoth(state.photos, second.photos) else state.photos
    // ⚠⚠ No naming while narrowed. Naming writes the name to EVERY photo of the
    // primary person — not the intersection on screen — so naming from an "Anna & Bob" grid would
    // label Anna-only photos the user cannot see. Naming belongs on the person's own page.
    val effectiveOnName: ((String) -> Unit)? = if (second != null) null else onName

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                // ⚠ A person's name heads the grid instead, beside their face — shown twice it
                // reads as two different things.
                title = { Text(if (state.person != null) "" else state.title) },
                navigationIcon = {
                    // ⚠ Not while an edit runs — see `App`'s Album entry.
                    IconButton(onClick = onBack, enabled = !state.writing) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // ⚠⚠⚠ **Always offered on a person, and the wording carries the whole
                    // distinction.** Naming is ADDITIVE — it writes "this photograph shows X" and
                    // removes nothing — so on a named card it adds a SECOND person rather than
                    // renaming the first. It is never labelled Rename, because the data layer
                    // cannot rename: "Anna" → "Anne" would leave the Anna rows synced beside the
                    // new ones.
                    //
                    // ⚠⚠ Hiding it once a name exists creates a dead end that **no rule on the
                    // data can avoid**. Two clusters holding the same photographs — one Anna's,
                    // one Bob's — are indistinguishable from ONE person split across two clusters.
                    // Suppression has to hide the duplicate in the second case and would strand
                    // Bob in the first; the affordance is what separates them, because only the
                    // user knows which they are looking at.
                    if (effectiveOnName != null) {
                        TextButton(onClick = { naming = true }) {
                            Text(
                                PersonPageCopy.nameAction(unfinished = unfinishedName != null, titleBlank = state.title.isBlank()),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (naming && effectiveOnName != null) {
            NameDialog(
                // ⚠⚠ The INTERRUPTED name when finishing one, and empty otherwise. Never the
                // card's title: on an already-named card the act is "add another person", so
                // handing back the existing name would invite an edit the data layer cannot
                // perform — and when a retry IS owed, the name owed is the one that was being
                // written, which may not be the card's.
                initial = unfinishedName.orEmpty(),
                onDismiss = { naming = false },
                onConfirm = { typed ->
                    naming = false
                    effectiveOnName(typed)
                },
            )
        }

        val companions = state.person?.companions.orEmpty()
        if (choosing && companions.isNotEmpty()) {
            CompanionPicker(
                companions = companions,
                loadPreview = loadPreview,
                onDismiss = { choosing = false },
                onChoose = { chosen ->
                    choosing = false
                    withId = chosen.id
                },
            )
        }

        if (nameFailure != null && nameFailure != NamingOutcome.Saved) {
            AlertDialog(
                onDismissRequest = onDismissNameRefusal,
                title = {
                    Text(PersonPageCopy.failureTitle(nameFailure))
                },
                // ⚠⚠ TWO sentences, because there are two instructions. Naming the
                // consent switch for a Control that is merely not running — or for an older
                // Control that does not have this operation yet, which adding an op does not
                // fence — sends the user to check a setting that is already correct.
                // ⚠⚠ Says what IS true rather than what failed (`PersonPageCopy.failureText`).
                text = { Text(PersonPageCopy.failureText(nameFailure)) },
                confirmButton = { TextButton(onClick = onDismissNameRefusal) { Text(PersonPageCopy.OK) } },
            )
        }
        when {
            state.loading ->
                Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                    CircularProgressIndicator()
                }

            // ⚠ The sentence is the shared `AlbumDetailUiState.statusText`; the iOS grid says the same.
            state.statusText != null ->
                Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), Alignment.Center) {
                    Text(
                        text = state.statusText!!,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }

            else -> LazyVerticalGrid(
                columns = WidthScaledGridCells(columns),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                state.person?.let { person ->
                    item(key = PERSON_HEADER_KEY, span = { GridItemSpan(maxLineSpan) }) {
                        PersonHeaderRow(
                            name = state.title,
                            person = person,
                            fallbackPath = state.photos.firstOrNull()?.previewPath,
                            shownCount = visiblePhotos.size,
                            onAddName = if (effectiveOnName != null) ({ naming = true }) else null,
                            loadPreview = loadPreview,
                            second = second,
                            onChooseWith = { choosing = true },
                            onClearWith = { withId = null },
                        )
                    }
                    // ⚠ Not while narrowed: a Yes names every photograph of this person, not the
                    // intersection on screen — the reason naming is off there too.
                    if (second == null && suggestion != null) {
                        item(key = NAME_SUGGESTION_KEY, span = { GridItemSpan(maxLineSpan) }) {
                            NameSuggestionRow(suggestion)
                        }
                    }
                    if (second != null && visiblePhotos.isEmpty()) {
                        item(key = NO_PHOTOS_TOGETHER_KEY, span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                text = PersonPageCopy.NO_PHOTOS_TOGETHER,
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
                items(visiblePhotos, key = { it.path }) { photo ->
                    PhotoTile(
                        photo = photo,
                        loadPreview = loadPreview,
                        onClick = {
                            onPhotoInListClick?.invoke(photo, visiblePhotos) ?: onPhotoClick(photo)
                        },
                        // ⚠ Not while an edit runs: the edits are single-flight, and a
                        // long-press the view model then ignores is a removal that silently never
                        // happened. The iOS grid disables its menu item on the same flag.
                        onLongClick = if (state.editable && !state.writing) {
                            { onRemove(photo) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

/**
 * What a person's page asks about a name (IN-6 F6c name suggestions), already worded: a question on
 * an unnamed group, or the unconfirmed photographs of a display fold on a named one.
 */
data class NameSuggestionPrompt(
    val text: String,
    val detail: String?,
    val yes: String,
    val no: String,
    val failed: Boolean,
    val onYes: () -> Unit,
    val onNo: () -> Unit,
) {
    companion object {
        /** From the shared page; `null` when there is nothing to ask. */
        fun of(
            suggestedName: String?,
            unconfirmedCount: Int,
            name: String,
            failed: Boolean,
            onYes: () -> Unit,
            onNo: () -> Unit,
        ): NameSuggestionPrompt? = when {
            suggestedName != null -> NameSuggestionPrompt(
                text = PersonPageCopy.suggestionQuestion(suggestedName),
                detail = PersonPageCopy.SUGGESTION_EXPLAINED,
                yes = PersonPageCopy.SUGGESTION_YES,
                no = PersonPageCopy.SUGGESTION_NO,
                failed = failed,
                onYes = onYes,
                onNo = onNo,
            )
            unconfirmedCount > 0 -> NameSuggestionPrompt(
                text = PersonPageCopy.unconfirmed(unconfirmedCount, name),
                detail = null,
                yes = PersonPageCopy.CONFIRM,
                no = PersonPageCopy.notThem(name),
                failed = failed,
                onYes = onYes,
                onNo = onNo,
            )
            else -> null
        }
    }
}

/** The question and its two answers, quiet — offered, never imposed (§7). */
@Composable
private fun NameSuggestionRow(prompt: NameSuggestionPrompt) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(prompt.text, style = MaterialTheme.typography.titleMedium)
        prompt.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (prompt.failed) {
            Text(
                PersonPageCopy.SUGGESTION_NOT_SAVED,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row {
            TextButton(onClick = prompt.onYes) { Text(prompt.yes) }
            TextButton(onClick = prompt.onNo) { Text(prompt.no) }
        }
    }
}

/**
 * WHO defines this group, before any photograph — the face, the name and the count.
 * Modelled on Google Photos' person page, where a large shared-photo group is otherwise hard to
 * read: the header says which face the grid is about.
 *
 * ⚠⚠ **No "Only them" chip, deliberately.** The face count it would rely on is what the
 * SHORT-RANGE detector finds, and in a group selfie that is the one face at arm's length — so it
 * files photos of five and seven people under "only them". It needs a detector that counts the
 * people behind (§6a).
 *
 * ⚠ An unnamed group's name slot IS the naming action, where one is offered — the first thing to
 * do with a face nobody has named is to name it.
 */
@Composable
private fun PersonHeaderRow(
    name: String,
    person: PersonHeader,
    fallbackPath: String?,
    shownCount: Int,
    onAddName: (() -> Unit)?,
    loadPreview: PreviewLoader,
    second: SmartAlbum? = null,
    onChooseWith: () -> Unit = {},
    onClearWith: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                FaceAvatar(
                    path = person.cover?.path ?: fallbackPath,
                    region = person.cover?.region,
                    loadPreview = loadPreview,
                )
                // The second face, smaller and overlapping the first's lower corner.
                if (second != null) {
                    FaceAvatar(
                        path = second.coverPath,
                        region = second.coverFace?.region,
                        loadPreview = loadPreview,
                        size = 40.dp,
                        modifier = Modifier.offset(x = 44.dp, y = 40.dp),
                    )
                }
            }
            Spacer(Modifier.width(if (second != null) 28.dp else 16.dp))
            Column {
                when {
                    second != null -> Text(
                        PersonPageCopy.both(name, second.title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    name.isNotBlank() -> Text(name, style = MaterialTheme.typography.headlineSmall)
                    onAddName != null -> TextButton(onClick = onAddName) { Text(PersonPageCopy.ADD_A_NAME) }
                    else -> Text(PersonPageCopy.UNNAMED, style = MaterialTheme.typography.headlineSmall)
                }
                Text(photoCount(shownCount), style = MaterialTheme.typography.bodyMedium)
            }
        }
        // Google Photos' "[face] +": narrow to photographs of both. ⚠ Absent when there
        // is nobody else to pick, rather than a chip that opens an empty list.
        if (person.companions.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            if (second == null) {
                AssistChip(
                    onClick = onChooseWith,
                    label = { Text(PersonPageCopy.WITH) },
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                )
            } else {
                // ⚠ Tapping the chip anywhere removes the second person — the ✕ says so.
                InputChip(
                    selected = true,
                    onClick = onClearWith,
                    label = { Text(PersonPageCopy.withChip(second.title)) },
                    avatar = {
                        FaceAvatar(
                            path = second.coverPath,
                            region = second.coverFace?.region,
                            loadPreview = loadPreview,
                            size = InputChipDefaults.AvatarSize,
                        )
                    },
                    trailingIcon = {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = PersonPageCopy.removeWith(second.title),
                        )
                    },
                )
            }
        }
    }
}

/**
 * Pick the second person for the "With…" chip. Unnamed people are offered too:
 * intersection is by photo path ([photosWithBoth]), which works the same for a named person and an
 * unnamed group.
 */
@Composable
private fun CompanionPicker(
    companions: List<SmartAlbum>,
    loadPreview: PreviewLoader,
    onDismiss: () -> Unit,
    onChoose: (SmartAlbum) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(PersonPageCopy.PICKER_TITLE) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                companions.forEach { companion ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onChoose(companion) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FaceAvatar(
                            path = companion.coverPath,
                            region = companion.coverFace?.region,
                            loadPreview = loadPreview,
                            size = 40.dp,
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            PersonPageCopy.name(companion.title),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(PersonPageCopy.CANCEL) } },
    )
}
private const val NO_PHOTOS_TOGETHER_KEY = "no-photos-together"

private const val PERSON_HEADER_KEY = "person-header"

/** ⚠ A key no photo path can be: paths start with `/`. */
private const val NAME_SUGGESTION_KEY = "name-suggestion"

/**
 * Type a person's name.
 *
 * ⚠⚠ The copy says what naming *does*, because it does more than label a screen: it writes
 * one row per photograph, and those rows **sync to the user's other devices** (threat model A40).
 * Every other derived thing in Photos is device-local, so a user has no reason to assume this one
 * is not — and the moment to say so is before they type, not in a settings page they will
 * never open.
 */
@Composable
private fun NameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(PersonPageCopy.dialogTitle(initial)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    // ⚠ Bounded at the field, because the name is repeated in EVERY call the
                    // write is split into: an unbounded one makes every chunk over-budget and
                    // every call fail beneath the typed-error layer, where nothing explains it.
                    onValueChange = { if (it.length <= PersonPageCopy.NAME_MAX_LENGTH) text = it },
                    singleLine = true,
                    label = { Text(PersonPageCopy.NAME_FIELD) },
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = PersonPageCopy.NAME_SYNCS,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            // ⚠ Disabled rather than accepting-then-ignoring: the op treats a blank name as a
            // no-op, so a live button would look like it had worked.
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank(),
            ) {
                Text(PersonPageCopy.SAVE)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(PersonPageCopy.CANCEL) } },
    )
}
