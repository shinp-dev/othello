# Edax Androidビルドと対応ソース

## 再現可能な入力

- Edaxの上流リポジトリ: `https://github.com/abulmo/edax-reversi`
- コミット: `14f048c05ddfa385b6bf954a9c2905bbe677e9d3`
- エンジンのバージョン表記: Edax 4.6（`v4.6-9-g14f048c`）
- ライセンス: GNU GPL バージョン3
- Android NDK: `27.3.13750724`（r27d LTS）
- CMake: `3.22.1`
- Android SDK: 36
- JDK: 17
- Gradle Wrapper: 9.4.1

ネイティブライブラリに使う完全なEdax Cソースは`third_party/edax/upstream/src`にあります。元の上流ライセンスとREADMEも同じ場所に保持します。`third_party/edax/UPSTREAM.md`は取り込み元とAndroid固有の全ソース変更を記録し、`third_party/edax/SHA256SUMS`はソース、告知、パッチの内容をハッシュで固定します。

## ビルド

リポジトリをAndroid Studioで開くか、次を実行します。

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
./gradlew :analysis:edax:assembleDebug
./gradlew :analysis:edax:connectedDebugAndroidTest
./gradlew :app:assembleDebug
```

リリースビルドには、実際のCHANRIVA本番Supabase URLとanonキーが必要です。値がない、プレースホルダー、HTTP、不正形式、別プロジェクトのいずれかであれば、リリースバリアントのパッケージ作成前に失敗します。値はGit管理外の`local.properties`または環境変数だけから設定します。その後、`assembleRelease`と`bundleRelease`を実行し、続けて`scripts/check-release-contents.ps1`を実行します。デバッグビルドと単体テストには本番の認証情報は不要です。

Android Gradle Pluginは、SDKライセンスの承認後に固定バージョンのNDK / CMakeをインストールします。端末固有の`ndkPath`を追跡対象ファイルへ追加しないでください。

CMakeは、固定した上流ソースと小さなAndroidブリッジを`arm64-v8a` / `x86_64`向けの`libedax_jni.so`へビルドします。Kotlinが呼ぶのはアプリ所有の`analysis:api`契約だけで、Edax Cの構造体が公開境界を越えることはありません。固定したr27dツールチェーンは柔軟なページサイズを既定で出力するより前のバージョンであるため、CMakeは最大／共通ページサイズを16 KiBとして明示的にリンクします。AGP 9.2は非圧縮ネイティブライブラリを16 KiBのZIP境界へ配置します。リリース内容検査は、パッケージ内の全ELF `LOAD`アラインメントとAPKのZIPアラインメントを検証します。

## 同梱しないデータ

`eval.dat`もOpening Bookもビルド入力ではありません。`src/main/assets`、`src/main/res`、`jniLibs`、`third_party`、リリースアーカイブのいずれにも入れてはいけません。インストルメンテーションテストは、テストアプリのキャッシュへ重みがすべて0の合成評価ファイルを実行時に作ります。本番成果物にはパッケージしません。

利用者は、正当に取得したEdax互換ファイルをAndroidのStorage Access Framework（SAF）から取り込みます。アプリは非公開ストレージへコピーし、名前、サイズ、取り込み時刻、SHA-256を記録してネイティブブリッジで検証します。広範なストレージ権限は要求しません。

## 公開成果物に対応するソース

配布するすべてのAPK / AABについて、ビルドに使ったリポジトリのコミットまたはタグを保持します。そのリビジョンには、Kotlinソース、Edaxソースとバージョン、Androidパッチ、Gradle Wrapper、CMakeファイル、データベースマイグレーション、このビルド手順が含まれます。`local.properties`、署名キー、Supabaseのサービスキー、取り込んだ評価／Bookファイル、テスト認証情報、生成済み成果物はコミットしないでください。

リリース前には全テスト、lint、境界検査に加え、リリースAPKとAABの両方へ`scripts/check-release-contents.ps1`を実行します。この検査は、第三者由来の評価／Bookデータ、合成テストデータ、デバッグ用フック、想定外のABI、広範なストレージ権限、16 KiB未満のネイティブELFアラインメントを拒否します。
