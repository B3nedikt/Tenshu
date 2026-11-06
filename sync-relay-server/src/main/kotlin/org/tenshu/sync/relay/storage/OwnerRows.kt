package org.tenshu.sync.relay.storage

import org.tenshu.sync.relay.mailbox.requireErasableOwner

/**
 * `DELETE FROM <table> WHERE <column> = ?` for each of [tables], on the shared connection — the body of
 * most SQLite `eraseOwner` implementations (account closure, F12).
 *
 * ⚠ Goes through [RelayDatabase.withConnection], so inside `AccountErasure`'s
 * [RelayDatabase.inTransaction] it joins that one transaction instead of committing on its own.
 * [tables] are compile-time constants at every call site, never caller input.
 */
internal fun RelayDatabase.deleteOwnerRows(ownerId: String, vararg tables: Pair<String, String>): Int {
    requireErasableOwner(ownerId)
    return withConnection { conn ->
        tables.sumOf { (table, column) ->
            conn.prepareStatement("DELETE FROM $table WHERE $column = ?").use { ps ->
                ps.setString(1, ownerId)
                ps.executeUpdate()
            }
        }
    }
}
