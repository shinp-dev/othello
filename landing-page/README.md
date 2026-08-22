# ちゃんりば公式サイト

`landing-page/`は、ちゃんりば / CHANRIVAの公式LPとGoogle Play向け法務・アカウント導線の正本です。Cloudflare Worker `chanriva`として`https://chanriva.shinp-studio.com`へ配信します。

## 公開ルート

- `/`: 製品LP
- `/privacy`: プライバシーポリシー
- `/account-deletion`: アプリを利用できないユーザー向けのWebアカウント削除受付
- `/account-deletion/confirm`: Supabase Authの確認リンクからWeb削除リクエストを開始するページ
- `/signup-complete`: Supabase Authのメール確認完了案内
- `/reset-password`: Supabase Authの再設定リンクから新しいパスワードを設定するページ
- `/api/account-deletion/start`: 同一originの削除受付API。既存Supabase Email/Password Authで本人確認し、Androidと同じ`request_account_deletion()` RPCを呼ぶ
- `/api/account-deletion/email/start`: パスワードを使えないユーザーへSupabase Authの確認リンクを送るAPI。新規アカウントは作らない
- `/api/account-deletion/email/confirm`: 確認リンクのaccess tokenでAndroidと同じ`request_account_deletion()` RPCを呼ぶAPI
- `/api/password-reset/complete`: 再設定リンクのaccess tokenでSupabase Authのパスワードを更新する同一origin API
- `/api/app-config`: Android起動ゲート向けの公開read-only設定API。`android_min_version_code`を返し、Supabaseへ問い合わせない

Web削除APIはservice-role keyを持ちません。パスワードやaccess tokenを保存・ログ出力せず、実削除は`cloudflare-admin`の信頼済みWorkerが行います。`SUPABASE_ANON_KEY`はCloudflare runtime設定から渡し、tracked fileへ値を記録しません。

パスワード再設定はSupabase Auth標準のメールリカバリを使用します。旧パスワードは表示・保存・復元せず、再設定完了時だけ新しいパスワードをAuthへ送信します。Supabase DashboardのAuth URL Configurationには、`https://chanriva.shinp-studio.com/reset-password`と`https://chanriva.shinp-studio.com/account-deletion/confirm`をRedirect URLとして登録してください。後者はメールリンク削除受付用です。

アカウントライフサイクルは、未確認登録を7日、確認済みで最終利用から365日経過したアカウントを削除処理の対象にします。どちらも`cloudflare-admin`の既存削除パイプラインへキューし、Web Workerから直接Authや個人データを削除しません。

`/account-deletion/confirm`のメールリンク受付と、未確認登録・休眠アカウントの自動削除は、リポジトリ上の実装とmigrationが正本です。`202608180027_account_lifecycle.sql`は本番適用済みです。確認メール用Supabase Redirect URL登録、管理WorkerのCron実行確認、メールリンクE2Eが残っています。既存のメールアドレス＋パスワードによるWeb削除受付は別経路です。

## 開発・検証

必要環境はNode.js `>=22.13.0`です。

```powershell
npm.cmd ci
npm.cmd test
npm.cmd run lint
npm.cmd run build
```

`npm test`はproduction build後、LP、Privacy Policy、Web account deletion、signup completion、same-origin/fail-closed API境界を確認します。

## Cloudflare設定

- Worker name: `chanriva`
- production domain: `chanriva.shinp-studio.com`
- tracked config: `wrangler.toml`
- minimum supported Android version: `wrangler.toml`の非secret変数`ANDROID_MIN_VERSION_CODE`（正の整数）
- required runtime value: `SUPABASE_ANON_KEY`
- `SUPABASE_SERVICE_ROLE_KEY`はこのWorkerへ設定しない

Cloudflare Workers BuildsはGitHub repository `shinp-dev/othello`と連携しています。production branchは`main`、root directoryは`landing-page`、production deploy commandは`npx wrangler deploy`です。`main`へのpushは、このディレクトリに差分がなくても監視path設定により本番Web deployを起動し得ます。main pushとCloudflare設定変更はOWNER承認対象です。

手動deploy、別Worker/siteの作成、route変更は通常のbuild/testに含めません。
