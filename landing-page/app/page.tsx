const tags = {
  match: ["オンライン対局", "競技志向", "棋譜", "振り返りへの接続"],
  review: ["評価値", "候補手", "局面検討", "棋譜解析", "全合法手解析"],
  analysis: ["プレイヤー全体の傾向", "よく選ばれる進行", "着手傾向", "勝・引分・負"],
};

function FeatureLabel({ number, children }: { number: string; children: React.ReactNode }) {
  return <div className="feature-label"><span>{number}</span>{children}</div>;
}

function Tags({ items }: { items: string[] }) {
  return <div className="tags">{items.map((tag) => <span key={tag}>{tag}</span>)}</div>;
}

export default function Home() {
  return (
    <main>
      <header className="site-header">
        <a className="brand" href="#top" aria-label="ちゃんりば トップへ">
          <img src="/images/app-icon.png" alt="" width="42" height="42" />
          <span><b>ちゃんりば</b><small>CHANRIVA</small></span>
        </a>
        <nav aria-label="メインナビゲーション">
          <a href="#concept">特徴</a>
          <a href="#match">対局</a>
          <a href="#review">検討</a>
          <a href="#analysis">傾向分析</a>
          <a className="nav-cta" href="#start">始める</a>
        </nav>
      </header>

      <section className="hero" id="top" aria-labelledby="hero-title">
        <div className="hero-media" aria-hidden="true"><img src="/images/hero-key-visual.png" alt="" width="877" height="493" /></div>
        <div className="hero-copy">
          <p className="eyebrow"><span className="eyebrow-line" /> ちゃんとリバーシ</p>
          <h1 id="hero-title">対局を、<br /><em>もっと深く。</em></h1>
          <p className="hero-lead">対局・検討・傾向分析まで。<br />強くなりたいプレイヤーのためのリバーシ。</p>
          <p className="hero-body">オンライン対局から一局の振り返り、さらにプレイヤー全体の傾向分析まで。打って終わりではなく、次の一手につながる体験を。</p>
          <a className="button button-primary" href="#start">ちゃんりばを始める <span aria-hidden="true">↗</span></a>
          <p className="hero-note">対局から、学びのサイクルへ。</p>
        </div>
        <div className="hero-mark" aria-hidden="true">C<span>/</span>R</div>
      </section>

      <section className="concept section" id="concept" aria-labelledby="concept-title">
        <div className="section-kicker"><span>01</span><i /> CONCEPT</div>
        <div className="concept-grid">
          <h2 id="concept-title">打って終わりに<br /><em>しない。</em></h2>
          <div><p className="concept-lead">一局の先に、次の一手がある。</p><p>ちゃんりばは、単なる対局アプリではありません。対局、検討、分析。そのすべてを次の対局につなげて、強くなるための体験をつくります。</p></div>
        </div>
        <div className="loop" aria-label="対局から次の対局へつながる流れ"><span>対局</span><b>→</b><span>検討</span><b>→</b><span>分析</span><b>→</b><span>次の対局</span></div>
      </section>

      <section className="features section" id="features" aria-labelledby="features-title">
        <div className="section-heading"><div className="section-kicker"><span>02</span><i /> FEATURES</div><h2 id="features-title">強くなるための、<br /><em>3つの視点。</em></h2></div>

        <article className="feature-match feature-block" id="match">
          <div className="match-copy feature-copy"><FeatureLabel number="A">MATCH</FeatureLabel><h3>ちゃんと対局する。</h3><p>オンラインで対局し、その一局を次の学びにつなげます。単に勝敗を決めるだけではなく、その後の検討・分析につながる対局体験です。</p><Tags items={tags.match} /></div>
          <figure className="match-screen feature-screen"><img src="/images/screen-online-match.png" alt="オンライン対局中のちゃんりば画面" loading="lazy" width="1080" height="2233" /><figcaption>開発中の画面です。表示内容は変更される場合があります。</figcaption></figure>
        </article>

        <article className="feature-review feature-block" id="review">
          <div className="review-copy feature-copy"><FeatureLabel number="B">REVIEW</FeatureLabel><h3>ちゃんと振り返る。</h3><p>評価値や候補手を確認しながら、一局を振り返ります。「勝った」「負けた」で終わらせず、どこで形勢が動いたのか、別の手ならどうだったのかを検討できます。</p><Tags items={tags.review} /></div>
          <figure className="review-screen feature-screen"><img src="/images/screen-review.png" alt="棋譜レビューと評価値、候補手が表示されたちゃんりば画面" loading="lazy" width="394" height="816" /></figure>
        </article>

        <aside className="analysis-setup" aria-labelledby="analysis-setup-title">
          <div className="setup-label"><span>ANALYSIS SETUP</span> EDAX</div>
          <h3 id="analysis-setup-title">解析環境も、<br />ちゃんと選べる。</h3>
          <p className="setup-lead">ちゃんりばでは、高度な棋譜解析を支えるEdax用の評価データやオープニングブックを利用できます。初めての方には必要なデータの取得・設定方法をアプリ内で案内し、対応データを持つ方は自分の環境に合わせて置き換えられます。</p>
          <div className="setup-cards">
            <section className="setup-card" aria-labelledby="guided-setup-title"><small>GUIDED SETUP</small><h4 id="guided-setup-title">はじめて使う人</h4><p>必要な評価データやオープニングブックの取得・設定方法を、アプリ内で案内します。</p></section>
            <section className="setup-card" aria-labelledby="custom-data-title"><small>CUSTOM DATA</small><h4 id="custom-data-title">すでに環境を持っている人</h4><p>対応する評価データやオープニングブックを、自分が所有するものへ置き換えて利用できます。</p></section>
          </div>
        </aside>

        <article className="feature-analysis feature-block" id="analysis">
          <div className="analysis-copy feature-copy"><FeatureLabel number="C">ANALYSIS</FeatureLabel><h3>人の打ち方まで、<br />分析する。</h3><p>実際の対局データから、プレイヤー全体の打ち方や進行の傾向を分析できます。AIの最善手だけを見るのではなく、人が実際にどのような手を選んでいるのかを研究できます。</p><div className="analysis-status"><strong>現在利用できること</strong><p>プレイヤー全体の傾向や、よく選ばれる進行・着手、勝・引分・負の傾向を、実際の対局データから確認できます。</p><div className="coming-soon"><span>COMING SOON</span><strong>レート帯別分析</strong><p>データの蓄積後、レート帯ごとの進行や着手傾向を比較できる分析機能を提供予定です。将来的には大会を意識した研究にも活用できる機能へ拡張します。</p></div></div><Tags items={tags.analysis} /></div>
          <figure className="analysis-screen feature-screen"><img src="/images/screen-analysis.png" alt="プレイヤー全体の傾向と勝敗分布を表示するちゃんりば画面" loading="lazy" width="394" height="814" /></figure>
        </article>
      </section>

      <section className="audience section" aria-labelledby="audience-title"><div className="audience-inner"><div className="section-kicker"><span>03</span><i /> FOR PLAYERS</div><h2 id="audience-title">もっと強くなりたい<br /><em>人へ。</em></h2><div className="audience-list"><p>リバーシをもっと強くなりたい</p><p>対局後にきちんと振り返りたい</p><p>プレイヤー全体の傾向を知りたい</p><p>リアル大会を意識している</p><p>感覚だけでなくデータも使って研究したい</p></div></div></section>

      <section className="product-flow section" aria-labelledby="flow-title"><div className="flow-copy"><div className="section-kicker"><span>04</span><i /> ONE APP</div><h2 id="flow-title">対局から、<br /><em>振り返りまで。</em></h2><p>次の一手まで、ちゃんとつながる。対局・AI対局・棋譜レビューなど、強くなるための入口をひとつのアプリに。</p></div><figure className="login-screen feature-screen"><img src="/images/screen-login.png" alt="ちゃんりばのアプリ入口画面" loading="lazy" width="394" height="815" /></figure></section>

      <section className="final-cta section" id="start" aria-labelledby="start-title"><div className="cta-glow" aria-hidden="true" /><div className="section-kicker"><span>05</span><i /> START HERE</div><h2 id="start-title">その一局を、<br /><em>次の一手へ。</em></h2><p>ちゃんりばは、対局・検討・傾向分析をひとつにつなぐリバーシアプリです。</p><a className="button button-primary" href="#top">ちゃんりばを始める <span aria-hidden="true">↗</span></a><small>サービスの公開準備中です。最新情報をお待ちください。</small></section>

      <footer className="site-footer"><div className="footer-brand"><img src="/images/app-icon.png" alt="" width="48" height="48" /><div><b>ちゃんりば</b><small>CHANRIVA</small></div></div><div className="footer-meta"><span>ちゃんとリバーシ</span><span>Shinp Studio</span></div><div className="footer-legal"><span>Privacy</span><span>Terms</span><span>© Shinp Studio</span></div></footer>
    </main>
  );
}
