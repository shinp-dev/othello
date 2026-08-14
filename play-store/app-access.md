# App access / 審査アクセス案

## Loginなしで確認できる機能

コード上、アプリ起動後にログインなしで利用できる範囲は次のとおりです。

- ホーム画面
- 端末を共有するふたり対局
- AI対局
- ローカル棋譜保存・ローカルレビュー（端末内データ）
- 解析設定画面。Edax評価データは審査端末へ別途安全に用意する必要がある

## Loginが必要な機能

- オンライン対局・matchmaking・rating
- Supabase上のGameRecord / online records
- 公開プロフィール、資格申請
- Research参加設定・研究データ表示
- アカウント削除リクエスト

## Reviewer instructions draft

`ちゃんりば`を起動すると、ログインなしでホーム、ふたり対局、AI対局を確認できます。オンライン対局、クラウド棋譜、Research、プロフィール、アカウント削除を確認するには、審査用のログインアカウントが必要です。Play ConsoleのApp access欄へ、OWNERが実際に利用できるreviewer accountのメールアドレスと一時パスワード、ログイン手順、必要なら確認メールの扱いを入力してください。

審査用credentialをGit、README、Play listing、公開LPには保存しません。MFA/OTPはアプリコード上の実装を確認できませんが、Supabase Authのprovider設定でEmail confirmation等が有効だと審査の障害になるため、OWNERがテスト前に確認します。

## OWNER ACTION REQUIRED

- reviewer用accountを作成し、Play Consoleへ安全に登録
- 主要オンライン機能を審査できる状態を用意
- Web account deletion受付をログイン不要で用意
- Edax解析を審査対象に含める場合、配布権利のある評価データを別途用意。アプリへ同梱しない
