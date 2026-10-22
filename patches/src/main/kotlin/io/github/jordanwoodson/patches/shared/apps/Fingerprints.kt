package io.github.jordanwoodson.patches.shared.apps

import app.morphe.patcher.Fingerprint
import io.github.jordanwoodson.patches.shared.settings.SETTINGS_CLASS

// The application class is named in the manifest, so R8 keeps its name.
internal class ApplicationOnCreateFingerprint(applicationClass: String) : Fingerprint(
    definingClass = applicationClass,
    name = "onCreate",
    returnType = "V",
    parameters = emptyList(),
)

// The extension method the settings patch fills with the registered settings.
internal object RegistryFingerprint : Fingerprint(
    definingClass = SETTINGS_CLASS,
    name = "registryJson",
    returnType = "Ljava/lang/String;",
    parameters = emptyList(),
)
