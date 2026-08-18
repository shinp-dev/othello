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

Current repository endpoint: `202608180027_account_lifecycle.sql`.
Apply migrations `001` through `027` in filename order. Migrations 018–023 add the
Research consent/capture/aggregation/unlink and least-privilege Actions batch boundary;
024 hardens the then-existing verification administration boundary; 025 adds private
match-start rating snapshots; and 026 removes the retired public-profile and verification
surface; 027 adds `profiles.last_active_at`, the once-per-day authenticated touch RPC,
and the service-only queue RPC for 7-day unconfirmed and 365-day dormant accounts. The
migration default keeps Research `collection_enabled = false`; enabling it is
a separate operator action described in `RESEARCH_OPERATIONS.md`.

After 019/020 are intentionally deployed, the trusted Worker can build one immutable
aggregate generation through authenticated `POST /admin/research/aggregate`. This is
an operator action in this stage; no production aggregation schedule is configured.

DashboardのSQL Editorを使う場合も、`202608090001_init.sql` から`202608180027_account_lifecycle.sql`までを順番に適用します。途中失敗時の部分適用を避けるため、まとめて実行する場合は `begin;` / `commit;` で囲みます。027は未確認登録7日・確認済み休眠365日の削除キューと、認証済みユーザーの1日1回の最終利用時刻更新を追加します。

適用後、`scripts/verify-hosted-supabase.sql` をSQL Editorで実行します。`realtime_tables` は `2`、ほかはすべて `true` であることを確認します。これにより次を同時に確認できます。

- Match lifecycle / result / start ACK RPC
- Realtime対象の `match_notifications` と `match_signaling`
- 初回公開版では公開プロフィール、表示名、連盟段級位、証明画像Storageを作らない最終migration
- Androidクライアントに必要な最小Data API権限
- account deletionのservice-role-only準備/完了RPCと匿名profile tombstone
- research private schema、Consent/participation RPC、compact source、service-only validator RPC
- active research policyの`collection_enabled = false`（019適用だけでは収集開始しない）

## Auth

- Authentication > ProvidersでEmail providerを有効のままにする。
- アプリの「アカウント作成」からEmail/Passwordユーザーを作成できる。production-shaped確認ではConfirm Emailを有効にし、確認後にログインする。
- 疎通用ユーザーA/Bを先に用意する場合は、Authentication > Usersから作成し、Dashboard上でそのテストユーザーだけを確認済みにしてもよい。これはproductionのConfirm Email設定を無効化する指示ではない。
- 確認メールを使う場合、Site URL / Redirect URLには公開済みの`https://chanriva.shinp-studio.com/signup-complete`を使用し、`localhost`へ戻さない。
- パスワード再設定を使う場合、Redirect URLに`https://chanriva.shinp-studio.com/reset-password`も登録し、再設定メールから同ページへ遷移させる。Supabase Dashboard側の登録はOWNER ACTION REQUIRED。
- パスワードを使えないWeb削除受付を使う場合、Redirect URLに`https://chanriva.shinp-studio.com/account-deletion/confirm`も登録する。Supabase Dashboard側の登録はOWNER ACTION REQUIRED。
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

1. `public_profiles`、`profiles.display_name`、連盟credential／verification tableが存在せず、旧`verification` bucketもStorage APIで削除済みである。
2. Authユーザー作成時に `profiles` と `ratings` が各1行ずつ自動作成される。
3. A/Bが同一Projectへログインできる。
4. Queue参加、Realtime signaling、DataChannel成立、両者start ACKまで到達する。
5. 同一棋譜の結果提出で `CONFIRMED`、GameRecord 1件、Rating更新1回になる。
6. migration直後は研究参加をON/OFFでき、研究収集は準備中（`collection_enabled = false`）と表示される。収集E2Eを行う場合だけ、`RESEARCH_OPERATIONS.md`の手順で明示的に有効化する。

## 信頼済み管理Worker

`cloudflare-admin/wrangler.toml`の`SUPABASE_URL`だけを対象Projectへ変更し、`SUPABASE_SERVICE_ROLE_KEY`と`ADMIN_TOKEN`は`wrangler secret put`で登録します。Gitへ値を保存しません。Worker Cronは10分ごとに、未確認7日・休眠365日のアカウントを削除要求へキューし、その後に既存のDB private data処理、Research unlink、Auth Admin削除、完了記録を行います。アプリの起動またはログイン成功時は`last_active_at`を最大1日1回更新します。Research validator / aggregationはGitHub Actionsの`Research batch` workflowが専用`research_batch` DB roleで実行します。Actionsへ`service_role`やDB owner passwordを渡してはいけません。`collection_enabled=false`ではbatch claimもno-opです。Cloudflare側で課金やカード登録を要求された場合はdeployしません。

この環境を破棄しても、DB定義の正本は `supabase/migrations`、画面設定の正本はこの文書です。
