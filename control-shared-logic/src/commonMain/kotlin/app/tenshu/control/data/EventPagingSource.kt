package app.tenshu.control.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

class EventPagingSource : PagingSource<Int, Event>() {
    override fun getRefreshKey(state: PagingState<Int, Event>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            state.closestPageToPosition(anchorPosition)?.prevKey?.plus(1)
                ?: state.closestPageToPosition(anchorPosition)?.nextKey?.minus(1)
        }
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Event> {
        val page = params.key ?: 0
        val pageSize = params.loadSize

        val currentInstant = Clock.System.now()

        val events = List(pageSize) { i ->
            val index = page * pageSize + i
            val timestamp = currentInstant.minus((index * 30).minutes)
            val id = "event_$index"

            // Mock empty byte arrays for local previews
            val mockPreview = byteArrayOf(0)

            when (Random.nextInt(5)) {
                0 -> Event.FileEvent.Added(
                    id = id,
                    timestamp = timestamp,
                    fileName = "vacation_photo_$index.jpg",
                    preview = mockPreview
                )

                1 -> Event.FileEvent.MultipleAdded(
                    id = id,
                    timestamp = timestamp,
                    firstFileName = "report_$index.pdf",
                    otherFilesCount = Random.nextInt(2, 25),
                    previews = listOf(mockPreview, mockPreview, mockPreview)
                )

                2 -> Event.FileEvent.Deleted(
                    id = id,
                    timestamp = timestamp,
                    fileName = "old_backup_$index.zip",
                    preview = mockPreview
                )

                3 -> Event.AppEvent.Registered(
                    id = id,
                    timestamp = timestamp,
                    appName = "Drive Client $index"
                )

                else -> Event.AppEvent.Removed(
                    id = id,
                    timestamp = timestamp,
                    appName = "Legacy Sync $index"
                )
            }
        }

        return LoadResult.Page(
            data = events,
            prevKey = if (page == 0) null else page - 1,
            nextKey = page + 1
        )
    }
}
