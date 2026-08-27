# ちゃんりば（ちゃんとリバーシ）

軽く一局打っても、その一局がちゃんと残り、ちゃんと振り返れて、次につながるAndroid向けリバーシアプリです。Supabase Auth・対戦相手の割り当て（matchmaking）・Realtimeを使った接続情報交換（signaling）・WebRTC DataChannelによるオンライン対局と、対局後のGameRecordをEdaxで解析するレビュー経路を実装しています。ローカル対局を含むAndroidアプリ全体でログインを必須とします。本アプリはEdax公式・公認アプリではありません。

設計、運用、リリース、監査資料は[技術資料インデックス](docs/README.md)から目的別に参照できます。

## 開発環境

- Android Studio Koala以降
- JDK 17
- Android SDK 36
- Android NDK `27.3.13750724` (r27d LTS) / CMake `3.22.1`
- Kotlin 2.2.10 / Compose Compiler plugin / Compose 1.6.8

## Androidの対応言語

Androidアプリは日本語と英語に対応します。`System default`では、日本語端末は日本語、それ以外のシステム言語は英語へ切り替わります。利用者は`Settings -> Language`から`日本語`または`English`を選べます。選択内容はAndroid/AppCompatが保存・同期するため、アプリ内の選択画面とAndroid 13以降のアプリ言語設定は同じ状態を共有します。言語選択にIPアドレス、国、地域情報は使いません。

言語メタデータは`app/src/main/res/xml/locales_config.xml`、翻訳文字列は`app/src/main/res/values/strings.xml`と`app/src/main/res/values-ja/strings.xml`にあります。修飾子のない`values`は英語なので、未対応のシステム言語は英語へ切り替わります。言語を追加する場合は、BCP 47 localeを`locales_config.xml`へ追加し、対応する`values-<locale>`ディレクトリを作成して既存リソースを翻訳し、`AppLanguage`と設定ダイアログへ選択肢を追加します。

## ビルドとテスト

```powershell
./gradlew test
./gradlew :data:supabase:testReleaseSupabaseConfig
./gradlew lint
./gradlew :app:assembleDebug
pwsh ./scripts/check-boundaries.ps1
pwsh ./scripts/check-sql-security.ps1
supabase start
supabase test db
supabase stop

# Emulator A/B smoke/E2E（認証情報は環境変数で渡し、commitしない）
./scripts/run-emulator-e2e.ps1 -StartSupabase -AutoPlay
```

リリース版（release variant）は、CHANRIVA本番Supabaseプロジェクトの設定が正しくない場合にビルドを失敗させます。環境変数またはGit管理外の`local.properties`から`SUPABASE_URL`と`SUPABASE_ANON_KEY`を渡す必要がありますが、debug／testビルドには本番の認証情報は不要です。リリースURLはHTTPSで、プロジェクト参照`zgzllmaoyymoeiqtybck`を対象にする必要があります。本番値を設定した後、次のコマンドで両方の成果物をビルド・検査します。

```powershell
./gradlew :app:assembleRelease :app:bundleRelease
pwsh ./scripts/check-release-contents.ps1
pwsh ./scripts/check-release-contents.ps1 -ArtifactPath app/build/outputs/bundle/release/app-release.aab
```

リリースメタデータへ記録するのは、秘密ではないプロジェクト参照、環境、URL、package ID、variantだけです。既存のクライアント用anonキーは引き続き実行時設定経路から渡し、このメタデータへ複製したりログへ出したりしません。リポジトリへ新しい認証情報も追加しません。

Android Studioでルートを開いて同期し、`app` configurationを実行することもできます。リポジトリにはGradle Wrapperを含めています。

## 構成

技術資料の入口は[docs/README.md](docs/README.md)です。システム構成の正本は[システム構成](docs/01_全体設計/システム構成.md)、運用責務、定期処理、Secret境界、障害確認先の正本は[運用マップ](docs/01_全体設計/運用マップ.md)です。初期の実装計画は現行手順ではないため、[旧実装計画](docs/09_履歴/旧実装計画.md)として履歴へ分離しています。`core:game`はAndroid/Supabase/WebRTC/Rating/Edaxを参照しない純粋Kotlinです。`feature:match`は`analysis`へ依存せず、棋譜／盤面レビューと専用の理論探求だけが`analysis:api`を参照します。

