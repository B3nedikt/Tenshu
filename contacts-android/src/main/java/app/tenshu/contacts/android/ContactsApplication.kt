package app.tenshu.contacts.android

import android.app.Application
import app.tenshu.contacts.di.contactsClientModule
import app.tenshu.contacts.di.contactsModules
import app.tenshu.contacts.di.platformClientModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.tenshu.core.common.SCHEMA_LOCKSTEP_LOG_TAG
import org.tenshu.core.common.schemaLockstepLine

// Contacts Android Application (docs/proposals/contacts-app.md §6). Starts Koin with the
// client graph (IPC to Control over Binder) plus the app modules — the contact list reads
// the real `:contact-service` rows Control hosts (Ph2).
class ContactsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Schema-lockstep beacon (smoke gate asserts Control + clients share this).
        android.util.Log.i(SCHEMA_LOCKSTEP_LOG_TAG, schemaLockstepLine(packageName))
        startKoin {
            androidLogger()
            androidContext(this@ContactsApplication)
            modules(listOf(contactsClientModule, platformClientModule) + contactsModules())
        }
    }
}
