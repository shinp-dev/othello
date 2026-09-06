# ちゃんりば Google Play公開準備

このディレクトリは、Androidアプリ「ちゃんりば / CHANRIVA」のGoogle Play公開に必要なドラフト、監査結果、所有者 操作を管理する正本です。Play コンソールへの入力値そのものではなく、コードとリポジトリから確認できた事実を記録します。

## 現在の識別子

- applicationId / Play パッケージ name: `com.shinpstudio.chanriva`
- Android namespace / Kotlin パッケージ: 既存の `com.example.othello` 系を維持
- label: `ちゃんりば`
- 正式アイコン: `landing-page/public/images/app-icon.png`（今回の作業では変更しない）
- versionCode: `4`
- versionName: `0.2.0`
- minSdk: `26`
- targetSdk: `36`（Android 16 / API 36）

現在のversionCodeは[`app/build.gradle.kts`](../../../app/build.gradle.kts)を正本とする。Play提出前にこの値とAABのversionCodeを照合し、資料だけを先行更新しない。

## 現行デバッグ版（2026年9月6日）

- `versionCode = 4`、`versionName = 0.2.0`
- GitHub `main` のcommit `bb2aa8c`を基にビルドし、バージョン更新commit `440e606`を適用
- APK: `app/build/outputs/apk/debug/app-debug.apk`
- 実機 `44031JEKB12776`へインストール・起動確認済み
- このAPKは実機確認用のdebug版であり、Google Playへ提出した`versionCode = 3`のAABを置き換えるものではない

## 2026年8月31日のGoogle Play提出

- 所有者確認により、`versionCode = 3`、`versionName = 0.1.0`のAABを正式アップロードキーで署名し、Google Playへ提出済み。
- `versionCode = 3`への更新はmainのcommit `dd34f90e15b96c0bcd3ea30b549e4758f042c4ad`に記録する。
- 提出したAAB、keystore、パスワード、非公開キー、Play Console固有の提出情報はGitで管理しない。
- 審査結果と公開状態はGoogle Play Consoleを正本とし、このリポジトリでは継続管理しない。
- リポジトリに残す事実は[Google Play提出記録 20260831](../../09_履歴/Google_Play提出記録_20260831.md)を参照する。

## リリース署名のidentity

- 正式アップロード証明書SHA-256: `47:87:8B:52:E9:3A:9C:FD:5F:D9:0C:DE:BF:E3:B6:E4:02:9D:BF:8F:FB:A9:B4:48:14:0B:05:DB:A8:1C:79:DD`
- alias: `chanriva-upload`
- 正式アップロードkeystoreのファイル名: `chanriva-upload-v3.jks`（所有者確認済み。2026年8月18日作成、Git外で保管）
- `chanriva-upload-v2.jks`という既存資料の記録は古い。ファイル名だけをidentityとして扱わず、上記fingerprintおよびPlay Consoleの登録証明書との一致を優先する。
- 既存manifestで`v3`と記録されたローカルAABから抽出した証明書SHA-256は`3A:6B:5A:94:62:45:6B:DE:65:B7:12:C0:66:B0:5C:A0:CB:96:52:2B:26:42:16:12:D5:FD:FD:95:E0:3E:0A:16`で、正式fingerprintと一致しない。この成果物をPlayへ提出しない。
- 所有者はGit外の`chanriva-upload-v3.jks`を正式アップロードキーとして使用し、2026年8月31日に署名済みAABをGoogle Playへ提出した。秘密鍵と提出物は引き続きGit外で管理する。
- `./gradlew verifyPlayReleaseSigning`はAAB内の公開証明書を正式fingerprintと照合し、不一致をfail-closedで拒否する。

## 公式要件の確認日

2026年8月14日に以下の公式情報を確認しました。

