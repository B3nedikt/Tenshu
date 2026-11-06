package app.tenshu.fit.data.xml

/**
 * A minimal, read-only XML node — exactly what the GPX/TCX activity parsers touch, nothing more.
 * **Namespace-agnostic**: [tag] and every lookup match the *local* name (the part after any ':'),
 * so `<gpxtpx:hr>` and `<hr>` both answer to `"hr"`.
 *
 * Deliberately a **plain interface bound via DI** (not `expect`/`actual`), mirroring the
 * [app.tenshu.fit.data.HealthImporter] convention: the JVM binds a `javax.xml` impl
 * ([JvmXmlReader]), web would bind a browser-`DOMParser` impl, and platforms with no file-import
 * story (Android → Health Connect, iOS → HealthKit) simply **don't bind one** — no stub `actual`
 * forced into every target. Keeping the XML seam here is what lets [app.tenshu.fit.data.ActivityFileParser]
 * live in `commonMain` (the parse logic is portable; only the DOM is platform-specific).
 */
interface XmlNode {

    /** This element's local tag name. */
    val tag: String

    /** All descendant elements (any depth) whose local name is [tag]. */
    fun descendants(tag: String): List<XmlNode>

    /** Trimmed text of the first descendant named [tag], or null if absent/empty. */
    fun text(tag: String): String?

    /**
     * Trimmed text of the first **direct child** named [tag], or null if absent/empty.
     *
     * ⚠ Not a convenience over [text] — the distinction is load-bearing. A container element often
     * shares a name with something inside its own subtree: a TCX `<Lap>` carries a summary
     * `<DistanceMeters>` **and** contains a `<Track>` whose every `<Trackpoint>` carries its own
     * `<DistanceMeters>`. Google Fit writes the `<Track>` *first*, so `text("DistanceMeters")` on a
     * lap returns the first trackpoint's cumulative-distance-so-far — `0.0` — and every Takeout
     * walk imported with a distance of zero. Read a container's own summary fields with this;
     * reach into a subtree with [text].
     */
    fun childText(tag: String): String?

    /** Value of attribute [name] on this element, or null if absent/empty. */
    fun attr(name: String): String?
}

/** Parses XML bytes into a root [XmlNode]. Platform-bound via DI. */
interface XmlReader {
    /** Parse [bytes] into the document root, or null if it isn't well-formed XML. */
    fun parse(bytes: ByteArray): XmlNode?
}
