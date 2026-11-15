package app.withingsecg.patches.ecg

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.InlineSmaliCompiler
import app.withingsecg.patches.shared.Constants.COMPATIBILITY_WITHINGS
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val FEATURE_CLASS = "Lcom/withings/feature/platform/model/PlatformFeature;"
private const val HELPER_CLASS = "Lcom/withings/feature/tag/platform/api/PlatformFeatureHelper"
private const val DEVICE_OWNER_CLASS = "Lcom/withings/feature/platform/model/DeviceOwner;"
private const val FEATURE_TAGS_CONVERSATION = "Lcom/withings/common/device/conversation/FeatureTagsConversation;"
private const val FEATURE_REPO_CLASS = "Lcom/withings/feature/tag/platform/model/RoomPlatformFeatureRepository;"

// Platform feature ids (see app feature catalog):
private const val FEATURE_ID_ECG = 0x4 // 4: electrocardiogram
private const val FEATURE_ID_ECG_SHORTCUT = 0x1b // 27: ECG long-press shortcut

// Bisect switch: device-side patches (3, 3b, 4). Set to false to reproduce the
// app-only configuration that is known to keep watch sync working.
private const val ENABLE_DEVICE_SIDE_UNLOCK = true

object IsFeatureActivatedForDeviceFingerprint : Fingerprint(
    definingClass = "Lcom/withings/feature/platform/model/PlatformFeatureKt;",
    name = "isActivatedForDevice",
    returnType = "Z",
    parameters = listOf(
        "Lcom/withings/feature/platform/model/PlatformFeature;",
        "J"
    )
)

object GetEcgFeatureStateFingerprint : Fingerprint(
    definingClass = "Lcom/withings/feature/tag/platform/api/PlatformFeatureHelper\$Companion;",
    name = "getEcgFeatureState",
    returnType = "Lcom/withings/feature/tag/platform/api/PlatformFeatureHelper\$EcgState;",
    parameters = listOf(
        "Ljava/util/List;",
        "J"
    )
)

/**
 * FeatureTagsConversation.K sends the active feature tags of the account to the
 * watch (CMD_FEATURE_TAGS_SET_DEPRECATED_V2). The watch firmware keys its menu
 * entries (among them the ECG app) on those tags. Without a tag for feature id 4
 * the watch hides ECG entirely, which is how the ScanWatch Vitals /
 * Healthmaster / region-locked ScanWatch 2 devices are restricted.
 */
object FeatureTagsConversationFingerprint : Fingerprint(
    definingClass = FEATURE_TAGS_CONVERSATION,
    name = "K",
    returnType = "V",
    parameters = listOf()
)

/**
 * defpackage.yf.z selects whether the WPP FeatureTags conversation is registered
 * in the device sync chain: it returns true only when the remote internal setting
 * IgnoreWppsFeatureTag is set or platform feature 108 is activated for the
 * account. Region-locked devices have neither, so the feature tag push to the
 * watch never happens even when tags exist.
 */
object IsFeatureTagSyncEnabledFingerprint : Fingerprint(
    definingClass = "Lyf;",
    name = "z",
    returnType = "Z",
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(
        app.morphe.patcher.literal(108)
    )
)

/**
 * RoomPlatformFeatureRepository.getAllFeaturesForDevice is the blocking read of
 * the locally synced platform features used by feature screens and tutorials.
 */
object GetAllFeaturesForDeviceFingerprint : Fingerprint(
    definingClass = FEATURE_REPO_CLASS,
    name = "getAllFeaturesForDevice",
    returnType = "Ljava/util/List;",
    parameters = listOf("J")
)

/**
 * Appends an rra feature tag (feature id 4, active) for the current device to the
 * tag list right after it is fetched, so the normal grouping logic pushes the ECG
 * tag to the watch on every sync.
 *
 * All injected instructions stay within v0..v15: dex instruction formats used here
 * (11n/11x/12x/21c/22c/35c/range) cannot encode higher registers, and the inline
 * smali compiler silently drops such instructions.
 */
