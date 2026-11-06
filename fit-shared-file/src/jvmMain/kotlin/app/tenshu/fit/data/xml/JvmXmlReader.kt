package app.tenshu.fit.data.xml

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** `javax.xml` (JDK, no external deps) — the desktop/JVM binding of the [XmlReader] seam. */
class JvmXmlReader : XmlReader {
    override fun parse(bytes: ByteArray): XmlNode? = runCatching {
        val root = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(bytes))
            .documentElement
        JvmXmlNode(root)
    }.getOrNull()
}

private class JvmXmlNode(private val element: Element) : XmlNode {
    override val tag: String get() = element.localOrTagName()

    override fun descendants(tag: String): List<XmlNode> {
        val out = ArrayList<XmlNode>()
        val nodes = element.getElementsByTagName("*")
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element && n.localOrTagName() == tag) out += JvmXmlNode(n)
        }
        return out
    }

    override fun text(tag: String): String? {
        val nodes = element.getElementsByTagName("*")
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element && n.localOrTagName() == tag) return n.textContent?.trim()?.ifEmpty { null }
        }
        return null
    }

    override fun childText(tag: String): String? {
        val nodes = element.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element && n.localOrTagName() == tag) return n.textContent?.trim()?.ifEmpty { null }
        }
        return null
    }

    override fun attr(name: String): String? = element.getAttribute(name).ifEmpty { null }
}

private fun Node.localOrTagName(): String = (localName ?: nodeName).substringAfterLast(':')