WebRTC SDKとSupabase SDKの具体実装は、それぞれ`transport:webrtc`と`data:supabase`へ隔離します。Protocol 2では`matches.release_status`がサーバーの正式状態であり、Android端末内のP2Pセッション状態とは別物です。`matches.server_status`はProtocol 1との一時的な互換性境界で、Protocol 2の正式状態ではありません。
WebRTC Android SDKはMaven Centralの`io.github.webrtc-sdk:android:144.7559.09`に固定しています。
`SupabaseModule`が`data:supabase`内でSDK clientとrepositoryを組み立て、appへは自前port interfaceだけを返します。

## Supabase / Android設定

Androidクライアントは、Git管理外の`local.properties`にある`supabase.url`と`supabase.anonKey`、または環境変数`SUPABASE_URL` / `SUPABASE_ANON_KEY`を読みます。値がない場合は設定エラーを画面に表示し、アプリを異常終了させません。サービスロール（service_role）のキーはAndroidリソースやBuildConfigへ絶対に置かないでください。

ホスト型（hosted）の疎通環境を同じ設定で作り直す手順は[Supabase検証環境構築](docs/06_基盤・外部サービス/Supabase検証環境構築.md)に記録しています。

1. Supabase projectを作成します。
2. `supabase/migrations`内のmigrationをファイル名順にすべてSupabase SQL EditorまたはSupabase CLIで適用します。
3. Android側へサービスロール（service_role）キーを置かず、AuthユーザーのJWTと公開anonキーだけをアプリ設定へ渡します。
4. 新規AuthユーザーはDBトリガーで`profiles` / `ratings`へ初期登録されます。Protocol 2のマッチングは`enqueue_or_match_v2`で開始し、応答喪失時も`claim_active_match_v2`でサーバー上の割り当てを復元できます。正式なレートのスナップショットと期限はDB側で管理します。
5. Protocol 2の結果提出には`submit_match_result_v2`を使用します。DBが正規化済み棋譜を再生し、正常終了では双方の一致する提出を確認した後、同一トランザクションでGameRecordとレートを確定します。参加者以外からの提出、レートの二重更新、不一致結果はDB側で拒否するか`DISPUTED`にします。
6. 公開プロフィールと`display_name`は初回公開版のDBから削除済みです。対局相手ratingは参加者限定のmatchmaking RPCが成立時snapshotだけを返します。

## Cloudflare Admin

`cloudflare-admin`はアカウント削除を扱う信頼済み管理BFFです。サービスロール（service_role）キーはワーカーのシークレットにだけ置き、`ADMIN_TOKEN`もワーカーのシークレットに置きます。初回公開版に段級位申請・証明画像・認証管理機能はなく、関連するDB／Storage／ワーカー経路も削除しています。

削除要求は10分ごとのワーカーCronまたは管理エンドポイントから再実行できます。非公開データの削除、Research識別子とアカウントの対応解除、Supabase Auth Adminによる削除、完了記録の順に処理し、途中失敗を完了扱いしません。共有棋譜に必要な内部IDのtombstoneは残りますが、表示名は保持しません。ブラウザやAndroidへサービスロール（service_role）キーを配布しません。

```powershell
cd cloudflare-admin
npm install
npx wrangler secret put SUPABASE_SERVICE_ROLE_KEY
npx wrangler secret put ADMIN_TOKEN
npm run deploy
```

## オンライン対局

`MatchTransport`のWebRTC DataChannel実装を使用します。Supabase RealtimeのPostgres Changesは、待機側の参加者だけが読める`match_notifications`と、SDPのOFFER / ANSWERを扱う`match_signals_v2`に限定します。通知を受けた待機側は`claim_active_match_v2`で割り当てを復元し、通知が届かない場合は同じリクエストIDによる回数を制限した状態照合を代替経路とします。P2P接続後の着手、時計、盤面、結果をRealtimeへ送信しません。Protocol 2のレート対象結果は、DBが正規化済み棋譜を合法手として再生し、`NORMAL`の終局、結果、ハッシュを導出してから確定します。実機2台では、Auth設定、同一Supabaseプロジェクト、現在のSTUN-only設定、2台のキュー参加、DataChannel成立、双方の開始ACK、同一棋譜・ハッシュ、結果提出の順で確認します。

