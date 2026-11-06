package org.tenshu.sync.relay

import io.ktor.server.application.Application
import io.ktor.server.application.plugin
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.routing.path
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ⚠⚠ **No Caddy access log names a capability** (relay-metadata-minimisation F4;
 * `docs/how-to/server-logging.md`). The `request>uri` filters in the repo's Caddyfiles are a
 * hand-kept rule list; this test is what keeps them honest.
 *
 * It walks the relay's **real** Ktor route tree (and the apex SPA's routes in `RuntimeApp.tsx`),
 * builds a concrete request path for every route that takes a path parameter, runs it through every
 * `format filter` block of every Caddyfile that fronts the relay or the apex — the rules applied in
 * order with `java.util.regex`, which treats the RE2 subset used there the same way — and asserts
 * that no capability survives. Also for the variants Ktor still routes but an anchored rule cannot
 * see (`//share/<id>`, `/%73hare/<id>`, a percent-encoded id) and with a query string. And the
 * other direction: a route with no parameter must come out of the filter unchanged, so a rule
 * cannot quietly eat a constant route (`/sync/content/declare` read as a content ref).
 *
 * A new path parameter fails here until it is given an example in [relayParams] / [apexParams] —
 * which is the moment to decide whether it is a capability.
 */
class CaddyLogFilterTest {

    private class Param(val example: String, val secret: Boolean)

    // Realistic values: the shapes clients mint, at their real lengths. The capability ones are what
    // the backstop's `{20,}` bound is measured against.
    private val relayParams = mapOf(
        "shareId" to Param("aB3_-xYz09QwErTyUiOpAs", secret = true), // 16 random bytes, base64url (22)
        "ref" to Param("blob-" + "0123456789abcdef".repeat(4), secret = true), // REF_PATTERN (69)
        "inviteId" to Param("1a2b3c4d5e6f708192a3b4c5d6e7f801", secret = true), // 32 hex
        "address" to Param("fedcba98765432100123456789abcdef", secret = true), // 32 hex
        "sessionId" to Param("00112233445566778899aabbccddeeff", secret = true), // 32 hex
        "chunkIndex" to Param("7", secret = false),
        "direction" to Param("up", secret = false),
    )

    private val apexParams = mapOf(
        "shareId" to Param("Zz9_-QwErTyUiOpAsDfGhJ", secret = true),
        "deviceId" to Param("3f2b8c1e-9a4d-4e6f-8b21-7c5d0e9a1f34", secret = true),
        "appId" to Param("drive", secret = false),
        "productId" to Param("storage-100", secret = false),
    )

    /**
     * The Caddyfiles in front of the relay or the apex. `commerce-service/Caddyfile` is not one: no
     * production commerce route takes a path parameter (only the dev-only fake Stripe checkout).
     */
    private val caddyfiles = listOf(
        "sync-relay-server/Caddyfile",
        "web/apex/Caddyfile",
        "deploy/scaleway-dev/stack/Caddyfile",
    )

    @Test
    fun noCaddyAccessLogKeepsACapabilityFromAnyRoute() {
        val relay = relayRouteTemplates()
        assertTrue(relay.any { it.startsWith("/share/{shareId}") }, "the route walk found no share route: $relay")
        val apex = apexRouteTemplates()
        assertTrue("/s/{shareId}" in apex, "RuntimeApp.tsx parse found no share route: $apex")

        val concrete = relay.filter { '{' in it }.map { concretise(it, relayParams) } +
            apex.filter { '{' in it }.map { concretise(it, apexParams) }
        val constant = relay.filterNot { '{' in it } + apex.filterNot { '{' in it }

        for (caddyfile in caddyfiles) {
            val filters = uriFilters(repoFile(caddyfile))
            for ((index, filter) in filters.withIndex()) {
                val where = "$caddyfile, filter #${index + 1}"
                for ((path, secrets) in concrete) {
                    for (variant in variants(path, secrets)) {
                        val logged = filter.apply(variant)
                        for (secret in secrets) {
                            // Not even a fragment: drop the first char (the encoded variants change it).
                            val probe = secret.drop(1).take(LOG_ID_PREFIX)
                            assertTrue(probe !in logged, "$where logs `$logged` for `$variant` — the id survives")
                        }
                    }
                }
                for (path in constant) {
                    assertEquals(path, filter.apply(path), "$where rewrites the constant route $path")
                }
            }
        }
    }

    // --- the routes -------------------------------------------------------------------------

    private fun relayRouteTemplates(): Set<String> {
        lateinit var app: Application
        var templates = emptySet<String>()
        testApplication {
            application {
                testModule()
                app = this
            }
            startApplication()
            // While it runs: a stopped application has uninstalled its plugins.
            templates = app.plugin(RoutingRoot).getAllRoutes().map { it.path.ifEmpty { "/" } }.toSet()
        }
        return templates
    }

