import assert from "node:assert/strict";
import { access, readFile, readdir } from "node:fs/promises";
import test from "node:test";

const templateRoot = new URL("../", import.meta.url);

async function render() {
  const workerUrl = new URL("../dist/server/index.js", import.meta.url);
  workerUrl.searchParams.set("test", `${process.pid}-${Date.now()}`);
  const { default: worker } = await import(workerUrl.href);

  return worker.fetch(
    new Request("http://localhost/", { headers: { accept: "text/html" } }),
    { ASSETS: { fetch: async () => new Response("Not found", { status: 404 }) } },
    { waitUntil() {}, passThroughOnException() {} },
  );
}

test("server-renders the Chanriva landing page", async () => {
  const response = await render();
  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type") ?? "", /^text\/html\b/i);

  const html = await response.text();
  assert.match(html, /<title>ちゃんりば \| CHANRIVA/);
  assert.match(html, /打って終わりに/);
  assert.match(html, /強くなるための、/);
  assert.match(html, /screen-online-match\.png/);
  assert.match(html, /screen-review\.png/);
  assert.match(html, /ANALYSIS SETUP/);
  assert.match(html, /GUIDED SETUP/);
  assert.match(html, /CUSTOM DATA/);
  assert.match(html, /Edax用の評価データやオープニングブック/);
  assert.match(html, /screen-analysis\.png/);
  assert.match(html, /screen-login\.png/);
  assert.match(html, /COMING SOON/);
  assert.match(html, /レート帯別分析/);
  assert.doesNotMatch(html, /Your site is taking shape|Building your site/);
});

test("keeps metadata, responsive styling, and current assets in place", async () => {
  const [css, page, layout, files] = await Promise.all([
    readFile(new URL("../app/globals.css", import.meta.url), "utf8"),
    readFile(new URL("../app/page.tsx", import.meta.url), "utf8"),
    readFile(new URL("../app/layout.tsx", import.meta.url), "utf8"),
    readdir(new URL("public/images", templateRoot)),
  ]);

  assert.match(layout, /canonical: "https:\/\/chanriva\.shinp-studio\.com\//);
  assert.match(layout, /app-icon\.png/);
  assert.match(page, /開発中の画面です。表示内容は変更される場合があります。/);
  assert.match(page, /プレイヤー全体の傾向/);
  assert.match(page, /取得・設定方法を、アプリ内で案内します。/);
  assert.match(page, /自分が所有するものへ置き換えて利用できます。/);
  assert.doesNotMatch(page, /ちゃんりばから.*ダウンロード|評価データを配布|すべて同梱/);
  assert.match(page, /COMING SOON/);
  assert.match(css, /@media \(max-width:820px\)/);
  assert.match(css, /@media \(max-width:390px\)/);
  assert.match(css, /@media \(max-width:320px\)/);
  assert.match(css, /prefers-reduced-motion/);

  for (const file of [
    "app-icon.png",
    "hero-key-visual.png",
    "screen-online-match.png",
    "screen-review.png",
    "screen-analysis.png",
    "screen-login.png",
  ]) {
    assert.ok(files.includes(file), `${file} should be published`);
    await access(new URL(`public/images/${file}`, templateRoot));
  }
});
