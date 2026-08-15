/* eslint-disable @next/next/no-html-link-for-pages -- vinext Link prefetch throws in the production legal pages. */
import AccountDeletionForm from "./AccountDeletionForm";

export const metadata = {
  title: "アカウント削除 | ちゃんりば",
  description: "ちゃんりば（CHANRIVA）のアカウント削除案内。",
};

export default function AccountDeletionPage() {
  return (
    <main className="policy-page">
      <div className="policy-page-inner">
        <a className="policy-back" href="/">← ちゃんりばトップへ</a>
        <p className="section-kicker">CHANRIVA / ACCOUNT DELETION</p>
        <h1>アカウント削除</h1>
        <p>ちゃんりば / CHANRIVAのアカウントと関連データの削除をリクエストできます。アプリを利用できる場合はアプリ内から、利用できない場合はこのページから手続きしてください。</p>
        <h2>アプリ内の手順</h2>
        <ol><li>ちゃんりばへログインする。</li><li>ホーム画面で「アカウントを削除」を選ぶ。</li><li>内容を確認し、「削除リクエストを送信」を選ぶ。</li></ol>
        <AccountDeletionForm />
        <h2>削除される情報</h2>
        <p>認証情報、アカウント管理用の内部データ、レーティング、本人の棋譜参照などを信頼されたサーバー処理で削除します。進行中の対局がある場合は、対局終了後にあらためてリクエストしてください。</p>
        <h2>匿名化して残る可能性のある情報</h2>
        <p>共有対局を維持するため、相手にも関係する棋譜は匿名化して残る場合があります。研究参加中に同意して提供済みの研究寄与は、アカウントとの紐付けを外し、研究の再集計・プライバシー保護に必要な期間保持される場合があります。削除リクエストの受付・処理状態は、再実行の安全性と処理記録のため必要な期間保持される場合があります。端末内の棋譜やインポート済みファイルは、端末のアプリデータ消去またはアプリ内の削除操作で削除してください。</p>
        <h2>本人確認と処理完了</h2>
        <p>Web受付では、登録済みメールアドレスとパスワードで本人確認を行います。認証情報はWeb受付で保存しません。リクエスト後は信頼されたサーバー処理が削除を順に実行します。処理中は同じアカウントでログインできる場合があります。フォームで問題が解決しない場合は、時間をおいて再試行し、アプリを利用できる場合はアプリ内の削除手順も利用してください。</p>
        <div className="policy-footer"><a href="/privacy">プライバシーポリシーへ</a> · <a href="/">ちゃんりばトップへ</a></div>
      </div>
    </main>
  );
}
