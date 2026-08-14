import Link from "next/link";

export const metadata = {
  title: "アカウント削除 | ちゃんりば",
  description: "ちゃんりば（CHANRIVA）のアカウント削除案内。",
};

export default function AccountDeletionPage() {
  return (
    <main className="policy-page">
      <div className="policy-page-inner">
        <Link className="policy-back" href="/">← ちゃんりばトップへ</Link>
        <p className="section-kicker">CHANRIVA / ACCOUNT DELETION</p>
        <h1>アカウント削除</h1>
        <p>ちゃんりばのアカウントを削除する場合は、アプリへログインし、ホーム画面の「アカウントを削除」からリクエストしてください。</p>
        <h2>アプリ内の手順</h2>
        <ol><li>ちゃんりばへログインする。</li><li>ホーム画面で「アカウントを削除」を選ぶ。</li><li>内容を確認し、「削除リクエストを送信」を選ぶ。</li></ol>
        <h2>削除される情報</h2>
        <p>認証情報、非公開プロフィール、レーティング、資格情報、証明画像、本人の棋譜参照などを信頼されたサーバー処理で削除します。共有対局を維持するため、相手にも関係する棋譜は匿名化して残る場合があります。研究参加中に同意して提供済みの研究寄与は、アカウントとの紐付けを外して保持される場合があります。</p>
        <h2>現在の公開前ステータス</h2>
        <p className="policy-notice">このページは削除処理の内容を説明する正本です。Google Playの要件を満たすログイン不要のWeb削除リクエスト受付は未実装です。公開前に受付方法、本人確認、失敗時の再試行・問い合わせ導線を確定し、このページから実際に送信できるようにする必要があります。</p>
        <div className="policy-footer"><Link href="/privacy">プライバシーポリシーへ</Link> · <Link href="/">ちゃんりばトップへ</Link></div>
      </div>
    </main>
  );
}