private fun injectEcgFeatureTag(method: MutableMethod) {
    val impl = method.implementation!!
    val thisReg = impl.registerCount - 1 // non-static, no params: this is the highest register
    val insertIndex = impl.instructions.indexOfFirst {
        it.opcode == Opcode.CHECK_CAST &&
            (it as? ReferenceInstruction)?.reference.toString() == "Ljava/util/List;"
    }
    if (insertIndex < 0) throw app.morphe.patcher.patch.PatchException("List check-cast not found in FeatureTagsConversation.K")

    val injection = """
        invoke-virtual {v$thisReg}, $FEATURE_TAGS_CONVERSATION->D()Lgjv;
        move-result-object v1
        invoke-virtual {v1}, Lgjv;->a()Lx0h;
        move-result-object v1
        iget-object v2, v$thisReg, $FEATURE_TAGS_CONVERSATION->f:Lye8;
        invoke-virtual {v2, v1}, Lye8;->b(Lx0h;)Lcom/withings/device/Device;
        move-result-object v2
        invoke-virtual {v2}, Lcom/withings/device/Device;->getId()J
        move-result-wide v1
        invoke-static {v0, v1, v2}, $FEATURE_TAGS_CONVERSATION->withingsInjectEcgTag(Ljava/util/List;J)V
    """.trimIndent()

    method.addInstructions(insertIndex + 1, injection)

    // Static helper added to the same class: builds qra(enabled, seen, activated)
    // and rra(featureId 4, deviceId, active, no window), then appends it to the
    // tag list. Its own register space keeps every encoding legal.
    val helperBody = """
        const/4 v1, 0x1
        const/4 v2, 0x1
        const/4 v3, 0x1
        const/4 v4, 0x0
        const/4 v5, 0x0
        const/4 v6, 0x0
        new-instance v0, Lqra;
        invoke-direct/range {v0 .. v6}, Lqra;-><init>(ZZZLorg/joda/time/Instant;Lorg/joda/time/Instant;Ljava/lang/Integer;)V
        new-instance v5, Ljava/util/HashMap;
        invoke-direct {v5}, Ljava/util/HashMap;-><init>()V
        invoke-static {p1, p2}, Ljava/lang/Long;->valueOf(J)Ljava/lang/Long;
        move-result-object v7
        invoke-virtual {v5, v7, v0}, Ljava/util/HashMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
        new-instance v6, Lrra;
        const/16 v7, $FEATURE_ID_ECG
        move-wide v8, p1
        move-object v10, v0
        move-object v11, v5
        invoke-direct/range {v6 .. v11}, Lrra;-><init>(IJLqra;Ljava/util/Map;)V
        invoke-interface {p0, v6}, Ljava/util/List;->add(Ljava/lang/Object;)Z
        return-void
    """.trimIndent()

    val compiled = app.morphe.patcher.util.smali.InlineSmaliCompiler.compile(helperBody, "Ljava/util/List;J", 15, true)
    val helper = com.android.tools.smali.dexlib2.immutable.ImmutableMethod(
        FEATURE_TAGS_CONVERSATION,
        "withingsInjectEcgTag",
        listOf(
            com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter("Ljava/util/List;", null, null),
            com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter("J", null, null)
        ),
        "V",
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        null,
        null,
        com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation(
            15,
            compiled,
            emptyList(),
            emptyList()
        )
    ).toMutable()

    mutableClassDefBy(FEATURE_TAGS_CONVERSATION).methods.add(helper)
}

/**
 * Adds a static factory method that builds the synthetic ECG PlatformFeature
 * (feature id 4, active DeviceOwner for the given device id), then reduces the
 * injection in getAllFeaturesForDevice to a single call. A dedicated method has
 * its own register space, which sidesteps the v0..v15 encodability limit of the
 * small dex formats inside the 8-register original.
 */
