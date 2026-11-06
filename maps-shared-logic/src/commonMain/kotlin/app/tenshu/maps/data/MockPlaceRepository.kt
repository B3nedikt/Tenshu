package app.tenshu.maps.data

import app.tenshu.maps.model.Place
import app.tenshu.maps.model.SavedList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory [PlaceRepository] over [MapsMockData] (Ph2, docs/proposals/maps-app.md §6). The
 * swappable seam — the real on-device `:place-service` store replaces this behind the
 * interface with one Koin binding. Reads are synchronous over held state; [setSaved] mutates
 * the backing [MutableStateFlow], which re-emits to every collector (the four tabs + detail).
 */
class MockPlaceRepository : PlaceRepository {

    private val places = MutableStateFlow(MapsMockData.places)
    private val savedLists = MutableStateFlow(MapsMockData.savedLists)

    override fun places(): Flow<List<Place>> = places.asStateFlow()

    override fun savedLists(): Flow<List<SavedList>> = savedLists.asStateFlow()

    override suspend fun place(id: String): Place? = places.value.firstOrNull { it.id == id }

    override suspend fun setSaved(id: String, saved: Boolean) {
        places.value = places.value.map { if (it.id == id) it.copy(saved = saved) else it }
    }
}
