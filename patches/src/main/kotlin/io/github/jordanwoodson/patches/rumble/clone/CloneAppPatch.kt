package io.github.jordanwoodson.patches.rumble.clone

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import io.github.jordanwoodson.patches.rumble.shared.Constants.COMPATIBILITY_RUMBLE
import io.github.jordanwoodson.patches.rumble.shared.childElements
import io.github.jordanwoodson.patches.rumble.shared.descendants
import org.w3c.dom.Element

private const val RUMBLE_PACKAGE = "com.rumble.battles"

// Rumble also declares names outside its package: a content provider its other apps read, and the
// permission guarding it. A second install with the same names would be refused, so they move too.
private const val RUMBLE_PROVIDER = "com.rumble.provider"
private const val RUMBLE_PROVIDER_PERMISSION = "com.rumble.READ_RUMBLE_DATA"

private val PACKAGE_NAME = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")

@Suppress("unused")
val cloneAppPatch = resourcePatch(
    name = "Clone app",
    description = "Installs Rumble under another package name, so it sits alongside the original app with its own " +
        "sign-in and settings. Use \"Change app name\" to tell the two apart in the launcher.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_RUMBLE)

    val packageName by stringOption(
        key = "packageName",
        default = "$RUMBLE_PACKAGE.lurk",
        values = mapOf("Rumble (Lurk)" to "$RUMBLE_PACKAGE.lurk"),
        title = "Package name",
        description = "The package name for the clone. Each install needs its own.",
        required = true,
    ) { it != null && it != RUMBLE_PACKAGE && PACKAGE_NAME.matches(it) }

    fun rename(name: String): String {
        val clone = packageName!!
        return when {
            name == RUMBLE_PACKAGE -> clone
            name.startsWith("$RUMBLE_PACKAGE.") -> clone + name.removePrefix(RUMBLE_PACKAGE)
            name == RUMBLE_PROVIDER -> "$clone.provider"
            name == RUMBLE_PROVIDER_PERMISSION -> "$clone.READ_RUMBLE_DATA"
            else -> name
        }
    }

    // Rumble's code uses the provider's authority as a constant; Android code everywhere else asks
    // for the package name at runtime, so it follows the manifest.
    dependsOn(
        bytecodePatch {
            execute {
                val users = classDefByStrings(RUMBLE_PROVIDER, StringComparisonType.EQUALS)
                if (users.isEmpty()) throw PatchException("Rumble: provider authority not found in code")
                users.forEach { classDef ->
                    mutableClassDefBy(classDef).methods.forEach { method ->
                        val instructions = method.implementation?.instructions?.toList() ?: return@forEach
                        instructions.forEachIndexed { index, instruction ->
                            if (instruction.opcode != Opcode.CONST_STRING) return@forEachIndexed
                            val string = ((instruction as ReferenceInstruction).reference as StringReference).string
                            if (string != RUMBLE_PROVIDER) return@forEachIndexed
                            val register = (instruction as OneRegisterInstruction).registerA
                            method.replaceInstruction(index, "const-string v$register, \"${rename(string)}\"")
                        }
                    }
                }
            }
        },
    )

    // In finalize, so permissions and providers other patches add are renamed as well.
    finalize {
        document("AndroidManifest.xml").use { document ->
            val manifest = document.documentElement
            if (manifest.getAttribute("package") != RUMBLE_PACKAGE) {
                throw PatchException("Rumble: unexpected package ${manifest.getAttribute("package")}")
            }
            manifest.setAttribute("package", packageName!!)

            // Permissions Rumble declares, and every reference to them.
            val declared = manifest.childElements("permission").map { it.getAttribute("android:name") }.toSet()
            val attributes = listOf("android:name", "android:permission", "android:readPermission",
                "android:writePermission")
            manifest.descendants().forEach { element ->
                when (element.tagName) {
                    "provider" -> element.setAttribute(
                        "android:authorities",
                        element.getAttribute("android:authorities").split(';').joinToString(";") { rename(it) },
                    )
                    // Intent categories carry the package name (push and Amazon messaging receivers).
                    "category" -> element.renameAttribute("android:name", ::rename)
                }
                attributes.forEach { attribute ->
                    if (element.getAttribute(attribute) in declared) element.renameAttribute(attribute, ::rename)
                }
                if (element.hasAttribute("android:taskAffinity")) {
                    element.renameAttribute("android:taskAffinity", ::rename)
                }
            }
        }
    }
}

private fun Element.renameAttribute(name: String, rename: (String) -> String) =
    setAttribute(name, rename(getAttribute(name)))
