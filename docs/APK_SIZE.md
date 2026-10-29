# APK size

How the release APK size was measured and reduced, and which ideas were rejected. Scope: build configuration, R8,
resources and dependency footprint. The SQLCipher native libraries (about 7.5 MB across four ABIs) are being removed
separately, so every table below excludes `lib/` unless it says otherwise.

## Method

1. `./gradlew :app:assembleRelease` on `main` (5d46915) and after each change. Without a keystore this produces
   `app-release-unsigned.apk`, which is enough to compare entry sizes.
2. Per-category totals from the zip central directory (compressed size of each entry, so stored entries such as
   `resources.arsc` count at full size). Zip headers and alignment padding are not part of these numbers; they are
   included in the "whole APK" row.
3. `apkanalyzer dex packages --defined-only --proguard-mappings mapping.txt` for the dex breakdown and
   `aapt2 dump resources` for the resource table.
4. Signing cost: both APKs were signed with the same throwaway 4096-bit RSA key using `apksigner` (v1+v2+v3 as
   configured before, v2+v3 as configured now), and the final build was also signed by Gradle through the
   `SIGNING_*` environment variables to check the real configuration.
5. "Whole APK without SQLCipher" removes the `libsqlcipher.so` entries (local header, data, padding and central
   directory record) from the measured file. It is an estimate within a few KB, because alignment padding of the
   remaining entries shifts slightly once those entries are gone.

## Before and after

| Entry (compressed bytes in the APK) | Before (main) | After | Change |
|---|---:|---:|---:|
| `classes.dex` | 2,129,237 | 2,119,259 | -9,978 |
| `resources.arsc` (stored) | 736,376 | 287,336 | -449,040 |
| `res/` (1,323 files) | 481,452 | 481,452 | 0 |
| `META-INF/` (unsigned) | 34,448 (103 files) | 33,653 (15 files) | -795 |
| `kotlin/` | 12,425 | 0 | -12,425 |
| `assets/` (baseline profile) | 10,814 | 10,907 | +93 |
| other (`AndroidManifest.xml`, `DebugProbesKt.bin`) | 4,352 | 3,578 | -774 |
| **Total, excluding `lib/`** | **3,409,104** | **2,936,185** | **-472,919 (-13.9%)** |
| AndroidX native helpers in `lib/` (8 files) | 73,584 + 53,288 padding | 31,099, no padding | -95,773 |
| v1 (JAR) signature files | ~100 KB | 0 | -99,737 |
| **Whole signed APK without SQLCipher (estimate)** | **3,798,338** | **3,117,154** | **-681,184 (-17.9%)** |

With SQLCipher still present (as on `main`), the signed APK goes from 11,506,827 to 7,039,639 bytes, because
`useLegacyPackaging = true` also compresses `libsqlcipher.so`. That part disappears once SQLCipher is removed.

## Changes

| Change | Saving | Notes |
|---|---:|---|
| `androidResources.localeFilters += "en"` | 449 KB | `resources.arsc` 736 KB to 287 KB. |
| `jniLibs.useLegacyPackaging = true` | 96 KB (without SQLCipher) | Compression plus no 16 KB zip-alignment padding. |
| `enableV1Signing = false` | 100 KB | `MANIFEST.MF` and `.SF` list a digest for every entry. |
| Packaging excludes | 21 KB | Entry data plus the zip headers of 90 removed entries. |
| R8 rules (narrow serialization keeps, strip `Log.v/d/i`) | 10 KB | Dex raw size 4,254,908 to 4,234,064. |

### Locale filter

The app's own strings exist only in English (`res/values`). Libraries ship about 80 translations each (AppCompat,
Biometric, Compose UI/Material 3, Glance, Core), which made up 61% of the resource table. With the filter the
app is consistently English.

Side effect: text that comes from library resources is English on every device. This covers Compose Material 3
accessibility labels and built-in strings (for example "Dismiss" and the date picker labels), Glance's default error
text, and the strings in `androidx.biometric`'s fallback UI. The system BiometricPrompt sheet on API 28+ is drawn by
the system and stays localized; its title and subtitle come from the app and were English already. On API 26-27 the
app uses the KeyguardManager confirm-credential screen, which is system UI and also stays localized.

