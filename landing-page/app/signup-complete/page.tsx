/* eslint-disable @next/next/no-html-link-for-pages -- vinext Link is unnecessary for this static confirmation page. */

import SignupConfirmationStatus from "./SignupConfirmationStatus";

export const metadata = {
  title: "メールアドレス確認 | ちゃんりば",
  description: "ちゃんりばのメールアドレス確認結果案内。",
};

export default function SignupCompletePage() {
  return (
    <main className="policy-page">
      <div className="policy-page-inner">
        <a className="policy-back" href="/">← ちゃんりばトップへ</a>
        <p className="section-kicker">CHANRIVA / SIGN-UP</p>
        <SignupConfirmationStatus />
        <div className="policy-footer">
          <a href="/privacy">プライバシーポリシー</a> ・ <a href="/account-deletion">アカウント削除</a> ・ <a href="/">ちゃんりばトップへ</a>
        </div>
      </div>
    </main>
  );
}
