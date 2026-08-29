import assert from "node:assert/strict";
import { access, readFile, readdir } from "node:fs/promises";
import test from "node:test";
import {
  resolveSignupConfirmationState,
  SIGNUP_CONFIRMATION_MESSAGE,
  SIGNUP_CONFIRMATION_STATE,
} from "../app/signup-complete/confirmation-state.js";

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

async function loadWorker(testName) {
  const workerUrl = new URL("../dist/server/index.js", import.meta.url);
  workerUrl.searchParams.set(testName, `${process.pid}-${Date.now()}`);
  return (await import(workerUrl.href)).default;
}

const testContext = { waitUntil() {}, passThroughOnException() {} };
const testAssets = { fetch: async () => new Response("Not found", { status: 404 }) };

test("serves the public Android app config with a strict no-store JSON contract", async () => {
  const worker = await loadWorker("app-config-test");
  const response = await worker.fetch(
    new Request("http://localhost/api/app-config"),
    { ASSETS: testAssets, ANDROID_MIN_VERSION_CODE: "1" },
    testContext,
  );

  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { android_min_version_code: 1 });
  assert.match(response.headers.get("content-type") ?? "", /^application\/json\b/i);
  assert.equal(response.headers.get("cache-control"), "no-store");
  assert.equal(response.headers.get("referrer-policy"), "no-referrer");
  assert.equal(response.headers.get("x-content-type-options"), "nosniff");
});

test("rejects invalid app config and non-GET methods", async () => {
  const worker = await loadWorker("invalid-app-config-test");
  for (const configuredValue of [undefined, "", "0", "-1", "1.5", "not-a-number", "9007199254740992"]) {
    const response = await worker.fetch(
      new Request("http://localhost/api/app-config"),
      { ASSETS: testAssets, ANDROID_MIN_VERSION_CODE: configuredValue },
      testContext,
    );
    assert.equal(response.status, 503);
    assert.deepEqual(await response.json(), { error: "service unavailable" });
    assert.equal(response.headers.get("cache-control"), "no-store");
  }

  const postResponse = await worker.fetch(
    new Request("http://localhost/api/app-config", { method: "POST" }),
    { ASSETS: testAssets, ANDROID_MIN_VERSION_CODE: "1" },
    testContext,
  );
  assert.equal(postResponse.status, 405);
  assert.equal(postResponse.headers.get("allow"), "GET");
  assert.match(postResponse.headers.get("content-type") ?? "", /^application\/json\b/i);
});

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
  assert.match(html, /13歳以上の利用者を対象/);
  assert.match(html, /shinpstudio@gmail\.com/);
  assert.doesNotMatch(html, /ブラウザ単独の削除リクエスト受付は現在準備中/);
});

