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
    calls.push({ url: String(url), method: init.method ?? "GET", body: init.body });
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

test("scheduled maintenance queues expired accounts before processing", async t => {
  const originalFetch = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (url, init = {}) => {
    calls.push({ url: String(url), method: init.method ?? "GET", body: init.body });
    if (String(url).includes("queue_expired_account_deletions")) return Response.json(1);
    if (String(url).includes("run_match_maintenance_v2")) return Response.json([{
      terminalized_matches: 0,
      deleted_signals: 0,
      deleted_queue_rows: 0,
    }]);
    if (String(url).includes("account_deletion_requests?")) return Response.json([{ user_id: userId }]);
    if (String(url).includes("/prepare_account_deletion")) return Response.json("PROCESSING");
    if (String(url).includes("/unlink_research_subject")) return Response.json("UNLINKED");
    if (String(url).includes("/auth/v1/admin/users/")) return Response.json({});
    if (String(url).includes("/complete_account_deletion")) return Response.json("COMPLETED");
    return Response.json({ error: "unexpected request" }, { status: 500 });
  };
  t.after(() => { globalThis.fetch = originalFetch; });

  let scheduledWork;
  worker.scheduled({}, env, { waitUntil(promise) { scheduledWork = promise; } });
  await scheduledWork;

  assert.deepEqual(calls.map(call => call.url), [
    `${env.SUPABASE_URL}/rest/v1/rpc/queue_expired_account_deletions`,
    `${env.SUPABASE_URL}/rest/v1/rpc/run_match_maintenance_v2`,
    `${env.SUPABASE_URL}/rest/v1/account_deletion_requests?status=in.(REQUESTED,PROCESSING)&select=user_id&order=requested_at.asc&limit=50`,
    `${env.SUPABASE_URL}/rest/v1/rpc/prepare_account_deletion`,
    `${env.SUPABASE_URL}/rest/v1/rpc/unlink_research_subject`,
    `${env.SUPABASE_URL}/auth/v1/admin/users/${userId}`,
    `${env.SUPABASE_URL}/rest/v1/rpc/complete_account_deletion`,
  ]);
  const matchMaintenance = calls.find(call => call.url.includes("run_match_maintenance_v2"));
  assert.equal(matchMaintenance.method, "POST");
  assert.deepEqual(JSON.parse(matchMaintenance.body), { p_limit: 100 });
});

test("scheduled tasks are isolated but any maintenance failure rejects the cron work", async t => {
  const originalFetch = globalThis.fetch;
  const originalError = console.error;
  const calls = [];
  globalThis.fetch = async (url) => {
    calls.push(String(url));
    if (String(url).includes("run_match_maintenance_v2")) return Response.json({ error: "failed" }, { status: 503 });
    if (String(url).includes("queue_expired_account_deletions")) return Response.json(0);
    if (String(url).includes("account_deletion_requests?")) return Response.json([]);
    return Response.json({ error: "unexpected request" }, { status: 500 });
  };
  console.error = () => {};
  t.after(() => {
    globalThis.fetch = originalFetch;
    console.error = originalError;
  });

  let scheduledWork;
  worker.scheduled({}, env, { waitUntil(promise) { scheduledWork = promise; } });
  await assert.rejects(scheduledWork, /scheduled maintenance failed/);

  assert.ok(calls.some(url => url.includes("run_match_maintenance_v2")));
  assert.ok(calls.some(url => url.includes("account_deletion_requests?")));
});
