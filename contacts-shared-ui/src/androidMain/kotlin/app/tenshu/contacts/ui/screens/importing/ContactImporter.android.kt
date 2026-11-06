package app.tenshu.contacts.ui.screens.importing

import android.Manifest
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every MIME type an exported address book turns up as. `text/x-vcard` is what Android's own
 * Contacts app writes, `text/vcard` is the RFC 6350 registration, and `text/directory` still
 * appears from older exporters. The wildcard is the backstop — file providers routinely
 * report a `.vcf` as `application/octet-stream`, and a picker that cannot see the file the
 * user is looking at is worse than one that lets them pick the wrong thing.
 */
private val VCARD_MIME_TYPES =
    arrayOf("text/x-vcard", "text/vcard", "text/directory", "*/*")

/**
 * Android's platform import (`docs/proposals/contacts-app.md` Ph7).
 *
 * Both contact sources read through [ContactsContract.Contacts.CONTENT_VCARD_URI] rather
 * than hand-mapping the `Data` table's MIME rows. That is the decision that makes this file
 * small **and** lossless: the platform serialises the contact itself, so structured names,
 * multiple labelled numbers, postal addresses and vendor `X-` extensions all survive into
 * the `rawVcard` that Ph6 stores verbatim. A hand-rolled mapper would have to enumerate
 * every MIME type it knows and would silently drop the rest — exactly the loss-by-omission
 * the Ph6 merge rule was written to prevent.
 */
@Composable
actual fun rememberContactImporter(): ContactImporter {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // The pending callback bridges each launcher's async result back to the import() call
    // that started it (the `RecorderPermissionGate` pattern).
    val pending = remember { mutableStateOf<((ContactImportOutcome) -> Unit)?>(null) }

    fun deliver(outcome: ContactImportOutcome) {
        val onOutcome = pending.value ?: return
        pending.value = null
        onOutcome(outcome)
    }

    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        if (uri == null) {
            deliver(ContactImportOutcome.Cancelled)
            return@rememberLauncherForActivityResult
        }
        scope.launch { deliver(readPickedContact(context.contentResolver, uri)) }
    }

    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            deliver(ContactImportOutcome.Cancelled)
            return@rememberLauncherForActivityResult
        }
        scope.launch { deliver(readVcardFile(context.contentResolver, uri)) }
    }

    val requestReadContacts = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            // Denied is a decision, not an error state to recover from — the sheet says so
            // and the other two sources stay available.
            deliver(ContactImportOutcome.Failed(ImportFailure.PermissionDenied))
            return@rememberLauncherForActivityResult
        }
        scope.launch { deliver(readAddressBook(context.contentResolver)) }
    }

    return remember(context, scope) {
        object : ContactImporter {
            override fun supports(source: ContactImportSource): Boolean = true

            override fun import(
                source: ContactImportSource,
                onOutcome: (ContactImportOutcome) -> Unit,
            ) {
                // A launcher already in flight owns the callback; a second launch would
                // orphan the first one's result.
                if (pending.value != null) return
                pending.value = onOutcome
                when (source) {
                    // No permission, no dialog: the picker runs in the Contacts app and
                    // hands back only what the user chose.
                    ContactImportSource.OneContact -> pickContact.launch(null)

                    ContactImportSource.File -> openDocument.launch(VCARD_MIME_TYPES)

                    ContactImportSource.AddressBook -> {
                        val held = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.READ_CONTACTS,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (held) {
                            scope.launch { deliver(readAddressBook(context.contentResolver)) }
                        } else {
                            // Requested here, at the point of use, and nowhere else — §6's
                            // "explicitly opt-in" is enforced by there being no other call.
                            requestReadContacts.launch(Manifest.permission.READ_CONTACTS)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The one contact the OS picker returned.
 *
 * ⚠ The picker's grant covers the URI it handed back, and `CONTENT_VCARD_URI` is a different
 * path — so on some OEM builds this read is refused even though the user just chose the
 * contact. That is caught, not crashed on: it surfaces as [ImportFailure.PermissionDenied],
 * whose copy points at the address-book row, which asks for the permission properly. The
 * alternative — requesting `READ_CONTACTS` up front so this can never fail — would make the
 * permissionless path permissioned, which is the whole reason it is offered first.
 */
private suspend fun readPickedContact(
    resolver: ContentResolver,
    contactUri: Uri,
): ContactImportOutcome = withContext(Dispatchers.IO) {
    val lookupKey = runCatching { lookupKeyOf(resolver, contactUri) }
        .getOrElse { return@withContext ContactImportOutcome.Failed(ImportFailure.PermissionDenied) }
        ?: return@withContext ContactImportOutcome.Failed(ImportFailure.Unreadable)

    when (val card = readVcard(resolver, lookupKey)) {
        null -> ContactImportOutcome.Failed(ImportFailure.PermissionDenied)
        else -> ContactImportOutcome.Cards(card)
    }
}

/**
 * Every contact in the platform address book, concatenated.
 *
 * Read one card at a time rather than through `CONTENT_MULTI_VCARD_URI`, which takes the
 * lookup keys joined into the URI itself and so has a length ceiling nobody documents. A few
 * thousand small reads is a one-off cost on an explicit user action; a truncated import
 * would be a silent one.
 */
private suspend fun readAddressBook(resolver: ContentResolver): ContactImportOutcome =
    withContext(Dispatchers.IO) {
        val keys = runCatching {
            resolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.LOOKUP_KEY),
                null,
                null,
                null,
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) cursor.getString(0)?.let { add(it) }
                }
            }
        }.getOrElse {
            return@withContext ContactImportOutcome.Failed(ImportFailure.PermissionDenied)
        } ?: return@withContext ContactImportOutcome.Failed(ImportFailure.Unreadable)

        // Aggregated contacts can repeat a lookup key across raw-contact rows; importing the
        // same person twice is exactly what D7 forbids doing accidentally.
        val cards = keys.distinct().mapNotNull { readVcard(resolver, it) }
        when {
            cards.isEmpty() -> ContactImportOutcome.Failed(ImportFailure.NoCards)
            else -> ContactImportOutcome.Cards(cards.joinToString(""))
        }
    }

/** A `.vcf` the user pointed at through the document picker. */
private suspend fun readVcardFile(resolver: ContentResolver, uri: Uri): ContactImportOutcome =
    withContext(Dispatchers.IO) {
        val text = runCatching {
            resolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        }.getOrNull() ?: return@withContext ContactImportOutcome.Failed(ImportFailure.Unreadable)

        when {
            text.isBlank() -> ContactImportOutcome.Failed(ImportFailure.NoCards)
            else -> ContactImportOutcome.Cards(text)
        }
    }

private fun lookupKeyOf(resolver: ContentResolver, contactUri: Uri): String? =
    resolver.query(
        contactUri,
        arrayOf(ContactsContract.Contacts.LOOKUP_KEY),
        null,
        null,
        null,
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

/** The platform's own serialisation of one contact. `null` on any refusal or absence. */
private fun readVcard(resolver: ContentResolver, lookupKey: String): String? = runCatching {
    val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, lookupKey)
    resolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
}.getOrNull()
