# ちゃんりば公式サイト

`landing-page/`は、ちゃんりば / CHANRIVAの公式LPとGoogle Play向け法務・アカウント導線の正本です。Cloudflare Worker `chanriva`として`https://chanriva.shinp-studio.com`へ配信します。

## 公開ルート

- `/`: 製品LP
- `/privacy`: プライバシーポリシー
- `/account-deletion`: アプリを利用できないユーザー向けのWebアカウント削除受付
- `/signup-complete`: Supabase Authのメール確認完了案内
- `/api/account-deletion/start`: 同一originの削除受付API。既存Supabase Email/Password Authで本人確認し、Androidと同じ`request_account_deletion()` RPCを呼ぶ

Web削除APIはservice-role keyを持ちません。パスワードやaccess tokenを保存・ログ出力せず、実削除は`cloudflare-admin`の信頼済みWorkerが行います。`SUPABASE_ANON_KEY`はCloudflare runtime設定から渡し、tracked fileへ値を記録しません。

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
- required runtime value: `SUPABASE_ANON_KEY`
- `SUPABASE_SERVICE_ROLE_KEY`はこのWorkerへ設定しない

Cloudflare Workers BuildsはGitHub repository `shinp-dev/othello`と連携しています。production branchは`main`、root directoryは`landing-page`、production deploy commandは`npx wrangler deploy`です。`main`へのpushは、このディレクトリに差分がなくても監視path設定により本番Web deployを起動し得ます。main pushとCloudflare設定変更はOWNER承認対象です。

手動deploy、別Worker/siteの作成、route変更は通常のbuild/testに含めません。
