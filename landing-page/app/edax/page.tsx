import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Edax評価データの設定 | ちゃんりば",
  description: "ちゃんりばで使用するEdax評価データの取得・設定方法。",
  alternates: { canonical: "https://chanriva.shinp-studio.com/edax" },
};

export default function EdaxPage() {
  return (
    <main className="policy-page">
      <div className="policy-page-inner">
        <a className="policy-back" href="/">← ちゃんりばトップへ</a>
        <p className="section-kicker">CHANRIVA / EDAX</p>
        <h1>Edax評価データの設定</h1>
        <p className="policy-notice">
          ちゃんりばのAI対局・棋譜解析には、Edaxの評価データが必要です。
        </p>

        <h2>使用するEdax</h2>
        <p>
          ちゃんりばは解析エンジンとしてEdax 4.6を使用します。評価データは、Edax公式READMEの案内に従い、公式GitHub Releasesのv4.4の<code>eval.7z</code>を使用します。
        </p>
        <p>
          <a href="https://github.com/abulmo/edax-reversi/releases/tag/v4.4" rel="noreferrer">Edax公式v4.4リリースを見る</a>
          <br />
          <a href="https://github.com/abulmo/edax-reversi" rel="noreferrer">Edax公式プロジェクトを見る</a>
        </p>

        <h2>アプリから自動設定</h2>
        <p>
          アプリの解析設定にある「公式の評価データを自動設定（推奨）」を使うと、公式GitHubから配布物を直接取得します。取得した7zは端末内で処理し、アーカイブ内の<code>eval.dat</code>だけを展開して、既存のEdax検証に成功した場合のみ設定します。
        </p>
        <p>
          ダウンロード、展開、検証に失敗した場合は、現在設定されている評価データを保持します。
        </p>

        <h2>手動インポート</h2>
        <p>
          手元にある正当なEdax互換の<code>eval.dat</code>を、アプリの解析設定にある「手元の eval.dat を選ぶ」から選択することもできます。
        </p>

        <h2>配布について</h2>
        <p>
          評価データはCHANRIVAのAPK/AABにも、Shinp Studioのサイトにも配布・同梱していません。ちゃんりばはEdax公式・公認アプリではありません。
        </p>
        <div className="policy-footer">
          <a className="policy-back" href="/">← トップへ戻る</a>
        </div>
      </div>
    </main>
  );
}
