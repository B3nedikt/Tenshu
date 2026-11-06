package app.tenshu.fit.data.xml

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSXMLParser
import platform.Foundation.NSXMLParserDelegateProtocol
import platform.Foundation.dataWithBytes
import platform.darwin.NSObject

/**
 * `NSXMLParser` (Foundation, no external deps) — the **iOS** binding of the [XmlReader] seam, twin
 * of [app.tenshu.fit.data.xml.JvmXmlReader]'s `javax.xml`.
 *
 * Without it iOS could open a Takeout archive and still not read a single workout: the daily-metrics
 * CSV would import, but every `.gpx`/`.tcx` activity — the actual sessions — would parse to nothing,
 * because [app.tenshu.fit.data.ActivityFileParser] is portable only down to this DOM seam.
 *
 * `NSXMLParser` is SAX, so this builds the small tree the interface describes as events arrive. That
 * is affordable because the documents are per-workout activity files (kilobytes), never the archive.
 *
 * **Semantics are matched to the JVM reader deliberately, not approximately** — the parsers above
 * this seam are shared, so a divergence would show up as platform-specific import bugs rather than
 * as a failure here: namespace-agnostic local names (`<gpxtpx:hr>` answers to `"hr"`),
 * [XmlNode.descendants] spans any depth in document order and excludes self, [XmlNode.text] takes
 * the *first* such descendant's full nested text, trimmed, and [XmlNode.attr] matches the attribute
 * name exactly.
 */
class IosXmlReader : XmlReader {

    @OptIn(ExperimentalForeignApi::class)
    override fun parse(bytes: ByteArray): XmlNode? {
        if (bytes.isEmpty()) return null
        val data: NSData = bytes.usePinned { pinned ->
            NSData.dataWithBytes(pinned.addressOf(0), bytes.size.convert())
        }
        val builder = TreeBuilder()
        val parser = NSXMLParser(data)
        parser.delegate = builder
        // `parse()` returning false means malformed input — the seam's contract is null, not a throw.
        val ok = parser.parse()
        return if (ok && !builder.failed) builder.root else null
    }
}

/**
 * Accumulates the SAX event stream into [IosXmlElement]s. Kept private to the reader: the tree is an
 * implementation detail of this binding, exactly as the W3C DOM is of the JVM one.
 */
private class TreeBuilder : NSObject(), NSXMLParserDelegateProtocol {

    var root: IosXmlElement? = null
    var failed: Boolean = false

    private val open = ArrayList<IosXmlElement>()

    override fun parser(
        parser: NSXMLParser,
        didStartElement: String,
        namespaceURI: String?,
        qualifiedName: String?,
        attributes: Map<Any?, *>,
    ) {
        // Local name: `NSXMLParser` reports the qualified name when namespace processing is off
        // (the default), so `gpxtpx:hr` arrives whole and is stripped here — matching the JVM
        // reader's `localName ?: nodeName` then `substringAfterLast(':')`.
        val element = IosXmlElement(didStartElement.substringAfterLast(':'))
        attributes.forEach { (key, value) ->
            val name = key as? String ?: return@forEach
            val text = value as? String ?: return@forEach
            element.attributes[name] = text
        }
        open.lastOrNull()?.children?.add(element)
        if (root == null) root = element
        open.add(element)
    }

    override fun parser(
        parser: NSXMLParser,
        didEndElement: String,
        namespaceURI: String?,
        qualifiedName: String?,
    ) {
        open.removeLastOrNull()
    }

    override fun parser(parser: NSXMLParser, foundCharacters: String) {
        open.lastOrNull()?.text?.append(foundCharacters)
    }

    override fun parser(parser: NSXMLParser, parseErrorOccurred: NSError) {
        failed = true
    }
}

private class IosXmlElement(override val tag: String) : XmlNode {

    val children = ArrayList<IosXmlElement>()
    val attributes = HashMap<String, String>()

    /** This element's *own* character data; nested text is folded in by [textContent]. */
    val text = StringBuilder()

    override fun descendants(tag: String): List<XmlNode> {
        val out = ArrayList<XmlNode>()
        collect(tag, out)
        return out
    }

    override fun text(tag: String): String? = firstDescendant(tag)?.textContent()?.trim()?.ifEmpty { null }

    override fun childText(tag: String): String? =
        children.firstOrNull { it.tag == tag }?.textContent()?.trim()?.ifEmpty { null }

    override fun attr(name: String): String? = attributes[name]?.ifEmpty { null }

    /** Document order, any depth, excluding self — the JVM reader's `getElementsByTagName("*")` walk. */
    private fun collect(tag: String, into: MutableList<XmlNode>) {
        children.forEach { child ->
            if (child.tag == tag) into.add(child)
            child.collect(tag, into)
        }
    }

    private fun firstDescendant(tag: String): IosXmlElement? {
        children.forEach { child ->
            if (child.tag == tag) return child
            child.firstDescendant(tag)?.let { return it }
        }
        return null
    }

    /** All text in this subtree, concatenated — the DOM's `textContent`. */
    private fun textContent(): String {
        val out = StringBuilder()
        appendTextTo(out)
        return out.toString()
    }

    private fun appendTextTo(out: StringBuilder) {
        out.append(text)
        children.forEach { it.appendTextTo(out) }
    }
}
