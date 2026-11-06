package app.tenshu.control.di

import app.tenshu.control.data.EventRepository
import app.tenshu.control.data.NotificationMapper
import app.tenshu.control.data.TenshuEventRepository
import org.koin.dsl.module

val dataModule = module {

    single { NotificationMapper() }

    single<EventRepository> {
        TenshuEventRepository(get(), get())
    }
}
