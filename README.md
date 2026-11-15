# 🩺 WithingsECG Patches

Community [Morphe](https://github.com/MorpheApp/morphe-manager) patch bundle that unlocks the
region-locked **electrocardiogram (ECG)** feature of Withings ScanWatch-family watches —
ScanWatch Vitals, ScanWatch Healthmaster and other ScanWatch 2 hardware sold in markets
without ECG clearance (Canada, Japan, Singapore, Thailand).

**Your device. Your risk. Nothing here is medical advice: an unlocked ECG is not certified
in your region, so treat results as informational only.**

### How to use these patches

Add this repo URL as a patch source in Morphe Manager:
https://github.com/gdevenyi/WithingsECG

<a href="https://morphe.software/add-source?github=gdevenyi/WithingsECG">
  <img src=".github/assets/add-to-morphe.svg" alt="Add to Morphe" height="48">
</a>

## 🩹 What the patch changes

The region lock is a server-side *platform feature* toggle (feature id **4** = ECG,
**27** = ECG shortcut) that is withheld for devices sold in un-cleared markets. The watch
itself is the same hwa10 hardware family as ScanWatch 2 and its firmware keys the ECG menu
on the WPP *feature tags* the app pushes during sync.

The patch (target: `com.withings.wiscale2`, developed against 26.46.0):

1. `PlatformFeatureKt.isActivatedForDevice` — reports platform features 4/27 as activated
   for every device.
2. `PlatformFeatureHelper.Companion.getEcgFeatureState` — always reports `EcgState.On`.
3. `FeatureTagsConversation.K` — appends a synthetic active feature tag (id 4) for the
   real device id to the tag list, so the sync pushes the ECG tag to the watch
   (`CMD_FEATURE_TAGS_SET_DEPRECATED_V2`), plus a static helper
   `withingsInjectEcgTag(List, long)` that builds the tag object.
4. `yf.z` — forces the FeatureTags sync conversation registration (normally gated on
   platform feature 108 / a remote internal setting).
5. `RoomPlatformFeatureRepository.getAllFeaturesForDevice` — appends the synthetic ECG
   platform feature (via `withingsSyntheticEcgFeature(long)`) to the blocking read so
   tutorials and feature screens see it.

## ⚠️ Risks and limitations

* Re-signed APK: Google login stops working (Google checks the app signature server-side);
  use email + password. Withings data lives server-side and survives app replacement.
* The patched app may offer ECG UI that your watch firmware still refuses; behavior varies
  by firmware build.
* Watch-side ECG on an un-cleared market device is not clinically validated there.
* Patch fingerprints target unobfuscated `com.withings.*` classes and are expected to
  survive minor app updates, but major refactors may require re-alignment.

## 🚀 Building locally

```bash
# requires a GitHub PAT with read:packages for Morphe's registry (see template setup)
./gradlew buildAndroid
# output: patches/build/libs/patches-*.mpp
# apply with Morphe Desktop or Morphe Manager as with any other bundle
```

## 📜 License

GPLv3 — see [LICENSE](LICENSE).
