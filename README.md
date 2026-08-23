# ちゃんりば（ちゃんとリバーシ）

軽く一局打っても、その一局がちゃんと残り、ちゃんと振り返れて、次につながるAndroid向けリバーシアプリです。アプリ全体をログイン必須とし、Supabase Auth・matchmaking・Realtime signaling・WebRTC DataChannelを使うオンライン対局と、対局後GameRecordをEdaxで解析するレビュー経路を実装しています。本アプリはEdax公式・公認アプリではありません。

## 開発環境

- Android Studio Koala以降
- JDK 17
- Android SDK 36
- Android NDK `27.3.13750724` (r27d LTS) / CMake `3.22.1`
- Kotlin 2.2.10 / Compose Compiler plugin / Compose 1.6.8

## ビルドとテスト

```powershell
./gradlew test
./gradlew lint
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
./gradlew :app:bundleRelease
pwsh ./scripts/check-boundaries.ps1
pwsh ./scripts/check-sql-security.ps1
pwsh ./scripts/check-release-contents.ps1
supabase start
supabase test db
supabase stop

# Emulator A/B smoke/E2E (credentials are environment variables, never committed)
./scripts/run-emulator-e2e.ps1 -StartSupabase -AutoPlay
```

Android Studioでルートを開いて同期し、`app` configurationを実行することもできます。リポジトリにはGradle Wrapperを含めています。

## 構成

詳細は [ARCHITECTURE.md](ARCHITECTURE.md) と [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) を参照してください。運用責務、定期処理、Secret境界、障害確認先の正本は [docs/OPERATIONS_MAP.md](docs/OPERATIONS_MAP.md) です。`core:game`はAndroid/Supabase/WebRTC/Rating/Edaxを参照しない純粋Kotlinです。`feature:match`は`analysis`へ依存せず、Reviewだけが`analysis:api`を参照します。

WebRTC SDKとSupabase SDKの具体実装は、それぞれ`transport:webrtc`と`data:supabase`へ隔離します。`matches.server_status`はサーバーが保証できる状態だけを保持し、AndroidのP2P session stateとは別物です。
WebRTC Android SDKはMaven Centralの`io.github.webrtc-sdk:android:144.7559.09`に固定しています。
`SupabaseModule`が`data:supabase`内でSDK clientとrepositoryを組み立て、appへは自前port interfaceだけを返します。

## Supabase / Android configuration

The Android client reads `supabase.url` and `supabase.anonKey` from the untracked
`local.properties` file, or `SUPABASE_URL` / `SUPABASE_ANON_KEY` from the environment.
Missing values produce a visible configuration error and do not crash the app. Never
place a service-role key in Android resources or BuildConfig.

Hosted疎通環境を同じ設定で作り直す手順は [docs/SUPABASE_HOSTED_SETUP.md](docs/SUPABASE_HOSTED_SETUP.md) に記録しています。

1. Supabase projectを作成します。
2. `supabase/migrations`内のmigrationをファイル名順にすべてSupabase SQL EditorまたはSupabase CLIで適用します。
3. Android側へservice-role keyを置かず、AuthユーザーのJWTと公開anon keyだけをアプリ設定へ渡します。
4. 新規AuthユーザーはDB triggerで`profiles`/`ratings`へbootstrapされます。マッチングは`enqueue_or_match()`だけを使用し、公式Rating snapshotとTTLをDB側で管理します。
5. 結果提出は`submit_match_result(...)`を使用します。2件目の提出時に同一transaction内で自動finalizeされ、`finalize_match_v2(...)`はreconciliation用に残します。参加者以外・二重Rating更新・不一致結果はDB側で拒否または`DISPUTED`になります。
6. 公開プロフィールと`display_name`は初回公開版のDBから削除済みです。対局相手ratingは参加者限定のmatchmaking RPCが成立時snapshotだけを返します。

## Cloudflare Admin

`cloudflare-admin`はアカウント削除を扱う信頼済み管理BFFです。service-role keyはWorker secretにだけ置き、`ADMIN_TOKEN`もWorker secretに置きます。初回公開版に段級位申請・証明画像・verification管理機能はなく、関連DB/Storage/Worker経路も削除しています。

