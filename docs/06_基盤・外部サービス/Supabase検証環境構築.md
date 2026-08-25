# ちゃんりば Supabase検証環境構築

この手順は、オンライン疎通確認用のSupabaseプロジェクトを同じ設定で作り直すためのメモです。永続運用や本番課金を前提にしません。

## 課金ガード

- Organizationとプロジェクトは必ずFreeプランで作成する。
- クレジットカード、請求先、有料アドオンは登録しない。
- カード登録や有料プランが必須と表示された場合は、その場で中止する。

## プロジェクト作成値

| 項目 | 値 |
| --- | --- |
| プロジェクト名 | `othello`（再作成時は任意の識別可能な名前でもよい） |
| リージョン | Northeast Asia (Tokyo) / `ap-northeast-1` |
| プラン／コンピュート | Free / nano |
| データベース パスワード | `Generate a password`で生成し、リポジトリへ保存しない |
| データAPIを有効化 | ON |
| 新規テーブルの自動公開 | OFF |
| 自動RLSを有効化 | ON |

データ APIのテーブル権限は `202608090014_hosted_data_api_grants.sql` で明示するため、Dashboardの自動公開には依存しません。

## データベース適用

`supabase/migrations` のSQLをファイル名順にすべて適用します。CLIを使える環境では次を推奨します。

```powershell
supabase login
supabase link --project-ref <project-ref>
supabase db push
```

現在のリポジトリ終端は`202608250030_release_match_hardening.sql`です。028は意図的な永久欠番です。マイグレーション001〜027、029、030をファイル名順に適用します。018〜023はResearchの同意／収集／集計／切り離しと、最小権限のActionsバッチ境界を追加します。024は当時存在した検証管理境界を強化し、025は非公開の対局開始時レートスナップショットを追加し、026は廃止した公開プロフィールと検証面を削除します。027は`profiles.last_active_at`、認証済みユーザーが1日1回だけ更新するtouch RPC、未確認7日・休眠365日アカウント用のサービス限定キューRPCを追加します。029は日次レートスナップショット、030はオンライン対局Protocol 2のバージョン付き契約を追加します。Researchの`collection_enabled`はマイグレーションの初期値で`false`のままです。有効化は[研究データ運用](../03_研究データ/研究データ運用.md)に従う別の運用者操作です。

019 / 020を意図して導入した後、信頼済み実行基盤は1つの変更不能な集計世代を構築できます。現在のバッチ実行基盤はGitHub Actionsの`Research batch`です。過去の`POST /admin/research/aggregate`経路を現行の実行手順として使いません。

DashboardのSQL Editorを使う場合も、`202608090001_init.sql`から`202608250030_release_match_hardening.sql`までを、028を除いて順番に適用します。途中失敗時の部分適用を避けるため、まとめて実行する場合は`begin;` / `commit;`で囲みます。027は未確認登録7日・確認済み休眠365日の削除キューと、認証済みユーザーの1日1回の最終利用時刻更新を追加します。

`scripts/verify-hosted-supabase.sql`は、030適用後のホスト型環境がオンライン対局Protocol 2の主要契約を備えているか確認する検証スクリプトです。全マイグレーション適用後にSQL Editorで実行し、出力された`hosted_contract`の全項目が`true`であることを確認します。`protocol1_compatibility`は移行期間中の旧オブジェクトを観測する参考情報であり、Protocol 2の成功条件ではありません。

この検証で確認する範囲は次のとおりです。

- `release_status`、`negotiation_epoch`、lease、再接続、結果管理に必要なカラムと制約
- `match_start_acks_v2`、`match_result_claims_v2`、`match_results_v2`、`match_signals_v2`
- Androidが使用するProtocol 2 RPCの正確なシグネチャと`authenticated` / `anon`の実行権限
- 管理用RPCと正式結果確定ヘルパーをクライアントから呼び出せない権限境界
- Protocol 2関連テーブルのRLS、直接書き込み禁止、参加者に限定した結果／signaling読み取り境界
- Realtime対象である`match_notifications`と`match_signals_v2`
- 初回公開版では公開プロフィール、表示名、連盟段級位、証明画像Storageを作らない最終マイグレーション
- Androidクライアントに必要な最小データ API権限
- アカウント削除のサービスロール（service_role）専用準備/完了RPCと匿名プロフィール tombstone
- Researchの非公開スキーマ、同意／参加RPC、圧縮ソース、サービス限定の検証器RPC
- 有効なResearchポリシーの`collection_enabled = false`（019適用だけでは収集開始しない）

