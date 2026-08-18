# ちゃんりば Google Play申請素材

このフォルダは、Play ConsoleのStore listingへアップロードする素材をまとめる受け渡し場所です。

## まずアップロードするもの

`upload-ready/` の以下を使用します。

- `app-icon-512x512.png`: Play用アプリアイコン。正本は `landing-page/public/images/app-icon.png` で、Android launcher iconのリソースは変更していません。
- `feature-graphic-1024x500.png`: Feature graphic。
- `phone-screenshot-01-login-1080x2160.png`: ログイン後ホーム／レート表示。
- `phone-screenshot-02-research-900x1800.png`: 棋譜解析・Research表示。
- `phone-screenshot-03-review-1080x2160.png`: 棋譜レビュー・候補手解析。

Google公式要件に合わせ、アイコンは512×512、Feature graphicは1024×500、スクリーンショットは最小辺の2倍以内に整形しています。いずれも実装済み画面を元にしています。

## 今回アップロードしないもの

`screenshots/screen-online-match.png` は元画像として保持していますが、画面内に `matchId`、ICE／Peer／DataChannel接続診断、対局状態の内部表示が残っているため、Store screenshotには使用しません。公開用には、デバッグ診断を表示しないrelease画面を再撮影してください。

Feature graphicには「COMING SOON」の表記が残っています。現行機能として誤解を招かないかをOWNERが確認してから使用してください。

## 申請時に開く資料

- Store listing文案: `../play-store/listing-ja.md`
- Data Safety回答案: `../play-store/data-safety.md`
- App access回答案: `../play-store/app-access.md`
- 公開チェックリスト: `../play-store/release-checklist.md`
- 詳細チェックリスト: `../play-store/08142327_公開前準備項目.md`

## 署名済みAAB

署名済みAABは `../release-artifacts/` に置きます。このフォルダはGit管理対象外です。Play Consoleへ提出するAABは、提出直前に `verifyPlayReleaseSigning` で再生成・検証したものを使用してください。

Google公式素材要件: <https://support.google.com/googleplay/android-developer/answer/9866151>