削除要求は10分ごとのWorker Cronまたは管理endpointから再実行できます。私有データ削除、Research identityのunlink、Supabase Auth Admin削除、完了記録の順に処理し、途中失敗を完了扱いしません。共有棋譜に必要な内部ID tombstoneは残りますが、表示名は保持しません。ブラウザやAndroidへservice-role keyを配布しません。

```powershell
cd cloudflare-admin
npm install
npx wrangler secret put SUPABASE_SERVICE_ROLE_KEY
npx wrangler secret put ADMIN_TOKEN
npm run deploy
```

## オンライン対局

`MatchTransport`のWebRTC DataChannel実装を使用します。Supabase RealtimeのPostgres Changesは、待機側へのparticipant限定`match_notifications`と、SDP offer/answer用`match_signaling`だけに使用します。通知を受けた待機側は即座にmatchをclaimし、heartbeat pollingは通知失敗時のfallbackとして残します。P2P接続後に着手、時計、盤面、結果をRealtimeへ送信しません。実機2台では、Auth設定、同一Supabase project、TURN/STUN設定、2台のqueue参加、DataChannel成立、両者start ACK、双方の同一棋譜・hash、結果提出の順で確認します。

`その他 -> アカウント`には本人のサーバー管理`ratings.current_rating`、「前日順位」、「この端末で確認した最高の前日順位」を表示します。前日順位は日本時間の前日終了時点のレートをもとに算出します。この端末の最高値は、ユーザーがアカウント画面を開き、その時点で有効な前日順位を実際に取得できた場合だけ比較・保存します。アプリ起動、foreground復帰、background処理では更新せず、画面を開かなかった日の順位も記録しません。日次順位の母集団は、東京のsnapshot cutoffから過去30日以内の半開区間に、既存の確定レート更新（`rating_history`）が1件以上ある未削除ユーザーです。順位計算にはcutoff前の最新`rating_history.rating`を使い、cutoff後の`ratings.current_rating`は前日順位へ混入しません。独自の最低対局数や仮レート条件は追加していません。Androidはsnapshot dateが東京基準の本当の前日と一致するときだけ表示・ローカル記録更新に利用します。この記録はSupabase AuthのユーザーUUIDごとに端末へ保存し、サーバーや別端末へ同期しません。対局画面には成立時に保存した相手rating snapshotだけを表示し、ニックネーム、メールアドレス、UUIDをプレイヤー名として表示しません。

`applicationId = com.shinpstudio.chanriva` をGoogle Play公開用の正式IDとして使用します。repository名と内部package/DB識別子の`othello`は、公開ブランドではなく既存の技術識別子として変更していません。

Emulator A/Bの再現手順と、emulatorで完了できる項目・物理端末に残る項目は
[`docs/DEVICE_TEST.md`](docs/DEVICE_TEST.md)を参照してください。`build/e2e/`には
secretを含めないXML、スクリーンショット、対象tagのlogcatを保存できます。

`matches`のCREATED leaseは5分のsignaling用です。DataChannel成立後、両participantが`ack_match_started`を一度呼び、クライアントが`get_match_start_state`で両者ACKを確認してからPLAYINGへ進みます。両者ACK後はP2P開始事実と24時間のbounded play leaseを記録します。PENDING_RESULTのactive reservationは5分で、30日保持の監査用submissionとは分離しています。期限切れはABANDONEDへ遷移してからreservationを解放します。matchmaking hot pathはcaller-scoped reconciliationとqueue expiryだけを行い、stale matchとterminal recordの全体cleanupはmaintenance pathで実行します。Supabase Cron/pg_cronを使う場合は、service role相当で次を1時間ごとに実行します。

```sql
select public.cleanup_stale_created_matches();
select public.cleanup_expired_pending_results();
select public.cleanup_expired_started_matches();
select public.cleanup_terminal_matches();
```

