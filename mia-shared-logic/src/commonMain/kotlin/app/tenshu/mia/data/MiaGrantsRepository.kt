package app.tenshu.mia.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import org.tenshu.core.common.TenshuClient
import org.tenshu.mia.MiaSource
import org.tenshu.preference.PreferenceService
import org.tenshu.preference.client.read
import org.tenshu.preference.client.set

/**
 * The switches behind MQ-D6: which sources Mia may read.
 *
 * ⚠ **Per service, and the copy must say exactly that.** There is no per-file or per-row
 * capability in Tenshu, so a UI implying finer control would promise something the system
 * cannot enforce.
 */
interface MiaGrantsRepository {

    /** The currently-granted sources; emits again when the user flips a switch. */
    fun granted(): Flow<Set<MiaSource>>

    /**
     * The sources Mia may also **write** (MQ-D6 as amended). ⚠ Only ever a subset of
     * [granted] — write without read is unsatisfiable and is never offered.
     */
    fun writable(): Flow<Set<MiaSource>>

    suspend fun setGranted(source: MiaSource, granted: Boolean)

    suspend fun setWritable(source: MiaSource, granted: Boolean)
}

/**
 * ⚠ Every source is granted when nothing is stored — the row only exists once the switch has
 * been touched, and `MiaSource`'s default is `true`. Same call as the proxy's `readMiaGrants`,
 * and for the same reason: a never-configured assistant must work.
 */
class TenshuMiaGrantsRepository(
    private val client: TenshuClient,
) : MiaGrantsRepository {

    private val service: PreferenceService?
        get() = runCatching { client.getService<PreferenceService>(PreferenceService.ID) }.getOrNull()

    override fun granted(): Flow<Set<MiaSource>> {
        val preferences = service ?: return flowOf(MiaSource.entries.toSet())
        val flows = MiaSource.entries.map { source ->
            preferences.read(source.preference)
        }
        return combine(flows) { values ->
            MiaSource.entries.filterIndexedTo(mutableSetOf()) { index, _ -> values[index] }
        }
    }

    override fun writable(): Flow<Set<MiaSource>> {
        val preferences = service ?: return flowOf(emptySet())
        val writables = MiaSource.entries.filter { it.writable }
        if (writables.isEmpty()) return flowOf(emptySet())
        val flows = writables.map { source -> preferences.read(source.writePreference!!) }
        return combine(flows) { values ->
            writables.filterIndexedTo(mutableSetOf()) { index, _ -> values[index] }
        }
    }

    override suspend fun setWritable(source: MiaSource, granted: Boolean) {
        val preference = source.writePreference ?: return
        service?.set(preference, granted)
    }

    override suspend fun setGranted(source: MiaSource, granted: Boolean) {
        // ⚠ A failed write must not read as success — the switch would spring back on the
        // next emission, which is confusing but honest. Silently swallowing it would leave
        // the user believing a source is revoked when it is not.
        service?.set(source.preference, granted)
    }
}
