# App access / 審査アクセス案

## Loginなしで確認できる機能

コード上、アプリ起動後にログインなしで利用できる範囲は次のとおりです。

- ホーム画面
- 端末を共有するふたり対局
- AI対局
- ローカル棋譜保存・ローカルレビュー（端末内データ）
- 解析設定画面。Edax公式GitHub Releasesから評価データを自動設定するか、審査端末で手動インポートできる

## Loginが必要な機能

- オンライン対局・matchmaking・rating
- ホームの本人current rating
- Supabase上のGameRecord / online records
- Research参加設定・研究データ表示
- アプリ内のアカウント削除リクエスト

## Reviewer instructions draft

`ちゃんりば`を起動すると、ログインなしでホーム、ふたり対局、AI対局を確認できます。オンライン対局、クラウド棋譜、Research、アカウント削除を確認するには、審査用のログインアカウントが必要です。公開プロフィール、ニックネーム、連盟段級位入力は初回公開版にありません。Play ConsoleのApp access欄へ、OWNERが実際に利用できるreviewer accountのメールアドレスと一時パスワード、ログイン手順、必要なら確認メールの扱いを入力してください。

アプリをインストールしていない審査担当者も、`https://chanriva.shinp-studio.com/account-deletion`へ直接アクセスして削除を開始できます。Email/Password経路では登録メールアドレスとパスワード、パスワードを使えない場合の確認メール経路では登録メールの確認リンクによる本人確認が必要です。確認メール経路はLPへ本番反映済みですが、Redirect URL登録とE2E完了までは審査案内へ確定記載しません。

審査用credentialをGit、README、Play listing、公開LPには保存しません。本番Supabase AuthはConfirm EmailとCustom SMTPを有効化し、確認メールから`https://chanriva.shinp-studio.com/signup-complete`へ到達する構成をOWNER確認済みです。審査用accountは事前にメール確認を完了し、審査時に新規登録や確認メール受信を要求しない状態にします。アプリ独自のMFA/OTP UIはありません。

パスワードを忘れた場合は、ログイン画面の「パスワードを忘れた場合」から登録メールアドレスへSupabase Auth標準の再設定メールを送信します。再設定リンクは`https://chanriva.shinp-studio.com/reset-password`で新しいパスワードだけを設定します。旧パスワードの表示・復元は行いません。審査用accountは、必要に応じて事前にログイン可能な状態を用意します。

## OWNER ACTION REQUIRED

- Web account deletionはアプリのインストールや起動なしで直接開ける。パスワードを使えない場合は登録メールへの確認リンクで本人確認する（メールアドレスだけでは削除しない）。確認リンク用Redirect URL登録とE2EはOWNER ACTION REQUIRED（LPは本番反映済み）

- reviewer用accountを作成し、Play Consoleへ安全に登録
- 主要オンライン機能を審査できる状態を用意
- Web account deletionページはアプリのインストールや起動なしで直接開ける。受付送信時は登録済みEmail/Password、または登録メールへの確認リンクで本人確認する（メールアドレスだけでは削除しない）
- Edax解析を審査対象に含める場合、解析設定画面の「公式の評価データを自動設定（推奨）」を使用する。アプリへ同梱しない