日次順位は既存のmaintenance pathに分散させず、Supabase Cron job `daily-rating-snapshot`がAsia/Tokyoの日付境界後の毎日00:10 JSTに`select public.refresh_rating_daily_snapshot();`を実行します。関数は`SECURITY DEFINER`かつ空の`search_path`で、`PUBLIC` / `anon` / `authenticated`からEXECUTEを剥奪し、`service_role`または明示的に権限を持つDB ownerのCronだけが実行します。正式なmigrationは`202608220029_daily_rating_snapshot.sql`です。028は本番未適用の永久欠番であり、適用してはいけません。migration自身は関数と最新snapshot用tableを追加するだけで、Cron extension / jobは別の本番操作として導入しています。適用監査、scheduler方針、導入結果は[`docs/DAILY_RATING_SNAPSHOT_ROLLOUT.md`](docs/DAILY_RATING_SNAPSHOT_ROLLOUT.md)を参照してください。

## Edax / OSS

対局後Reviewの解析エンジンには[Edax 4.6](https://github.com/abulmo/edax-reversi)を使用します。upstream commitは`14f048c05ddfa385b6bf954a9c2905bbe677e9d3`へ固定し、`Kotlin -> analysis:api -> analysis:edax -> JNI -> native Edax`で統合しています。Android app全体はGNU GPL version 3で配布します。ライセンス全文は[`LICENSE`](LICENSE)、著作権・第三者dependency表示は[`NOTICE.md`](NOTICE.md)、固定ソース・patch・再構築手順は[`third_party/edax/UPSTREAM.md`](third_party/edax/UPSTREAM.md)と[`docs/EDAX_BUILD.md`](docs/EDAX_BUILD.md)を参照してください。

Edaxの評価データ（`eval.dat`等）とOpening Bookは、権利をEdax本体と分離して扱い、APK/AABにもrepositoryにも同梱しません。評価データは`設定 -> 解析`から、Edax公式GitHub Releasesのv4.4 `eval.7z`から`eval.dat`だけを自動設定するか、ユーザーが正当に取得・所有するファイルをStorage Access Frameworkで選んで、アプリprivate storageへコピーできます。ダウンロード・展開・検証に成功するまで既存データは置き換えません。評価データ未設定時は偽の値を表示しません。Bookは任意で、未設定またはbook miss時は通常のEdax探索を使います。詳しい導線は`https://chanriva.shinp-studio.com/edax`を使用します。

Reviewでは実戦開始局面、任意ply、最終局面、保存前variation局面を解析でき、現在手番の全合法手へ予測終局石差を盤面上表示します。完全読みの`exact`、深さ依存の`heuristic`、import済みBook由来の`book`を区別します。解析は明示操作時だけ単一background workerで実行し、ply変更・variation変更・画面離脱・新規解析でcancel/stale-result破棄を行います。

対応ABIは`arm64-v8a`と開発用`x86_64`だけです。Edaxを含む全native libraryとAPK packagingは16 KiB page-size alignmentをrelease検査します。`feature:match`と`core:game`はanalysis/JNI/Edaxへ依存せず、ranked/live DataChannel経路から解析へ到達できません。

## 公開前に残る判断と実機確認

- ユーザー判断: repository renameの要否、Google Playの公開version・価格・対象年齢・配信国、公開support email、TURN provider、Play App Signingとtesting/publication。
- 物理端末: arm64 native実性能、Edaxの持続性能・thermal・battery、Wi-Fi↔mobile/mobile↔mobile、carrier NAT/CGNAT/symmetric NAT、STUN-only成功率とTURN必要率、network handover、メーカー固有background挙動。

launcher icon、アプリ内Privacy Policy導線、公開Privacy Policy（`https://chanriva.shinp-studio.com/privacy`）、アプリ不要のWeb削除受付（`https://chanriva.shinp-studio.com/account-deletion`）は実装済みです。signed release / Play生成APKでの最終runtime確認はPlay App Signing後の別ゲートです。

現在のICE設定はPublic STUNのみです。Emulator A/B成功はTURN不要の根拠にはせず、物理ネットワーク試験後に導入判断します。有料サービスを前提にした設定は含めていません。
