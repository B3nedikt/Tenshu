package app.tenshu.contacts.ui.screens.importing

import androidx.compose.runtime.Composable

/**
 * Where a batch of `.vcf` text can come from. Not every platform offers every source, which
 * is why [ContactImporter.supports] exists rather than a fixed row list in the sheet.
 */
enum class ContactImportSource {
    /**
     * **One** person, chosen in the OS contact picker — and on Android that costs **no
     * permission at all**: the picker runs in the Contacts app, and only the contact the
     * user chose comes back. It is the source to reach for first, and the reason the sheet
     * lists it above the address book.
     */
    OneContact,

    /**
     * The whole platform address book, which does need `READ_CONTACTS`. Explicitly opt-in
     * (§6): nothing requests it at startup, and a user who never taps this row is never
     * asked.
     */
    AddressBook,

    /** A `.vcf` file the user points at. Permissionless everywhere. */
    File,
}

/**
 * What a launched import came back with. `Cards` carries `.vcf` **text**, because that is
 * exactly what the Ph6 repository seam takes — the platform layer's whole job is to produce
 * that string, and it holds no logic beyond doing so.
 */
sealed interface ContactImportOutcome {
    data class Cards(val vcard: String) : ContactImportOutcome

    /** The user backed out. Distinct from a failure: it deserves no error message. */
    data object Cancelled : ContactImportOutcome

    data class Failed(val problem: ImportFailure) : ContactImportOutcome
}

/** Why a platform read produced no cards. Mapped to copy by the sheet, never by the actual. */
enum class ImportFailure { PermissionDenied, Unreadable, NoCards }

/**
 * The platform contact/file picker (`docs/proposals/contacts-app.md` Ph7).
 *
 * `:contacts-shared-ui` is android + desktop only, so this is a plain `expect`/`actual` with
 * no iOS actual — iOS import is `CNContact`, which needs a `contacts-ios` shell to consume
 * it, and that is Ph10. Following `RecorderPermissionGate`'s shape (`:fit-shared-ui`): the
 * seam is `@Composable`-obtained so the Android actual can hold `ActivityResult` launchers,
 * which may only be registered during composition.
 */
interface ContactImporter {

    /** Whether this platform can offer [source] — the sheet renders exactly what is true. */
    fun supports(source: ContactImportSource): Boolean

    /**
     * Launch [source]'s picker. [onOutcome] fires once, on the main thread, when the user is
     * done with it — including when they cancel.
     *
     * ⚠ Reading an address book of any size must not run on the UI thread; the actuals move
     * the `ContentResolver` / file work off it and only the callback comes back on it.
     */
    fun import(source: ContactImportSource, onOutcome: (ContactImportOutcome) -> Unit)
}

@Composable
expect fun rememberContactImporter(): ContactImporter
