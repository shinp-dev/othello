# ちゃんりば Google Play公開準備

このディレクトリは、Androidアプリ「ちゃんりば / CHANRIVA」のGoogle Play公開に必要なドラフト、監査結果、OWNER ACTIONを管理する正本です。Play Consoleへの入力値そのものではなく、コードとリポジトリから確認できた事実を記録します。

## 現在の識別子

- applicationId / Play package name: `com.shinpstudio.chanriva`
- Android namespace / Kotlin package: 既存の `com.example.othello` 系を維持
- label: `ちゃんりば`
- 正式アイコン: `landing-page/public/images/app-icon.png`（今回の作業では変更しない）
- versionCode: `1`
- versionName: `0.1.0`
- minSdk: `26`
- targetSdk: `36`（Android 16 / API 36）

## 公式要件の確認日

2026年8月14日に以下の公式情報を確認しました。

- [Target API level requirement](https://developer.android.com/google/play/requirements/target-sdk): 2026年8月31日から新規アプリ・更新はAPI 36以上。
- [16 KB page sizes](https://developer.android.com/guide/practices/page-sizes): 2025年11月1日から、Android 15以上を対象とする新規アプリ・更新は16 KiB page size対応が必要。
- [Upload an app bundle](https://developer.android.com/studio/publish/upload-bundle): 新規PlayアプリはAABで公開し、Play App Signingを利用する。
- [Sign your app](https://developer.android.com/studio/publish/app-signing): upload keyとPlay App Signingのapp signing keyを分離する運用を推奨。
- [Registering Play package names](https://support.google.com/googleplay/android-developer/answer/16984799): package登録とdeveloper identity verificationを確認。2026年9月30日から全Play package登録が必要。
- [App testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465): 2023年11月13日以後に作成した個人アカウントでは、production access前に12人以上が14日間継続opt-inしたclosed testが必要。
- [Data safety](https://support.google.com/googleplay/android-developer/answer/10787469)、[account deletion](https://support.google.com/googleplay/android-developer/answer/13327111)、[prepare for review](https://support.google.com/googleplay/android-developer/answer/9859455)、[store listing assets](https://support.google.com/googleplay/android-developer/answer/9866151)、[content rating](https://support.google.com/googleplay/android-developer/answer/9859655)

## Account deletion

- Web削除の追加経路: パスワードを使えない場合は登録メールへ確認リンクを送り、`https://chanriva.shinp-studio.com/account-deletion/confirm`で同じ`request_account_deletion()` RPCを呼び出す。LP本番反映とmigration適用は完了。Supabase Redirect URL登録とメール送信E2EはOWNER ACTION REQUIRED。
- アカウントライフサイクル: 未確認登録は7日、確認済みで最終利用から365日経過したアカウントを同じ削除パイプラインへキューするmigrationは本番適用済み。管理WorkerのCron実行確認は未完了。予告メールは送信しない。

- IN-APP: 実装済み。認証済みAndroidユーザーが既存の `request_account_deletion()` を呼び出す。
- EXTERNAL WEB（Email/Password）: 本番確認済み。`https://chanriva.shinp-studio.com/account-deletion` のフォームが既存Supabase Email/Password Authで本人確認し、同じ `request_account_deletion()` を呼び出す。アプリの再インストールや起動は要求しない。
- EXTERNAL WEB（確認メールリンク）: 実装・LP本番反映済み。メールアドレスだけで削除せず、登録メールの確認リンクから本人確認し、同じ削除受付経路へ進む。Supabase Redirect URL登録とE2Eは未完了。
- 実削除: 受付RPCの後、既存の信頼済み `cloudflare-admin` Workerがprivate dataとAuth identityを処理し、Research identityをunlinkする。初回公開版に証明画像機能はなく、Web側に削除ロジックを複製していない。
- Play Console Account deletion URL: `https://chanriva.shinp-studio.com/account-deletion`
- Email/Password経路は本番稼働確認済み（2026-08-14）。`chanriva` Workerへ必要なsecretを設定し、既存Cloudflare環境へdeploy済み。OWNERが実機からWeb削除を開始し、約4分54秒後に管理Workerが `COMPLETED` へ到達した。確認メールリンク経路は別実装であり、本番反映前である。
- 旧構成の本番後監査では、対象Auth identityの削除、レーティング・本人の棋譜参照の0件化、共有棋譜3件の保持を確認した。その後、未公開の資格情報・証明Storage・表示名機能は物理削除した。
- cleanup後はResearch参加済みの使い捨てテストアカウントで、対局capture、削除完了、account link unlink、accepted contributionと統計値の保持、削除済みaccountへ戻る識別子がResearch境界に残らないことをE2E確認済み。

Play Console、developer account、署名鍵、外部サービス管理画面にはこのリポジトリからアクセスできません。未確認の項目は各資料で明示しています。

## 資料

- [release-checklist.md](release-checklist.md)
- [data-safety.md](data-safety.md)
- [listing-ja.md](listing-ja.md)
- [app-access.md](app-access.md)
