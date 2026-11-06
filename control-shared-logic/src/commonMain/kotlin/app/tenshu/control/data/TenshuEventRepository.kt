package app.tenshu.control.data

import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import org.tenshu.core.collection.list.sortByDescending
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.paging.toPagingData
import org.tenshu.notifications.client.notificationService

/**
 * Recent-events feed for Control Home — reactive windowed paging over the
 * synced notification relation, symmetric with Drive's Files listing
 * (`TenshuFilesRepository.getFilesPaged`): build the chain, then
 * `toPagingData` (`docs/proposals/notification-burst-aggregation.md` §2.3).
 *
 * ⚠ Never read the whole relation and sort or burst-fold it client-side: that is
 * O(n log n) per emission over an unbounded backlog, and it ANRs the Home feed.
 * Burst-collapsing happens at the producer (one `FileAddedNotification` row per
 * burst with an in-place `count`), so mapping is per-row. Each page load reads
 * only its `drop().take()` window; the sort runs proxy-side; change-reactivity
 * rides `pagingSource`'s conflated invalidate-on-change observer (`:core-paging`).
 *
 * [NotificationMapper.mapToEventOrNull] is a *partial* mapper — `null` drops a
 * forward-incompatible notification subtype this build predates. The paging
 * source's key math is filter-safe (keys advance by the raw window size), so
 * dropped rows cost nothing and never duplicate neighbours.
 */
class TenshuEventRepository(
    private val client: TenshuClient,
    private val notificationMapper: NotificationMapper,
) : EventRepository {

    override fun getEvents(): Flow<PagingData<Event>> =
        client.notificationService.notifications
            .sortByDescending { timestamp }
            .toPagingData(notificationMapper::mapToEventOrNull)
}
