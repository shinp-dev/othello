const tags = {
  match: ["オンライン対局", "AI対局", "ふたり対局", "競技志向", "棋譜", "振り返りへの接続"],
  review: ["評価値", "候補手", "局面検討", "棋譜解析", "全合法手解析", "JSON取り込み"],
  theory: ["Edax評価", "開放度", "相手モビリティ", "フロンティア石数", "潜在モビリティ"],
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
          <p className="hero-lead">対局・検討・理論探求・傾向分析まで。<br />強くなりたいプレイヤーのためのリバーシ。</p>
          <p className="hero-body">オンライン対局から棋譜の振り返り、外部局面の取り込み、Edaxによる全合法手の比較、盤面由来の理論指標を使った探求まで。打って終わりではなく、次の一手につながる体験を。</p>
          <a className="button button-primary" href="#start">ちゃんりばを始める <span aria-hidden="true">↗</span></a>
          <p className="hero-note">対局から、検討と探求のサイクルへ。</p>
        </div>
        <div className="hero-mark" aria-hidden="true">C<span>/</span>R</div>
      </section>

      <section className="concept section" id="concept" aria-labelledby="concept-title">
        <div className="section-kicker"><span>01</span><i /> CONCEPT</div>
        <div className="concept-grid">
          <h2 id="concept-title">打って終わりに<br /><em>しない。</em></h2>
          <div><p className="concept-lead">一局の先に、次の一手がある。</p><p>ちゃんりばは、単なる対局アプリではありません。対局、棋譜レビュー、任意盤面の比較、理論探求、傾向分析。そのすべてを次の対局につなげて、強くなるための体験をつくります。</p></div>
        </div>
        <div className="loop" aria-label="対局から次の対局へつながる流れ"><span>対局</span><b>→</b><span>検討</span><b>→</b><span>分析・探求</span><b>→</b><span>次の対局</span></div>
      </section>

      <section className="features section" id="features" aria-labelledby="features-title">
        <div className="section-heading"><div className="section-kicker"><span>02</span><i /> FEATURES</div><h2 id="features-title">強くなるための、<br /><em>3つの視点。</em></h2></div>

        <article className="feature-match feature-block" id="match">
          <div className="match-copy feature-copy">
            <FeatureLabel number="A">MATCH</FeatureLabel>
            <h3>ちゃんと対局する。</h3>
            <p>
              対局タブから、オンライン対局・端末内AIとの対局・この端末を使ったふたり対局へ。オンラインの一局は、その後の検討・分析にもつながります。残り時間の音による通知や、任意で使える集中用ピンクノイズにも対応しています。
            </p>
            <Tags items={tags.match} />
          </div>
          <div className="screenshot-stack match-screens">
            <figure className="feature-screen phone-screen">
              <img
                src="/images/screen-match-home.jpg"
                alt="オンライン、AI、ふたり対局を選べる現在のちゃんりば対局タブ"
                loading="lazy"
                decoding="async"
                width="691"
                height="1536"
              />
              <figcaption>
                <strong>対局タブ</strong>
                オンライン・AI・ふたり対局の入口をひとつにまとめています。
              </figcaption>
            </figure>
            <figure className="match-screen feature-screen">
              <img
                src="/images/screen-online-match.png"
                alt="オンライン対局中のちゃんりば画面"
                loading="lazy"
                decoding="async"
                width="1080"
                height="2233"
              />
              <figcaption>
                <strong>オンライン対局</strong>
                開発中の画面です。表示内容は変更される場合があります。
              </figcaption>
            </figure>
          </div>
        </article>

        <article className="feature-review feature-block" id="review">
          <div className="review-copy feature-copy">
            <FeatureLabel number="B">REVIEW</FeatureLabel>
            <h3>ちゃんと振り返る。</h3>
            <p>
              オンライン棋譜やオフライン棋譜を、評価値や候補手を確認しながら振り返ります。「勝った」「負けた」で終わらせず、どこで形勢が動いたのか、別の手ならどうだったのかを検討できます。
            </p>
            <Tags items={tags.review} />
          </div>
          <figure className="review-screen feature-screen">
            <img
              src="/images/screen-review.png"
              alt="棋譜レビューと評価値、候補手が表示されたちゃんりば画面"
              loading="lazy"
              decoding="async"
              width="394"
              height="816"
            />
          </figure>
        </article>

        <section
          className="study-detail position-review"
          aria-labelledby="position-review-title"
        >
          <div className="study-copy feature-copy">
            <FeatureLabel number="B-2">POSITION REVIEW</FeatureLabel>
            <h3 id="position-review-title">
              外部の局面から、
              <br />
              検討を始める。
            </h3>
            <p className="study-lead">
              外部の盤面をちゃんりば形式のJSONで取り込み、その局面からEdaxによる検討を始められます。
            </p>
          </div>

          <ol className="position-flow" aria-label="任意盤面から検討する流れ">
            <li>
              <div>
                <strong>外部盤面を取り込む</strong>
                <span>ちゃんりばが受け取れるJSONを読み込みます。</span>
              </div>
            </li>
            <li>
              <div>
                <strong>盤面を確認・修正する</strong>
                <span>
                  石の配置を確認し、必要なら盤面をタップして修正します。
                </span>
              </div>
            </li>
            <li>
              <div>
                <strong>手番を指定する</strong>
                <span>黒番・白番を選び、検討を開始します。</span>
              </div>
            </li>
            <li>
              <div>
                <strong>Edaxで全合法手を解析する</strong>
                <span>
                  現在の局面にある合法手ごとのEdax評価を盤面上で比較できます。
                </span>
              </div>
            </li>
            <li>
              <div>
                <strong>保存して再確認する</strong>
                <span>盤面検討を保存し、後から開いて続きを確認できます。</span>
              </div>
            </li>
          </ol>

          <div className="study-screens">
            <figure className="study-screen feature-screen">
              <img
                src="/images/screen-position-import.jpg"
                alt="JSONから読み込んだ石の配置を盤面で確認し、手番を指定するちゃんりば画面"
                loading="lazy"
                decoding="async"
                width="691"
                height="1536"
              />
              <figcaption>
                <strong>取り込みと確認</strong>
                JSONの石配置を盤面で確認。必要な修正と手番指定をして検討へ進みます。
              </figcaption>
            </figure>
            <figure className="study-screen feature-screen">
              <img
                src="/images/screen-position-analysis.jpg"
                alt="任意盤面の合法手ごとにEdax評価を表示するちゃんりば画面"
                loading="lazy"
                decoding="async"
                width="691"
                height="1536"
              />
              <figcaption>
                <strong>全合法手の比較</strong>
                候補となる合法手と、それぞれのEdax評価を盤面上に表示します。
              </figcaption>
            </figure>
          </div>

          <aside className="external-ai-note" aria-labelledby="external-ai-title">
            <small>EXTERNAL AI EXAMPLE</small>
            <h4 id="external-ai-title">外部AIを使った活用例</h4>
            <p className="external-ai-flow">
              盤面画像 → 外部LLM等でJSON化 → ちゃんりばへ読み込み
            </p>
            <p>
              画像の読み取りとJSON化は外部サービス側で行う活用例です。ちゃんりばに画像認識機能が内蔵されているという案内ではありません。
            </p>
          </aside>
        </section>

        <section
          className="study-detail theory-exploration"
          aria-labelledby="theory-title"
        >
          <div className="study-copy feature-copy">
            <FeatureLabel number="B-3">THEORY EXPLORATION</FeatureLabel>
            <h3 id="theory-title">
              評価値の先まで、
              <br />
              局面を掘り下げる。
            </h3>
            <p className="study-lead">
              理論探求は、棋譜を順番に振り返るレビューとは別に、局面を自由に進めながら盤面の傾向を探る機能です。
            </p>
            <p>
              各合法手の上段にEdax評価、下段に選択中の理論指標を同時表示。Edaxの評価と盤面由来の数値を見比べながら、「なぜこの手が良いのか」を考える材料にできます。
            </p>
            <Tags items={tags.theory} />
          </div>

          <div className="metric-summary" aria-label="理論探求で扱う指標">
            <strong>現在扱う理論指標</strong>
            <div>
              <span>開放度</span>
              <span>相手モビリティ</span>
              <span>フロンティア石数</span>
              <span>潜在モビリティ</span>
            </div>
            <p>
              各指標は局面を見るための一つの観点です。指標だけで最善手が決まるものではなく、Edax評価と見比べながら局面の傾向を探ります。
            </p>
          </div>

          <div className="study-screens theory-screens">
            <figure className="study-screen feature-screen">
              <img
                src="/images/screen-theory-mobility.jpg"
                alt="合法手ごとに上段のEdax評価と下段の相手モビリティを表示する理論探求画面"
                loading="lazy"
                decoding="async"
                width="691"
                height="1536"
              />
              <figcaption>
                <strong>相手モビリティ</strong>
                着手後に相手が打てる場所の数を、Edax評価と同時に確認します。
              </figcaption>
            </figure>
            <figure className="study-screen feature-screen">
              <img
                src="/images/screen-theory-openness.jpg"
                alt="合法手ごとに上段のEdax評価と下段の開放度を表示する理論探求画面"
                loading="lazy"
                decoding="async"
                width="691"
                height="1536"
              />
              <figcaption>
                <strong>開放度</strong>
                反転した石の周囲に残る空きの数を、Edax評価と並べて確認します。
              </figcaption>
            </figure>
          </div>

          <p className="cache-note">
            解析済み局面の結果は効率よく再利用。前の局面へ戻ったときも、探求を続けやすくしています。
          </p>
        </section>

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

      <section className="audience section" aria-labelledby="audience-title"><div className="audience-inner"><div className="section-kicker"><span>03</span><i /> FOR PLAYERS</div><h2 id="audience-title">もっと強くなりたい<br /><em>人へ。</em></h2><div className="audience-list"><p>リバーシをもっと強くなりたい</p><p>対局後にきちんと振り返りたい</p><p>外部の盤面を取り込んで研究したい</p><p>Edax評価と理論指標を見比べたい</p><p>プレイヤー全体の傾向を知りたい</p><p>リアル大会を意識している</p><p>感覚だけでなくデータも使って研究したい</p></div></div></section>

      <section
        className="product-flow section"
        aria-labelledby="flow-title"
      >
        <div className="flow-copy">
          <div className="section-kicker">
            <span>04</span>
            <i /> ONE APP
          </div>
          <h2 id="flow-title">
            4つのタブで、
            <br />
            <em>つながる。</em>
          </h2>
          <p>
            対局、検討、設定、その他。現在のアプリは4つの入口から、対局と研究に必要な機能へ迷わず進めます。
          </p>
        </div>
        <div className="tab-map" aria-label="ちゃんりばの4つのタブ">
          <section>
            <small>01</small>
            <h3>対局</h3>
            <p>オンラインで対局・AIと対局・ふたりで対局</p>
          </section>
          <section>
            <small>02</small>
            <h3>検討</h3>
            <p>
              任意盤面から検討・理論探求・オンライン棋譜・オフライン棋譜
            </p>
          </section>
          <section>
            <small>03</small>
            <h3>設定</h3>
            <p>対局設定・検討設定・共通設定・研究参加・言語</p>
          </section>
          <section>
            <small>04</small>
            <h3>その他</h3>
            <p>アカウント・研究データについて・ちゃんりばについて</p>
          </section>
        </div>
      </section>

      <section className="final-cta section" id="start" aria-labelledby="start-title"><div className="cta-glow" aria-hidden="true" /><div className="section-kicker"><span>05</span><i /> START HERE</div><h2 id="start-title">その一局を、<br /><em>次の一手へ。</em></h2><p>ちゃんりばは、対局・検討・理論探求・傾向分析をひとつにつなぐリバーシアプリです。</p><a className="button button-primary" href="#top">ちゃんりばを始める <span aria-hidden="true">↗</span></a><small>サービスの公開準備中です。最新情報をお待ちください。</small></section>

      <footer className="site-footer"><div className="footer-brand"><img src="/images/app-icon.png" alt="" width="48" height="48" /><div><b>ちゃんりば</b><small>CHANRIVA</small></div></div><div className="footer-meta"><span>ちゃんとリバーシ</span><span>Shinp Studio</span></div><div className="footer-legal"><a href="/privacy">Privacy</a><a href="/account-deletion">Account deletion</a><span>© Shinp Studio</span></div></footer>
    </main>
  );
}