`その他 -> アカウント`には本人のサーバー管理`ratings.current_rating`、「前日順位」、「この端末で確認した最高の前日順位」を表示します。前日順位は日本時間の前日終了時点のレートをもとに算出します。この端末の最高値は、ユーザーがアカウント画面を開き、その時点で有効な前日順位を実際に取得できた場合だけ比較・保存します。アプリ起動、foreground復帰、background処理では更新せず、画面を開かなかった日の順位も記録しません。日次順位の母集団は、東京のsnapshot cutoffから過去30日以内の半開区間に、既存の確定レート更新（`rating_history`）が1件以上ある未削除ユーザーです。順位計算にはcutoff前の最新`rating_history.rating`を使い、cutoff後の`ratings.current_rating`は前日順位へ混入しません。独自の最低対局数や仮レート条件は追加していません。Androidはsnapshot dateが東京基準の本当の前日と一致するときだけ表示・ローカル記録更新に利用します。この記録はSupabase AuthのユーザーUUIDごとに端末へ保存し、サーバーや別端末へ同期しません。対局画面には成立時に保存した相手rating snapshotだけを表示し、ニックネーム、メールアドレス、UUIDをプレイヤー名として表示しません。

`applicationId = com.shinpstudio.chanriva` をGoogle Play公開用の正式IDとして使用します。repository名と内部package/DB識別子の`othello`は、公開ブランドではなく既存の技術識別子として変更していません。

Emulator A/Bの再現手順と、emulatorで完了できる項目・物理端末に残る項目は
[`実機・エミュレーター確認`](docs/08_テスト・監査/実機・エミュレーター確認.md)を参照してください。`build/e2e/`には
secretを含めないXML、スクリーンショット、対象tagのlogcatを保存できます。

Protocol 2では`matches.release_status`が`MATCHED`、`ACTIVE`、`RECONNECTING`、`RESULT_PENDING`などの正式状態を管理します。初回接続は接続世代（epoch）0、開始済み対局の再接続はepoch 1〜3を使います。DataChannelが開いただけでは対局を開始せず、双方が`ack_match_started_v2`を完了してサーバーが`ACTIVE`を返した後でだけ`PLAYING`へ進みます。`MATCHED`のリースは2分、`ACTIVE`の異常終了に対する安全網は15分、再接続と結果待ちの猶予は45秒です。応答喪失や古い端末内状態は`get_release_match_state_v2`、`resume_match_v2`、`reconcile_match_v2`などでサーバーの正式状態と照合します。詳細な契約と設計理由は[通常接続設計](docs/02_オンライン対局/通常接続設計.md)、[再接続設計](docs/02_オンライン対局/再接続設計.md)、[リリースハードニング設計](docs/07_リリース・移行/リリースハードニング設計.md)を正本とします。

オンライン対局の定期処理は既存の`cloudflare-admin` Cronへ集約し、10分ごとにサービスロール（service_role）で`run_match_maintenance_v2(100)`と`run_legacy_match_maintenance_v1(100)`を独立実行します。後者は期限切れの旧プロトコル状態を先に永続化し、その後Protocol 1の接続情報とキューをそれぞれ100行以下で削除します。オンライン対局のクリーンアップ用に追加のSupabase Cron / pg_cronは作成しません。

日次順位は既存の保守経路へ分散させず、Supabase Cronジョブ`daily-rating-snapshot`がAsia/Tokyoの日付境界後の毎日00:10 JSTに`select public.refresh_rating_daily_snapshot();`を実行します。関数は`SECURITY DEFINER`かつ空の`search_path`で、`PUBLIC` / `anon` / `authenticated`からEXECUTEを剥奪し、`service_role`または明示的に権限を持つDB所有者のCronだけが実行します。正式なマイグレーションは`202608220029_daily_rating_snapshot.sql`です。028は本番未適用の永久欠番であり、適用してはいけません。マイグレーション自身は関数と最新スナップショット用テーブルを追加するだけで、Cron拡張とジョブは別の本番操作として導入しています。適用監査、定期処理の方針、導入結果は[日次レートスナップショット運用](docs/04_レーティング/日次レートスナップショット運用.md)を参照してください。

