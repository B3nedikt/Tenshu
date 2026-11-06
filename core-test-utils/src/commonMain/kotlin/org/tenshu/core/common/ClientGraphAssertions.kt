package org.tenshu.core.common

import org.tenshu.core.common.module.ModuleConfiguration

/**
 * Assert that every service an app **reaches** is registered in its client graph.
 *
 * ## ⚠⚠ The failure this exists for is silent, which is why it needs a test at all
 *
 * `TenshuEnvironment.getService` **errors** on an unregistered schema — *"No schema registered for
 * service id"* — and app repositories catch. So a missing registration is not a crash; it is a
 * screen that renders as though the data were simply absent. Photos shipped three phases that way
 * (`photos-app.md` §18.4) and each looked like a different, plausible, ordinary bug: an empty
 * Places section, a *"Can't reach Tenshu Control"* in Settings, a toggle stuck at its default.
 *
 * ⚠⚠ **The announce gate cannot catch it, and that is by design rather than an oversight.**
 * `AnnounceSessionImplementation` iterates the *client's own* version vector and its KDoc states the
 * rule: *"Proxy services the client did not announce are NOT mismatches — the client simply doesn't
 * use them."* A service the client never registered contributes no version, so there is nothing to
 * disagree about and the session is admitted. All five of §18.4's unregistered services announced
 * `admitted=true`.
 *
 * ⚠ Nothing derives [reached] from source — the compiler cannot see the gap, because each side is
 * correct in isolation: the app declares a Gradle dependency (which compiles) and the runtime list
 * lives two modules away. So it is a list a human maintains, and it carries an instruction rather
 * than a claim: **when a repository reaches a new service, add it here and to the app's configs.**
 *
 * @param app the app's name, for the failure message.
 * @param configs the app's schema-config list, as its DI module registers it.
 * @param reached what the app actually calls: *why it is reached* → the service id.
 */
fun assertClientGraphRegisters(
    app: String,
    configs: List<ModuleConfiguration>,
    reached: Map<String, String>,
) {
    // ⚠ A guard on the guard: an empty or unresolved config list would make every assertion below
    // pass for the worst possible reason.
    val ids = schemaIdsOf(configs)
    check(configs.isNotEmpty()) { "$app: the schema-config list resolved to nothing" }
    check(ids.size > 10) { "$app: the config list contributed only ${ids.size} schema ids" }

    val missing = reached.filterValues { it !in ids }
    check(missing.isEmpty()) {
        "$app's client graph does not register: " +
            missing.entries.joinToString { "${it.value} (${it.key})" } +
            "\n\n⚠⚠ This does NOT crash at runtime — `getService` throws and the caller catches, " +
            "so the screen renders as though the data were absent. Add the service's `-common` " +
            "and `-client` modules to the app's schema configs."
    }
}

/** Every schema id [configs] contribute, following prerequisites depth-first. */
fun schemaIdsOf(configs: List<ModuleConfiguration>): Set<String> {
    val seen = mutableSetOf<ModuleConfiguration>()
    val ids = mutableSetOf<String>()
    fun walk(config: ModuleConfiguration) {
        if (!seen.add(config)) return
        config.prerequisites.forEach(::walk)
        ids += config.schemaMapping.keys
    }
    configs.forEach(::walk)
    return ids
}
