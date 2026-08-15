interface Env { SUPABASE_URL: string; SUPABASE_SERVICE_ROLE_KEY: string; ADMIN_TOKEN: string }

interface ExecutionContextLike { waitUntil(promise: Promise<unknown>): void }

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (!env.ADMIN_TOKEN || request.headers.get("authorization") !== `Bearer ${env.ADMIN_TOKEN}`) return json({ error: "unauthorized" }, 401);
    const url = new URL(request.url);
    if (request.method !== "GET" && request.method !== "POST") return json({ error: "method not allowed" }, 405);
    if (url.pathname === "/admin/account-deletion/pending" && request.method === "GET") {
      return supabase(env, "/rest/v1/account_deletion_requests?status=in.(REQUESTED,PROCESSING)&select=user_id,requested_at,status&order=requested_at.asc");
    }
    const deletion = url.pathname.match(/^\/admin\/account-deletion\/([^/]+)\/process$/);
    if (deletion && request.method === "POST") return processAccountDeletion(env, deletion[1]);
    return json({ error: "not found" }, 404);
  },
  scheduled(_controller: unknown, env: Env, context: ExecutionContextLike) {
    context.waitUntil(runScheduledMaintenance(env));
  },
};

async function runScheduledMaintenance(env: Env): Promise<void> {
  const results = await Promise.allSettled([processPendingAccountDeletions(env)]);
  results.forEach((result, index) => {
    if (result.status === "rejected") console.error(`scheduled maintenance task ${index} failed`);
  });
}

function supabase(env: Env, path: string, init: RequestInit = {}): Promise<Response> {
  return fetch(`${env.SUPABASE_URL}${path}`, {
    ...init,
    headers: { apikey: env.SUPABASE_SERVICE_ROLE_KEY, Authorization: `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`, "content-type": "application/json", ...(init.headers ?? {}) },
  });
}

async function processPendingAccountDeletions(env: Env): Promise<void> {
  const response = await supabase(
    env,
    "/rest/v1/account_deletion_requests?status=in.(REQUESTED,PROCESSING)&select=user_id&order=requested_at.asc&limit=50",
  );
  if (!response.ok) throw new Error(`account deletion list failed: ${response.status}`);
  const rows = await response.json() as Array<{ user_id?: unknown }>;
  for (const row of rows) {
    if (typeof row.user_id !== "string") continue;
    const result = await processAccountDeletion(env, row.user_id);
    if (!result.ok) console.error(`account deletion failed for ${row.user_id}: ${result.status}`);
  }
}

async function processAccountDeletion(env: Env, userId: string): Promise<Response> {
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(userId)) {
    return json({ error: "invalid user id" }, 400);
  }
  const prepared = await supabase(env, "/rest/v1/rpc/prepare_account_deletion", {
    method: "POST",
    body: JSON.stringify({ p_user_id: userId }),
  });
  if (!prepared.ok) return prepared;

  // The account link must be removed before Auth deletion. This call is
  // service-only and idempotent so a worker crash can safely retry it.
  const unlinked = await supabase(env, "/rest/v1/rpc/unlink_research_subject", {
    method: "POST",
    body: JSON.stringify({ p_user_id: userId }),
  });
  if (!unlinked.ok) return unlinked;

  const authDeleted = await supabase(env, `/auth/v1/admin/users/${userId}`, { method: "DELETE" });
  if (!authDeleted.ok && authDeleted.status !== 404) return authDeleted;

  const completed = await supabase(env, "/rest/v1/rpc/complete_account_deletion", {
    method: "POST",
    body: JSON.stringify({ p_user_id: userId }),
  });
  if (!completed.ok) return completed;
  return json({ userId, status: "COMPLETED" });
}
