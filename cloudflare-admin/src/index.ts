import {
  RESEARCH_VALIDATOR_VERSION,
  extractResearchDecisions,
  validateResearchGame,
  type ResearchAggregationContributor,
} from "./research-validator.js";

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
    if (url.pathname === "/admin/research/validate" && request.method === "POST") {
      return json({ processed: await processResearchValidationBatch(env) });
    }
    if (url.pathname === "/admin/research/aggregate" && request.method === "POST") {
      return json(await processResearchAggregation(env));
    }
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
    context.waitUntil(runScheduledMaintenance(env));
  },
};

async function runScheduledMaintenance(env: Env): Promise<void> {
  const results = await Promise.allSettled([
    processPendingAccountDeletions(env),
    processResearchValidationBatch(env),
  ]);
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

interface ClaimedResearchGame {
  research_game_id: number;
  lease_token: string;
  canonical_moves: string;
  result: "BLACK_WIN" | "WHITE_WIN" | "DRAW";
  finish_reason: "NORMAL" | "RESIGNATION" | "TIMEOUT" | "DISCONNECT";
  final_position_hash: string;
  ruleset_version: number;
}

interface AggregationClaim {
  generation_id: number;
  lease_token: string;
  source_watermark: number;
  ruleset_version: number;
  normalization_version: number;
}

interface AggregationSource {
  research_game_id: number;
  canonical_moves: string;
  result: "BLACK_WIN" | "WHITE_WIN" | "DRAW";
  finish_reason: "NORMAL" | "RESIGNATION" | "TIMEOUT" | "DISCONNECT";
  final_position_hash: string;
  ruleset_version: number;
  contributors: Array<{
    research_subject_id: string;
    disc: "BLACK" | "WHITE";
    outcome: "WIN" | "DRAW" | "LOSS";
  }>;
}

async function processResearchValidationBatch(env: Env): Promise<number> {
  const claimed = await supabase(env, "/rest/v1/rpc/claim_research_validation_batch", {
    method: "POST",
    body: JSON.stringify({ p_limit: 10, p_lease_seconds: 300 }),
  });
  if (!claimed.ok) throw new Error(`research validation claim failed: ${claimed.status}`);
  const rows = await claimed.json() as ClaimedResearchGame[];
  let processed = 0;
  for (const row of rows) {
    try {
      const validation = validateResearchGame({
        canonicalMoves: row.canonical_moves,
        result: row.result,
        finishReason: row.finish_reason,
        finalPositionHash: row.final_position_hash,
        rulesetVersion: row.ruleset_version,
      });
      const completion = await supabase(env, "/rest/v1/rpc/complete_research_validation", {
        method: "POST",
        body: JSON.stringify({
          p_research_game_id: row.research_game_id,
          p_lease_token: row.lease_token,
          p_validator_version: RESEARCH_VALIDATOR_VERSION,
          p_accepted: validation.accepted,
          p_rejection_code: validation.accepted ? null : validation.rejectionCode,
          p_black_decision_count: validation.accepted ? validation.blackDecisionCount : null,
          p_white_decision_count: validation.accepted ? validation.whiteDecisionCount : null,
        }),
      });
      if (!completion.ok) {
        console.error(`research validation completion failed for game ${row.research_game_id}: ${completion.status}`);
        continue;
      }
      processed += 1;
    } catch {
      // One malformed/poison game must not prevent the rest of the claimed batch.
      const rejected = await supabase(env, "/rest/v1/rpc/complete_research_validation", {
        method: "POST",
        body: JSON.stringify({
          p_research_game_id: row.research_game_id,
          p_lease_token: row.lease_token,
          p_validator_version: RESEARCH_VALIDATOR_VERSION,
          p_accepted: false,
          p_rejection_code: "VALIDATOR_INTERNAL_ERROR",
          p_black_decision_count: null,
          p_white_decision_count: null,
        }),
      });
      if (rejected.ok) processed += 1;
      else console.error(`research validation rejection failed for game ${row.research_game_id}: ${rejected.status}`);
    }
  }
  return processed;
}

async function processResearchAggregation(env: Env): Promise<Record<string, unknown>> {
  const claimed = await supabase(env, "/rest/v1/rpc/claim_research_aggregation_build", {
    method: "POST",
    body: JSON.stringify({ p_lease_seconds: 900 }),
  });
  if (!claimed.ok) throw new Error(`research aggregation claim failed: ${claimed.status}`);
  const claims = await claimed.json() as AggregationClaim[];
  if (claims.length === 0) return { status: "BUSY", processed: 0 };
  const claim = claims[0];
  try {
    let afterGameId = 0;
    let processed = 0;
    while (afterGameId < claim.source_watermark) {
      const response = await supabase(env, "/rest/v1/rpc/get_research_aggregation_sources", {
        method: "POST",
        body: JSON.stringify({
          p_generation_id: claim.generation_id,
          p_lease_token: claim.lease_token,
          p_after_game_id: afterGameId,
          p_limit: 50,
        }),
      });
      if (!response.ok) throw new Error(`research aggregation source page failed: ${response.status}`);
      const sources = await response.json() as AggregationSource[];
      if (sources.length === 0) break;
      for (const source of sources) {
        const contributors: ResearchAggregationContributor[] = source.contributors.map(value => ({
          researchSubjectId: value.research_subject_id,
          disc: value.disc,
          outcome: value.outcome,
        }));
        const decisions = extractResearchDecisions({
          canonicalMoves: source.canonical_moves,
          result: source.result,
          finishReason: source.finish_reason,
          finalPositionHash: source.final_position_hash,
          rulesetVersion: source.ruleset_version,
        }, contributors);
        const appended = await supabase(env, "/rest/v1/rpc/append_research_aggregation_game", {
          method: "POST",
          body: JSON.stringify({
            p_generation_id: claim.generation_id,
            p_lease_token: claim.lease_token,
            p_research_game_id: source.research_game_id,
            p_decisions: decisions,
          }),
        });
        if (!appended.ok) throw new Error(`research aggregation append failed: ${appended.status}`);
        afterGameId = source.research_game_id;
        processed += 1;
      }
    }
    const published = await supabase(env, "/rest/v1/rpc/publish_research_aggregation", {
      method: "POST",
      body: JSON.stringify({ p_generation_id: claim.generation_id, p_lease_token: claim.lease_token }),
    });
    if (!published.ok) throw new Error(`research aggregation publish failed: ${published.status}`);
    return { status: await published.json() as unknown, generationId: claim.generation_id, processed };
  } catch (error) {
    await supabase(env, "/rest/v1/rpc/fail_research_aggregation", {
      method: "POST",
      body: JSON.stringify({
        p_generation_id: claim.generation_id,
        p_lease_token: claim.lease_token,
        p_failure_code: "WORKER_BUILD_FAILED",
      }),
    });
    throw error;
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
