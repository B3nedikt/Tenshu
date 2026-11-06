package app.tenshu.contacts.di

import app.tenshu.contacts.data.ContactsRepository
import app.tenshu.contacts.data.MockContactsRepository
import app.tenshu.contacts.data.TenshuContactsRepository
import app.tenshu.contacts.data.contactsControlReachability
import app.tenshu.reachability.MutableSchemaSkewSource
import app.tenshu.contacts.ui.screens.detail.ContactDetailViewModel
import app.tenshu.contacts.ui.screens.importing.ContactImportViewModel
import app.tenshu.contacts.ui.screens.invite.ConfirmContactViewModel
import app.tenshu.contacts.ui.screens.invite.InviteViewModel
import app.tenshu.contacts.ui.screens.list.ContactListViewModel
import app.tenshu.contacts.ui.screens.merge.MergeViewModel
import app.tenshu.contacts.ui.screens.profile.ProfileViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.tenshu.core.common.TenshuClient
import app.tenshu.reachability.realmPresence
import org.tenshu.core.get
import org.tenshu.sync.client.syncService
import org.tenshu.sync.getSyncStatus
import org.tenshu.sync.getUnlockStatus

/**
 * Koin assembly for the Contacts app (docs/proposals/contacts-app.md §6). Both app shells
 * (`:contacts-desktop`, `:contacts-android`) `startKoin { modules(contactsClientModule,
 * platformClientModule) + contactsModules() }`.
 *
 * The repository is bound behind its interface, which is what made the Ph2 swap a one-line
 * change here with no view model or screen touched (D8).
 */
fun contactsModules(): List<Module> = listOf(
    contactsRepositoryModule(),
    viewModelsModule(),
)

/**
 * The real binding (Ph2): [TenshuContactsRepository] over the **Control** proxy's
 * [TenshuClient]. The contact graph is durable, synced across the user's devices, and the
 * same rows Drive's handshake flows write — which is the point, since a contact is a
 * contact whichever app is looking at it.
 */
fun contactsRepositoryModule(): Module = module {
    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    // The app's ONE reachability engine (fit-app.md §9.14 recipe): probe on the app scope,
    // one signal feeding the shell banner.
    // SK-1b: the seam between the schema announce and the shell banner's words. A single, because
    // the engine reads it and the client module writes it, in whichever order Koin resolves.
    single { MutableSchemaSkewSource() }
    single {
        contactsControlReachability(
            get<TenshuClient>(),
            probeScope = get(),
            // Why it is unreachable, when the answer is "the two sides disagree on a schema
            // version" rather than "the channel is down" (SK-1b).
            schemaSkew = get<MutableSchemaSkewSource>().flow,
            schemaFence = get<MutableSchemaSkewSource>().fence,
            proxyHost = get<MutableSchemaSkewSource>().host,
            // ADR 0025 §Decision 8: Control installed and answering, no realm on this device → the
            // wall. The same two reads as Control's landing router; `get()` throws on a failed read,
            // which the engine reads as "cannot tell".
            controlHasRealm = app.tenshu.reachability.RealmProbe.Wired(get<TenshuClient>().let { client ->
                {
                    realmPresence(
                        isLocked = client.syncService.getUnlockStatus().get().isLocked.get(),
                        ownerId = client.syncService.getSyncStatus().get().ownerId?.get(),
                    )
                }
            }),
        )
    }
    single<ContactsRepository> { TenshuContactsRepository(get<TenshuClient>(), reachability = get()) }
}

/**
 * The original Ph1 mock binding — kept for a one-line revert, previews, and the desktop
 * render tests, which must not need a running Control.
 */
fun mockContactsRepositoryModule(): Module = module {
    single<ContactsRepository> { MockContactsRepository() }
    // ⚠⚠ Bound here too, even though a mock graph has no announce to mirror.
    // `contactsClientModule` resolves this with `get`, not `getOrNull`, so a graph missing it
    // fails loudly rather than silently reporting every skewed device as a transport outage —
    // and the price of that choice is that BOTH repository modules must bind it (SK-1b).
    single { MutableSchemaSkewSource() }
}

fun viewModelsModule(): Module = module {
    viewModel { ContactListViewModel(get()) }
    viewModel { ProfileViewModel(get()) }
    viewModel { (personId: String) -> ContactDetailViewModel(get(), personId) }
    viewModel { InviteViewModel(get()) }
    viewModel { ContactImportViewModel(get()) }
    viewModel { MergeViewModel(get()) }
    viewModel { (inviteId: String) -> ConfirmContactViewModel(get(), inviteId) }
}