このスクリプトは、ホスト型環境へのマイグレーション反映、オブジェクト存在、シグネチャ、権限、RLS、Realtime publicationを確認するものです。トランザクション、競合、認可動作、状態遷移、冪等性などの振る舞いは検証しないため、ローカルのpgTAPを置き換えません。完全な動作検証にはローカルSupabaseで`supabase test db`を実行し、[リリースハードニング設計](../07_リリース・移行/リリースハードニング設計.md#マイグレーションと協調切替え)に従って確認します。

## Auth

- Authentication > Providersでメール providerを有効のままにする。
- アプリの「アカウント作成」からメール/パスワードユーザーを作成できる。本番-shaped確認ではConfirm メールを有効にし、確認後にログインする。
- 疎通用ユーザーA/Bを先に用意する場合は、Authentication > Usersから作成し、Dashboard上でそのテストユーザーだけを確認済みにしてもよい。これは本番のConfirm メール設定を無効化する指示ではない。
- 確認メールを使う場合、Site URL / Redirect URLには公開済みの`https://chanriva.shinp-studio.com/signup-complete`を使用し、`localhost`へ戻さない。
- パスワード再設定を使う場合、Redirect URLに`https://chanriva.shinp-studio.com/reset-password`も登録し、再設定メールから同ページへ遷移させる。Supabase Dashboard側の登録は所有者 操作 必須。
- パスワードを使えないWeb削除受付を使う場合、Redirect URLに`https://chanriva.shinp-studio.com/account-deletion/confirm`も登録する。Supabase Dashboard側の登録は所有者 操作 必須。
- A/Bのメールアドレスとパスワードはリポジトリへ保存しない。

## Androidの接続値

プロジェクトのConnectまたはSettings > API KeysからプロジェクトURLとPublishableキー（または旧anonキー）を取得し、gitignore済みのルート`local.properties`にだけ設定します。シークレットキー／サービスロール（service_role）キーはAndroidへ渡しません。

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
4. キュー参加、Realtimeによる接続情報交換、DataChannel成立、双方の開始ACKまで到達する。
5. 同一棋譜の結果提出で `CONFIRMED`、GameRecord 1件、レート更新1回になる。
6. マイグレーション直後は研究参加をON/OFFでき、研究収集は準備中（`collection_enabled = false`）と表示される。収集E2Eを行う場合だけ、[研究データ運用](../03_研究データ/研究データ運用.md)の手順で明示的に有効化する。

## 信頼済み管理ワーカー

`cloudflare-admin/wrangler.toml`の`SUPABASE_URL`だけを対象プロジェクトへ変更し、`SUPABASE_SERVICE_ROLE_KEY`と`ADMIN_TOKEN`は`wrangler secret put`で登録します。Gitへ値を保存しません。ワーカーCronは10分ごとに、未確認7日・休眠365日のアカウントを削除要求へキューし、その後に既存のDB非公開データ処理、Researchの切り離し、Auth管理者削除、完了記録を行います。アプリの起動またはログイン成功時は`last_active_at`を最大1日1回更新します。Researchの検証器／集計はGitHub Actionsの`Research batch`ワークフローが専用の`research_batch` DBロールで実行します。Actionsへ`service_role`やDB所有者パスワードを渡してはいけません。`collection_enabled=false`ではバッチのclaimも処理なしで終了します。Cloudflare側で課金やカード登録を要求された場合はデプロイしません。

この環境を破棄しても、DB定義の正本は `supabase/migrations`、画面設定の正本はこの文書です。
