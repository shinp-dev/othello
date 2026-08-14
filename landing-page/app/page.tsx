const features = [
  {
    id: "match",
    number: "01",
    label: "MATCH",
    title: "ちゃんと対局する。",
    body: "オンラインで対局し、その一局を次の学びにつなげます。単に勝敗を決めるだけではなく、その後の検討・分析につながる対局体験として設計します。",
    tags: ["オンライン対局", "競技志向", "棋譜", "振り返りへの接続"],
    image: "/images/match.jpg",
    alt: "女性とリバーシ盤、着手と評価を想起させる赤い分析ライン",
  },
  {
    id: "review",
    number: "02",
    label: "REVIEW",
    title: "ちゃんと振り返る。",
    body: "評価値や候補手を確認しながら、一局を振り返ります。「勝った」「負けた」で終わらせず、どこで形勢が動いたのか、別の手ならどうだったのかを検討できます。",
    tags: ["評価値", "候補手", "局面検討", "棋譜解析"],
    image: "/images/review.jpg",
    alt: "複数の候補手と評価値が表示されたリバーシ盤を見つめる女性",
  },
  {
    id: "analysis",
    number: "03",
    label: "ANALYSIS",
    title: "人の打ち方まで、分析する。",
    body: "ちゃんりばの大きな特徴は、レート帯別の傾向分析。実際のプレイヤーが、その実力帯でどのような傾向を持っているかを研究できます。リアル大会を意識する人にとって、実際に対戦する人間の傾向を知ることは、次の一手をつくる大きな材料になります。",
    tags: ["レート帯別分析", "プレイヤー傾向", "実戦研究", "大会対策"],
    image: "/images/analysis.jpg",
    alt: "グラフや分布、プレイヤー層を思わせる分析画面と女性",
  },
];

export default function Home() {
  return (
    <main>
      <header className="site-header">
        <a className="brand" href="#top" aria-label="ちゃんりば トップへ">
          <img src="/images/icon.jpg" alt="" width="42" height="42" />
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
        <div className="hero-media" aria-hidden="true"><img src="/images/hero.jpg" alt="" /></div>
        <div className="hero-copy">
          <p className="eyebrow"><span className="eyebrow-line" /> ちゃんとリバーシ</p>
          <h1 id="hero-title">対局を、<br /><em>もっと深く。</em></h1>
          <p className="hero-lead">対局・検討・傾向分析まで。<br />強くなりたいプレイヤーのためのリバーシ。</p>
          <p className="hero-body">オンライン対局から一局の振り返り、さらにレート帯ごとの傾向分析まで。打って終わりではなく、次の一手につながる体験を。</p>
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
        {features.map((feature, index) => (
          <article className={`feature ${index % 2 ? "feature-reverse" : ""}`} id={feature.id} key={feature.id}>
            <div className="feature-image"><img src={feature.image} alt={feature.alt} loading="lazy" width="1280" height="960" /></div>
            <div className="feature-copy"><div className="feature-label"><span>{feature.number}</span>{feature.label}</div><h3>{feature.title}</h3><p>{feature.body}</p><div className="tags">{feature.tags.map(tag => <span key={tag}>{tag}</span>)}</div></div>
          </article>
        ))}
      </section>

      <section className="audience section" aria-labelledby="audience-title"><div className="audience-inner"><div className="section-kicker"><span>03</span><i /> FOR PLAYERS</div><h2 id="audience-title">もっと強くなりたい<br /><em>人へ。</em></h2><div className="audience-list"><p>リバーシをもっと強くなりたい</p><p>対局後にきちんと振り返りたい</p><p>自分より上のレート帯の傾向を知りたい</p><p>リアル大会を意識している</p><p>感覚だけでなくデータも使って研究したい</p></div></div></section>

      <section className="final-cta section" id="start" aria-labelledby="start-title"><div className="cta-glow" aria-hidden="true" /><div className="section-kicker"><span>04</span><i /> START HERE</div><h2 id="start-title">その一局を、<br /><em>次の一手へ。</em></h2><p>ちゃんりばは、対局・検討・傾向分析をひとつにつなぐリバーシアプリです。</p><a className="button button-primary" href="#top">ちゃんりばを始める <span aria-hidden="true">↗</span></a><small>サービスの公開準備中です。最新情報をお待ちください。</small></section>

      <footer className="site-footer"><div className="footer-brand"><img src="/images/icon.jpg" alt="" width="48" height="48" /><div><b>ちゃんりば</b><small>CHANRIVA</small></div></div><div className="footer-meta"><span>ちゃんとリバーシ</span><span>Shinp Studio</span></div><div className="footer-legal"><span>Privacy</span><span>Terms</span><span>© Shinp Studio</span></div></footer>
    </main>
  );
}
