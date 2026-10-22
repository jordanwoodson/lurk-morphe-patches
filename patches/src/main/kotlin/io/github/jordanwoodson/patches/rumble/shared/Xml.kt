package io.github.jordanwoodson.patches.rumble.shared

import org.w3c.dom.Element

// DOM helpers for the Rumble resource patches (manifest and resource XML).

/** The direct child elements, or only those named [tag]. */
internal fun Element.childElements(tag: String? = null): List<Element> =
    (0 until childNodes.length).map { childNodes.item(it) }
        .filterIsInstance<Element>()
        .filter { tag == null || it.tagName == tag }

/** Every element below this one, in document order. */
internal fun Element.descendants(): List<Element> {
    val nodes = getElementsByTagName("*")
    return (0 until nodes.length).map { nodes.item(it) as Element }
}
