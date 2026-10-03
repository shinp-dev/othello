import {
  Room,
  PeopleSocialRegistry,
  createPeoplePlayHandler,
  type ProfileResolver,
  type RequestAuthenticator,
} from "../src/index.js";
import { RoomRegistry } from "../src/registry.js";

const TEST_USERS: Record<string, { userId: string; displayName: string }> = {
  "local-test-token": { userId: "10000000-0000-4000-8000-000000000001", displayName: "Local Tester" },
  "test-user-a": { userId: "00000000-0000-4000-8000-00000000000a", displayName: "サンプル・アルファ" },
  "test-user-a-duplicate": { userId: "00000000-0000-4000-8000-00000000000a", displayName: "サンプル・アルファ" },
  "test-user-b": { userId: "00000001-0000-4000-8000-00000000000b", displayName: "Sample Birch" },
  "test-user-c": { userId: "00000002-0000-4000-8000-00000000000c", displayName: "Sample Cedar" },
  "test-user-d": { userId: "00000003-0000-4000-8000-00000000000d", displayName: "Sample Dahlia" },
  "test-user-e": { userId: "00000004-0000-4000-8000-00000000000e", displayName: "Sample Elm" },
  "test-user-f": { userId: "00000005-0000-4000-8000-00000000000f", displayName: "Sample Fir" },
};

const testAuthenticator: RequestAuthenticator = async (request) => {
  const match = request.headers.get("authorization")?.match(/^Bearer ([^\s,]+)$/i);
  const user = match ? TEST_USERS[match[1]] : undefined;
  return user ? { userId: user.userId, accessToken: match![1] } : null;
};

const testProfileResolver: ProfileResolver = async (identity) => {
  const user = TEST_USERS[identity.accessToken];
  if (!user || user.userId !== identity.userId) throw new Error("Missing test profile");
  return { displayName: user.displayName };
};

const peoplePlayHandler = createPeoplePlayHandler(testAuthenticator, testProfileResolver);

export { Room, RoomRegistry, PeopleSocialRegistry };

function response(body: unknown, status = 200): Response {
  return Response.json(body, { status });
}

export default {
  async fetch(request: Request<unknown, IncomingRequestCfProperties>, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname === "/__test/health" && request.method === "GET") return response({ ok: true });
    if (url.pathname === "/__test/upgrade-required-header" && request.method === "GET") {
      const inspected = await peoplePlayHandler.fetch!(
        new Request("http://internal.test/v1/people-play/rooms/new/socket") as Request<unknown, IncomingRequestCfProperties>,
        env,
        ctx,
      );
      return response({ status: inspected.status, upgrade: inspected.headers.get("upgrade") });
    }
    if (url.pathname === "/__test/social/pending-count" && request.method === "GET") {
      const social = env.SOCIAL_REGISTRY.getByName("people-social-registry");
      return response({ count: await social.pendingNotificationCount() });
    }
    if (url.pathname === "/__test/social/claim-pending" && request.method === "POST") {
      const body = await request.json<{ now?: number; limit?: number }>();
      const social = env.SOCIAL_REGISTRY.getByName("people-social-registry");
      return response({
        notifications: await social.claimPendingNotifications(
          Number(body.now ?? Date.now()),
          Number(body.limit ?? 50),
        ),
      });
    }
    if (url.pathname === "/__test/social/record-room-created" && request.method === "POST") {
      const body = await request.json<{ roomId?: string; creatorUserId?: string; createdAt?: number }>();
      const social = env.SOCIAL_REGISTRY.getByName("people-social-registry");
      const queued = await social.recordRoomCreated(
        String(body.roomId ?? ""),
        String(body.creatorUserId ?? ""),
        Number(body.createdAt ?? Date.now()),
      );
      return response({ queued });
    }
    if (url.pathname === "/__test/room/abort-create" && request.method === "POST") {
      const body = await request.json<{ roomId?: string }>();
      const roomId = String(body.roomId ?? "");
      await env.ROOM.getByName(roomId).abortCreate(roomId);
      return response({ ok: true });
    }
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
      case "/__test/registry/resolve-join-target":
        return response(await registry.resolveJoinTarget(String(body.roomId ?? "")));
      case "/__test/registry/list":
        return response({ rooms: await registry.listRoomProjectionCandidates() });
      default:
        return response({ error: "NOT_FOUND" }, 404);
    }
  },
};
