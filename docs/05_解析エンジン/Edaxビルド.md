# Edax Androidビルドと対応ソース

## 再現可能な入力

- Edax upstream: `https://github.com/abulmo/edax-reversi`
- コミット: `14f048c05ddfa385b6bf954a9c2905bbe677e9d3`
- Engine バージョン表記: Edax 4.6（`v4.6-9-g14f048c`）
- License: GNU GPL バージョン 3
- Android NDK: `27.3.13750724`（r27d LTS）
- CMake: `3.22.1`
- Android SDK: 36
- JDK: 17
- Gradle wrapper: 9.4.1

native libraryに使う完全なEdax C ソースは`third_party/edax/upstream/src`にあります。元のupstream licenseとREADMEも同じ場所に保持します。`third_party/edax/UPSTREAM.md`はimportとAndroid固有の全ソース変更を記録し、`third_party/edax/SHA256SUMS`はソース、notice、patchの内容をhashで固定します。

## ビルド

リポジトリをAndroid Studioで開くか、次を実行します。

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
./gradlew :analysis:edax:assembleDebug
./gradlew :analysis:edax:connectedDebugAndroidTest
./gradlew :app:assembleDebug
```

リリースビルドには、実際のCHANRIVA本番Supabase URLとanon キーが必要です。値がない、placeholder、HTTP、不正形式、別プロジェクトのいずれかであれば、リリース variantのパッケージ作成前に失敗します。値はGit管理外の`local.properties`または環境変数だけから設定します。その後、`assembleRelease`と`bundleRelease`を実行し、続けて`scripts/check-release-contents.ps1`を実行します。debug ビルドと単体テストには本番の認証情報は不要です。

Android Gradle Pluginは、SDK licenseの承認後に固定バージョンのNDK / CMakeをinstallします。machine固有の`ndkPath`を追跡対象fileへ追加しないでください。

CMakeは、固定したupstream ソースと小さなAndroid bridgeを`arm64-v8a` / `x86_64`向けの`libedax_jni.so`へビルドします。Kotlinが呼ぶのはアプリ所有の`analysis:api` 契約だけで、Edax C structが公開境界を越えることはありません。固定したr27d toolchainはflexible page sizeを既定で出力するより前のバージョンであるため、CMakeは最大／共通page sizeを16 KiBとして明示的にリンクします。AGP 9.2は非圧縮native libraryを16 KiB ZIP境界へ配置します。リリース内容検査は、パッケージ内の全ELF `LOAD` alignmentとAPK ZIP alignmentを検証します。

## 同梱しないデータ

`eval.dat`もopening bookもビルド入力ではありません。`src/main/assets`、`src/main/res`、`jniLibs`、`third_party`、リリースアーカイブのいずれにも入れてはいけません。instrumentationテストは、テストアプリのキャッシュへ合成重み0の eval fileを実行時に作ります。本番成果物にはパッケージしません。

利用者は、正当に取得したEdax互換fileをAndroidのStorage アクセス Frameworkからimportします。アプリは非公開ストレージへcopyし、name、size、import time、SHA-256を記録してnative bridgeで検証します。広範なストレージ権限は要求しません。

## 公開成果物に対応するソース

配布するすべてのAPK / AABについて、ビルドに使ったリポジトリ コミット / tagを保持します。そのrevisionには、Kotlin ソース、Edax ソースとバージョン、Android patch、Gradle wrapper、CMake file、データベース マイグレーション、このビルド手順が含まれます。`local.properties`、署名キー、Supabaseのserviceキー、importしたeval / book file、テスト認証情報、生成済み成果物はコミットしないでください。

リリース前にはfull テスト / lint / 境界 suiteに加え、リリース APKとAABの両方へ`scripts/check-release-contents.ps1`を実行します。この検査は、third-party eval / book データ、合成テストデータ、debug hook、想定外ABI、広範なストレージ権限、16 KiB未満のnative ELF alignmentを拒否します。