Recommendation: keep it. 449 KB is the largest single saving (about 12% of the APK once SQLCipher is gone), and a
partly translated UI (system widgets in the device language, the rest in English) is arguably worse than a
consistently English one. Remove the filter, or extend the list, when the app gets its own translations.

### Native libraries

After SQLCipher is gone, the only native code is `libandroidx.graphics.path.so` (Compose graphics, API < 34 path
iteration) and `libdatastore_shared_counter.so` (DataStore), about 10 KB each per ABI.

- `useLegacyPackaging = false` stores them uncompressed and page-aligns each entry to 16 KB inside the APK so they can
  be mapped directly from the APK (`extractNativeLibs=false`). For these eight small files that costs 43 KB of lost
  compression and 53 KB of alignment padding.
- `useLegacyPackaging = true` compresses them; AGP sets `android:extractNativeLibs="true"` and the package manager
  extracts the ABI that matches the device at install (about 20 KB on disk).

Extracted libraries load on 16 KB-page devices (Android 15+) as long as the ELF `LOAD` segments are 16 KB-aligned.
`readelf -lW` on every `.so` in the APK shows `Align 0x4000` for all of them, in all four ABIs (SQLCipher's too).
The APK still passes `zipalign -c -P 16 4`. Compressed packaging gives the smaller download and stays safe on 16 KB
devices, so it is now the setting.

### APK signature scheme v1

`minSdk` is 26. Android 7.0+ verifies v2/v3 signatures and ignores v1 when a v2 signature is present, so the JAR
signature only added about 100 KB of digests. `scripts/verify-release.sh` and `docs/RELEASING.md` already require v2
or v3. A test build signed by Gradle with a throwaway key verifies as v2 and v3, with v1 reported as false.

### Packaging excludes

- `/DebugProbesKt.bin`: data for the kotlinx-coroutines debug agent. It is read only when
  `kotlinx-coroutines-debug` is installed, which the app never does.
- `/kotlin/**.kotlin_builtins`: metadata used only by `kotlin-reflect`. The app does not depend on `kotlin-reflect`
  (checked with `:app:dependencies --configuration releaseRuntimeClasspath`), and kotlinx.serialization and navigation
  use generated serializers rather than reflection.
- `/META-INF/*.version`: 88 AndroidX version stamps, a few bytes each, plus their zip headers. Nothing reads them at
  runtime.

### R8 rules (`app/proguard-rules.pro`)

- The old kotlinx.serialization rules kept the `Companion` field of every class in `com.riyadm.unsendvault.**`. That
  stopped R8 from inlining or removing about 40 unrelated companions (ViewModels, repositories, workers, the
  notification listener, widgets). `kotlinx-serialization-core` 1.12 already ships R8 rules for all `@Serializable`
  classes. The app rules now only add a safety net for the two packages that declare `@Serializable` types:
  `data.backup` (the backup DTOs) and `ui.navigation` (type-safe routes).
- `-assumenosideeffects` on `android.util.Log.v/d/i` removes verbose, debug and info logging from release builds,
  including in libraries. `Log.w` and `Log.e` stay. In the app only `RetentionWorker`'s "Retention removed N
  message(s)" line goes; capture logging was already behind `BuildConfig.DEBUG`.

Verified in the release mapping (`app/build/outputs/mapping/release/`):

- `BackupPayload`, `BackupConversation`, `BackupMessage`, `BackupEvidence`, `BackupRevision`: the class,
  `$Companion` and `$$serializer` survive with unchanged names, and every `$Companion.serializer()` appears in
  `seeds.txt`. The same is true for the route classes in `ui.navigation` (`ConversationDest`, `MessageDest` and the
  `data object` routes, whose `serializer()` is kept directly).
- `VaultDatabase_Impl`, `RetentionWorker`, `VaultNotificationListener`, `MainActivity`, `UnsendVaultApp` and all five
  widget receivers are kept with their original names. `VaultDao_Impl` is obfuscated, as it was before; Room creates
  it from generated code, not by reflection.
- `BackupCrypto.Header` is now inlined away by R8 (before, the broad `Companion` rule pinned it). It is a plain
  binary header class, not serialized by kotlinx.serialization, so this is a normal optimization. It is still worth
  covering with an export/import round trip on a release build.

## Already in place, no change needed

- R8 full mode, `-allowaccessmodification` (from `proguard-android-optimize.txt`) and class repackaging: 5,471 of the
  5,664 obfuscated classes in the mapping are already moved into the default package.
- Optimized resource shrinking (`android.r8.optimizedResourceShrinking`) and strict full-mode keep rules
  (`android.r8.strictFullModeForKeepRules`) default to `true` in AGP 9.5.0 (read from AGP's `BooleanOption`), so
  setting them again would do nothing.
- `kotlin.Metadata` annotations are already stripped; the dex has no reference to `Lkotlin/Metadata;`.
- Kotlin null-check intrinsics (`Intrinsics.checkNotNullParameter`) are already rewritten by R8; none remain in the
  mapping, so an `-assumenosideeffects` rule or `-Xno-param-assertions` would gain nothing.
- `vectorDrawables.useSupportLibrary = true` has no effect on size. With `minSdk` 26 AGP never generates PNG
  fallbacks for vector drawables. Left unchanged.
- `resources.arsc` must stay stored and 4-byte aligned (`targetSdk` 30+ requirement).

## Considered and rejected

| Idea | Why not |
|---|---|
| Replace `material-icons-extended` with core icons or vector drawables | R8 already strips unused icons: `androidx.compose.material.icons` is 37.5 KB of raw dex for the ~45 icons the app uses. Copying them into the app would save almost nothing. |
| Remove `material3-window-size-class`, `lifecycle-process`, `ui-graphics`, `ui-tooling-preview` | `calculateWindowSizeClass` (MainActivity) and `ProcessLifecycleOwner` (AppLockManager) are used. `ui-graphics` comes in through `ui` anyway. `ui-tooling-preview` only holds annotations, which R8 removes. |
| Remove AppCompat (about 187 KB raw dex, 128 KB of `abc_*` drawables and layouts) | It comes from `androidx.biometric` 1.1.0, whose `FingerprintDialogFragment` uses `androidx.appcompat.app.AlertDialog`. The only newer versions are alphas (1.4.0-alpha07), and they still depend on AppCompat. The app never shows that dialog (API 28+ uses BiometricPrompt and API 26-27 uses KeyguardManager), but R8 cannot prove it. |
| Shrink Glance layouts (about 290 KB of `res/`) | Glance looks up its ~1,100 pre-generated RemoteViews layouts by resource ID at runtime. They are required. |
| Density filters for the AppCompat 9-patch PNGs | Density-based resource filtering is no longer supported for APKs, and it would make the (unused) fingerprint dialog look wrong on other screens. |
| Drop the ten `META-INF/androidx/**/LICENSE.txt` files (about 32 KB) | They are the only Apache 2.0 text shipped in the APK (the About screen lists the components but not the license text). They stay for license compliance. A follow-up could ship one copy of the license and exclude the duplicates. |
| Exclude `libdatastore_shared_counter.so` | It is only loaded by multi-process DataStore, which the app does not use, but excluding a native library that a dependency can load is a crash risk for about 13 KB. |
| `ndk { abiFilters }` or per-ABI splits | Once SQLCipher is gone, all ABIs together add 31 KB compressed. A single universal APK keeps releases simple and installs on every device. |
| Baseline profile (`androidx.baselineprofile`) | It would increase size. The APK already ships the library profiles that AGP merges (`assets/dexopt/baseline.prof`, about 11 KB). An app-specific profile would speed up cold start and first frame by roughly 15-30%, so it is worth revisiting if startup becomes a concern. |
| Remove `META-INF/version-control-info.textproto` | It is 42 bytes and records the source commit. |
