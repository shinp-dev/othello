# ちゃんりば 現行仕様テスト・ドキュメント監査

監査日: 2026-08-18

## 固定する初回公開仕様

- 公開プロフィール、ニックネーム、表示名は提供しない。
- 対局相手へ返すユーザー情報は、対局成立時にサーバーが保存した相手の rating snapshot のみ。
- 相手 rating が取得できない場合、Android UI は識別子で代替せず `---` を表示する。
- ログイン中のホーム画面には、本人のサーバー管理 `ratings.current_rating` を表示する。取得失敗時は `---` とする。
- メールアドレス、UUID、Auth metadata は相手向けUI・matchmaking応答に含めない。
- Researchの同意、匿名化、アカウント削除後のunlink/retention仕様は変更しない。
- メール確認前の登録は7日、確認済みで最終利用から365日経過したアカウントは、既存の信頼済み削除パイプラインへキューする。予告メールは送信しない。
- `last_active_at`は認証済みアプリの起動またはログイン成功時に、最大1日1回更新する。
- パスワード再設定はSupabase Auth標準メールと`/reset-password`を使い、旧パスワードを扱わない。パスワードを使えないWeb削除は登録メールの確認リンクを使う。

## 追加した受入れテスト

`supabase/tests/202608090003_hardening.sql` に以下の契約を追加した。

- matchmaking RPC が `opponent_rating` を返すこと。
- 待機matchのclaim RPCも `opponent_rating` を返すこと。
- 新規Authユーザーのbootstrapが表示名を取り込まないこと。
- matchmaking RPCが内部対局制御に必要な`opponent_id`を返しつつ、相手の名前・メール・UUIDを表示用に返さないこと。
- matchmaking関連スキーマに相手の名前、メール、UUID用の公開表示フィールドがないこと。
- 既存の `matches` rating snapshot、private profile、public profile削除、RLS検査と同じDB hardening suiteで検証すること。

GitHub ActionsのDB testでplan `285`、実行`285`、Failed `0`、parse errorなしを確認済み。同じCIでGradle test、lint、debug/release build、dependency boundary、SQL security、release contentsも成功した。

Android側の自動テストでは、相手ratingの正常値と未取得時fallback（`---`）を固定している。本人の現在rating表示はCompose画面内の非同期取得であり、コード構造から接続先を確認しているが、表示結果は実機・エミュレーター確認が必要なため、release前に以下を手動確認する。

1. ログイン後、ホームに本人の現在ratingが表示される。
2. rating取得失敗時にメールアドレスやUUIDへfallbackしない。
3. 対局画面には相手ratingだけが表示され、名前・メール・UUIDが表示されない。

## ドキュメント整合性の確認事項

- Store listingはオンライン対局、棋譜、レビュー、Edax、Researchを説明し、ニックネーム・公開プロフィール・段級位を機能として訴求しない。
- Data Safety資料は自由入力表示名を収集する前提を置かず、ratingをサーバー算出の対局データとして扱う。
- Privacy Policyは表示名・公開プロフィールを収集／公開する説明を含めず、対局時のrating表示、Researchの同意・保持・削除、パスワード再設定、7日／365日のライフサイクル説明と一致させる。
- Account deletionはprivate ratingを削除し、共有棋譜とResearchの保持・unlinkは既存の正本仕様に従う。今回の監査では保持期間やResearchデータを変更しない。
- Content Rating資料ではfree-text chat、コメント、プロフィール、自由入力UGCを「なし」とし、オンライン対局そのものは別項目として扱う。

## 残る手動確認

- Play Consoleへ入力するData Safety / Content Rating / Target Audienceの最終回答。
- Play生成APKまたはsigned releaseの実機で、ホームの本人ratingと対局画面の相手ratingを確認。
- Cloudflare Cronによる削除キュー、確認メールリンク削除のRedirect URL登録・本番E2E。`202608180027_account_lifecycle.sql`の本番適用は完了。

## 実機・本番E2Eで確認済み

- 使い捨てテストアカウントでResearch参加中のオンライン対局を完了し、Research captureを確認した。
- 同アカウントの削除完了後、Auth/個人データが削除され、Research account linkがunlinkされたことを確認した。
- accepted contributionと統計値が削除前後で減らず、公開集計またはResearch schemaから削除済みaccountへ逆参照する識別子が残らないことを確認した。

上記はdebug実機と本番backendのE2Eであり、signed release / Play生成APKのruntime確認を代替しない。

この文書は設計・確認事項の記録であり、本番DB、Play Console、Cloudflare設定を変更しない。
