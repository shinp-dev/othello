interface Env { SUPABASE_URL: string; SUPABASE_SERVICE_ROLE_KEY: string; ADMIN_TOKEN: string; SUPABASE_VERIFICATION_BUCKET: string }

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.headers.get("authorization") !== `Bearer ${env.ADMIN_TOKEN}`) return json({ error: "unauthorized" }, 401);
    const url = new URL(request.url);
    if (request.method !== "GET" && request.method !== "POST") return json({ error: "method not allowed" }, 405);
    if (url.pathname === "/admin/verification/pending" && request.method === "GET") return supabase(env, "/rest/v1/verification_submissions?status=eq.PENDING&select=*");
    const action = url.pathname.match(/^\/admin\/verification\/([^/]+)\/(approve|reject)$/);
    if (action && request.method === "POST") {
      const decision = action[2] === "approve" ? "VERIFIED" : "REJECTED";
      const review = await supabase(env, "/rest/v1/rpc/review_verification_submission", {
        method: "POST",
        body: JSON.stringify({ p_submission_id: action[1], p_decision: decision }),
      });
      if (!review.ok) return review;
      const evidencePath = await review.json() as unknown;
      if (typeof evidencePath === "string" && evidencePath.length > 0 && await deleteEvidence(env, evidencePath)) {
        await supabase(env, "/rest/v1/rpc/mark_verification_evidence_deleted", {
          method: "POST",
          body: JSON.stringify({ p_submission_id: action[1] }),
        });
      }
      return json({ id: action[1], status: decision });
    }
    return json({ error: "not found" }, 404);
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