private fun injectEcgFeatureForDevice(method: MutableMethod) {
    val impl = method.implementation!!
    val regCount = impl.registerCount
    val p1 = regCount - 2 // low half of the long device id parameter
    val p2 = regCount - 1 // high half of the long device id parameter
    if (p2 > 15) throw app.morphe.patcher.patch.PatchException("Device id parameter registers exceed v15")

    val helperBody = """
        invoke-static {p0, p1}, Ljava/lang/Long;->valueOf(J)Ljava/lang/Long;
        move-result-object v1
        const/4 v2, 0x1
        const/4 v3, 0x0
        const/4 v4, 0x0
        const-wide/16 v5, 0x0
        const/4 v7, 0x0
        const/4 v8, 0x0
        const/4 v9, 0x0
        const/4 v10, 0x0
        const/4 v11, 0x0
        new-instance v0, $DEVICE_OWNER_CLASS
        invoke-direct/range {v0 .. v11}, $DEVICE_OWNER_CLASS-><init>(Ljava/lang/Long;ZZZJLjava/lang/Long;Ljava/lang/Long;Ljava/lang/Long;ZLjava/lang/Integer;)V
        new-instance v4, Ljava/util/ArrayList;
        invoke-direct {v4}, Ljava/util/ArrayList;-><init>()V
        invoke-virtual {v4, v0}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
        new-instance v2, $FEATURE_CLASS
        const/16 v3, $FEATURE_ID_ECG
        const/4 v5, 0x0
        const/4 v6, 0x1
        invoke-direct {v2, v3, v5, v4, v6}, $FEATURE_CLASS-><init>(ILjava/lang/String;Ljava/util/List;Z)V
        return-object v2
    """.trimIndent()

    val compiled = app.morphe.patcher.util.smali.InlineSmaliCompiler.compile(helperBody, "J", 15, true)
    val helper = com.android.tools.smali.dexlib2.immutable.ImmutableMethod(
        FEATURE_REPO_CLASS,
        "withingsSyntheticEcgFeature",
        listOf(com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter("J", null, null)),
        FEATURE_CLASS,
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        null,
        null,
        com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation(
            15,
            compiled,
            emptyList(),
            emptyList()
        )
    ).toMutable()

    mutableClassDefBy(FEATURE_REPO_CLASS).methods.add(helper)

    val finalInstruction = impl.instructions.last()
    if (finalInstruction.opcode != Opcode.RETURN_OBJECT) {
        throw app.morphe.patcher.patch.PatchException("Unexpected final instruction in getAllFeaturesForDevice")
    }
    val retRegister = (finalInstruction as OneRegisterInstruction).registerA

    val injection = """
        invoke-static {v$p1, v$p2}, $FEATURE_REPO_CLASS->withingsSyntheticEcgFeature(J)$FEATURE_CLASS
        move-result-object v3
        invoke-interface {v$retRegister, v3}, Ljava/util/List;->add(Ljava/lang/Object;)Z
    """.trimIndent()

    val insertIndex = impl.instructions.count() - 1
    method.addInstructions(insertIndex, injection)
}

@Suppress("unused")
val unlockEcgPatch = patch(
    name = "Unlock ECG",
    description = "Enables the region-locked electrocardiogram (ECG) feature on ScanWatch Vitals, " +
        "ScanWatch Healthmaster and other ScanWatch 2 family watches sold in regions without " +
        "ECG clearance (Canada, Japan, Singapore, Thailand). The ECG platform feature is reported " +
        "as activated in the app, and the ECG feature tag is pushed to the watch so its menu " +
        "shows the ECG app again.",
    default = true
) {
    compatibleWith(COMPATIBILITY_WITHINGS)

    execute {
        // 1) Force-activate the ECG platform features (4, 27) for every device so
        //    isActivatedForDevice checks (shortcut picker, health features, sync) pass.
        IsFeatureActivatedForDeviceFingerprint.method.addInstructions(
            0,
            """
                invoke-virtual {p0}, $FEATURE_CLASS->getFeatureId()I
                move-result v0
                const/16 v1, $FEATURE_ID_ECG
                if-ne v0, v1, :withings_check_shortcut
                goto :withings_ecg_activated
                :withings_check_shortcut
                const/16 v1, $FEATURE_ID_ECG_SHORTCUT
                if-ne v0, v1, :withings_original
                :withings_ecg_activated
                const/4 v0, 0x1
                return v0
                :withings_original
            """
        )

        // 2) Always report the aggregated ECG feature state as On.
        GetEcgFeatureStateFingerprint.method.addInstructions(
            0,
            """
                sget-object v0, $HELPER_CLASS${'$'}EcgState;->On :$HELPER_CLASS${'$'}EcgState;
                return-object v0
            """
        )

        if (ENABLE_DEVICE_SIDE_UNLOCK) {
            // 3) Push the ECG feature tag (id 4) to the watch on every sync.
            injectEcgFeatureTag(FeatureTagsConversationFingerprint.method)

            // 3b) Force-enable the FeatureTags sync conversation registration
            //     (yf.z would otherwise skip it for region-locked devices).
            IsFeatureTagSyncEnabledFingerprint.method.addInstructions(
                0,
                """
                    const/4 v0, 0x1
                    return v0
                """
            )

            // 4) Inject the ECG platform feature into the blocking per-device feature read.
            injectEcgFeatureForDevice(GetAllFeaturesForDeviceFingerprint.method)
        }
    }
}
