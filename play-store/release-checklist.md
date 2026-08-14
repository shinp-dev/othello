# Release checklist

## Repository / build

- [x] 専用branch `codex/google-play-prep` で作業
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
- [ ] OWNER ACTION REQUIRED: upload keyを安全な端末またはsecret managerで作成
- [ ] OWNER ACTION REQUIRED: Play ConsoleでPlay App Signingへ登録し、upload certificate / app signing certificateを管理
- [ ] OWNER ACTION REQUIRED: CIでrelease signingを行う場合はGitHub Actions secretへ登録。秘密情報をrepoへ保存しない
- [ ] OWNER ACTION REQUIRED: Google/OAuth等の外部サービスへPlay app signing certificateのSHA-256を登録（必要なサービスのみ）

## Play Console

- [ ] `com.shinpstudio.chanriva`のpackage nameをPlay Consoleで登録・所有確認
- [ ] developer identity verification完了を確認
- [ ] developer account種別を確定。ResearchがHuman Subjects Research appに該当する場合はOrganization account要件を確認
- [ ] Data Safetyを入力（[data-safety.md](data-safety.md)）
- [ ] Privacy Policy URL: `https://chanriva.shinp-studio.com/privacy`
- [x] Account deletion URL: `https://chanriva.shinp-studio.com/account-deletion`。LPに既存Authで本人確認するWeb受付を実装
- [x] `chanriva` Workerへ必要なsecretを設定し、既存Cloudflare本番環境へdeploy。HTTPS表示と実受付を確認
- [x] OWNER実機E2E: Web本人確認から管理Workerの `COMPLETED` まで約4分54秒。Auth identity、個人DB参照、証明Storageの削除と共有棋譜の匿名化保持を管理画面で確認
- [ ] Research参加履歴を持つ専用テストアカウントで、account linkのunlinkとaccepted contribution保持をE2E確認
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

- [ ] release AABをupload keyで署名
- [ ] 本番endpointのみを参照
- [ ] Privacy Policyと実装の一致
- [x] 本番にデプロイ済みであることを確認したWeb account deletion request受付
- [ ] 16 KiB native alignmentとPlay Console bundle inspection
- [ ] 主要機能のrelease smoke / physical device確認
