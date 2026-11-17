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

/**
 * PlatformFeatureKt.isDeviceEligible — same shape as isActivatedForDevice but
 * without the active flag. DeviceFeaturesLiveData and feature screens use it to
 * keep synthetic entities in their lists; without the force-through the
 * synthetic ECG entity would be nulled out (its owners cannot match a real
 * device id) and null entries would reach feature-id consumers.
 */
object IsDeviceEligibleFingerprint : Fingerprint(
    definingClass = "Lcom/withings/feature/platform/model/PlatformFeatureKt;",
    name = "isDeviceEligible",
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
 * PlatformFeatureDao_ProdRoomDb_Impl per-id reads. The region-locked account has
 * no feature 4/27 rows at all, so per-id lookups return null and downstream
 * consumers (shortcut picker list, feature state screens) never see the ECG
 * feature. The patch returns a synthetic active entity for ids 4 and 27.
 */
object GetFeatureDaoFingerprint : Fingerprint(
    definingClass = "Lcom/withings/feature/tag/platform/model/PlatformFeatureDao_ProdRoomDb_Impl;",
    name = "getFeature\$lambda\$0",
    returnType = "Lcom/withings/feature/tag/platform/model/PlatformFeatureEntity;",
    parameters = listOf(
        "Ljava/lang/String;",
        "I",
        "Lcom/withings/feature/tag/platform/model/PlatformFeatureDao_ProdRoomDb_Impl;",
        "Luym;"
    )
)

object GetFeatureAsFlowDaoFingerprint : Fingerprint(
    definingClass = "Lcom/withings/feature/tag/platform/model/PlatformFeatureDao_ProdRoomDb_Impl;",
    name = "getFeatureAsFlow\$lambda\$0",
    returnType = "Lcom/withings/feature/tag/platform/model/PlatformFeatureEntity;",
    parameters = listOf(
        "Ljava/lang/String;",
        "I",
        "Lcom/withings/feature/tag/platform/model/PlatformFeatureDao_ProdRoomDb_Impl;",
        "Luym;"
    )
)

object GetFeatureLiveDataDaoFingerprint : Fingerprint(
    definingClass = "Lcom/withings/feature/tag/platform/model/PlatformFeatureDao_ProdRoomDb_Impl;",
    name = "getFeatureLiveData\$lambda\$0",
    returnType = "Lcom/withings/feature/tag/platform/model/PlatformFeatureEntity;",
    parameters = listOf(
        "Ljava/lang/String;",
        "I",
        "Lcom/withings/feature/tag/platform/model/PlatformFeatureDao_ProdRoomDb_Impl;",
        "Luym;"
    )
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
        invoke-interface {v0}, Ljava/util/List;->size()I
        move-result v11
        new-instance v12, Ljava/lang/StringBuilder;
        invoke-direct {v12}, Ljava/lang/StringBuilder;-><init>()V
        const-string v13, "withings-ecg: K fetched tags, size="
        invoke-virtual {v12, v13}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
        invoke-virtual {v12, v11}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;
        invoke-virtual {v12}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;
        move-result-object v13
        sget-object v11, Ljava/lang/System;->out:Ljava/io/PrintStream;
        invoke-virtual {v11, v13}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V
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
        const-string v1, "withings-ecg: ECG tag (id 4) appended to tag list"
        sget-object v2, Ljava/lang/System;->out:Ljava/io/PrintStream;
        invoke-virtual {v2, v1}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V
    """.trimIndent()

    method.addInstructions(insertIndex + 1, injection)

    // Success marker just before K's final return: if the watch rejected the
    // deprecated tag command (yms), the catch path returns without this marker.
    val finalIdx = impl.instructions.count() - 1
    if (impl.instructions.last().opcode != Opcode.RETURN_VOID) {
        throw app.morphe.patcher.patch.PatchException("Unexpected final instruction in FeatureTagsConversation.K")
    }
    method.addInstructions(
        finalIdx,
        """
            const-string v0, "withings-ecg: feature tag push completed"
            sget-object v1, Ljava/lang/System;->out:Ljava/io/PrintStream;
            invoke-virtual {v1, v0}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V
        """.trimIndent()
    )

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
 * Returns a synthetic active PlatformFeatureEntity for the ECG feature ids
 * (4 = ECG, 27 = ECG shortcut) from the per-id DAO reads. Entity owners stay
 * empty: the patched isActivatedForDevice never inspects them.
 */
private fun injectSyntheticDaoEntity(method: MutableMethod) {
    val impl = method.implementation!!
    val idParamReg = 1 // static (String, I, Impl, uym): p1 = feature id
    val injection = """
        const/16 v0, $FEATURE_ID_ECG
        if-ne p$idParamReg, v0, :withings_not_ecg
        new-instance v3, Ljava/util/ArrayList;
        invoke-direct {v3}, Ljava/util/ArrayList;-><init>()V
        new-instance v4, $DEVICE_OWNER_CLASS
        invoke-direct {v4}, $DEVICE_OWNER_CLASS-><init>()V
        invoke-virtual {v3, v4}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
        new-instance v0, Lcom/withings/feature/tag/platform/model/PlatformFeatureEntity;
        const/16 v1, $FEATURE_ID_ECG
        const/4 v2, 0x0
        const/4 v4, 0x1
        invoke-direct {v0, v1, v2, v3, v4}, Lcom/withings/feature/tag/platform/model/PlatformFeatureEntity;-><init>(ILjava/lang/String;Ljava/util/List;Z)V
        return-object v0
        :withings_not_ecg
        const/16 v0, $FEATURE_ID_ECG_SHORTCUT
        if-ne p$idParamReg, v0, :withings_orig
        new-instance v3, Ljava/util/ArrayList;
        invoke-direct {v3}, Ljava/util/ArrayList;-><init>()V
        new-instance v4, $DEVICE_OWNER_CLASS
        invoke-direct {v4}, $DEVICE_OWNER_CLASS-><init>()V
        invoke-virtual {v3, v4}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
        new-instance v0, Lcom/withings/feature/tag/platform/model/PlatformFeatureEntity;
        const/16 v1, $FEATURE_ID_ECG_SHORTCUT
        const/4 v2, 0x0
        const/4 v4, 0x1
        invoke-direct {v0, v1, v2, v3, v4}, Lcom/withings/feature/tag/platform/model/PlatformFeatureEntity;-><init>(ILjava/lang/String;Ljava/util/List;Z)V
        return-object v0
        :withings_orig
    """.trimIndent()

    method.addInstructions(0, injection) // original method writes its locals before reading them
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
        // 1) Force-activate the ECG platform feature (4) for every device so
        //    isActivatedForDevice checks (shortcut picker, health features, sync) pass.
        IsFeatureActivatedForDeviceFingerprint.method.addInstructions(
            0,
            """
                invoke-virtual {p0}, $FEATURE_CLASS->getFeatureId()I
                move-result v0
                const/16 v1, $FEATURE_ID_ECG
                if-ne v0, v1, :withings_original
                const/4 v0, 0x1
                return v0
                :withings_original
            """
        )

        // 2) Force device eligibility for the ECG platform feature so synthetic
        //    entities survive list assembly (prevents null entries downstream).
        IsDeviceEligibleFingerprint.method.addInstructions(
            0,
            """
                invoke-virtual {p0}, $FEATURE_CLASS->getFeatureId()I
                move-result v0
                const/16 v1, $FEATURE_ID_ECG
                if-ne v0, v1, :withings_eligible_original
                const/4 v0, 0x1
                return v0
                :withings_eligible_original
            """
        )

        // 3) Always report the aggregated ECG feature state as On.
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

            // 4) Inject the ECG platform feature into the sync-written feature database.
            }

        // 5) Per-id DAO reads return a synthetic active ECG entity (4/27) so the
        //    shortcut picker and feature screens see the feature at all.
        injectSyntheticDaoEntity(GetFeatureDaoFingerprint.method)
        injectSyntheticDaoEntity(GetFeatureAsFlowDaoFingerprint.method)
        injectSyntheticDaoEntity(GetFeatureLiveDataDaoFingerprint.method)
    }
}
