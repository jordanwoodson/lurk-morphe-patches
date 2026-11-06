package io.github.jordanwoodson.patches.shared

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import org.w3c.dom.Element

/**
 * Copies the vector drawable [icon] to [name], keeping only the paths [keep] picks from all of them
 * (null when the icon isn't the expected one). Used for seek icons without their built-in number.
 */
internal fun ResourcePatchContext.copyDrawablePaths(
    icon: String,
    name: String,
    app: String,
    keep: (List<Element>) -> List<Element>?,
) {
    val source = get("res/drawable/$icon.xml")
    if (!source.exists()) throw PatchException("$app: no $icon icon")
    get("res/drawable/$name.xml").apply { parentFile.mkdirs() }.writeBytes(source.readBytes())
    document("res/drawable/$name.xml").use { doc ->
        val paths = doc.getElementsByTagName("path").let { list -> List(list.length) { list.item(it) as Element } }
        val kept = keep(paths) ?: throw PatchException("$app: the $icon icon changed")
        (paths - kept.toSet()).forEach { it.parentNode.removeChild(it) }
    }
}
