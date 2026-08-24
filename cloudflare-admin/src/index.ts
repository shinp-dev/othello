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
  const results = await Promise.allSettled([
    queueExpiredAccountDeletions(env).then(() => processPendingAccountDeletions(env)),
    runReleaseMatchMaintenance(env),
    runLegacyMatchMaintenance(env),
  ]);
  const failures = results.flatMap((result, index) => result.status === "rejected"
    ? [{ index, reason: result.reason }]
    : []);
  failures.forEach(({ index, reason }) => {
    const detail = reason instanceof Error ? reason.message : String(reason);
    console.error(`scheduled maintenance task ${index} failed: ${detail}`);
  });
  if (failures.length > 0) throw new AggregateError(failures.map(({ reason }) => reason), "scheduled maintenance failed");
}

/**
 * Terminalizes expired v2 matches before retention cleanup. The migration and this
 * caller ship together, but production deployment remains a separate coordinated cutover.
 */
async function runReleaseMatchMaintenance(env: Env): Promise<void> {
  const response = await supabase(env, "/rest/v1/rpc/run_match_maintenance_v2", {
    method: "POST",
    body: JSON.stringify({ p_limit: 100 }),
  });
  if (!response.ok) throw new Error(`match maintenance failed: ${response.status}`);
  const payload = await response.json() as unknown;
  const row = Array.isArray(payload) ? payload[0] : payload;
  if (!isMaintenanceResult(row)) throw new Error("match maintenance returned an invalid response");
  console.log(
    `release match maintenance: terminalized=${row.terminalized_matches}, ` +
    `signals=${row.deleted_signals}, queue=${row.deleted_queue_rows}`,
  );
  if (row.terminalized_matches >= 100) console.warn("release match maintenance reached its batch limit; backlog may remain");
}

/** Bounded protocol-1 coexistence cleanup; no legacy client RPC is changed. */
async function runLegacyMatchMaintenance(env: Env): Promise<void> {
  const response = await supabase(env, "/rest/v1/rpc/run_legacy_match_maintenance_v1", {
    method: "POST",
    body: JSON.stringify({ p_limit: 100 }),
  });
  if (!response.ok) throw new Error(`legacy match maintenance failed: ${response.status}`);
  const payload = await response.json() as unknown;
  const row = Array.isArray(payload) ? payload[0] : payload;
  if (!isMaintenanceResult(row)) throw new Error("legacy match maintenance returned an invalid response");
  console.log(
    `legacy match maintenance: terminalized=${row.terminalized_matches}, ` +
    `signals=${row.deleted_signals}, queue=${row.deleted_queue_rows}`,
  );
  if (row.terminalized_matches >= 100) console.warn("legacy match maintenance reached its batch limit; backlog may remain");
}

interface MaintenanceResult {
  terminalized_matches: number;
  deleted_signals: number;
  deleted_queue_rows: number;
}

function isMaintenanceResult(value: unknown): value is MaintenanceResult {
  if (typeof value !== "object" || value === null) return false;
  const row = value as Record<string, unknown>;
  return ["terminalized_matches", "deleted_signals", "deleted_queue_rows"]
    .every(key => Number.isInteger(row[key]) && (row[key] as number) >= 0);
}

async function queueExpiredAccountDeletions(env: Env): Promise<void> {
  const response = await supabase(env, "/rest/v1/rpc/queue_expired_account_deletions", {
    method: "POST",
    body: "{}",
  });
  if (!response.ok) throw new Error(`expired account deletion queue failed: ${response.status}`);
}

async function supabase(env: Env, path: string, init: RequestInit = {}): Promise<Response> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort("Supabase request timed out"), SUPABASE_TIMEOUT_MILLIS);
  try {
    return await fetch(`${env.SUPABASE_URL}${path}`, {
      ...init,
      signal: init.signal ?? controller.signal,
      headers: { apikey: env.SUPABASE_SERVICE_ROLE_KEY, Authorization: `Bearer ${env.SUPABASE_SERVICE_ROLE_KEY}`, "content-type": "application/json", ...(init.headers ?? {}) },
    });
  } finally {
    clearTimeout(timer);
  }
}

const SUPABASE_TIMEOUT_MILLIS = 10_000;

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
