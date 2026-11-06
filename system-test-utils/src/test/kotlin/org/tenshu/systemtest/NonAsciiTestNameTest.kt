package org.tenshu.systemtest

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A backtick test name must stay ASCII, because it becomes a class **file** name.
 *
 * ⚠⚠ This is a build-portability guard, not a style rule. `sun.jnu.encoding` is what the
 * JVM uses to encode file paths (`sun.nio.fs.UnixPath.encode`), and it is derived from the
 * OS locale at startup — under a POSIX/C locale it is ASCII. So one em dash inside
 * `fun \`… — …\`()` makes the whole module fail to compile on any machine whose locale is
 * not UTF-8:
 *
 * ```
 * e: java.nio.file.InvalidPathException: Malformed input or input contains unmappable
 *    characters: …/AccountClosureDeliveryTest$a failed delivery is simply re-minted next
 *    sweep — there is no queue$1.class
 * ```
 *
 * ⚠ There is no build-file fix. Unlike `file.encoding`, `sun.jnu.encoding` is overwritten
 * by the JVM during startup, so `-Dsun.jnu.encoding=UTF-8` in `org.gradle.jvmargs` is
 * silently ignored (verified with `java -XshowSettings:properties`); only `LANG`/`LC_ALL`
 * changes it, and the build cannot set its own launching environment. Keeping the names
 * ASCII is what makes the build independent of the developer's locale — four names had
 * already drifted, and `:commerce-service` was uncompilable here because of one of them.
 *
 * ⚠ Deliberately a **test**, not a lint rule or a configuration-time check: it costs one
 * file walk, it runs inside the ordinary gate, and it reports the offending line with the
 * reason attached — which is the part the raw `InvalidPathException` never gives you.
 */
class NonAsciiTestNameTest {

    @Test
    fun backtickTestNamesAreAscii() {
        val root = repoRoot()
        val offenders = root.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" && it.name != "node_modules" }
            .filter { it.isFile && it.extension == "kt" && "${File.separator}src${File.separator}" in it.path }
            .flatMap { file ->
                file.readLines().asSequence().mapIndexedNotNull { index, line ->
                    val name = BACKTICK_FUN.find(line)?.groupValues?.get(1)
                    val bad = name?.filter { it.code > 127 }?.toSet().orEmpty()
                    if (bad.isEmpty()) null else {
                        val chars = bad.joinToString(" ") { "'$it' (U+%04X)".format(it.code) }
                        "${file.relativeTo(root)}:${index + 1}  $chars  in `$name`"
                    }
                }
            }
            .toList()

        assertTrue(
            offenders.isEmpty(),
            "A backtick test name becomes a class FILE name, and file names are encoded with " +
                "`sun.jnu.encoding` — which follows the OS locale and is ASCII under a POSIX/C " +
                "locale. These names make their module fail to COMPILE for anyone not on a " +
                "UTF-8 locale, with an `InvalidPathException` that never mentions encoding. " +
                "Replace the non-ASCII characters (an em dash reads fine as '-'):\n" +
                offenders.joinToString("\n").prependIndent("  "),
        )
    }

    /** Walk up to the directory holding `settings.gradle.kts` — the test's working dir is its module. */
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return checkNotNull(dir) { "repo root not found above ${System.getProperty("user.dir")}" }
    }

    private companion object {
        /** `fun \`some name\`(` — the backtick-declared test-name form. */
        val BACKTICK_FUN = Regex("""\bfun\s+`([^`]+)`\s*\(""")
    }
}
