group = "io.github.jordanwoodson"

patches {
    about {
        name = "Lurk"
        description = "Privacy patches for Twitch, Kick and Rumble. Watch the stream, skip the tracking."
        source = "git@github.com:jordanwoodson/lurk-morphe-patches.git"
        author = "jordanwoodson"
        contact = "https://github.com/jordanwoodson/lurk-morphe-patches/issues"
        website = "https://github.com/jordanwoodson/lurk-morphe-patches"
        license = "GPLv3"
    }
}

// Separate configuration so gson is available at runtime for the
// generatePatchesList task but never bundled into the APK.
val patchListGeneratorClasspath = configurations.create("patchListGeneratorClasspath")

dependencies {
    // Shared Morphe helpers: hermesPatch (Kick is React Native), string/hex replacement, BytecodeUtils.
    implementation(libs.morphe.patches.library)

    compileOnly(libs.gson)
    patchListGeneratorClasspath(libs.gson)
}

tasks {
    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"

        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath + patchListGeneratorClasspath
        mainClass.set("util.PatchListGeneratorKt")
    }
}

// Compile committed sources at build time. No generated HBC is kept in the checkout.
val compiledHermes = layout.buildDirectory.dir("generated/compiledHermes")
val compileHermes = tasks.register("compileHermes") {
    inputs.files(fileTree("src/main/runtime") { include("**/*.js") })
    inputs.file(rootProject.file("bin/lib/compile-hermes.py"))
    inputs.file(rootProject.file("mise.toml"))
    outputs.dir(compiledHermes)
    doLast {
        delete(compiledHermes)
        fileTree("src/main/runtime").matching { include("**/*.js") }.files.sorted().forEach { source ->
            val relative = source.relativeTo(file("src/main/runtime"))
            val family = when (relative.invariantSeparatorsPath.substringBefore('/')) {
                "kick" -> "99"
                "twitch" -> "98"
                else -> error("Unknown Hermes runtime family: $relative")
            }
            val output = compiledHermes.get().file(relative.invariantSeparatorsPath.removeSuffix(".js") + ".hbc").asFile
            providers.exec {
                commandLine("python3", rootProject.file("bin/lib/compile-hermes.py"), family, source, output)
            }.result.get().assertNormalExitValue()
        }
    }
}
tasks.named<ProcessResources>("processResources") {
    dependsOn(compileHermes)
    from(compiledHermes)
}