test("renders the email confirmation completion page without an app redirect", async () => {
  const response = await render("/signup-complete");
  assert.equal(response.status, 200);
  const html = await response.text();
  assert.match(html, /CHANRIVA \/ SIGN-UP/);
  assert.match(html, /メールアドレスの確認結果を確認しています/);
  assert.doesNotMatch(html, /intent:|android-app:|market:\/\//i);
});

test("distinguishes successful, expired, and failed email confirmation redirects", () => {
  assert.equal(resolveSignupConfirmationState(), SIGNUP_CONFIRMATION_STATE.SUCCESS);
  assert.equal(
    resolveSignupConfirmationState("#error=access_denied&error_code=otp_expired&error_description=Email+link+has+expired"),
    SIGNUP_CONFIRMATION_STATE.EXPIRED,
  );
  assert.equal(
    resolveSignupConfirmationState("", "?error=access_denied&error_code=validation_failed"),
    SIGNUP_CONFIRMATION_STATE.FAILURE,
  );
  assert.equal(SIGNUP_CONFIRMATION_MESSAGE.success.title, "登録完了");
  assert.match(SIGNUP_CONFIRMATION_MESSAGE.success.action, /アプリに戻ってログイン/);
  assert.match(SIGNUP_CONFIRMATION_MESSAGE.expired.action, /確認メールをもう一度送信/);
  assert.match(SIGNUP_CONFIRMATION_MESSAGE.failure.title, /確認できませんでした/);
});

test("renders the web deletion email confirmation page without an app redirect", async () => {
  const response = await render("/account-deletion/confirm");
  assert.equal(response.status, 200);
  const html = await response.text();
  assert.match(html, /削除リクエストの確認/);
  assert.match(html, /メール内の確認リンク/);
  assert.doesNotMatch(html, /intent:|android-app:|market:\/\//i);
});

test("renders the password reset page without asking for the old password", async () => {
  const response = await render("/reset-password");
  assert.equal(response.status, 200);
  const html = await response.text();
  assert.match(html, /パスワード再設定/);
  assert.match(html, /以前のパスワードを表示・復元することはありません/);
  assert.match(html, /新しいパスワード/);
  assert.doesNotMatch(html, /旧パスワード.*入力|現在のパスワード.*入力/);
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

  const emailStartResponse = await worker.fetch(new Request("http://localhost/api/account-deletion/email/start", {
    method: "POST",
    headers: { "content-type": "application/json", Origin: "https://chanriva.shinp-studio.com" },
    body: JSON.stringify({ email: "someone@example.com" }),
  }), env, ctx);
  assert.equal(emailStartResponse.status, 503);

  const emailConfirmResponse = await worker.fetch(new Request("http://localhost/api/account-deletion/email/confirm", {
    method: "POST",
    headers: { "content-type": "application/json", Origin: "https://chanriva.shinp-studio.com" },
    body: JSON.stringify({ access_token: "not-a-real-token" }),
  }), env, ctx);
  assert.equal(emailConfirmResponse.status, 503);
});

test("keeps the password reset API same-origin and fail-closed without its runtime secret", async () => {
  const workerUrl = new URL("../dist/server/index.js", import.meta.url);
  workerUrl.searchParams.set("password-api-test", `${process.pid}-${Date.now()}`);
  const { default: worker } = await import(workerUrl.href);
  const env = { ASSETS: { fetch: async () => new Response("Not found", { status: 404 }) } };
  const ctx = { waitUntil() {}, passThroughOnException() {} };

  const getResponse = await worker.fetch(new Request("http://localhost/api/password-reset/complete"), env, ctx);
  assert.equal(getResponse.status, 405);

  const crossOriginResponse = await worker.fetch(new Request("http://localhost/api/password-reset/complete", {
    method: "POST",
    headers: { "content-type": "application/json", Origin: "https://attacker.example" },
    body: JSON.stringify({ access_token: "not-a-real-token", password: "not-a-real-password" }),
  }), env, ctx);
  assert.equal(crossOriginResponse.status, 403);

  const missingSecretResponse = await worker.fetch(new Request("http://localhost/api/password-reset/complete", {
    method: "POST",
    headers: { "content-type": "application/json", Origin: "https://chanriva.shinp-studio.com" },
    body: JSON.stringify({ access_token: "not-a-real-token", password: "not-a-real-password" }),
  }), env, ctx);
  assert.equal(missingSecretResponse.status, 503);
});

test("keeps passwordless deletion generic and reuses the authenticated deletion RPC", async t => {
  const workerUrl = new URL("../dist/server/index.js", import.meta.url);
  workerUrl.searchParams.set("email-deletion-test", `${process.pid}-${Date.now()}`);
  const { default: worker } = await import(workerUrl.href);
  const env = {
    ASSETS: { fetch: async () => new Response("Not found", { status: 404 }) },
    SUPABASE_URL: "https://example.supabase.co",
    SUPABASE_ANON_KEY: "test-anon-key",
  };
  const ctx = { waitUntil() {}, passThroughOnException() {} };
  const originalFetch = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (url, init = {}) => {
    calls.push({ url: String(url), body: String(init.body ?? ""), headers: init.headers ?? {} });
    if (String(url).endsWith("/auth/v1/otp")) return new Response("not found", { status: 400 });
    return Response.json({});
  };
  t.after(() => { globalThis.fetch = originalFetch; });

  const start = await worker.fetch(new Request("http://localhost/api/account-deletion/email/start", {
    method: "POST",
    headers: { "content-type": "application/json", Origin: "https://chanriva.shinp-studio.com" },
    body: JSON.stringify({ email: "unknown@example.com" }),
  }), env, ctx);
  assert.equal(start.status, 200);
  assert.match(await start.text(), /登録済みの場合は確認メールを送信しました/);
  assert.match(calls[0].body, /"create_user":false/);
  assert.ok(calls[0].body.includes("account-deletion/confirm"));

  calls.length = 0;
  const confirm = await worker.fetch(new Request("http://localhost/api/account-deletion/email/confirm", {
    method: "POST",
    headers: { "content-type": "application/json", Origin: "https://chanriva.shinp-studio.com" },
    body: JSON.stringify({ access_token: "a".repeat(32) }),
  }), env, ctx);
  assert.equal(confirm.status, 200);
  const confirmBody = await confirm.text();
  assert.doesNotMatch(confirmBody, /a{32}/);
  assert.equal(calls[0].url, `${env.SUPABASE_URL}/rest/v1/rpc/request_account_deletion`);
  assert.equal(calls[0].body, "{}");
});

test("server-renders the Chanriva landing page", async () => {
  const response = await render();
  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type") ?? "", /^text\/html\b/i);

  const html = await response.text();
  assert.match(html, /<title>ちゃんりば \| CHANRIVA/);
  assert.match(html, /対局を、/);
  assert.match(html, /もっと深く/);
  assert.match(html, /対局して終わりではなく/);
  assert.match(html, /screen-match\.webp/);
  assert.match(html, /screen-review\.webp/);
  assert.match(html, /screen-position-import\.webp/);
  assert.match(html, /screen-theory\.webp/);
  assert.match(html, /screen-trend-analysis\.webp/);
  assert.match(html, /全合法手を比較する/);
  assert.match(html, /画像の読み取りとJSON化は外部AIサービス側/);
  assert.match(html, /上段にEdax評価、下段に盤面由来の理論指標/);
  assert.match(html, /フロンティア石数/);
  assert.match(html, /潜在モビリティ/);
  assert.match(html, /ADVANCED RESEARCH/);
  assert.match(html, /実際の対局データ/);
  assert.match(html, /サービスの公開準備中/);
  assert.doesNotMatch(html, /screen-login\.png/);
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
  assert.match(page, /対局 → 検討 → 次の対局/);
  assert.match(page, /オンライン対局、AI対局/);
  assert.match(page, /外部AIサービス/);
  assert.match(page, /指標単体で最善手を決めるものではありません/);
  assert.match(page, /実際の対局データ/);
  assert.doesNotMatch(page, /ちゃんりばが画像を自動認識|画像を自動でJSON化/);
  assert.doesNotMatch(page, /screen-login\.png/);
  assert.match(css, /@media \(max-width:820px\)/);
  assert.match(css, /@media \(max-width:390px\)/);
  assert.match(css, /@media \(max-width:320px\)/);
  assert.match(css, /prefers-reduced-motion/);
  assert.match(css, /aspect-ratio:691\/1536/);
  assert.match(css, /overflow-x:hidden/);

  for (const file of [
    "app-icon.png",
    "hero-key-visual.png",
    "screen-match.webp",
    "screen-review.webp",
    "screen-position-import.webp",
    "screen-theory.webp",
    "screen-trend-analysis.webp",
  ]) {
    assert.ok(files.includes(file), `${file} should be published`);
    await access(new URL(`public/images/${file}`, templateRoot));
  }
});
