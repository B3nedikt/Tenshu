package app.tenshu.fit.data.xml

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.parsing.DOMParser

/**
 * The browser (`DOMParser`) binding of the [XmlReader] seam — the web twin of the desktop
 * `JvmXmlReader` (`javax.xml`). Lets the `commonMain` GPX/TCX [app.tenshu.fit.data.ActivityFileParser]
 * run on the web with no platform XML API leaking into the parse logic. Bound via DI by a web Fit
 * consumer (mirroring the [HealthImporter]/reader-seam convention: a platform binds the reader its
 * file-import story needs; Android/iOS — Health Connect / HealthKit — bind none).
 *
 * **Namespace note.** `DOMParser` parses `application/xml` **namespace-aware**, whereas the JVM
 * reader runs non-namespace-aware. Both resolve a node by its *local* name (the part after any
 * `':'`), so a well-formed file behaves identically across platforms. The one difference is
 * strictness: a browser rejects an **undeclared** namespace prefix as a parse error where the JVM
 * tolerates it. Real-world GPX/TCX declare their namespaces (e.g. `xmlns:gpxtpx`), so this only
 * bites hand-rolled fixtures that omit them — see the jsTest, which uses namespaced GPX.
 */
class JsXmlReader : XmlReader {
    override fun parse(bytes: ByteArray): XmlNode? {
        val doc = runCatching {
            DOMParser().parseFromString(bytes.decodeToString(), "application/xml")
        }.getOrNull() ?: return null
        val root = doc.documentElement ?: return null
        // DOMParser signals malformed XML by returning a <parsererror> document, not by throwing.
        if (root.localName() == "parsererror" || root.getElementsByTagName("parsererror").length > 0) return null
        return JsXmlNode(root)
    }
}

private class JsXmlNode(private val element: Element) : XmlNode {
    override val tag: String get() = element.localName()

    override fun descendants(tag: String): List<XmlNode> {
        val out = ArrayList<XmlNode>()
        val nodes = element.getElementsByTagName("*")
        for (i in 0 until nodes.length) {
            val n = nodes.item(i) ?: continue
            if (n.localName() == tag) out += JsXmlNode(n)
        }
        return out
    }

    override fun text(tag: String): String? {
        val nodes = element.getElementsByTagName("*")
        for (i in 0 until nodes.length) {
            val n = nodes.item(i) ?: continue
            if (n.localName() == tag) return n.textContent?.trim()?.ifEmpty { null }
        }
        return null
    }

    override fun childText(tag: String): String? {
        val nodes = element.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i) ?: continue
            if (n.nodeType == Node.ELEMENT_NODE && n.localName() == tag) {
                return n.textContent?.trim()?.ifEmpty { null }
            }
        }
        return null
    }

    override fun attr(name: String): String? = element.getAttribute(name)?.ifEmpty { null }
}

/**
 * The element's local name. Read off `nodeName` (the *qualified* name — `gpxtpx:hr`) rather than
 * the DOM `localName` property so the result is identical whether the parse was namespace-aware
 * (browser) or not (JVM); stripping any prefix matches the JVM reader's `substringAfterLast(':')`.
 */
private fun Node.localName(): String = nodeName.substringAfterLast(':')
