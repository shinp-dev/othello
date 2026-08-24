# Edax Android build and corresponding source

## Reproducible inputs

- Edax upstream: `https://github.com/abulmo/edax-reversi`
- Commit: `14f048c05ddfa385b6bf954a9c2905bbe677e9d3`
- Reported engine version: Edax 4.6 (`v4.6-9-g14f048c`)
- License: GNU GPL version 3
- Android NDK: `27.3.13750724` (r27d LTS)
- CMake: `3.22.1`
- Android SDK: 36
- JDK: 17
- Gradle wrapper: 9.4.1

The complete Edax C source used for the native library is under
`third_party/edax/upstream/src`. The original upstream license and README are
kept beside it. `third_party/edax/UPSTREAM.md` records the import and every
Android-specific source modification; `third_party/edax/SHA256SUMS` fixes the
content identity of the source, notices, and patch.

## Build

Open the repository in Android Studio or use:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
./gradlew :analysis:edax:assembleDebug
./gradlew :analysis:edax:connectedDebugAndroidTest
./gradlew :app:assembleDebug
```

Release builds require the real CHANRIVA production Supabase URL and anon key;
missing, placeholder, HTTP, malformed, or other-project values fail before the
release variant is packaged. Configure them only through untracked
`local.properties` or environment variables, then run `assembleRelease` and
`bundleRelease` followed by `scripts/check-release-contents.ps1`. Debug builds
and unit tests do not require production credentials.

Android Gradle Plugin installs the pinned NDK/CMake after SDK licenses are
accepted. Do not add a machine-specific `ndkPath` to tracked files.

CMake builds the fixed upstream source and the small Android bridge into
`libedax_jni.so` for `arm64-v8a` and `x86_64`. Kotlin calls only the app-owned
contracts in `analysis:api`; Edax C structs never cross that public boundary.
Because the pinned r27d toolchain predates default flexible-page-size output,
CMake explicitly links with 16 KiB maximum/common page sizes. AGP 9.2 packages
uncompressed native libraries on 16 KiB ZIP boundaries. The release-content
check validates every packaged ELF `LOAD` alignment and the APK ZIP alignment.

## Data not included

Neither `eval.dat` nor any opening book is a build input. They must not appear
under `src/main/assets`, `src/main/res`, `jniLibs`, `third_party`, or release
archives. Instrumentation tests create a synthetic zero-weight eval file in the
test app cache at runtime. It is not packaged in the production artifact.

Users import legitimately obtained Edax-compatible files through Android's
Storage Access Framework. The app copies them to private storage, records name,
size, import time and SHA-256, validates them through the native bridge, and
never requests broad storage permission.

## Release corresponding source

For every distributed APK/AAB, retain the repository commit/tag that built it.
That revision contains the Kotlin source, Edax source and version, Android
patches, Gradle wrapper, CMake files, database migrations, and these build
instructions. Do not commit `local.properties`, signing keys, Supabase service
keys, imported eval/book files, test credentials, or generated artifacts.

Before release run `scripts/check-release-contents.ps1` against both the release
APK and AAB, in addition to the full test/lint/boundary suite. It rejects
third-party eval/book data, synthetic fixtures, debug hooks, unexpected ABIs,
broad storage permissions, or native ELF alignment below 16 KiB.
