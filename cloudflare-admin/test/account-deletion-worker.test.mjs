import assert from "node:assert/strict";
import test from "node:test";
import worker from "../dist/index.js";

const userId = "11111111-1111-4111-8111-111111111111";
const env = {
  SUPABASE_URL: "https://example.supabase.co",
  SUPABASE_SERVICE_ROLE_KEY: "test-service-role",
  ADMIN_TOKEN: "test-admin-token",
};

test("account deletion no longer depends on retired verification storage", async t => {
  const originalFetch = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (url, init = {}) => {
    calls.push({ url: String(url), method: init.method ?? "GET" });
    if (String(url).includes("/prepare_account_deletion")) return Response.json("PROCESSING");
    if (String(url).includes("/unlink_research_subject")) return Response.json("UNLINKED");
    if (String(url).includes("/auth/v1/admin/users/")) return Response.json({});
    if (String(url).includes("/complete_account_deletion")) return Response.json("COMPLETED");
    return Response.json({ error: "unexpected request" }, { status: 500 });
  };
  t.after(() => { globalThis.fetch = originalFetch; });

  const request = new Request(`https://admin.example/admin/account-deletion/${userId}/process`, {
    method: "POST",
    headers: { authorization: `Bearer ${env.ADMIN_TOKEN}` },
  });
  const response = await worker.fetch(request, env);

  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { userId, status: "COMPLETED" });
  assert.deepEqual(calls.map(call => call.url), [
    `${env.SUPABASE_URL}/rest/v1/rpc/prepare_account_deletion`,
    `${env.SUPABASE_URL}/rest/v1/rpc/unlink_research_subject`,
    `${env.SUPABASE_URL}/auth/v1/admin/users/${userId}`,
    `${env.SUPABASE_URL}/rest/v1/rpc/complete_account_deletion`,
  ]);
  assert.ok(calls.every(call => !call.url.includes("verification") && !call.url.includes("/storage/")));
});

test("retired verification admin endpoints are absent", async () => {
  const response = await worker.fetch(new Request("https://admin.example/admin/verification/pending", {
    headers: { authorization: `Bearer ${env.ADMIN_TOKEN}` },
  }), env);
  assert.equal(response.status, 404);
});
