export default function Home() {
  return (
    <main>
      <header className="site-header">
        <a className="brand" href="#top" aria-label="ちゃんりば トップへ">
          <img src="/images/app-icon.png" alt="" width="42" height="42" />
          <span><b>ちゃんりば</b><small>CHANRIVA</small></span>
        </a>
        <nav aria-label="メインナビゲーション">
          <a href="#play-to-review">対局と検討</a>
          <a href="#deep-research">深く研究する</a>
          <a className="nav-cta" href="#start">始める</a>
        </nav>
      </header>

      <section className="hero" id="top" aria-labelledby="hero-title">
        <div className="hero-media" aria-hidden="true"><img src="/images/hero-key-visual.png" alt="" width="877" height="493" /></div>
        <div className="hero-copy">
          <p className="eyebrow"><span className="eyebrow-line" /> ちゃんとリバーシ</p>
          <h1 id="hero-title">対局を、<br /><em>もっと深く。</em></h1>
          <p className="hero-lead">対局して終わりではなく、<br />振り返って、試して、次の一局へ。</p>
          <p className="hero-body">対局 → 検討 → 次の対局。ちゃんりばは、一局ごとの気づきを次の一手につなげます。</p>
          <a className="button button-primary" href="#start">ちゃんりばを始める <span aria-hidden="true">↗</span></a>
          <p className="hero-note">解析にはEdaxを使用しています。</p>
        </div>
        <div className="hero-mark" aria-hidden="true">C<span>/</span>R</div>
      </section>

      <a className="hero-special-link" href="#deep-research">
        <span>対戦と盤面検討だけじゃない。</span>
        <strong>ちゃんりば独自の機能を見る <span aria-hidden="true">→</span></strong>
      </a>

      <section className="experience section" id="play-to-review" aria-labelledby="experience-title">
        <div className="section-kicker"><span>01</span><i /> PLAY → REVIEW</div>
        <div className="experience-heading">
          <h2 id="experience-title">対局から、<br /><em>そのまま検討へ。</em></h2>
          <p className="experience-intro">オンライン対局、AI対局、1台の端末を使ったふたり対局に対応しています。対局した棋譜は保存され、終局後はそのまま一手ずつ振り返れます。</p>
        </div>

        <div className="experience-grid">
          <div className="experience-copy">
            <p>評価値や候補手を確認しながら、どこで形勢が動いたのか、他にどんな手があったのか、その先がどう進むのかを調べられます。</p>
            <p>対局した棋譜だけでなく、気になった任意の盤面から検討を始めることもできます。</p>
          </div>
          <ul className="experience-list">
            <li><strong>棋譜を一手ずつ振り返る</strong><span>対局後の局面を、評価値と候補手と一緒に確認。</span></li>
            <li><strong>任意盤面を取り込む</strong><span>JSONで石の配置と手番を確認・修正して検討へ。</span></li>
            <li><strong>全合法手を比較する</strong><span>その局面にある合法手ごとのEdax評価を比べられます。</span></li>
          </ul>
        </div>

        <div className="supporting-screens match-review-screens" aria-label="対局から対局後の検討へ">
          <figure className="supporting-screen">
            <img src="/images/screen-match.webp" alt="ちゃんりばの対局画面" loading="lazy" decoding="async" width="691" height="1536" />
            <figcaption>対局</figcaption>
          </figure>
          <figure className="supporting-screen">
            <img src="/images/screen-review.webp" alt="評価値や候補手を確認する対局後の検討画面" loading="lazy" decoding="async" width="691" height="1536" />
            <figcaption>対局後の検討</figcaption>
          </figure>
        </div>

      </section>

      <section className="front-closure section" id="start" aria-labelledby="start-title">
        <div className="section-kicker"><span>02</span><i /> NEXT GAME</div>
        <h2 id="start-title">その一局を、<br /><em>次の一手へ。</em></h2>
        <div className="closure-copy"><p>対局する。</p><p>振り返る。</p><p>気になった局面を試す。</p><p>そして、もう一度対局する。</p></div>
        <p className="closure-lead">ちゃんりばは、一局ごとの気づきを次の対局につなげます。</p>
        <a className="button button-primary" href="#top">ちゃんりばを始める <span aria-hidden="true">↗</span></a>
        <small>サービスの公開準備中です。最新情報をお待ちください。</small>
      </section>

      <section className="deep-research-intro" id="deep-research" aria-labelledby="deep-research-title">
        <div className="deep-research-intro-inner">
          <p className="deep-label">03 / ADVANCED RESEARCH</p>
          <h2 id="deep-research-title">さらに深く<br />研究する。</h2>
          <p>対局と検討だけでなく、局面を別の視点から考えたり、実際のプレイヤーの打ち方と比べたり。ちゃんりばには、もう一歩深く研究するための機能もあります。</p>
        </div>
      </section>

      <section className="board-research section" aria-labelledby="board-research-title">
        <div className="section-kicker"><span>04</span><i /> POSITION RESEARCH</div>
        <div className="board-research-heading">
          <h2 id="board-research-title">目の前のオセロ盤を、<br /><em>そのまま検討へ。</em></h2>
          <p>大会や対面対局で気になった局面を、その場で撮影。アプリ内から専用プロンプトをコピーし、盤面画像と一緒に外部AIサービスへ渡します。</p>
        </div>
        <div className="board-research-grid">
          <p>外部AIがちゃんりば形式のJSONを生成。返されたJSONをちゃんりばへ貼り付け、石の配置と手番を確認・修正すれば、その局面からすぐに検討を始められます。</p>
          <p>目の前の実物の盤面を、そのまま研究へ。全合法手のEdax評価を比較しながら、気になった一局を深掘りできます。</p>
        </div>
        <figure className="supporting-screen single-supporting-screen">
          <img src="/images/screen-position-import.webp" alt="JSONから読み込んだ盤面の石の配置と手番を確認する画面" loading="lazy" decoding="async" width="691" height="1536" />
          <figcaption>取り込んだ盤面を確認して、検討へ</figcaption>
        </figure>
        <p className="board-research-note">画像の読み取りとJSON化は外部AIサービス側で行います。ちゃんりばは、受け取った盤面から検討を行います。</p>
      </section>

      <section className="research-section section" aria-labelledby="theory-title">
        <div className="research-heading">
          <div className="section-kicker"><span>05</span><i /> THEORY EXPLORATION</div>
          <h2 id="theory-title">評価値の先を<br /><em>考える。</em></h2>
          <p>「なぜこの手なのか。」理論探求では、棋譜を順番に振り返るレビューとは別に、局面を自由に掘り下げられます。</p>
        </div>
        <div className="research-grid">
          <div className="research-copy">
            <p>各合法手について上段にEdax評価、下段に盤面由来の理論指標を同時に表示。評価値と別の数値を見比べながら、自分がどんな特徴の手を選びやすいかを知るための材料にできます。</p>
            <p>指標単体で最善手を決めるものではありません。いつもの考え方とは違う視点を持ち、局面の傾向を探るための機能です。</p>
          </div>
          <div className="research-metrics" aria-label="現在扱う理論指標">
            <strong>現在扱う理論指標</strong>
            <ul>
              <li>開放度</li>
              <li>相手モビリティ</li>
              <li>フロンティア石数</li>
              <li>潜在モビリティ</li>
            </ul>
          </div>
        </div>
        <figure className="supporting-screen single-supporting-screen">
          <img src="/images/screen-theory.webp" alt="Edax評価と理論指標を同時に確認する理論探求画面" loading="lazy" decoding="async" width="691" height="1536" />
          <figcaption>評価値と盤面の特徴を同時に見る</figcaption>
        </figure>
        <p className="research-note">解析済み局面の結果は効率よく再利用でき、前の局面へ戻ったときも研究を続けやすくしています。</p>
      </section>

      <section className="human-analysis section" aria-labelledby="human-analysis-title">
        <div className="section-kicker"><span>06</span><i /> HUMAN PLAY</div>
        <h2 id="human-analysis-title">AIだけでなく、<br /><em>人の打ち方を見る。</em></h2>
        <div className="human-analysis-grid">
          <p>実際の対局データから、どの進行がよく選ばれているか、どの手がよく打たれているか、その先でどんな結果になっているかを研究できます。</p>
          <p>AIの最善手だけではなく、人間が実際に選ぶ手も見る。データが蓄積した後は、レート帯別の着手傾向や進行比較にも対応予定です。</p>
        </div>
        <figure className="supporting-screen single-supporting-screen">
          <img src="/images/screen-trend-analysis.webp" alt="実際のプレイヤーの着手傾向と結果傾向を確認する分析画面" loading="lazy" decoding="async" width="691" height="1536" />
          <figcaption>人の着手傾向と結果を見る</figcaption>
        </figure>
      </section>

      <footer className="site-footer"><div className="footer-brand"><img src="/images/app-icon.png" alt="" width="48" height="48" /><div><b>ちゃんりば</b><small>CHANRIVA</small></div></div><div className="footer-meta"><span>ちゃんとリバーシ</span><span>Shinp Studio</span></div><div className="footer-legal"><a href="/privacy">Privacy</a><a href="/account-deletion">Account deletion</a><span>© Shinp Studio</span></div></footer>
    </main>
  );
}
