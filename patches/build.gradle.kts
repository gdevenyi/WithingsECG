group = "app.withingsecg"

patches {
    // TODO: Update this section with your project details.
    about {
        name = "WithingsECG Patches"
        description = "Unlocks the region-locked ECG feature on Withings ScanWatch family watches"
        source = "https://github.com/gdevenyi/WithingsECG"
        author = "gdevenyi"
        contact = "https://github.com/gdevenyi/WithingsECG/issues"
        website = "https://github.com/gdevenyi/WithingsECG"
        license = "GPLv3"
    }
}

// Separate configuration so gson is available at runtime for the
// generatePatchesList task but never bundled into the APK.
val patchListGeneratorClasspath = configurations.create("patchListGeneratorClasspath")

dependencies {
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

    // Used by gradle-semantic-release-plugin.
    publish {
        dependsOn("generatePatchesList")
    }
}
