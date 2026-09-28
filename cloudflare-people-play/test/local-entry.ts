import { createPeoplePlayHandler, type RequestAuthenticator } from "../src/index.js";
import { RoomRegistry } from "../src/registry.js";

const testAuthenticator: RequestAuthenticator = async (request) =>
  request.headers.get("authorization") === "Bearer local-test-token";

const peoplePlayHandler = createPeoplePlayHandler(testAuthenticator);

export { RoomRegistry };

function response(body: unknown, status = 200): Response {
  return Response.json(body, { status });
}

export default {
  async fetch(request: Request<unknown, IncomingRequestCfProperties>, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname === "/__test/health" && request.method === "GET") return response({ ok: true });
    if (!url.pathname.startsWith("/__test/registry/")) return peoplePlayHandler.fetch!(request, env, ctx);
    if (request.method !== "POST") return response({ error: "METHOD_NOT_ALLOWED" }, 405);

    let body: Record<string, unknown>;
    try {
      const parsed: unknown = await request.json();
      if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) return response({ error: "BAD_BODY" }, 400);
      body = parsed as Record<string, unknown>;
    } catch {
      return response({ error: "BAD_BODY" }, 400);
    }

    const registry = env.ROOM_REGISTRY.getByName("people-play-room-registry");
    switch (url.pathname) {
      case "/__test/registry/allocate":
        return response({ roomId: await registry.allocateRoomId() });
      case "/__test/registry/publish":
        return response(await registry.publishRoom(String(body.roomId ?? ""), body.projection));
      case "/__test/registry/update":
        return response(await registry.updateRoomProjection(String(body.roomId ?? ""), body.projection));
      case "/__test/registry/close":
        return response(await registry.closeRoom(String(body.roomId ?? "")));
      case "/__test/registry/resolve":
        return response(await registry.resolveRoom(String(body.roomId ?? "")));
      case "/__test/registry/list":
        return response({ rooms: await registry.listRooms() });
      default:
        return response({ error: "NOT_FOUND" }, 404);
    }
  },
};
