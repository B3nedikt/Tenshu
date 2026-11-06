package app.tenshu.control.ui.screens.recovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.tenshu.control.shared.resources.Res
import app.tenshu.control.shared.resources.mnemonic_grid_paste
import app.tenshu.control.shared.resources.mnemonic_word_label
import org.jetbrains.compose.resources.stringResource
import org.tenshu.core.common.util.BIP39_ENGLISH_WORDLIST

/**
 * 24-field mnemonic input for §7.1 / §4b.4 per
 * `docs/proposals/control-onboarding-ux.md`. Renders the user's recovery
 * phrase as a [columns]-column grid of numbered text fields, each
 * validated against the BIP-39 English wordlist on edit. Invalid words
 * surface as the field's error state without blocking entry — the
 * caller decides when to gate Recover.
 *
 * The "Paste from clipboard" button at the top splits the pasted text
 * across the 24 fields, lowercasing + trimming each token. Matches
 * `Bip39.decode()`'s lenient input handling so a phrase pasted from a
 * password manager works regardless of capitalisation or stray
 * whitespace.
 *
 * Autocomplete dropdown (spec'd) is a follow-up; the wordlist
 * validation here gives the user inline "this word isn't valid"
 * feedback without the popup machinery.
 */
@Composable
fun MnemonicGrid(
    words: List<String>,
    onWordChange: (Int, String) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = DEFAULT_COLUMNS,
    wordCount: Int = MNEMONIC_WORD_COUNT,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val rows = (wordCount + columns - 1) / columns
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = {
                val text = clipboardManager.getText()?.text ?: return@TextButton
                pasteAcross(text, wordCount).forEachIndexed { i, w -> onWordChange(i, w) }
            }) {
                Text(stringResource(Res.string.mnemonic_grid_paste))
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        for (row in 0 until rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (col in 0 until columns) {
                    val index = row * columns + col
                    Box(modifier = Modifier.widthIn(min = 0.dp).weight(1f)) {
                        if (index < wordCount) {
                            WordInput(
                                index = index,
                                value = words.getOrNull(index).orEmpty(),
                                onChange = { onWordChange(index, it) },
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * Single labelled mnemonic-word input. Lowercases on commit, marks
 * itself as error when non-empty + not in [BIP39_ENGLISH_WORDLIST].
 *
 * Suitable for ConfirmPhraseStep's 3-of-24 layout (call directly with
 * an explicit per-position [index]) and for [MnemonicGrid].
 */
@Composable
fun WordInput(
    index: Int,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Track focus so the "not a valid word" error only surfaces after
    // the user has finished typing; otherwise every prefix keystroke
    // looks invalid and the UI feels noisy.
    var hasBeenEdited by remember(index) { mutableStateOf(false) }
    LaunchedEffect(value) { if (value.isNotEmpty()) hasBeenEdited = true }

    val normalized = value.trim().lowercase()
    val isInvalid = hasBeenEdited &&
            normalized.isNotEmpty() &&
            normalized !in BIP39_ENGLISH_WORDLIST

    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it) },
        label = {
            Text(stringResource(Res.string.mnemonic_word_label, index + 1))
        },
        singleLine = true,
        isError = isInvalid,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = FontFamily.Monospace,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * Splits [text] into up to [size] words, lowercased + trimmed. Shorter
 * pastes pad with empty strings so callers can rely on indexed assignment.
 */
internal fun pasteAcross(text: String, size: Int): List<String> {
    val tokens = text.trim().split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .map { it.lowercase() }
    return List(size) { i -> tokens.getOrNull(i).orEmpty() }
}

private const val DEFAULT_COLUMNS = 4
private const val MNEMONIC_WORD_COUNT = 24
