package app.tenshu.drive.di

import app.tenshu.ingest.backup.BackupPreferences
import app.tenshu.drive.data.DisplayPreferences
import app.tenshu.drive.data.OfflinePreferences
import app.tenshu.drive.data.SyncRulesPreferences
import app.tenshu.ingest.backup.TenshuBackupPreferences
import app.tenshu.drive.data.TenshuDisplayPreferences
import app.tenshu.drive.data.TenshuOfflinePreferences
import app.tenshu.drive.data.TenshuSyncRulesPreferences
import app.tenshu.drive.domain.LogUnexpectedErrorUseCase
import org.koin.dsl.module

val domainModule = module {
    single { LogUnexpectedErrorUseCase() }
    single<OfflinePreferences> { TenshuOfflinePreferences(get()) }
    single<SyncRulesPreferences> { TenshuSyncRulesPreferences(get()) }
    single<BackupPreferences> { TenshuBackupPreferences(get()) }
    single<DisplayPreferences> { TenshuDisplayPreferences(get()) }
}