- [Target API レベル requirement](https://developer.android.com/google/play/requirements/target-sdk): 2026年8月31日から新規アプリ・更新はAPI 36以上。
- [16 KB page sizes](https://developer.android.com/guide/practices/page-sizes): 2025年11月1日から、Android 15以上を対象とする新規アプリ・更新は16 KiB page size対応が必要。
- [アプリバンドルのアップロード](https://developer.android.com/studio/publish/upload-bundle): 新規PlayアプリはAABで公開し、Playアプリ署名を利用する。
- [アプリへの署名](https://developer.android.com/studio/publish/app-signing): アップロードキーとPlayアプリ署名のアプリ署名キーを分離する運用を推奨。
- [Registering Play パッケージ names](https://support.google.com/googleplay/android-developer/answer/16984799): パッケージ登録とdeveloper 識別情報 検証を確認。2026年9月30日から全Play パッケージ登録が必要。
- [アプリ testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465): 2023年11月13日以後に作成した個人アカウントでは、本番 アクセス前に12人以上が14日間継続opt-inしたclosed テストが必要。
- [データ safety](https://support.google.com/googleplay/android-developer/answer/10787469)、[アカウント削除](https://support.google.com/googleplay/android-developer/answer/13327111)、[prepare for レビュー](https://support.google.com/googleplay/android-developer/answer/9859455)、[store listing assets](https://support.google.com/googleplay/android-developer/answer/9866151)、[content レート](https://support.google.com/googleplay/android-developer/answer/9859655)

## アカウント削除

- Web削除の追加経路: パスワードを使えない場合は登録メールへ確認リンクを送り、`https://chanriva.shinp-studio.com/account-deletion/confirm`で同じ`request_account_deletion()` RPCを呼び出す。LP本番反映とマイグレーション適用は完了。Supabase Redirect URL登録とメール送信E2Eは所有者 操作 必須。
- アカウントライフサイクル: 未確認登録は7日、確認済みで最終利用から365日経過したアカウントを同じ削除パイプラインへキューするマイグレーションは本番適用済み。管理ワーカーのCron実行確認は未完了。予告メールは送信しない。

- アプリ内: 実装済み。認証済みAndroidユーザーが既存の `request_account_deletion()` を呼び出す。
- EXTERNAL WEB（メール/パスワード）: 本番確認済み。`https://chanriva.shinp-studio.com/account-deletion` のフォームが既存Supabase メール/パスワード Authで本人確認し、同じ `request_account_deletion()` を呼び出す。アプリの再インストールや起動は要求しない。
- EXTERNAL WEB（確認メールリンク）: 実装・LP本番反映済み。メールアドレスだけで削除せず、登録メールの確認リンクから本人確認し、同じ削除受付経路へ進む。Supabase Redirect URL登録とE2Eは未完了。
- 実削除: 受付RPCの後、既存の信頼済み `cloudflare-admin` ワーカーが非公開データとAuthの識別情報を処理し、Researchの識別情報を切り離しする。初回公開版に証明画像機能はなく、Web側に削除ロジックを複製していない。
- Play コンソール アカウント削除 URL: `https://chanriva.shinp-studio.com/account-deletion`
- メール/パスワード経路は本番稼働確認済み（2026-08-14）。`chanriva` ワーカーへ必要なシークレットを設定し、既存Cloudflare環境へデプロイ済み。所有者が実機からWeb削除を開始し、約4分54秒後に管理ワーカーが `COMPLETED` へ到達した。確認メールリンク経路は別実装であり、本番反映前である。
- 旧構成の本番後監査では、対象Authの識別情報の削除、レーティング・本人の棋譜参照の0件化、共有棋譜3件の保持を確認した。その後、未公開の資格情報・証明Storage・表示名機能は物理削除した。
- クリーンアップ後はResearch参加済みの使い捨てテストアカウントで、対局収集、削除完了、アカウント リンク 切り離し、受理済み 提供データと統計値の保持、削除済みアカウントへ戻る識別子がResearch境界に残らないことをE2E確認済み。

Play コンソール、developer アカウント、署名鍵、外部サービス管理画面にはこのリポジトリからアクセスできません。未確認の項目は各資料で明示しています。

## 資料

- [公開チェックリスト](公開チェックリスト.md)
- [データセーフティ回答案](データセーフティ回答案.md)
- [ストア掲載文案](ストア掲載文案.md)
- [審査アクセス回答案](審査アクセス回答案.md)
- [公開前準備項目](公開前準備項目.md)

## 申請素材

アップロード用に整形したicon、Feature graphic、screenshotはリポジトリ直下の[`store-assets/upload-ready`](../../../store-assets/upload-ready)にまとめています。素材の選定理由と未採用素材は[`store-assets/README.md`](../../../store-assets/README.md)を確認してください。

署名済みAABは[`store-assets/release-artifacts`](../../../store-assets/release-artifacts)に置きますが、秘密鍵・AABともにGitへコミットしません。
