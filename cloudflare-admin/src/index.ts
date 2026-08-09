interface Env { SUPABASE_URL: string; SUPABASE_SERVICE_ROLE_KEY: string; ADMIN_TOKEN: string }

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    if (request.headers.get("authorization") !== `Bearer ${env.ADMIN_TOKEN}`) return json({ error: "unauthorized" }, 401);
    const url = new URL(request.url);
    if (request.method !== "GET" && request.method !== "POST") return json({ error: "method not allowed" }, 405);
    if (url.pathname === "/admin/verification/pending" && request.method === "GET") return supabase(env, "/rest/v1/verification_submissions?status=eq.PENDING&select=*");
    const action = url.pathname.match(/^\/admin\/verification\/([^/]+)\/(approve|reject)$/);
    if (action && request.method === "POST") {
      const status = action[2] === "approve" ? "VERIFIED" : "REJECTED";
      return supabase(env, `/rest/v1/verification_submissions?id=eq.${action[1]}`, { method: "PATCH", body: JSON.stringify({ status, reviewed_at: new Date().toISOString() }) });
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
