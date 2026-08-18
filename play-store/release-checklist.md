# Release checklist

## Repository / build

- [x] Google Play準備変更をレビュー・CI後に`main`へ統合
- [x] `applicationId = com.shinpstudio.chanriva`
- [x] namespace / Kotlin packageは変更なし
- [x] `targetSdk = 36`, `minSdk = 26`
- [x] AABを作成（署名状態は実行結果を参照）
- [ ] 公開versionをOWNER DECISION: `1.0.0`にするか`0.x`とするか
- [ ] versionCodeは公開ごとに単調増加。初回候補は現在の`1`、以後は`2, 3, ...`等を運用ルールとしてOWNER確定

## Signing

- [x] Secret-free signing acceptance path: all four `CHANRIVA_UPLOAD_*` variables unset keeps normal release builds unsigned for CI audits; all four set applies the upload signing config.
- [x] `./gradlew verifyPlayReleaseSigning` is the fail-closed Play artifact path and requires all four variables before accepting a signed AAB.

- [x] repository内にkeystore、private key、password、signing propertiesは見つからない
- [x] release build scriptにdebug keyを明示する設定はない
- [x] OWNERがちゃんりば専用upload key `chanriva-upload-v2.jks` を作成し、signed AABで使用した
- [x] 正式upload key alias: `chanriva-upload`
- [x] upload certificate SHA-256: `47:87:8B:52:E9:3A:9C:FD:5F:D9:0C:DE:BF:E3:B6:E4:02:9D:BF:8F:FB:A9:B4:48:14:0B:05:DB:A8:1C:79:DD`
- [x] keystore側とsigned AAB側のcertificate fingerprint完全一致をOWNER確認済み
- [x] upload key署名済みAABを生成し、`verifyPlayReleaseSigning` と署名検証に成功
- [ ] OWNER ACTION REQUIRED: upload keystoreの暗号化された外部バックアップを作成
- [ ] OWNER ACTION REQUIRED: Play ConsoleでPlay App Signingへ登録し、upload certificate / app signing certificateを管理
- [ ] OWNER ACTION REQUIRED: CIでrelease signingを行う場合はGitHub Actions secretへ登録。秘密情報をrepoへ保存しない
- [ ] OWNER ACTION REQUIRED: Google/OAuth等の外部サービスへPlay app signing certificateのSHA-256を登録（必要なサービスのみ）

## Play Console

- [ ] Account deletion追加経路（確認メールリンク）のSupabase Redirect URL登録・メール送信E2EをOWNER ACTION REQUIREDとして完了する（LP本番deploy済み）
- [ ] 未確認登録7日・確認済み休眠365日の自動削除について、管理WorkerのCron実行を本番確認する（migrationは適用済み）

- [ ] `com.shinpstudio.chanriva`のpackage nameをPlay Consoleで登録・所有確認
- [ ] developer identity verification完了を確認
- [ ] developer account種別を確定。ResearchがHuman Subjects Research appに該当する場合はOrganization account要件を確認
- [ ] Data Safetyを入力（[data-safety.md](data-safety.md)）
- [ ] Privacy Policy URL: `https://chanriva.shinp-studio.com/privacy`
- [x] Existing Email/Password account deletion URL: `https://chanriva.shinp-studio.com/account-deletion`。LPに既存Authで本人確認するWeb受付を実装
- [x] `chanriva` Workerへ必要なsecretを設定し、既存Cloudflare本番環境へdeploy。HTTPS表示と実受付を確認
- [x] OWNER実機E2E: Web本人確認から管理Workerの `COMPLETED` まで約4分54秒。Auth identity、個人DB参照、証明Storageの削除と共有棋譜の匿名化保持を管理画面で確認
- [x] Research参加済みの使い捨てテストアカウントで、capture、account link unlink、accepted contribution／統計値保持、削除済みaccountへの逆参照不可をE2E確認
- [ ] App accessを入力（[app-access.md](app-access.md)）
- [ ] Ads declaration: Contains ads = No（dependency/code監査根拠あり）
- [ ] Target audienceをOWNER DECISIONとして確定
- [ ] Content rating / IARC質問票を入力
- [ ] Store listingを入力（[listing-ja.md](listing-ja.md)）
- [ ] Feature graphicと審査用スクリーンショットを、Release版の実機キャプチャで準備
- [ ] Play App Signing後の生成APKをPlay Consoleのbundle explorerで確認

## Testing / production access

- [ ] Internal testingでAABを配布し、install/startup/主要機能を確認
- [ ] Personal account created after 2023-11-13の場合、closed testを12人以上・14日間継続opt-inで実施
- [ ] closed testの参加状況とfeedbackを記録
- [ ] production access申請をOWNERがPlay Consoleから送信
- [ ] review account / credentialsをGitへ保存しない

## Blocker gate

公開前に以下をすべて解消する。

- [x] upload keyによるsigned AAB生成とcertificate fingerprint一致を検証（Play提出直前に最終AABを再生成する）
- [x] release contents監査で本番endpointのみを参照
- [x] Privacy Policyと本番DBの公開プロフィール／rating／削除／Research仕様の一致（027のライフサイクルmigrationと更新Privacy Policyの本番反映を確認）
- [x] 本番にデプロイ済みであることを確認したWeb account deletion request受付
- [ ] 16 KiB native alignmentとPlay Console bundle inspection
- [ ] 主要機能のrelease smoke / physical device確認
