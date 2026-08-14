/** Cloudflare Worker entry point for the vinext-starter template. */
import { handleImageOptimization, DEFAULT_DEVICE_SIZES, DEFAULT_IMAGE_SIZES } from "vinext/server/image-optimization";
import handler from "vinext/server/app-router-entry";

interface Env {
  ASSETS: Fetcher;
  DB: D1Database;
  SUPABASE_URL: string;
  SUPABASE_ANON_KEY: string;
  IMAGES: {
    input(stream: ReadableStream): {
      transform(options: Record<string, unknown>): {
        output(options: { format: string; quality: number }): Promise<{ response(): Response }>;
      };
    };
  };
}

interface ExecutionContext {
  waitUntil(promise: Promise<unknown>): void;
  passThroughOnException(): void;
}

const ACCOUNT_DELETION_PATH = "/api/account-deletion/start";
const PUBLIC_ORIGIN = "https://chanriva.shinp-studio.com";
const MAX_ACCOUNT_DELETION_BODY_BYTES = 2_048;
const MAX_REQUESTS_PER_WINDOW = 5;
const RATE_LIMIT_WINDOW_MS = 10 * 60 * 1_000;
const deletionRateLimits = new Map<string, { count: number; resetAt: number }>();

const genericDeletionResponse = () => new Response(JSON.stringify({
  message: "確認処理を受け付けました。認証に成功し、進行中の対局がなければ削除リクエストが開始されます。",
}), {
  status: 200,
  headers: {
    "cache-control": "no-store",
    "content-type": "application/json; charset=utf-8",
    "referrer-policy": "no-referrer",
    "x-content-type-options": "nosniff",
  },
});

function jsonError(message: string, status: number): Response {
  return new Response(JSON.stringify({ error: message }), {
    status,
    headers: {
      "cache-control": "no-store",
      "content-type": "application/json; charset=utf-8",
      "x-content-type-options": "nosniff",
    },
  });
}

function isRateLimited(request: Request): boolean {
  const key = request.headers.get("CF-Connecting-IP") ?? "unknown-client";
  const now = Date.now();
  const current = deletionRateLimits.get(key);
  if (!current || current.resetAt <= now) {
    deletionRateLimits.set(key, { count: 1, resetAt: now + RATE_LIMIT_WINDOW_MS });
    if (deletionRateLimits.size > 5_000) deletionRateLimits.clear();
    return false;
  }
  current.count += 1;
  return current.count > MAX_REQUESTS_PER_WINDOW;
}

async function startAccountDeletion(request: Request, env: Env): Promise<Response> {
  if (request.method !== "POST") return jsonError("method not allowed", 405);
  if (request.headers.get("Origin") !== PUBLIC_ORIGIN) return jsonError("origin not allowed", 403);
  if (isRateLimited(request)) return jsonError("too many requests", 429);
  if (!env.SUPABASE_URL || !env.SUPABASE_ANON_KEY) return jsonError("service unavailable", 503);

  const contentLength = Number(request.headers.get("Content-Length") ?? "0");
  if (contentLength > MAX_ACCOUNT_DELETION_BODY_BYTES) return jsonError("request too large", 413);
  const body = await request.text();
  if (new TextEncoder().encode(body).byteLength > MAX_ACCOUNT_DELETION_BODY_BYTES) return jsonError("request too large", 413);

  let input: unknown;
  try {
    input = JSON.parse(body);
  } catch {
    return jsonError("invalid request", 400);
  }
  if (!input || typeof input !== "object") return jsonError("invalid request", 400);
  const { email, password } = input as { email?: unknown; password?: unknown };
  if (typeof email !== "string" || typeof password !== "string" || email.length < 3 || email.length > 254 || password.length === 0 || password.length > 256) {
    return jsonError("invalid request", 400);
  }

  // Use the existing Email/Password Auth provider only to establish the user's
  // identity. The password and access token are never logged, persisted, or
  // returned to the browser.
  try {
    const authResponse = await fetch(`${env.SUPABASE_URL}/auth/v1/token?grant_type=password`, {
      method: "POST",
      headers: { apikey: env.SUPABASE_ANON_KEY, "content-type": "application/json" },
      body: JSON.stringify({ email, password }),
    });
    if (!authResponse.ok) return genericDeletionResponse();

    const authBody = await authResponse.json() as { access_token?: unknown };
    if (typeof authBody.access_token !== "string" || authBody.access_token.length === 0) return genericDeletionResponse();

    // This is the same authenticated, user-scoped RPC used by Android. The
    // trusted cloudflare-admin Worker remains the only component that performs
    // Storage/Auth deletion and the service-only research unlink.
    await fetch(`${env.SUPABASE_URL}/rest/v1/rpc/request_account_deletion`, {
      method: "POST",
      headers: {
        apikey: env.SUPABASE_ANON_KEY,
        Authorization: `Bearer ${authBody.access_token}`,
        "content-type": "application/json",
      },
      body: "{}",
    });
    // Do not distinguish an unknown account, bad credentials, an active match,
    // or a successful request in the browser response.
    return genericDeletionResponse();
  } catch {
    return jsonError("service unavailable", 503);
  }
}

// Image security config. SVG sources with .svg extension auto-skip the
// optimization endpoint on the client side (served directly, no proxy).
// To route SVGs through the optimizer (with security headers), set
// dangerouslyAllowSVG: true in next.config.js and uncomment below:
// const imageConfig: ImageConfig = { dangerouslyAllowSVG: true };

const worker = {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname === ACCOUNT_DELETION_PATH) {
      return startAccountDeletion(request, env);
    }

    if (url.pathname === "/_vinext/image") {
      const allowedWidths = [...DEFAULT_DEVICE_SIZES, ...DEFAULT_IMAGE_SIZES];
      return handleImageOptimization(request, {
        fetchAsset: (path) => env.ASSETS.fetch(new Request(new URL(path, request.url))),
        transformImage: async (body, { width, format, quality }) => {
          const result = await env.IMAGES.input(body).transform(width > 0 ? { width } : {}).output({ format, quality });
          return result.response();
        },
      }, allowedWidths);
    }

    return handler.fetch(request, env, ctx);
  },
};

export default worker;
