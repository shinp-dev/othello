/* eslint-disable @next/next/no-html-link-for-pages -- policy routes use plain links for this static page. */

import ResetPasswordForm from "./ResetPasswordForm";

export const metadata = {
  title: "パスワード再設定 | ちゃんりば",
  description: "ちゃんりばのパスワード再設定。",
};

export default function ResetPasswordPage() {
  return (
    <main className="policy-page">
      <div className="policy-page-inner">
        <a className="policy-back" href="/">← ちゃんりばトップへ</a>
        <p className="section-kicker">CHANRIVA / PASSWORD RESET</p>
        <h1>パスワード再設定</h1>
        <p>
          登録メールアドレスに届いた再設定リンクから、新しいパスワードを設定してください。
        </p>
        <p className="policy-notice">
          このページでは、以前のパスワードを表示・復元することはありません。
        </p>
        <ResetPasswordForm />
        <div className="policy-footer">
          <a href="/privacy">プライバシーポリシー</a> ・ <a href="/account-deletion">アカウント削除</a> ・ <a href="/">ちゃんりばトップへ</a>
        </div>
      </div>
    </main>
  );
}
