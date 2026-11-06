package app.tenshu.control.data

import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow

interface EventRepository {
    fun getEvents(): Flow<PagingData<Event>>
}
