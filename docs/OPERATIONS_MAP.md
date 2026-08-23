# ちゃんりば 運用責務マップ

この文書は、ちゃんりばの実行基盤、運用主体、Cron / schedule、Secret境界、障害確認先、本番導入状態を機能単位で追うための正本です。詳細な手順や設計根拠はリンク先の個別文書を正本とし、ここでは「何がどこで動き、問題時にどこを見るか」を一元化します。

- 基準日: 2026-08-23（Asia/Tokyo）
- 基準commit: `de5ae6b3aaaaf2186be475953df83690d8858395`
- 本番状態は、リポジトリ設定と本番監査で確認できた範囲を区別して記載します。
- 本番Supabaseには`202608180027_account_lifecycle.sql`までの関連機能が存在します。日次順位の正式migration `202608220029_daily_rating_snapshot.sql`は未適用です。

## 全体サマリ

| 機能 | 目的 | 主な実行場所 | 実行契機 / 頻度 | 本番状態 |
|---|---|---|---|---|
| minimum supported app version | 古いAndroidをAuth開始前に起動ゲートする | Android / public Cloudflare Worker `chanriva` | Androidプロセス起動時。設定変更はWorker変数更新時 | 稼働中 |
| 認証 | Email / Passwordによる登録、確認、ログイン、session管理 | Android / Supabase Auth / PostgreSQL | ユーザー操作、アプリ起動時のsession restore | 稼働中 |
| パスワード再設定 | メールのrecovery linkから新しいパスワードを設定する | Android / Supabase Auth / public Cloudflare Worker | ユーザー要求時 | 稼働中。Redirect URL設定とE2Eは運用確認対象 |
| オンライン対局 | matchmakingからP2P対局、確定結果、rating更新までを管理する | Android / Supabase PostgreSQL・Realtime / WebRTC | ユーザー操作、対局イベント | 稼働中 |
| 着手傾向集計（Research） | 実プレイヤーの局面ごとの着手傾向を匿名集計し、レート帯別に分析する | Supabase PostgreSQL / GitHub Actions | 毎時17分・47分（UTC）、手動実行 | 稼働中（直近schedule run成功を確認） |
| アカウント削除・休眠整理 | 本人要求、未確認7日、確認済み休眠365日のアカウントを安全に削除する | Android / public Worker / Supabase / trusted admin Worker | 要求時、Cloudflare Cronは10分ごと | 稼働設定あり。Cron実行履歴は継続監視対象 |
| 前日順位 | 日本時間の前日終了時点の順位を日次確定し、AccountScreenへ表示する | Supabase PostgreSQL / Android | 将来は毎日00:10 JST | **未導入**（029未適用、Supabase Cron 0件） |
| LP / Web account API | LP、法務・アカウント画面、client-facing HTTP APIを公開する | public Cloudflare Worker `chanriva` + Assets | HTTP request、main連携のWorkers Builds | 稼働中 |
| CI | Android、admin Worker、SQL・境界・release内容を検証する | GitHub Actions | push / pull_request | 稼働中。deploy処理なし |

## 1. minimum supported app version

