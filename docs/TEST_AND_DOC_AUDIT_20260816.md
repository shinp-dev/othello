# ちゃんりば 現行仕様テスト・ドキュメント監査

監査日: 2026-08-16

## 固定する初回公開仕様

- 公開プロフィール、ニックネーム、表示名は提供しない。
- 対局相手へ返すユーザー情報は、対局成立時にサーバーが保存した相手の rating snapshot のみ。
- 相手 rating が取得できない場合、Android UI は識別子で代替せず `---` を表示する。
- ログイン中のホーム画面には、本人のサーバー管理 `ratings.current_rating` を表示する。取得失敗時は `---` とする。
- メールアドレス、UUID、Auth metadata は相手向けUI・matchmaking応答に含めない。
- Researchの同意、匿名化、アカウント削除後のunlink/retention仕様は変更しない。

## 追加した受入れテスト

`supabase/tests/202608090003_hardening.sql` に以下の契約を追加した。

- matchmaking RPC が `opponent_rating` を返すこと。
- 待機matchのclaim RPCも `opponent_rating` を返すこと。
- 新規Authユーザーのbootstrapが表示名を取り込まないこと。
- matchmaking RPCが内部対局制御に必要な`opponent_id`を返しつつ、相手の名前・メール・UUIDを表示用に返さないこと。
- matchmaking関連スキーマに相手の名前、メール、UUID用の公開表示フィールドがないこと。
- 既存の `matches` rating snapshot、private profile、public profile削除、RLS検査と同じDB hardening suiteで検証すること。

Android側の自動テストでは、相手ratingの正常値と未取得時fallback（`---`）を固定している。本人の現在rating表示はCompose画面内の非同期取得であり、コード構造から接続先を確認しているが、表示結果は実機・エミュレーター確認が必要なため、release前に以下を手動確認する。

1. ログイン後、ホームに本人の現在ratingが表示される。
2. rating取得失敗時にメールアドレスやUUIDへfallbackしない。
3. 対局画面には相手ratingだけが表示され、名前・メール・UUIDが表示されない。

## ドキュメント整合性の確認事項

- Store listingはオンライン対局、棋譜、レビュー、Edax、Researchを説明し、ニックネーム・公開プロフィール・段級位を機能として訴求しない。
- Data Safety資料は自由入力表示名を収集する前提を置かず、ratingをサーバー算出の対局データとして扱う。
- Privacy Policyは表示名・公開プロフィールを収集／公開する説明を含めず、対局時のrating表示とResearchの同意・保持・削除説明を維持する。
- Account deletionはprivate ratingを削除し、共有棋譜とResearchの保持・unlinkは既存の正本仕様に従う。今回の監査では保持期間やResearchデータを変更しない。
- Content Rating資料ではfree-text chat、コメント、プロフィール、自由入力UGCを「なし」とし、オンライン対局そのものは別項目として扱う。

## 残る手動確認

- Play Consoleへ入力するData Safety / Content Rating / Target Audienceの最終回答。
- Play生成APKまたはsigned releaseの実機で、ホームの本人ratingと対局画面の相手ratingを確認。
- Research contributionを持つ使い捨てテストアカウントで、capture後の削除、account link unlink、統計値の保持を確認。

この文書は設計・確認事項の記録であり、本番DB、Play Console、Cloudflare設定を変更しない。
