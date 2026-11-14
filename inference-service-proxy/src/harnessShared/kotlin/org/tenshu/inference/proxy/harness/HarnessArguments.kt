package org.tenshu.inference.proxy.harness

import java.io.File

/**
 * The face-recall harness's optional instrumentation arguments, split out of
 * `FaceDetectionStrategyExperimentTest` so they can be tested off the phone.
 *
 * ⚠⚠ **Supplied is not the same as non-empty.** Each argument is either omitted — the default
 * behaviour — or supplied, and a supplied one that parses to nothing, parses partly, or matches
 * nothing FAILS the run. Treating an empty parse like an omitted argument analyses every portrait
 * preview sideways and still writes a complete, wrong report.
 */
object HarnessArguments {

    /**
     * `-e photos <dir>`: the corpus, relative to [root]. Omitted, it is [DEFAULT_PHOTOS], and an
     * absent or empty default corpus returns no photos — the harness's existing skip. Supplied, the
     * directory must exist, be a readable directory and hold at least one photo, else the run fails:
     * a typo would otherwise walk nothing and pass having written no report (§15.12.6's
     * empty-versus-unreadable ambiguity). Either way, two photos with one basename fail.
     *
     * [readable] is `File.canRead` outside tests. ⚠ A test cannot make a file unreadable for root
     * (`setReadable(false)` changes nothing under `CAP_DAC_OVERRIDE`, and cloud containers run
     * as root), so the unreadable case is tested through this seam rather than through `chmod`.
     */
    fun photos(root: File, requested: String?, readable: (File) -> Boolean = File::canRead): List<File> {
        val dir = File(root, requested ?: DEFAULT_PHOTOS)
        if (requested != null) {
            check(dir.isDirectory) { "IN-6 recall: -e photos $dir is not a directory" }
            check(readable(dir) && dir.list() != null) { "IN-6 recall: -e photos $dir is unreadable" }
        }
        val found = dir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in PHOTO_EXTENSIONS }
            .sortedBy { it.absolutePath }
            .toList()
        check(requested == null || found.isNotEmpty()) { "IN-6 recall: -e photos $dir holds no photos" }
        // ⚠ The manifest and the per-photo orientation lookup key on the BASENAME, and the walk is
        // recursive: two `a.jpg`s in different subdirectories would share one orientation, so one
        // could be analysed sideways. Fail whether or not a manifest was supplied.
        val clashes = found.groupBy { it.name }.filterValues { it.size > 1 }
        check(clashes.isEmpty()) {
            "IN-6 recall: the corpus $dir has duplicate photo names: " +
                clashes.values.joinToString("; ") { same -> same.joinToString(", ") { it.relativeTo(dir).path } }
        }
        return found
    }

    /**
     * `-e orientations <file>`, relative to [root]: omitted means no orientation; supplied, the file
     * must be a readable regular file, then [orientations]' rules apply. [readable] as in [photos].
     */
    fun orientationManifest(
        root: File,
        requested: String?,
        photoNames: Collection<String>,
        readable: (File) -> Boolean = File::canRead,
    ): Map<String, Int> {
        val lines = requested?.let { name ->
            val file = File(root, name)
            check(file.isFile && readable(file)) { "IN-6 recall: orientation manifest $file is missing or unreadable" }
            file.readLines()
        }
        return orientations(lines, photoNames)
    }

    const val DEFAULT_PHOTOS = "face_photos"
    private val PHOTO_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "img")

    /**
     * `-e orientations <file>`: one `name orientation` per line (EXIF 1..8), blank lines ignored.
     * [lines] is null when the argument was omitted, which means "no orientation for any photo".
     * Otherwise every non-blank line must parse, there must be at least one, and they must name at
     * least one of [photoNames]; coverage is printed.
     */
    fun orientations(lines: List<String>?, photoNames: Collection<String>): Map<String, Int> {
        if (lines == null) return emptyMap()
        val parsed = mutableMapOf<String, Int>()
        lines.forEachIndexed { i, raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEachIndexed
            val parts = line.split(Regex("\\s+"))
            val orientation = parts.getOrNull(1)?.toIntOrNull()
            check(parts.size == 2 && orientation != null && orientation in 1..8) {
                "IN-6 recall: orientation manifest line ${i + 1} is not `name orientation(1..8)`: '$raw'"
            }
            val previous = parsed.put(parts[0], orientation)
            check(previous == null || previous == orientation) {
                "IN-6 recall: orientation manifest gives ${parts[0]} both $previous and $orientation"
            }
        }
        check(parsed.isNotEmpty()) { "IN-6 recall: the orientation manifest is empty" }
        val covered = photoNames.count { it in parsed }
        println("IN-6 recall: orientation manifest covers $covered of ${photoNames.size} photos")
        check(covered > 0) { "IN-6 recall: the orientation manifest names none of the photos" }
        return parsed
    }

    /**
     * `-e strategies a,b`: which of [known] to run. The first ([known]`.first()`, single stage) is
     * the baseline every other one is diffed against, so it always runs. A supplied name that is
     * not in [known] fails rather than being filtered away.
     */
    fun strategies(requested: String?, known: List<String>): List<String> {
        if (requested == null) return known
        val names = requested.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        check(names.isNotEmpty()) { "IN-6 recall: -e strategies names no strategy" }
        val unknown = names - known.toSet()
        check(unknown.isEmpty()) { "IN-6 recall: unknown strategies $unknown; known: $known" }
        return known.filter { it == known.first() || it in names }
    }
}