    /** `<Route path="s/:shareId" …>` → `/s/{shareId}`. Wildcards are not served paths. */
    private fun apexRouteTemplates(): Set<String> =
        Regex("""\bpath="([^"]*)"""").findAll(repoFile("web/apex/src/RuntimeApp.tsx").readText())
            .map { it.groupValues[1] }
            .filterNot { '*' in it }
            .map { "/" + it.trimStart('/').replace(Regex(""":([A-Za-z0-9_]+)"""), "{$1}") }
            .toSet()

    /** The template with every parameter filled in, and the values that must not reach a log. */
    private fun concretise(template: String, params: Map<String, Param>): Pair<String, List<String>> {
        val secrets = mutableListOf<String>()
        val path = Regex("""\{([A-Za-z0-9_]+)(?:\?|\.\.\.)?}""").replace(template) { match ->
            val name = match.groupValues[1]
            val param = params[name] ?: fail(
                "`$template` takes a path parameter `{$name}` this test has no example for. Add one to " +
                    "CaddyLogFilterTest — and if it is a capability, make sure the Caddyfiles hide it.",
            )
            if (param.secret) secrets += param.example
            param.example
        }
        return path to secrets
    }

    /** Every spelling of [path] that a client could send and Ktor would still route the same way. */
    private fun variants(path: String, secrets: List<String>): List<String> {
        val firstSegment = path.drop(1).substringBefore('/')
        val encodedPrefix = "/%" + firstSegment.first().code.toString(16) + path.drop(2) // /%73hare/<id>
        val encodedIds = secrets.fold(path) { p, id -> p.replace(id, "%" + id.first().code.toString(16) + id.drop(1)) }
        return listOf(path, "/$path", encodedPrefix, encodedIds, "$path?token=x&email=a@b.c")
    }

    // --- the Caddyfiles ---------------------------------------------------------------------

    /** One `format filter { … request>uri … }` block: its regexp rules, applied in order. */
    private class UriFilter(val rules: List<Pair<Regex, String>>) {
        fun apply(uri: String): String = rules.fold(uri) { acc, (regex, replacement) -> regex.replace(acc, replacement) }
    }

    private fun uriFilters(file: File): List<UriFilter> {
        val lines = file.readLines().map { it.trim() }.filterNot { it.startsWith("#") }
        val filterBlocks = lines.count { it.startsWith("format filter") }
        val filters = mutableListOf<UriFilter>()
        var i = 0
        while (i < lines.size) {
            val tokens = tokens(lines[i])
            if (tokens.firstOrNull() == "request>uri") {
                when (tokens.getOrNull(1)) {
                    "regexp" -> filters += UriFilter(listOf(rule(file, tokens.drop(2))))
                    "multi_regexp" -> {
                        val rules = mutableListOf<Pair<Regex, String>>()
                        while (lines[++i] != "}") {
                            val rule = tokens(lines[i])
                            assertEquals("regexp", rule.first(), "${file.path}: unexpected `${lines[i]}` in multi_regexp")
                            rules += rule(file, rule.drop(1))
                        }
                        filters += UriFilter(rules)
                    }
                    else -> fail("${file.path}: `${lines[i]}` — this test knows regexp and multi_regexp only")
                }
            }
            i++
        }
        assertTrue(filterBlocks > 0, "${file.path} has no `format filter` block")
        assertEquals(filterBlocks, filters.size, "${file.path}: every `format filter` block needs its own request>uri filter")
        return filters
    }

    /** Caddy's regexp replacement is Go's `Regexp.ReplaceAllString`; translate it to Java's. */
    private fun rule(file: File, args: List<String>): Pair<Regex, String> {
        assertEquals(2, args.size, "${file.path}: a uri regexp takes a pattern and a replacement: $args")
        val (pattern, replacement) = args
        // Go reads `$1x` as the group NAMED `1x` (empty); Java as group 1 then `x`. Ban the ambiguity.
        assertTrue(!Regex("""\$\d+\w""").containsMatchIn(replacement), "${file.path}: write `\${1}x`, not `\$1x`: $replacement")
        val java = replacement.replace("\\", "\\\\").replace(Regex("""\$\{(\d+)}"""), "\\$$1")
        return Regex(pattern) to java
    }

    /** Caddyfile tokens: whitespace-separated, `"…"` quoted with `\"` escapes. */
    private fun tokens(line: String): List<String> =
        Regex(""""((?:[^"\\]|\\.)*)"|(\S+)""").findAll(line).map { m ->
            m.groups[1]?.value?.replace("\\\"", "\"") ?: m.groupValues[2]
        }.toList()

    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        fail("could not find $relative walking up from ${System.getProperty("user.dir")}")
    }
}
