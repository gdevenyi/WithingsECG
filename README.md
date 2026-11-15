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

## 🔍 Lock analysis notes (for maintainers)

"ScanWatch Vitals" is a marketing name spanning multiple regional SKUs of the same
`hwa10` hardware (US Rx variant, Canadian wellness variant, etc.). The model id
reported by all of them is 94 — identical to ScanWatch 2. Since one firmware series
(3411) covers the family and the US Rx variant ships ECG on the same model, the ECG
app is almost certainly present in the shared firmware and gated at runtime by
(1) the server-withheld platform features (4/27), (2) the absent WPP feature tag
(id 4) push, and (3) the FeatureTags-conversation registration gate (`yf.z`,
platform feature 108). The patch neutralises all three app-side gates.

## 🔬 Deep debugging (if the watch still hides ECG after patching)

If the watch menu stays empty after a successful sync, add the official
[Morphe patches](https://github.com/MorpheApp/morphe-patches) source in Morphe Manager as a
second bundle and enable two universal patches alongside **Unlock ECG**:

* **Override certificate pinning** — trusts user CAs (`overridePins`), letting mitmproxy
  observe `scalews.withings.net` traffic. This shows exactly which feature tags were pushed
  and captures the authenticated `v2/firmware?action=getupdate` and
  `v2/feature?action=set` exchanges.
* **Enable Android debugging** — makes the patched app debuggable so
  `adb shell run-as com.withings.wiscale2` can read the Room database
  (`feature`, `featuretag` tables), shared preferences and the app's file logs directly.

Then: install a mitmproxy CA on the device, set the Wi-Fi proxy, open the device page and
force a sync. The captured `v2/feature?action=getbydevice` response shows the server-side
feature set for the device, and `v2/feature?action=set&featureid=4&deviceid=<id>` can be
replayed with the app session to attempt a server-side activation.

### 🛰️ Server-response injection (auto-inject the ECG feature)

With the certificate-pinning override and a mitmproxy CA installed:

```bash
uv tool install mitmproxy   # or: pacman -S mitmproxy
mitmdump -s mitm_ecg.py --listen-port 8080
# set the phone's Wi-Fi proxy to this host:8080, then open the device page and sync
```

`tools/mitm_ecg.py` (in this repo) logs
`v2/feature?action=getbydevice`, rewrites the response to include platform
feature 4 as active for each device, logs any `action=set` calls, and captures
`v2/firmware?action=getupdate` offers for offline diffing between the Vitals
and ScanWatch 2 builds.
