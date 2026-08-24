# ちゃんりば Supabase検証環境構築

この手順は、オンライン疎通確認用のSupabaseプロジェクトを同じ設定で作り直すためのメモです。永続運用や本番課金を前提にしません。

## 課金ガード

- Organizationとプロジェクトは必ずFree planで作成する。
- クレジットカード、請求先、Paid add-onは登録しない。
- カード登録や有料プランが必須と表示された場合は、その場で中止する。

## プロジェクト作成値

| 項目 | 値 |
| --- | --- |
| プロジェクト name | `othello`（再作成時は任意の識別可能な名前でもよい） |
| Region | Northeast Asia (Tokyo) / `ap-northeast-1` |
| Plan / Compute | Free / nano |
| データベース パスワード | `Generate a password`で生成し、リポジトリへ保存しない |
| Enable データ API | ON |
| Automatically expose new tables | OFF |
| Enable automatic RLS | ON |

データ APIのテーブル権限は `202608090014_hosted_data_api_grants.sql` で明示するため、Dashboardの自動公開には依存しません。

## データベース適用

`supabase/migrations` のSQLをファイル名順にすべて適用します。CLIを使える環境では次を推奨します。

```powershell
supabase login
supabase link --project-ref <project-ref>
supabase db push
```

現在のリポジトリ終端は`202608250030_release_match_hardening.sql`です。028は意図的な永久欠番です。マイグレーション 001〜027、029、030をfilename順に適用します。018〜023はResearchの同意 / 収集 / 集計 / 切り離しと最小権限のActions バッチ境界を追加します。024は当時存在した検証管理境界を強化し、025は非公開な対局開始時レートスナップショットを追加し、026は廃止した公開プロフィールと検証 surfaceを削除します。027は`profiles.last_active_at`、認証済みユーザーが1日1回だけ更新するtouch RPC、未確認7日・休眠365日アカウント用のservice限定キュー RPCを追加します。029は日次レートスナップショット、030はオンライン対局プロトコル v2のバージョン付き契約を追加します。Researchの`collection_enabled`はマイグレーション デフォルトで`false`のままです。有効化は[研究データ運用](../03_研究データ/研究データ運用.md)に従う別のoperator操作です。

019 / 020を意図して導入した後、信頼済み実行基盤は1つの変更不能な集計 世代を構築できます。現在のバッチ実行基盤はGitHub Actions `Research batch`です。過去の`POST /admin/research/aggregate`経路を現行の実行手順として使いません。

DashboardのSQL Editorを使う場合も、`202608090001_init.sql`から`202608250030_release_match_hardening.sql`までを、028を除いて順番に適用します。途中失敗時の部分適用を避けるため、まとめて実行する場合は`begin;` / `commit;`で囲みます。027は未確認登録7日・確認済み休眠365日の削除キューと、認証済みユーザーの1日1回の最終利用時刻更新を追加します。

適用後、`scripts/verify-hosted-supabase.sql` をSQL Editorで実行します。`realtime_tables` は `2`、ほかはすべて `true` であることを確認します。これにより次を同時に確認できます。

- 対局ライフサイクル / 結果 / 開始 ACK RPC
- Realtime対象の `match_notifications` と `match_signaling`
- 初回公開版では公開プロフィール、表示名、連盟段級位、証明画像Storageを作らない最終マイグレーション
- Androidクライアントに必要な最小データ API権限
- アカウント削除のサービスロール（service_role）専用準備/完了RPCと匿名プロフィール tombstone
- research 非公開 スキーマ、同意/参加 RPC、compact ソース、service-only 検証器 RPC
- 有効なResearchポリシーの`collection_enabled = false`（019適用だけでは収集開始しない）

## Auth

- Authentication > Providersでメール providerを有効のままにする。
- アプリの「アカウント作成」からメール/パスワードユーザーを作成できる。本番-shaped確認ではConfirm メールを有効にし、確認後にログインする。
- 疎通用ユーザーA/Bを先に用意する場合は、Authentication > Usersから作成し、Dashboard上でそのテストユーザーだけを確認済みにしてもよい。これは本番のConfirm メール設定を無効化する指示ではない。
- 確認メールを使う場合、Site URL / Redirect URLには公開済みの`https://chanriva.shinp-studio.com/signup-complete`を使用し、`localhost`へ戻さない。
- パスワード再設定を使う場合、Redirect URLに`https://chanriva.shinp-studio.com/reset-password`も登録し、再設定メールから同ページへ遷移させる。Supabase Dashboard側の登録は所有者 操作 必須。
- パスワードを使えないWeb削除受付を使う場合、Redirect URLに`https://chanriva.shinp-studio.com/account-deletion/confirm`も登録する。Supabase Dashboard側の登録は所有者 操作 必須。
- A/Bのメールアドレスとパスワードはリポジトリへ保存しない。

## Androidの接続値

プロジェクトのConnectまたはSettings > API Keysからプロジェクト URLとPublishable キー（またはlegacy anon キー）を取得し、gitignore済みのルート `local.properties` にだけ設定します。シークレット キー / サービスロール（service_role） キーはAndroidへ渡しません。

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

1. `public_profiles`、`profiles.display_name`、連盟認証情報／検証 テーブルが存在せず、旧`verification` bucketもStorage APIで削除済みである。
2. Authユーザー作成時に `profiles` と `ratings` が各1行ずつ自動作成される。
3. A/Bが同一プロジェクトへログインできる。
4. キュー参加、Realtime signaling、DataChannel成立、両者開始 ACKまで到達する。
5. 同一棋譜の結果提出で `CONFIRMED`、GameRecord 1件、レート更新1回になる。
6. マイグレーション直後は研究参加をON/OFFでき、研究収集は準備中（`collection_enabled = false`）と表示される。収集E2Eを行う場合だけ、[研究データ運用](../03_研究データ/研究データ運用.md)の手順で明示的に有効化する。

## 信頼済み管理ワーカー

`cloudflare-admin/wrangler.toml`の`SUPABASE_URL`だけを対象プロジェクトへ変更し、`SUPABASE_SERVICE_ROLE_KEY`と`ADMIN_TOKEN`は`wrangler secret put`で登録します。Gitへ値を保存しません。ワーカー Cronは10分ごとに、未確認7日・休眠365日のアカウントを削除要求へキューし、その後に既存のDB 非公開データ処理、Research 切り離し、Auth 管理者削除、完了記録を行います。アプリの起動またはログイン成功時は`last_active_at`を最大1日1回更新します。Research 検証器 / 集計はGitHub Actionsの`Research batch` ワークフローが専用`research_batch` DB ロールで実行します。Actionsへ`service_role`やDB 所有者 パスワードを渡してはいけません。`collection_enabled=false`ではバッチ 取得もno-opです。Cloudflare側で課金やカード登録を要求された場合はデプロイしません。

この環境を破棄しても、DB定義の正本は `supabase/migrations`、画面設定の正本はこの文書です。
