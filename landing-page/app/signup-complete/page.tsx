/* eslint-disable @next/next/no-html-link-for-pages -- vinext Link is unnecessary for this static confirmation page. */

export const metadata = {
  title: "登録完了 | ちゃんりば",
  description: "ちゃんりばのメールアドレス確認完了案内。",
};

export default function SignupCompletePage() {
  return (
    <main className="policy-page">
      <div className="policy-page-inner">
        <a className="policy-back" href="/">← ちゃんりばトップへ</a>
        <p className="section-kicker">CHANRIVA / SIGN-UP</p>
        <h1>登録完了</h1>
        <p className="policy-notice">メールアドレスの確認が完了しました。</p>
        <h2>アプリに戻ってログインしてください</h2>
        <p>
          ちゃんりばアプリを開き、登録したメールアドレスとパスワードでログインしてください。
        </p>
        <h2>リンクが期限切れの場合</h2>
        <p>
          確認リンクが期限切れまたは無効になった場合は、アプリから確認メールをもう一度送信してください。
        </p>
        <div className="policy-footer">
          <a href="/privacy">プライバシーポリシー</a> ・ <a href="/account-deletion">アカウント削除</a> ・ <a href="/">ちゃんりばトップへ</a>
        </div>
      </div>
    </main>
  );
}
