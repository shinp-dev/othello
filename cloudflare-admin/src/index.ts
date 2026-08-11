interface Env { SUPABASE_URL: string; SUPABASE_SERVICE_ROLE_KEY: string; ADMIN_TOKEN: string; SUPABASE_VERIFICATION_BUCKET: string }

interface ExecutionContextLike { waitUntil(promise: Promise<unknown>): void }

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.headers.get("authorization") !== `Bearer ${env.ADMIN_TOKEN}`) return json({ error: "unauthorized" }, 401);
    const url = new URL(request.url);
    if (request.method !== "GET" && request.method !== "POST") return json({ error: "method not allowed" }, 405);
    if (url.pathname === "/admin/verification/pending" && request.method === "GET") return supabase(env, "/rest/v1/verification_submissions?status=eq.PENDING&select=*");
    if (url.pathname === "/admin/account-deletion/pending" && request.method === "GET") {
      return supabase(env, "/rest/v1/account_deletion_requests?status=in.(REQUESTED,PROCESSING)&select=user_id,requested_at,status&order=requested_at.asc");
    }
    const deletion = url.pathname.match(/^\/admin\/account-deletion\/([^/]+)\/process$/);
    if (deletion && request.method === "POST") return processAccountDeletion(env, deletion[1]);
    const action = url.pathname.match(/^\/admin\/verification\/([^/]+)\/(approve|reject)$/);
    if (action && request.method === "POST") {
      const decision = action[2] === "approve" ? "VERIFIED" : "REJECTED";
      const review = await supabase(env, "/rest/v1/rpc/review_verification_submission", {
        method: "POST",
        body: JSON.stringify({ p_submission_id: action[1], p_decision: decision }),
      });
      if (!review.ok) return review;
      const actualStatus = await review.json() as unknown;
      const cleanup = await supabase(env, "/rest/v1/rpc/get_verification_evidence_cleanup", {
        method: "POST",
        body: JSON.stringify({ p_submission_id: action[1] }),
      });
      const evidencePath = cleanup.ok ? await cleanup.json() as unknown : null;
      if (typeof evidencePath === "string" && evidencePath.length > 0 && await deleteEvidence(env, evidencePath)) {
        await supabase(env, "/rest/v1/rpc/mark_verification_evidence_deleted", {
          method: "POST",
          body: JSON.stringify({ p_submission_id: action[1] }),
        });
      }
      return json({ id: action[1], status: actualStatus });
    }
    return json({ error: "not found" }, 404);
  },
  scheduled(_controller: unknown, env: Env, context: ExecutionContextLike) {
    context.waitUntil(processPendingAccountDeletions(env));
  },
};

function supabase(env: Env, path: string, init: RequestInit = {}): Promise<Response> {
  return fetch(`${env.SUPABASE_URL}${path}`, {
    ...init,
    headers: { apikey: env.SUPABASE_SERVICE_ROLE_KEY, Authorization: `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`, "content-type": "application/json", ...(init.headers ?? {}) },
  });
}

async function deleteEvidence(env: Env, evidencePath: string): Promise<boolean> {
  const response = await fetch(`${env.SUPABASE_URL}/storage/v1/object/${env.SUPABASE_VERIFICATION_BUCKET}/${evidencePath}`, {
    method: "DELETE",
    headers: { apikey: env.SUPABASE_SERVICE_ROLE_KEY, Authorization: `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}` },
  });
  return response.ok || response.status === 404;
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
  const evidence = await supabase(env, "/rest/v1/rpc/get_account_deletion_evidence", {
    method: "POST",
    body: JSON.stringify({ p_user_id: userId }),
  });
  if (!evidence.ok) return evidence;
  const rawPaths = await evidence.json() as unknown;
  if (!Array.isArray(rawPaths) || rawPaths.some(path => typeof path !== "string" || !path.startsWith(`${userId}/`))) {
    return json({ error: "invalid evidence cleanup response" }, 502);
  }
  const paths = rawPaths as string[];
  for (let index = 0; index < paths.length; index += 1000) {
    const removed = await supabase(env, `/storage/v1/object/${env.SUPABASE_VERIFICATION_BUCKET}`, {
      method: "DELETE",
      body: JSON.stringify({ prefixes: paths.slice(index, index + 1000) }),
    });
    if (!removed.ok) return removed;
  }

  const prepared = await supabase(env, "/rest/v1/rpc/prepare_account_deletion", {
    method: "POST",
    body: JSON.stringify({ p_user_id: userId }),
  });
  if (!prepared.ok) return prepared;

  const authDeleted = await supabase(env, `/auth/v1/admin/users/${userId}`, { method: "DELETE" });
  if (!authDeleted.ok && authDeleted.status !== 404) return authDeleted;

  const completed = await supabase(env, "/rest/v1/rpc/complete_account_deletion", {
    method: "POST",
    body: JSON.stringify({ p_user_id: userId }),
  });
  if (!completed.ok) return completed;
  return json({ userId, status: "COMPLETED" });
}
