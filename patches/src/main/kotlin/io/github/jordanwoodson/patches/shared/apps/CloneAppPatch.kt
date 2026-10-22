package io.github.jordanwoodson.patches.shared.apps

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchBuilder
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.stringOption
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * A name Clone app changes besides the app's package and the names under it, because Android refuses a second
 * install that declares the same one. The app's [LurkApp.cloneRules] list the rules that apply to it.
 */
internal sealed interface CloneRule {
    /** The clone's name for [name], or null when this rule doesn't cover it. */
    fun rename(name: String, original: String, clone: String): String?

    /** Schemes of `<data>` entries whose host is the package name. */
    val hostSchemes: Set<String> get() = emptySet()

    /** Names the app's code spells as string constants instead of asking Android; they change there too. */
    val codeStrings: Set<String> get() = emptySet()
}

/**
 * Amazon's sign-in SDK: its providers (MapInfoProvider, CrossAppContextProvider) put the package name last in
 * their authority, and its `amzn://<package>` redirect brings the sign-in back to the app.
 */
internal data object AmazonSignIn : CloneRule {
    override fun rename(name: String, original: String, clone: String) =
        if (name.endsWith(".$original")) name.removeSuffix(original) + clone else null

    override val hostSchemes = setOf("amzn")
}

/**
 * [name], outside the app's package (a provider authority, a permission), becomes `<clone>.<suffix>`. With
 * [inCode], also where the app's code spells it.
 */
internal class OutsideName(
    private val name: String,
    private val suffix: String,
    inCode: Boolean = false,
) : CloneRule {
    override fun rename(name: String, original: String, clone: String) =
        if (name == this.name) "$clone.$suffix" else null

    override val codeStrings = if (inCode) setOf(name) else emptySet()
}

private val PACKAGE_NAME = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")
private val COMPONENTS = setOf("application", "activity", "activity-alias", "service", "receiver", "provider")
private val PERMISSION_ATTRIBUTES =
    listOf("android:name", "android:permission", "android:readPermission", "android:writePermission")

/**
 * The body of an app's "Clone app" patch: moves the app to the `packageName` option's package, with
 * everything a second install can't share (declared permissions, provider authorities, intent categories,
 * task affinities, the app's own URL scheme, and the app's [LurkApp.cloneRules]). [packageNameDescription]
 * and [packageNameValues] are the option's description and suggested values in Manager.
 */
internal fun ResourcePatchBuilder.cloneApp(
    app: LurkApp,
    packageNameDescription: String = "A unique package name for this install.",
    packageNameValues: Map<String, String?>? = null,
) {
    compatibleWith(app.compatibility)

    val original = app.packageName
    val packageName by stringOption(
        key = "packageName",
        default = "$original.lurk",
        values = packageNameValues,
        title = "Package name",
        description = packageNameDescription,
        required = true,
    ) { it != null && it != original && PACKAGE_NAME.matches(it) }

    fun rename(name: String): String {
        val clone = packageName!!
        return when {
            name == original -> clone
            name.startsWith("$original.") -> clone + name.removePrefix(original)
            else -> app.cloneRules.firstNotNullOfOrNull { it.rename(name, original, clone) } ?: name
        }
    }

    // Code elsewhere asks for the package name at runtime, so it follows the manifest; these constants don't.
    val codeStrings = app.cloneRules.flatMap { it.codeStrings }.toSet()
    if (codeStrings.isNotEmpty()) {
        dependsOn(
            bytecodePatch {
                execute {
                    codeStrings.forEach { constant ->
                        val users = classDefByStrings(constant, StringComparisonType.EQUALS)
                        if (users.isEmpty()) throw PatchException("${app.name}: $constant not found in code")
                        users.forEach { classDef ->
                            mutableClassDefBy(classDef).methods.forEach { method ->
                                val instructions = method.implementation?.instructions?.toList() ?: return@forEach
                                instructions.forEachIndexed { index, instruction ->
                                    if (instruction.opcode != Opcode.CONST_STRING) return@forEachIndexed
                                    val string = ((instruction as ReferenceInstruction).reference as StringReference).string
                                    if (string != constant) return@forEachIndexed
                                    val register = (instruction as OneRegisterInstruction).registerA
                                    method.replaceInstruction(index, "const-string v$register, \"${rename(string)}\"")
                                }
                            }
                        }
                    }
                }
            },
        )
    }

    // In finalize, so permissions and providers other patches add are renamed as well.
    finalize {
        document("AndroidManifest.xml").use { document ->
            val manifest = document.documentElement
            if (manifest.getAttribute("package") != original) {
                throw PatchException("${app.name}: unexpected package ${manifest.getAttribute("package")}")
            }
            val elements = manifest.descendants()
            val declared = elements.filter { it.tagName == "permission" }.map { it.getAttribute("android:name") }.toSet()
            val hostSchemes = app.cloneRules.flatMap { it.hostSchemes }.toSet()
            elements.forEach { element ->
                // Relative class names still resolve to the original compiled code after changing the app ID.
                if (element.tagName in COMPONENTS) {
                    listOf("android:name", "android:targetActivity", "android:parentActivityName").forEach { attribute ->
                        val value = element.getAttribute(attribute)
                        if (value.startsWith('.')) element.setAttribute(attribute, original + value)
                        else if (value.isNotEmpty() && '.' !in value) element.setAttribute(attribute, "$original.$value")
                    }
                }
                // Permissions the app declares, and every reference to them.
                PERMISSION_ATTRIBUTES.forEach { attribute ->
                    val value = element.getAttribute(attribute)
                    if (value in declared) element.setAttribute(attribute, rename(value))
                }
                if (element.tagName == "provider") {
                    element.setAttribute("android:authorities", element.getAttribute("android:authorities")
                        .split(';').joinToString(";", transform = ::rename))
                }
                // Intent categories carry the package name (push and Amazon messaging receivers).
                if (element.tagName == "category") {
                    element.setAttribute("android:name", rename(element.getAttribute("android:name")))
                }
                if (element.hasAttribute("android:taskAffinity")) {
                    element.setAttribute("android:taskAffinity", rename(element.getAttribute("android:taskAffinity")))
                }
                if (element.tagName == "data" && element.getAttribute("android:scheme") in hostSchemes &&
                    element.getAttribute("android:host") == original) {
                    element.setAttribute("android:host", packageName!!)
                }
                if (element.tagName == "data" && element.getAttribute("android:scheme") == original) {
                    element.setAttribute("android:scheme", packageName!!)
                }
            }
            manifest.setAttribute("package", packageName!!)
        }
    }
}