## Edax / OSS

対局後レビューの解析エンジンには[Edax 4.6](https://github.com/abulmo/edax-reversi)を使用します。upstream commitは`14f048c05ddfa385b6bf954a9c2905bbe677e9d3`へ固定し、`Kotlin -> analysis:api -> analysis:edax -> JNI -> native Edax`で統合しています。Androidアプリ全体はGNU GPL version 3で配布します。ライセンス全文は[`LICENSE`](LICENSE)、著作権・第三者依存関係の表示は[`NOTICE.md`](NOTICE.md)、固定ソース・patch・再構築手順は[`third_party/edax/UPSTREAM.md`](third_party/edax/UPSTREAM.md)と[Edaxビルド](docs/05_解析エンジン/Edaxビルド.md)を参照してください。

Edaxの評価データ（`eval.dat`等）とOpening Bookは、権利をEdax本体と分離して扱い、APK/AABにもrepositoryにも同梱しません。評価データは`設定 -> 解析`から、Edax公式GitHub Releasesのv4.4 `eval.7z`から`eval.dat`だけを自動設定するか、ユーザーが正当に取得・所有するファイルをStorage Access Frameworkで選んで、アプリprivate storageへコピーできます。ダウンロード・展開・検証に成功するまで既存データは置き換えません。評価データ未設定時は偽の値を表示しません。Bookは任意で、未設定またはbook miss時は通常のEdax探索を使います。詳しい導線は`https://chanriva.shinp-studio.com/edax`を使用します。

Reviewでは実戦開始局面、任意ply、最終局面、保存前variation局面を解析でき、現在手番の全合法手へ予測終局石差を盤面上表示します。完全読みの`exact`、深さ依存の`heuristic`、import済みBook由来の`book`を区別します。棋譜Reviewは明示操作で解析し、「盤面から検討」は初期局面と局面移動のたびに自動解析します。どちらも単一background workerを使い、局面変更・画面離脱・新規解析でcancel/stale-result破棄を行います。「盤面から検討」のrequest世代管理と64局面LRUは[盤面検討自動解析設計](docs/05_解析エンジン/盤面検討自動解析.md)を参照してください。

「理論探求」は棋譜Reviewから分離した一時研究セッションです。分岐を失わない変化木を自由に進み、各合法手についてEdax評価と、Edaxから独立した開放度・相手モビリティ・フロンティア石数・潜在モビリティを2段で比較します。変化木は次回起動用に端末へ一時保存し、解析結果はbuild番号で無効化する100MBの容量ベースLRUへ保存します。責務境界と指標定義は[理論探求設計](docs/05_解析エンジン/理論探求設計.md)を参照してください。

対応ABIは`arm64-v8a`と開発用`x86_64`だけです。Edaxを含む全native libraryとAPK packagingは16 KiB page-size alignmentをrelease検査します。`feature:match`と`core:game`はanalysis/JNI/Edaxへ依存せず、ranked/live DataChannel経路から解析へ到達できません。

## 公開前に残る判断と実機確認

- ユーザー判断: リポジトリ改名の要否、Google Playの公開バージョン・価格・対象年齢・配信国、公開サポートメール、TURN提供者、Play App Signingとテスト／公開。
- 物理端末: arm64 native実性能、Edaxの持続性能・thermal・battery、Wi-Fi↔mobile/mobile↔mobile、carrier NAT/CGNAT/symmetric NAT、STUN-only成功率とTURN必要率、network handover、メーカー固有background挙動。

launcher icon、アプリ内Privacy Policy導線、公開Privacy Policy（`https://chanriva.shinp-studio.com/privacy`）、アプリ不要のWeb削除受付（`https://chanriva.shinp-studio.com/account-deletion`）は実装済みです。signed release / Play生成APKでの最終runtime確認はPlay App Signing後の別ゲートです。

現在のICE設定はPublic STUNのみです。Emulator A/B成功はTURN不要の根拠にはせず、物理ネットワーク試験後に導入判断します。有料サービスを前提にした設定は含めていません。
