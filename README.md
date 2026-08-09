# Othello Online MVP

責務分離を優先したAndroid向けオンラインオセロの基盤です。現在の実行可能なMVPは、Supabase設定なしで動く端末内2人対局です。ホームの「対局する」から8x8盤を開き、合法手をタップして遊べます。

## 開発環境

- Android Studio Koala以降
- JDK 17
- Android SDK 36
- Kotlin 2.2.10 / Compose Compiler plugin / Compose 1.6.8

## ビルドとテスト

```powershell
./gradlew test
./gradlew :app:assembleDebug
pwsh ./scripts/check-boundaries.ps1
pwsh ./scripts/check-sql-security.ps1
supabase start
supabase test db
supabase stop

# Emulator A/B smoke/E2E (credentials are environment variables, never committed)
./scripts/run-emulator-e2e.ps1 -StartSupabase -AutoPlay
```

Android Studioでルートを開いて同期し、`app` configurationを実行することもできます。リポジトリにはGradle Wrapperを含めています。

## 構成

詳細は [ARCHITECTURE.md](ARCHITECTURE.md) と [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) を参照してください。`core:game`はAndroid/Supabase/WebRTC/Rating/Edaxを参照しない純粋Kotlinです。`feature:match`は`analysis`へ依存せず、Reviewだけが`analysis:api`を参照します。

WebRTC SDKとSupabase SDKの具体実装は、それぞれ`transport:webrtc`と`data:supabase`へ隔離します。`matches.server_status`はサーバーが保証できる状態だけを保持し、AndroidのP2P session stateとは別物です。
WebRTC Android SDKはMaven Centralの`io.github.webrtc-sdk:android:144.7559.09`に固定しています。
`SupabaseModule`が`data:supabase`内でSDK clientとrepositoryを組み立て、appへは自前port interfaceだけを返します。

## Supabase / Android configuration

The Android client reads `supabase.url` and `supabase.anonKey` from the untracked
`local.properties` file, or `SUPABASE_URL` / `SUPABASE_ANON_KEY` from the environment.
Missing values produce a visible configuration error and do not crash the app. Never
place a service-role key in Android resources or BuildConfig.

1. Supabase projectを作成します。
2. `supabase/migrations/202608090001_init.sql`、続けて`202608090002_hardening_additive.sql`をSupabase SQL EditorまたはSupabase CLIで適用します。
3. Android側へservice-role keyを置かず、AuthユーザーのJWTと公開anon keyだけをアプリ設定へ渡します。
4. 新規AuthユーザーはDB triggerで`profiles`/`ratings`へbootstrapされます。マッチングは`enqueue_or_match()`だけを使用し、公式Rating snapshotとTTLをDB側で管理します。
5. 結果提出は`submit_match_result(...)`を使用します。2件目の提出時に同一transaction内で自動finalizeされ、`finalize_match_v2(...)`はreconciliation用に残します。参加者以外・二重Rating更新・不一致結果はDB側で拒否または`DISPUTED`になります。
6. `public_profiles`は公開実績専用projectionです。rating history、証明画像、本名、evidence pathは含みません。

## Cloudflare Admin

`cloudflare-admin`は段級位申請の管理BFFです。service-role keyはWorker secretにだけ置き、`ADMIN_TOKEN`もWorker secretに置きます。Storage bucketは`verification`、object名は`<auth.uid()>/<filename>`とし、提出RPCがStorage ownerとprefixを検証します。承認・却下は`review_verification_submission` RPCで原子的に更新し、WorkerはDBが発行する`get_verification_evidence_cleanup`のpathだけを削除します。削除失敗時は同じ管理操作で再試行でき、成功後はpathをnull化します。ブラウザへservice-role keyを配布しません。

```powershell
cd cloudflare-admin
npm install
npx wrangler secret put SUPABASE_SERVICE_ROLE_KEY
npx wrangler secret put ADMIN_TOKEN
npm run deploy
```

## オンライン対局の実装順

MVP後は `MatchTransport` にWebRTC DataChannel実装を追加し、Supabase RealtimeはSDP offer/answerの確立時だけ使用します。P2P接続後に着手や時計をSupabaseへ送信しません。Android実機2台での確認は、Auth設定、同一Supabase project、TURN/STUN設定、2台のqueue参加、DataChannel成立、双方の同一棋譜・hash確認、結果提出の順で行います。

`applicationId = com.example.othello` は開発用の仮値です。Store公開前に正式な所有ドメイン由来のIDを決定し、公開後に変更しない運用へ移行します。

Emulator A/Bの再現手順と、emulatorで完了できる項目・物理端末に残る項目は
[`docs/DEVICE_TEST.md`](docs/DEVICE_TEST.md)を参照してください。`build/e2e/`には
secretを含めないXML、スクリーンショット、対象tagのlogcatを保存できます。

`matches`のCREATED leaseは5分のsignaling用です。DataChannel成立後、両participantが`ack_match_started`を一度呼ぶと、P2P開始事実と24時間のbounded play leaseを記録します。PENDING_RESULTには30日の結果待ち期限を設定し、期限切れはABANDONEDへ遷移してからreservationを解放します。CONFIRMED/DISPUTED/ABANDONEDのterminal match、game record、30日経過のsubmissionはmaintenance pathでretention条件を満たしたものから削除します。matchmaking hot pathではqueue/signaling leaseだけを処理します。Supabase Cron/pg_cronを使う場合は、service role相当で次を1時間ごとに実行します。

```sql
select public.cleanup_stale_created_matches();
select public.cleanup_expired_pending_results();
select public.cleanup_expired_started_matches();
select public.cleanup_terminal_matches();
```

## Edax / OSS

Edax JNIはまだバイナリを同梱していません。`analysis:api`の`AnalysisEngine`と`analysis:edax`の`ProductionAnalysisEngine`が差し替え境界です。`HeuristicTestAnalysisEngine`はtest/debug専用で、本番の偽評価には使用しません。Edaxを追加する際は、Edaxの配布ライセンスとJNI/NDKビルド成果物の著作権表示をアプリのOSS画面へ追加してください。現時点の依存ライセンスはGradleのCompose/AndroidX/Kotlin標準ライセンスに従います。
