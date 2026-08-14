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

Play Console、developer account、署名鍵、外部サービス管理画面にはこのリポジトリからアクセスできません。未確認の項目は各資料で明示しています。

## 資料

- [release-checklist.md](release-checklist.md)
- [data-safety.md](data-safety.md)
- [listing-ja.md](listing-ja.md)
- [app-access.md](app-access.md)
