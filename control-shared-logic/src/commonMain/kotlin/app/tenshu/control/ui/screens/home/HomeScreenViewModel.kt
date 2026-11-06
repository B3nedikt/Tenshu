package app.tenshu.control.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.tenshu.control.data.Event
import app.tenshu.control.data.EventRepository
import kotlinx.coroutines.flow.Flow

class HomeScreenViewModel(
    repository: EventRepository
) : ViewModel() {

    val eventFlow: Flow<PagingData<Event>> = repository.getEvents()
        .cachedIn(viewModelScope)
}
