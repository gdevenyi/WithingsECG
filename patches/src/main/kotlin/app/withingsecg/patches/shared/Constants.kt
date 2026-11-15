package app.withingsecg.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_WITHINGS = Compatibility(
        name = "Withings",
        packageName = "com.withingings.wiscale2".replace("withingings", "withings"), // com.withings.wiscale2
        apkFileType = ApkFileType.APKM,
        appIconColor = 0xFF004B,
        targets = listOf(
            // Version present on supported devices as of Nov 2025. Patches were
            // developed and verified against this release.
            AppTarget(
                version = "26.46.0"
            ),
            // Expected to keep working for newer releases (fingerprints match
            // unobfuscated com.withings.* classes).
            AppTarget(
                version = null,
                isExperimental = true
            )
        )
    )
}
