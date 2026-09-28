import { isAuthenticatedPeoplePlayRequest } from "./auth.js";
import type { LobbyProjection } from "./registry-core.js";
import { RoomRegistry } from "./registry.js";

const ROOMS_PATH = "/v1/people-play/rooms";
const REGISTRY_NAME = "people-play-room-registry";

export type RequestAuthenticator = (request: Request, env: Env) => Promise<boolean>;

const jsonResponse = (body: unknown, status = 200, extraHeaders: HeadersInit = {}): Response => {
  const headers = new Headers(extraHeaders);
  headers.set("content-type", "application/json");
  return new Response(JSON.stringify(body), { status, headers });
};

export function createPeoplePlayHandler(
  authenticate: RequestAuthenticator = (request, env) => isAuthenticatedPeoplePlayRequest(request, env.SUPABASE_URL),
): ExportedHandler<Env> {
  return {
    async fetch(request, env): Promise<Response> {
      const url = new URL(request.url);
      if (url.pathname !== ROOMS_PATH) return jsonResponse({ error: "NOT_FOUND" }, 404);
      if (request.method !== "GET") return jsonResponse({ error: "METHOD_NOT_ALLOWED" }, 405, { allow: "GET" });
      if (!(await authenticate(request, env))) return jsonResponse({ error: "AUTH_REQUIRED" }, 401);

      try {
        const rooms: LobbyProjection[] = await env.ROOM_REGISTRY.getByName(REGISTRY_NAME).listRooms();
        return jsonResponse({ rooms });
      } catch {
        return jsonResponse({ error: "INTERNAL_ERROR" }, 500);
      }
    },
  };
}

export { RoomRegistry };

export default createPeoplePlayHandler();
