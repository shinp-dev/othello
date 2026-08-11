# ちゃんりば Hosted Supabase setup (disposable E2E project)

この手順は、オンライン疎通確認用のSupabaseプロジェクトを同じ設定で作り直すためのメモです。永続運用や本番課金を前提にしません。

## 課金ガード

- OrganizationとProjectは必ずFree planで作成する。
- クレジットカード、請求先、Paid add-onは登録しない。
- カード登録や有料プランが必須と表示された場合は、その場で中止する。

## Project作成値

| 項目 | 値 |
| --- | --- |
| Project name | `othello`（再作成時は任意の識別可能な名前でもよい） |
| Region | Northeast Asia (Tokyo) / `ap-northeast-1` |
| Plan / Compute | Free / nano |
| Database password | `Generate a password`で生成し、リポジトリへ保存しない |
| Enable Data API | ON |
| Automatically expose new tables | OFF |
| Enable automatic RLS | ON |

Data APIのテーブル権限は `202608090014_hosted_data_api_grants.sql` で明示するため、Dashboardの自動公開には依存しません。

## Database適用

`supabase/migrations` のSQLをファイル名順にすべて適用します。CLIを使える環境では次を推奨します。

```powershell
supabase login
supabase link --project-ref <project-ref>
supabase db push
```

Current repository endpoint: `202608110020_research_aggregation_privacy.sql`.
Apply migrations `001` through `020` in filename order. Migration 020 adds only the
private aggregation/generation pipeline and privacy-safe RPC boundary; it does not
enable collection. The active policy must remain `collection_enabled = false` until
the separate operations/launch phase.

After 019/020 are intentionally deployed, the trusted Worker can build one immutable
aggregate generation through authenticated `POST /admin/research/aggregate`. This is
an operator action in this stage; no production aggregation schedule is configured.

DashboardのSQL Editorを使う場合も、`202608090001_init.sql` から`202608110019_research_capture_validator.sql`までを順番に適用します。途中失敗時の部分適用を避けるため、まとめて実行する場合は `begin;` / `commit;` で囲みます。

適用後、`scripts/verify-hosted-supabase.sql` をSQL Editorで実行します。`realtime_tables` は `2`、ほかはすべて `true` であることを確認します。これにより次を同時に確認できます。

- Match lifecycle / result / start ACK RPC
- Realtime対象の `match_notifications` と `match_signaling`
- privateな `verification` bucket、5 MiB上限、JPEG/PNG/WebP制限
- Androidクライアントに必要な最小Data API権限
- account deletionのservice-role-only準備/完了RPCと匿名profile tombstone
- research private schema、Consent/participation RPC、compact source、service-only validator RPC
- active research policyの`collection_enabled = false`（019適用だけでは収集開始しない）

## Auth

- Authentication > ProvidersでEmail providerを有効のままにする。
- アプリの「アカウント作成」からEmail/Passwordユーザーを作成できる。確認メールを有効にする場合は、確認後にログインする。
- 疎通用ユーザーA/Bを先に用意する場合は、Authentication > Usersから作成してもよい。
- Dashboard作成時にAuto Confirm Userを有効にし、メール送信に依存させない。
- A/Bのメールアドレスとパスワードはリポジトリへ保存しない。

## Androidの接続値

ProjectのConnectまたはSettings > API KeysからProject URLとPublishable key（またはlegacy anon key）を取得し、gitignore済みのルート `local.properties` にだけ設定します。Secret key / service-role keyはAndroidへ渡しません。

```properties
supabase.url=https://<project-ref>.supabase.co
supabase.anonKey=<publishable-or-anon-key>
```

Emulator A/Bの自動疎通では、認証情報をプロセス環境変数で渡します。

```powershell
$env:OTHELLO_E2E_PLAYER_A_EMAIL='<player-a-email>'
$env:OTHELLO_E2E_PLAYER_A_PASSWORD='<player-a-password>'
$env:OTHELLO_E2E_PLAYER_B_EMAIL='<player-b-email>'
$env:OTHELLO_E2E_PLAYER_B_PASSWORD='<player-b-password>'
./scripts/run-emulator-e2e.ps1 -AutoPlay
```

## 作成後の確認

1. `verification` bucketがprivateである。
2. Authユーザー作成時に `profiles` と `ratings` が各1行ずつ自動作成される。
3. A/Bが同一Projectへログインできる。
4. Queue参加、Realtime signaling、DataChannel成立、両者start ACKまで到達する。
5. 同一棋譜の結果提出で `CONFIRMED`、GameRecord 1件、Rating更新1回になる。
6. 研究参加をON/OFFでき、研究収集は準備中（`collection_enabled = false`）と表示される。

## 信頼済み管理Worker

`cloudflare-admin/wrangler.toml`の`SUPABASE_URL`だけを対象Projectへ変更し、`SUPABASE_SERVICE_ROLE_KEY`と`ADMIN_TOKEN`は`wrangler secret put`で登録します。Gitへ値を保存しません。`SUPABASE_VERIFICATION_BUCKET`はmigrationが作る`verification`です。Worker Cronは10分ごとに削除要求を再実行し、Storage APIで証明画像を消してからDB匿名化とAuth Admin削除を行います。同じCronは、collection開始後に作成された研究gameを最大10件ずつ5分leaseでclaimし、独立したReversi validatorでACCEPTED/REJECTEDへ遷移させます。`collection_enabled=false`ではclaim対象自体が作られません。Cloudflare側で課金やカード登録を要求された場合はdeployせず、同じWorkerを別の信頼済み無料実行基盤へ配置します。

この環境を破棄しても、DB定義の正本は `supabase/migrations`、画面設定の正本はこの文書です。
