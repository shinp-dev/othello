import assert from "node:assert/strict";
import { access, readFile, readdir } from "node:fs/promises";
import test from "node:test";

const templateRoot = new URL("../", import.meta.url);

async function render(path = "/") {
  const workerUrl = new URL("../dist/server/index.js", import.meta.url);
  workerUrl.searchParams.set("test", `${process.pid}-${Date.now()}`);
  const { default: worker } = await import(workerUrl.href);

  return worker.fetch(
    new Request(`http://localhost${path}`, { headers: { accept: "text/html" } }),
    { ASSETS: { fetch: async () => new Response("Not found", { status: 404 }) } },
    { waitUntil() {}, passThroughOnException() {} },
  );
}

test("renders the external account deletion request flow without app redirect", async () => {
  const response = await render("/account-deletion");
  assert.equal(response.status, 200);
  const html = await response.text();
  assert.match(html, /アカウント削除/);
  assert.match(html, /削除リクエストを開始/);
  assert.match(html, /登録済みのちゃんりばアカウント/);
  assert.match(html, /削除リクエストの受付・処理状態/);
  assert.doesNotMatch(html, /公開前ステータス|Web削除リクエスト受付は未実装/);
  assert.doesNotMatch(html, /intent:|android-app:|market:\/\//i);
});

test("renders privacy policy with the same deletion URL", async () => {
  const response = await render("/privacy");
  assert.equal(response.status, 200);
  const html = await response.text();
  assert.match(html, /アカウント削除ページ/);
  assert.doesNotMatch(html, /ブラウザ単独の削除リクエスト受付は現在準備中/);
});

test("renders the email confirmation completion page without an app redirect", async () => {
  const response = await render("/signup-complete");
  assert.equal(response.status, 200);
  const html = await response.text();
  assert.match(html, /CHANRIVA \/ SIGN-UP/);
  assert.match(html, /メールアドレスの確認が完了しました/);
  assert.match(html, /アプリに戻ってログインしてください/);
  assert.doesNotMatch(html, /intent:|android-app:|market:\/\//i);
});

test("keeps the deletion API same-origin and fail-closed without its runtime secret", async () => {
  const workerUrl = new URL("../dist/server/index.js", import.meta.url);
  workerUrl.searchParams.set("api-test", `${process.pid}-${Date.now()}`);
  const { default: worker } = await import(workerUrl.href);
  const env = { ASSETS: { fetch: async () => new Response("Not found", { status: 404 }) } };
  const ctx = { waitUntil() {}, passThroughOnException() {} };

  const getResponse = await worker.fetch(new Request("http://localhost/api/account-deletion/start"), env, ctx);
  assert.equal(getResponse.status, 405);

  const crossOriginResponse = await worker.fetch(new Request("http://localhost/api/account-deletion/start", {
    method: "POST",
    headers: { "content-type": "application/json", Origin: "https://attacker.example" },
    body: JSON.stringify({ email: "someone@example.com", password: "not-a-real-password" }),
  }), env, ctx);
  assert.equal(crossOriginResponse.status, 403);

  const missingSecretResponse = await worker.fetch(new Request("http://localhost/api/account-deletion/start", {
    method: "POST",
    headers: { "content-type": "application/json", Origin: "https://chanriva.shinp-studio.com" },
    body: JSON.stringify({ email: "someone@example.com", password: "not-a-real-password" }),
  }), env, ctx);
  assert.equal(missingSecretResponse.status, 503);
});

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
