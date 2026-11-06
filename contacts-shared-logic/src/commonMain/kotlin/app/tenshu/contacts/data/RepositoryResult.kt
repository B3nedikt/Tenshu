package app.tenshu.contacts.data

import app.tenshu.error.ErrorCopyKey

/**
 * The result of a repository action that can fail. Deliberately tiny: no caller acts on the
 * kind of failure, so a failure carries only the copy the UI shows.
 *
 * ⚠⚠ [kotlin.native.ObjCName], same reason as `ContactsRepository` (D12): Drive's
 * `RepositoryResult` is on the same umbrella and sorts *later*, so without this the export would
 * rename **Drive's** to `RepositoryResult_` and break the shipped `drive-ios`. ⚠ Only the parent
 * needs annotating — a nested member's Obj-C name is derived from its parent's, so `Success` and
 * `Failure` follow automatically.
 *
 * A near-twin of Drive's `RepositoryResult` rather than a shared one: the two apps have no
 * other reason to couple, and Drive's carries `InsufficientStorage` and friends that mean
 * nothing here. If a third app wants it, that is when it graduates to `:ui-core`.
 */
@OptIn(kotlin.experimental.ExperimentalObjCName::class)
@kotlin.native.ObjCName("ContactsRepositoryResult")
sealed interface RepositoryResult<out T> {
    data class Success<T>(val value: T) : RepositoryResult<T>
    /** ⚠ A catalogue key, never a proxy sentence — `core-error-copy-catalogue.md` §5.0. */
    data class Failure(val copyKey: ErrorCopyKey) : RepositoryResult<Nothing>
}

/** The value on success, `null` on failure — for call sites that only need the happy path. */
fun <T> RepositoryResult<T>.valueOrNull(): T? = (this as? RepositoryResult.Success)?.value

/**
 * Catalogue keys the Contacts app owns, for failures that are **not** `TError`s — a file the user
 * picked had nothing in it, a contact had nothing to export.
 *
 * ⭐ This is `core-error-copy-catalogue.md` §5.1's chaining: `:ui-core` owns the core
 * `TError` taxonomy and the fallback; an app contributes its own keys in front. The copy for these
 * lives in `:contacts-shared-ui`'s `strings.xml`, not in `:ui-core` — putting app-specific
 * sentences in the shared module would be the wrong layer, and ADR 0016 anchor #7 says strings
 * live where the UI lives.
 */
object ContactsErrorKeys {
    val NO_CONTACTS_IN_FILE = ErrorCopyKey("error_contacts_none_in_file")
    val NOTHING_TO_EXPORT = ErrorCopyKey("error_contacts_nothing_to_export")
    /**
     * ⚠⚠ The card's big photo is on the content plane and could not be fetched (`contacts-app.md`
     * §9.10) — the export FAILS rather than ship the card without it.
     */
    val PHOTO_UNAVAILABLE = ErrorCopyKey("error_contacts_photo_unavailable")
}
