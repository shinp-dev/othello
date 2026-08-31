# Google Play提出記録 20260831

## 実施結果

2026年8月31日、所有者がAndroidアプリ「ちゃんりば / CHANRIVA」のAABを正式アップロードキーで署名し、Google Playへ提出した。

## リポジトリで確認できる情報

- applicationId: `com.shinpstudio.chanriva`
- versionCode: `3`
- versionName: `0.1.0`
- versionCode更新commit: `dd34f90e15b96c0bcd3ea30b549e4758f042c4ad`
- versionCodeの正本: `app/build.gradle.kts`

`dd34f90e15b96c0bcd3ea30b549e4758f042c4ad`は、UI変更を統合したmainへ`versionCode = 3`だけを追加したcommitである。履歴を不要に往復させないため、revertや再適用は行わない。

## 所有者確認による外部操作

- Git外で保管する正式アップロードキーを使用
- `versionCode = 3`のAABを署名
- Google Playへ提出

## 管理対象外

次の情報はこのリポジトリへ保存せず、Play Consoleまたは所有者の安全な保管先を正本とする。

- 提出したAAB本体とその保管
- keystore、非公開キー、パスワード
- Play Console固有の提出IDや画面記録
- 審査結果、公開日時、公開・配信状態

この記録は提出物を再現・保管するためのmanifestではなく、リポジトリのバージョンと外部提出が行われた事実を対応付けるための履歴である。