| 項目 | 内容 |
|---|---|
| 機能名 | Android minimum supported app version gate |
| 目的 | Auth/session開始前に`BuildConfig.VERSION_CODE`が利用可能かを判定し、将来の非対応版をserver-side設定だけで停止できるようにする |
| 主な実行場所 | Android `VersionGate`、public Cloudflare Worker `chanriva` |
| 実行契機 | Androidプロセス起動時に1回。Error画面の再試行時に再取得 |
| 実行頻度 | Compose再描画ごとには取得しない |
| 主な読み取り先 | `GET /api/app-config`の固定JSON contract、Worker変数`ANDROID_MIN_VERSION_CODE` |
| 主な書き込み先 | なし |
| Secret / credential | 認証・Secret不要。`ANDROID_MIN_VERSION_CODE`は非Secret Worker変数 |
| 失敗時の確認場所 | Android Logcat / VersionGate Error UI、`chanriva` Worker logs、Cloudflare deployment/build logs |
| 関連migration | なし |
| 関連RPC | なし |
| 関連workflow / Worker | `landing-page/worker/index.ts`、Worker `chanriva` |
| 関連ドキュメント | [Architecture](../ARCHITECTURE.md#application-http-boundary-and-startup-gates)、[landing-page README](../landing-page/README.md) |
| 本番状態 | 稼働中。初期設定値は`1` |
| 注意事項 | 起動順は`VersionGate -> AuthGate -> AuthenticatedApp`。無効設定・通信失敗はfail closed。値を現行公開versionより上げる操作はリリース状況を確認して行う |

## 2. 認証

| 項目 | 内容 |
|---|---|
| 機能名 | Supabase Email / Password認証 |
| 目的 | アプリ全体への入口を認証済みユーザーに限定し、登録、Email確認、ログイン、session restore、logoutを提供する |
| 主な実行場所 | Android `AuthGate` / `AuthSessionController` / `SupabaseAuthGateway`、Supabase Auth、PostgreSQL |
| 実行契機 | `VersionGate`通過後のsession restore、登録・ログイン・logout操作、Auth session event |
| 実行頻度 | 起動session restoreはプロセス起動ごと。`touch_last_active()`は認証成立時にbest effortで呼び、DB側で1日より短い更新を抑制 |
| 主な読み取り先 | Supabase Auth user/session、`profiles`、`ratings` |
| 主な書き込み先 | Supabase Auth user/session、bootstrap triggerによる`profiles` / `ratings`、`profiles.last_active_at` |
| Secret / credential | Androidの公開Supabase URL / publishable（anon）key、ユーザーのAuth session JWT。service-roleやDB owner credentialは使用しない |
| 失敗時の確認場所 | Androidの認証エラーUI / Logcat、Supabase DashboardのAuth logs、必要に応じPostgres / API logs |
| 関連migration | 初期Auth user bootstrap、`202608180027_account_lifecycle.sql` |
| 関連RPC | `touch_last_active()` |
| 関連workflow / Worker | なし |
| 関連ドキュメント | [Supabase hosted setup](SUPABASE_HOSTED_SETUP.md#auth)、[Architecture](../ARCHITECTURE.md#application-http-boundary-and-startup-gates) |
| 本番状態 | 稼働中 |
| 注意事項 | アプリ全体はログイン必須。Email確認redirectは`https://chanriva.shinp-studio.com/signup-complete`。`last_active_at`はaccount lifecycle用で、前日順位のactive判定には使わない |

認証成立後は`AuthSessionController`が`touch_last_active()`をbest effortで実行してから認証済み画面へ進みます。logout時はオンラインsession終了を試みた後、Supabase Auth sessionを終了します。

## 3. パスワード再設定

| 項目 | 内容 |
|---|---|
| 機能名 | Supabase Auth password recovery |
| 目的 | 旧パスワードを表示・復元せず、登録Emailへの本人確認リンクから新しいパスワードを設定する |
| 主な実行場所 | Android、Supabase Auth、public Cloudflare Worker `chanriva` |
| 実行契機 | Androidログイン画面の再設定要求、メールリンクのブラウザ遷移、Webフォーム送信 |
| 実行頻度 | ユーザー要求時 |
| 主な読み取り先 | Supabase Auth user、メールリンクの一時recovery access token |
| 主な書き込み先 | Supabase Auth password |
| Secret / credential | Androidのpublishable（anon）key、Worker secret `SUPABASE_ANON_KEY`、一時recovery access token。service-roleは使わない |
| 失敗時の確認場所 | Android UI / Logcat、Supabase Auth logs、`chanriva` Worker logs、Cloudflare deployment/build logs |
| 関連migration | なし |
| 関連RPC | なし（Supabase Auth APIを使用） |
| 関連workflow / Worker | `landing-page/worker/index.ts`、`POST /api/password-reset/complete` |
| 関連ドキュメント | [landing-page README](../landing-page/README.md)、[Supabase hosted setup](SUPABASE_HOSTED_SETUP.md#auth) |
| 本番状態 | Web経路は稼働中。Dashboard Redirect URL登録とメールE2Eを運用時に確認する |
| 注意事項 | Supabase Dashboard Auth URL Configurationに`https://chanriva.shinp-studio.com/reset-password`を許可する。token・passwordをログへ出さない |

経路は次のとおりです。

```text
Android reset mail要求
  -> Supabase Authがrecovery mail送信
  -> https://chanriva.shinp-studio.com/reset-password
  -> public Workerの同一origin API
  -> Supabase Authで新passwordへ更新
```

## 4. オンライン対局

| 項目 | 内容 |
|---|---|
| 機能名 | matchmaking・WebRTC対局・結果確定・rating更新 |
| 目的 | participant限定の待機・signalingを経てP2P対局を行い、両者が一致して提出した確定結果だけを保存・rating反映する |
| 主な実行場所 | Android、Supabase PostgreSQL / Realtime、WebRTC DataChannel |
| 実行契機 | 対局開始操作、queue heartbeat / notification、signaling、start ACK、両者のresult submission |
| 実行頻度 | ユーザー対局ごと。foreground待機中heartbeatは10秒ごと |
| 主な読み取り先 | `match_queue`、`matches`、`match_notifications`、`match_signaling`、`match_submissions`、`ratings`、`rating_history` |
| 主な書き込み先 | queue、match lifecycle、notification / signaling、start ACK、submission、confirmed `game_records`、`ratings`、`rating_history` |
| Secret / credential | Androidのpublishable（anon）keyとユーザーJWT。service-role / DB owner credentialは使わない |
| 失敗時の確認場所 | Android match diagnostics / Logcat、Supabase Realtime logs、Postgres / API logs、対象RPCとRLSのcatalog |
| 関連migration | `202608150025_private_match_rating.sql`ほかmatch lifecycle / Realtime / result関連migration |
| 関連RPC | `enqueue_or_match()`、`cancel_waiting()`、`heartbeat_waiting()`、`claim_waiting_match()`、`reconcile_expired_active_match_for_user()`、`ack_match_started()`、`get_match_start_state()`、`abandon_match()`、`submit_match_result(...)`、`finalize_match_v2(...)` |
| 関連workflow / Worker | なし |
| 関連ドキュメント | [Architecture](../ARCHITECTURE.md#client-session-state-vs-server-persisted-match-state)、[Device test](DEVICE_TEST.md) |
| 本番状態 | 稼働中 |
| 注意事項 | P2P成立後の盤面・着手・時計・結果をSupabase Realtimeへ送らない。Realtimeはparticipant限定のmatch notification / SDP signaling用。確定結果だけがratingを更新する |

`matches.black_rating_at_start` / `white_rating_at_start`はmatch成立時のserver-owned snapshotで、各クライアントには相手ratingだけを返します。両者一致でCONFIRMEDになった確定レート対象対局は、両participantの`rating_history`を重複なく作成します。`rating_history`はrating監査だけでなく、着手傾向集計（Research）のrating帯判定と前日順位の時点復元にも利用されます。

## 5. 着手傾向集計（Research）

| 項目 | 内容 |
|---|---|
| 機能名 | 着手傾向集計（Research） |
| 目的 | 実プレイヤーの局面ごとの着手傾向を匿名集計し、レート帯別に分析する |
| 主な実行場所 | Supabase PostgreSQL、GitHub Actions `Research batch` |
| 実行契機 | 同意中ユーザーの対局確定時に収集。validator / aggregationはGitHub Actions scheduleまたは手動dispatch |
| 実行頻度 | cron `17,47 * * * *`（UTC、毎時17分・47分の1時間2回）。schedule遅延はGitHub Actionsの特性上あり得る |
| 主な読み取り先 | consent / participation、`rating_history`、`research_private`の未処理game・position・move source、policy |
| 主な書き込み先 | Research participation、匿名subject / contributor、validation状態、position / move aggregate、rating帯別公開aggregate |
| Secret / credential | DB role `research_batch`の接続情報をGitHub Environment `research-production`のsecret `RESEARCH_BATCH_DATABASE_URL`へ保存。service-role、DB owner password、JWT signing secret、Cloudflare credentialは渡さない |
| 失敗時の確認場所 | GitHub Actionsの`Research batch` run / step logs、Supabase Postgres logs、[Research operations](RESEARCH_OPERATIONS.md#monitoring)の確認query |
| 関連migration | Research schema / capture / validator / aggregation / unlinkの018〜023 |
| 関連RPC | `get_research_participation_status()`、`set_research_participation(...)`、`get_research_position(...)`、trusted unlink / batch wrapper functions |
| 関連workflow / Worker | [`.github/workflows/research-batch.yml`](../.github/workflows/research-batch.yml)。`ubuntu-latest`、timeout 30分、concurrency `research-production-batch`、`cancel-in-progress: false` |
| 関連ドキュメント | [Research operations](RESEARCH_OPERATIONS.md)、[Research data design](RESEARCH_DATA_DESIGN.md) |
| 本番状態 | 稼働中。2026-08-23の監査で直近schedule runの成功を確認 |
| 注意事項 | validation最大200 game、aggregation最大500 game。Research batchはCloudflare Admin Cronでは動かさない。manual dispatchはproduction DBを処理し得るためOWNER承認対象 |

account deletionでは新規captureを停止し、`unlink_research_subject`でAuth userとの逆参照を切ります。同意中に受理済みの匿名contributor / aggregate weightは、削除によって再計算・消去しません。詳細な権限、recovery、停止手順は[Research operations](RESEARCH_OPERATIONS.md)を参照してください。

## 6. アカウント削除・休眠整理

| 項目 | 内容 |
|---|---|
| 機能名 | 本人要求とaccount lifecycleによるアカウント削除 |
| 目的 | Android / Webの本人要求、および未確認7日・確認済み休眠365日の対象をqueueし、private data、Research link、Auth userをretry可能な順序で削除する |
| 主な実行場所 | Android、public Worker `chanriva`、Supabase PostgreSQL、trusted admin Worker `othello-admin`、Supabase Auth Admin API |
| 実行契機 | Android / Webの削除要求。Cloudflare scheduled handlerが10分ごとに休眠対象queueとpending処理を実行。Bearer保護admin endpointからの明示的再処理も可能 |
| 実行頻度 | Cloudflare Cron `*/10 * * * *`（UTCでもローカル時刻でも10分ごと） |
| 主な読み取り先 | Auth user、`profiles.last_active_at` / Email確認状態、`account_deletion_requests`、削除対象private data、Research subject link |
| 主な書き込み先 | `account_deletion_requests`、削除・匿名化されたprivate / profile data、Research unlink状態、Supabase Auth user削除、完了状態 |
| Secret / credential | public経路は`SUPABASE_ANON_KEY`と本人credential。trusted WorkerはCloudflare secrets `SUPABASE_SERVICE_ROLE_KEY` / `ADMIN_TOKEN`。Android / browserへservice-roleを渡さない |
| 失敗時の確認場所 | Android / Webエラー、`chanriva` Worker logs、`othello-admin` Worker logs / Cron trigger履歴、Supabase Auth Admin / Postgres logs、`account_deletion_requests`状態 |
| 関連migration | account deletion 016・017、Research unlink 021、private data整理 026、`202608180027_account_lifecycle.sql` |
| 関連RPC | `request_account_deletion()`、`queue_expired_account_deletions()`、`prepare_account_deletion(...)`、`unlink_research_subject(...)`、`complete_account_deletion(...)` |
| 関連workflow / Worker | `cloudflare-admin/src/index.ts`、Worker `othello-admin`、Cron `*/10 * * * *` |
| 関連ドキュメント | [Supabase hosted setup](SUPABASE_HOSTED_SETUP.md#信頼済み管理worker)、[Research operations](RESEARCH_OPERATIONS.md#account-deletion-verification)、[landing-page README](../landing-page/README.md) |
| 本番状態 | migration 027は本番適用済み。Worker Cron設定はrepositoryに存在し、実行成否はWorker logs / trigger履歴で継続確認する |
| 注意事項 | 未確認登録は7日、確認済みは`last_active_at`が365日より古い場合にqueueする。Cronは直接削除せず、既存のidempotent request / prepare / unlink / Auth delete / complete経路を使う。途中失敗を完了扱いしない |

AndroidとWebはどちらも最終的に`request_account_deletion()`へ到達します。WebのEmail / Password経路は`/api/account-deletion/start`、パスワードを使えない場合は確認メールから`/account-deletion/confirm`と`/api/account-deletion/email/confirm`へ進みます。Supabase DBでprivate data整理とprofile tombstoneを準備し、trusted WorkerがResearch identityをunlinkしてからAuth Admin userを削除し、完了を記録します。

## 7. 前日順位

| 項目 | 内容 |
|---|---|
| 機能名 | 前日順位と「この端末で確認した最高の前日順位」 |
| 目的 | Asia/Tokyoの前日終了時点のrating順位を日次で確定し、本人のAccountScreenにだけ表示する |
| 主な実行場所 | Supabase PostgreSQL、Android AccountScreen / device-local SharedPreferences |
| 実行契機 | 将来のSupabase Cron / pg_cron。AndroidはAccountScreenを開いた時だけ本人rowを取得し、有効な前日分だけlocal bestと比較 |
| 実行頻度 | **予定:** cron `10 15 * * *`（毎日15:10 UTC = 翌日00:10 JST） |
| 主な読み取り先 | cutoff前の`rating_history`、未削除`profiles`、Androidは本人の`rating_daily_snapshot` |
| 主な書き込み先 | 最新分だけの`rating_daily_snapshot`、UUIDごとのdevice-local best percentile / achieved date |
| Secret / credential | Androidはpublishable（anon）key + user JWTで本人rowだけをSELECT。refreshは将来のDB owner Cronまたは`service_role`のEXECUTE権限。Androidへservice-roleを渡さない |
| 失敗時の確認場所 | 導入前監査はSupabase migration catalog。導入後はSupabase Cron History / `cron.job_run_details`、Postgres logs、snapshot整合query。AndroidはAccountScreenの未生成表示 / Logcat |
| 関連migration | **正式:** `202608220029_daily_rating_snapshot.sql`。**028は永久欠番・本番未適用・適用禁止** |
| 関連RPC | `refresh_rating_daily_snapshot(date default null)` |
| 関連workflow / Worker | **予定:** Supabase Cron job `daily-rating-snapshot`、command `select public.refresh_rating_daily_snapshot();`。Cloudflare / GitHub Actionsでは動かさない |
| 関連ドキュメント | [Daily rating snapshot rollout](DAILY_RATING_SNAPSHOT_ROLLOUT.md)、[Architecture](../ARCHITECTURE.md#daily-rating-position) |
| 本番状態 | **未導入:** 029は本番未適用、`pg_cron`未導入、`cron.job`なし、Supabase Cron 0件 |
| 注意事項 | 028を復活・適用しない。029適用とextension / job作成は別の本番操作。導入前にrollout文書の停止条件を再確認する |

029の完成仕様は次のとおりです。

- cutoffはAsia/Tokyoのsnapshot date翌日00:00。`rating_history`からcutoffより前の最新ratingを復元します。
- active userは未削除で、`[cutoff - 30 days, cutoff)`に確定レート更新が1件以上あるユーザーです。`profiles.last_active_at`は使いません。
- rating降順、同率は`RANK()`、`top_percentile = rank / active_user_count * 100`です。
- databaseはlatest snapshotだけを保持します。同一snapshot dateのretryは既存結果を変えず、古いdateへの巻き戻しを拒否します。
- Androidはsnapshot dateがAsia/Tokyo基準の本当の前日と一致する場合だけ「前日順位」として表示します。古い・当日・取得失敗のrowは表示にもlocal best更新にも使いません。
- 「この端末で確認した最高の前日順位」は、ユーザーがAccountScreenを開いて有効な前日順位を確認できた時だけUUID単位で更新する参考値です。サーバー実績・履歴・端末間同期ではありません。

## 8. LP / Webとclient-facing HTTP boundary

| 項目 | 内容 |
|---|---|
| 機能名 | CHANRIVA LP / Web account UI / client-facing API |
| 目的 | LP、Privacy Policy、Auth完了画面、Web削除、password recovery、app configurationを同一のpublic HTTP boundaryで提供する |
| 主な実行場所 | Cloudflare Worker `chanriva` + static Assets（Vinext build）。Cloudflare Pagesではない |
| 実行契機 | public HTTP request。GitHub `main`連携のCloudflare Workers Buildsでbuild / deploy |
| 実行頻度 | requestごと。deployはmain pushで起動し得る |
| 主な読み取り先 | static assets、非Secret Worker vars、必要なaccount APIだけSupabase Auth / REST RPC |
| 主な書き込み先 | password reset時のSupabase Auth、account deletion request。LP / privacy / app-configはread-only |
| Secret / credential | Worker secret `SUPABASE_ANON_KEY`。`SUPABASE_URL` / `ANDROID_MIN_VERSION_CODE`は非Secret vars。service-role / `ADMIN_TOKEN`は禁止 |
| 失敗時の確認場所 | Cloudflare Workers Buildsのbuild / deployment logs、`chanriva` Worker logs、下流がAuthならSupabase Auth logs、RPCならPostgres / API logs |
| 関連migration | Web削除はaccount deletion系migration。LP / reset / app-configはなし |
| 関連RPC | `request_account_deletion()`のみaccount deletion APIから使用 |
| 関連workflow / Worker | `landing-page/worker/index.ts`、`landing-page/wrangler.toml`、Worker `chanriva` |
| 関連ドキュメント | [landing-page README](../landing-page/README.md)、[Architecture](../ARCHITECTURE.md#application-http-boundary-and-startup-gates)、[Edax provenance](../third_party/edax/UPSTREAM.md) |
| 本番状態 | custom domain `chanriva.shinp-studio.com`で稼働中 |
| 注意事項 | public Workerとtrusted admin Workerを混同しない。main pushは`landing-page`差分がなくてもCloudflareの監視path設定によりWorkers Buildを起動し得る |

主要routeは次のとおりです。

| Route | 役割 | Supabase接点 |
|---|---|---|
| `/` | CHANRIVA LP | なし |
| `/privacy` | 公開Privacy Policy | なし |
| `/signup-complete` | Email確認後の案内 | Supabase Authのredirect着地点 |
| `/reset-password` | recovery linkから新passwordを入力 | `/api/password-reset/complete`経由でSupabase Auth更新 |
| `/account-deletion` | アプリ外の削除受付 | Email / Password認証後に`request_account_deletion()` |
| `/account-deletion/confirm` | 削除確認メールの着地点 | access tokenで本人確認後に同じRPC |
| `/edax` | Edaxと評価データの公開案内 | なし |
| `/api/app-config` | Android minimum versionのread-only JSON | なし |

独立した`/provenance` runtime routeは現行実装にありません。Edax source provenanceの正本は[`third_party/edax/UPSTREAM.md`](../third_party/edax/UPSTREAM.md)で、一般ユーザー向けの公開案内は`/edax`です。存在しないrouteを運用対象として扱わないでください。

## 9. GitHub Actions全体

`.github/workflows/`に存在するworkflowは次の2件です。GitHub Actions内にdeploy workflowはありません。LP本番deployはGitHub ActionsではなくCloudflare Workers Buildsのrepository連携です。

| Workflow | 分類 | Trigger / schedule | Runner / timeout | Credential | 主な処理 | 状態・確認場所 |
|---|---|---|---|---|---|---|
| `CI` (`ci.yml`) | CI / build / test | `push`, `pull_request` | `ubuntu-latest` / job timeout明示なし | 自動`GITHUB_TOKEN`（checkout用途）。production credentialなし | admin Worker typecheck/test、Android test/lint/debug・release・AAB、境界・SQL security・release内容、local Supabase pgTAP | 稼働中。GitHub Actions run / step logs。deployなし |
| `Research batch` (`research-batch.yml`) | production定期処理 / 手動運用 | `17,47 * * * *`、`workflow_dispatch` | `ubuntu-latest` / 30分 | GitHub Environment `research-production`の`RESEARCH_BATCH_DATABASE_URL` | bounded validator（最大200）とaggregation（最大500） | 稼働設定あり。concurrency `research-production-batch`、cancelなし。GitHub Actions run / step logs |

`Research batch` jobは`shinp-dev/othello`の`main`だけで実行します。manual dispatchもproduction DBへ作用し得るため、障害回復手順を確認してOWNER承認後に行います。

## 10. 定期処理一覧

| 処理 | 実行基盤 | schedule | 目的 | 状態 | 監視場所 |
|---|---|---|---|---|---|
| 着手傾向集計（Research） | GitHub Actions `Research batch` | `17,47 * * * *`（UTC、毎時17分・47分） | validationとposition / move aggregationをbounded batchで進める | 稼働中（直近schedule run成功） | GitHub Actions run / step logs、Research DB確認query |
| アカウント削除・休眠整理 | Cloudflare Cron Trigger / Worker `othello-admin` | `*/10 * * * *`（10分ごと） | 期限対象をqueueし、pending requestをprepare / Research unlink / Auth delete / completeする | 稼働設定あり。実行履歴は継続確認対象 | Cloudflare Worker logs / Cron trigger履歴、Supabase request状態 |
| 前日順位 | **予定:** Supabase Cron / pg_cron | `10 15 * * *`（15:10 UTC = 00:10 JST） | `select public.refresh_rating_daily_snapshot();`を1日1回実行 | **未導入**（029未適用、extension / jobなし） | 導入後のSupabase Cron History / `cron.job_run_details` / Postgres logs |

Cloudflare側で定期実行するコードは`othello-admin`のaccount deletion maintenanceだけです。public Worker `chanriva`にはscheduled handler / Cron Triggerがありません。GitHub Actionsのscheduleは`Research batch`だけです。

### Supabase内の定期処理

本番監査時点では`cron.job`は存在せず、`pg_cron` extensionは未導入、Supabase DashboardのCron jobは0件です。したがって、前日順位も、READMEに候補として記載されたmatch lifecycle cleanup関数群も現在はSupabase Cronで定期実行されていません。029用Cronを本番導入した時点で、この節、全体サマリ、前日順位、定期処理一覧を同じ変更で更新してください。

## 11. Secret / credential boundary

Secretの実値はrepository、issue、運用ログ、この文書へ記録しません。

| Secret / credential | 利用主体・用途 | 保管場所 | clientへ渡してよいか |
|---|---|---|---|
| Android Supabase publishable / anon key | AndroidからAuth / RLS / RPC / Realtimeへ接続 | 開発はuntracked `local.properties`または環境変数、build時にBuildConfig | **可**。公開client keyとしてRLS前提。service-roleと混同しない |
| User Auth session JWT / refresh token | 本人としてSupabaseへアクセス | Supabase Android SDKのlocal session、Web recoveryでは一時link context | 本人clientだけ。ログ・文書・別ユーザーへ渡さない |
| `SUPABASE_ANON_KEY` | public WorkerからSupabase Auth / RESTへアクセス | Cloudflare Worker `chanriva` secret | public key相当だが現在の保管境界はWorker。service-roleへ置換しない |
| `SUPABASE_SERVICE_ROLE_KEY` | `othello-admin`がtrusted削除RPCとAuth Admin APIを実行 | Cloudflare Worker `othello-admin` secret | **不可**。Android、browser、public Worker、GitHub batchへ渡さない |
| `ADMIN_TOKEN` | `othello-admin`の手動管理endpointをBearer認証 | Cloudflare Worker `othello-admin` secret | **不可**。operatorだけが必要時に使用し、ログへ出さない |
| `RESEARCH_BATCH_DATABASE_URL` | DB role `research_batch`でwrapper functionだけを実行 | GitHub Environment `research-production` secret | **不可**。Android / Cloudflareへ渡さない |
| Supabase DB owner credential | migration、DB owner限定操作、将来のCron管理 | Supabase / operatorのcredential store。repositoryや通常workflowへ保存しない | **不可** |
| Cloudflare Workers Builds連携credential | GitHub mainからWorkerをbuild / deploy | Cloudflare / GitHubの連携設定 | **不可**。アプリやrepositoryへ埋め込まない |
| 自動`GITHUB_TOKEN` | workflow checkout等 | GitHub Actionsがrun単位で発行 | clientへ渡さない。workflow permissionsを最小化する |

`SUPABASE_URL`、`ANDROID_MIN_VERSION_CODE`、`NODE_EXTRA_CA_CERTS`で指定するrepository内CA certificate pathはSecretではありません。Secretでない値も、権限境界を広げる代替credentialとして扱わないでください。

## 12. 障害確認先

| 対象 | 最初に見る場所 | 次の確認 |
|---|---|---|
| Android client | ユーザー向けerror state、Logcat、match diagnostics | 対象repository / RPC / network layerの入力と時系列 |
| Supabase Auth | Dashboard Auth logs | Redirect URL、Email provider、user / session状態。passwordやtokenは出力しない |
| Supabase Data API / DB | Dashboard API / Postgres logs、SQL catalog | RLS / function ACL、migration履歴、対象rowの件数・状態をread-onlyで確認 |
| Supabase Cron | 現在は未導入 | 導入後はCron History、`cron.job`、`cron.job_run_details`、Postgres logs |
| public Cloudflare Worker `chanriva` | Worker logs | Workers Buildsのbuild / deployment logs、下流Supabase Auth / API logs |
| trusted Worker `othello-admin` | Worker logsとCron Trigger履歴 | `account_deletion_requests`、対象RPC、Supabase Auth Admin側の結果 |
| GitHub Actions CI | `CI` workflow run / failed step | Android test report、admin Worker test、pgTAP output、境界check output |
| 着手傾向集計（Research） | `Research batch` workflow run / step logs | [Research operations](RESEARCH_OPERATIONS.md#monitoring)のDB状態・backlog・aggregate確認 |
| LP deploy | Cloudflare Workers Buildsのdeployment / build logs | active deployment、custom domain、`chanriva` Worker logs |

障害調査でproduction dataのwrite、手動batch、再deployが必要になった場合は、read-only調査と切り分けてOWNER承認を得ます。test user / dummy matchを本番に作らないでください。

## 13. 詳細正本と履歴資料

| 文書 | 役割 |
|---|---|
| [ARCHITECTURE.md](../ARCHITECTURE.md) | module / security / server responsibilityなど設計境界の正本 |
| [DAILY_RATING_SNAPSHOT_ROLLOUT.md](DAILY_RATING_SNAPSHOT_ROLLOUT.md) | 029の本番適用前監査、停止条件、scheduler比較、planned Cron |
| [RESEARCH_OPERATIONS.md](RESEARCH_OPERATIONS.md) | 着手傾向集計（Research）のproduction credential、monitoring、recovery、rollback |
| [RESEARCH_DATA_DESIGN.md](RESEARCH_DATA_DESIGN.md) | Researchのprivacy / consent / aggregation設計 |
| [SUPABASE_HOSTED_SETUP.md](SUPABASE_HOSTED_SETUP.md) | hosted Supabase再構築、Auth、trusted Worker設定 |
| [landing-page/README.md](../landing-page/README.md) | public routes、Redirect URL、Worker build / deploy設定 |
| [PRODUCTION_CUTOVER_202608150025.md](PRODUCTION_CUTOVER_202608150025.md) | 2026-08-15 cutover当時の履歴。現在状態の正本ではない |
| [third_party/edax/UPSTREAM.md](../third_party/edax/UPSTREAM.md) | Edax source provenanceとAndroid向け変更 |

日付付きcutover / audit文書は当時の判断と結果を保存する履歴資料です。現在状態と異なる記述を見つけても履歴を上書きせず、本ファイルまたは現行の個別運用正本を更新してください。

## 14. 更新ルール

- 新しいCron / scheduleを追加、または既存scheduleを削除・変更したら、本番変更と同じpull request / commitでこの文書を更新します。
- 処理をSupabase、Cloudflare、GitHub Actions、Android間で移動する場合、設計変更前にこの文書を読み、変更後に実行場所、credential、監視場所、本番状態を更新します。
- 新しいWorker / GitHub Actions workflowを追加・廃止したら、全体サマリ、該当機能、定期処理一覧を更新します。
- Secret境界、保管場所、利用主体を変えたら、実値を記載せずSecret / credential boundaryを更新します。
- 本番導入前の処理は`未導入`または`予定`と明記し、repositoryにコードがあるだけで`稼働中`としません。本番導入後は確認根拠とともに状態を更新します。
- migrationの本番状態が運用に影響する機能は、正式番号と適用済み / 未適用を明記します。欠番・禁止migrationは事故防止に必要な間、`廃止 / 使用禁止`として残します。
- 廃止した運用は原則この文書から削除します。ただし再実行事故の可能性があるものは、028のように`廃止 / 使用禁止`を明記します。
- 障害時の入口やDashboard上の名称が変わった場合も更新対象です。
- 人間とAIエージェントは、運用変更・production操作・責務移動の前にこの文書を読みます。
