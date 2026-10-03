import {
  authenticatePeoplePlayRequest,
  type AuthenticatedPeoplePlayRequest,
} from "./auth.js";
import { deriveAvatarId } from "./avatar.js";
import {
  ProfileResolutionError,
  resolveCanonicalPeoplePlayProfile,
  type CanonicalPeoplePlayProfile,
} from "./profile.js";
import { TIME_CONTROLS, type LobbyProjection, type TimeControl } from "./registry-core.js";
import { RoomRegistry } from "./registry.js";
import { Room } from "./room.js";
import {
  parseAvailabilityMutation,
  parsePushDeviceMutation,
  parseSocialSlotStarts,
  validateSocialSlotForWrite,
} from "./social-core.js";
import { PeopleSocialRegistry } from "./social-registry.js";

const ROOMS_PATH = "/v1/people-play/rooms";
const NEW_SOCKET_PATH = "/v1/people-play/rooms/new/socket";
const ROOM_SOCKET_PATTERN = /^\/v1\/people-play\/rooms\/([^/]+)\/socket$/;
const SOCIAL_AVAILABILITY_PATH = "/v1/people-social/availability";
const SOCIAL_PUSH_DEVICE_PATH = "/v1/people-social/push-device";
const REGISTRY_NAME = "people-play-room-registry";
const SOCIAL_REGISTRY_NAME = "people-social-registry";
const INTERNAL_IDENTITY_HEADER = "x-people-play-internal-identity";
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export type RequestAuthenticator = (
  request: Request,
  env: Env,
) => Promise<AuthenticatedPeoplePlayRequest | null>;

export type ProfileResolver = (
  identity: AuthenticatedPeoplePlayRequest,
  env: Env,
) => Promise<CanonicalPeoplePlayProfile>;

interface InternalIdentity {
  userId: string;
  displayName: string;
  avatarId: ReturnType<typeof deriveAvatarId>;
}

const jsonResponse = (body: unknown, status = 200, extraHeaders: HeadersInit = {}): Response => {
  const headers = new Headers(extraHeaders);
  headers.set("content-type", "application/json");
  return new Response(JSON.stringify(body), { status, headers });
};

function encodeBase64UrlUtf8(value: string): string {
  const bytes = new TextEncoder().encode(value);
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
}

function internalIdentityHeader(identity: InternalIdentity): string {
  return encodeBase64UrlUtf8(JSON.stringify(identity));
}

function isWebSocketUpgrade(request: Request): boolean {
  return request.headers.get("upgrade")?.toLowerCase() === "websocket";
}

function parseTimeControl(value: string | null): TimeControl | null {
  return value && TIME_CONTROLS.includes(value as TimeControl) ? value as TimeControl : null;
}

async function parseJsonObject(request: Request): Promise<unknown> {
  try {
    return await request.json();
  } catch {
    throw new Error("BAD_BODY");
  }
}

function socialMutationError(error: unknown): Response {
  const code = error instanceof Error ? error.message : "";
  if (code === "SLOT_EXPIRED") return jsonResponse({ error: "SLOT_EXPIRED" }, 409);
  if (code === "BAD_SLOT" || code === "BAD_BODY" || code === "BAD_TOKEN") {
    return jsonResponse({ error: code }, 400);
  }
  return jsonResponse({ error: "SOCIAL_UNAVAILABLE" }, 503);
}

async function resolveProfileForSocket(
  identity: AuthenticatedPeoplePlayRequest,
  env: Env,
  resolver: ProfileResolver,
): Promise<CanonicalPeoplePlayProfile | Response> {
  try {
    return await resolver(identity, env);
  } catch (error) {
    if (error instanceof ProfileResolutionError && error.code === "PROFILE_REQUIRED") {
      return jsonResponse({ error: "PROFILE_REQUIRED" }, 403);
    }
    return jsonResponse({ error: "PROFILE_UNAVAILABLE" }, 503);
  }
}

export function createPeoplePlayHandler(
  authenticate: RequestAuthenticator = (request, env) => authenticatePeoplePlayRequest(request, env.SUPABASE_URL),
  resolveProfile: ProfileResolver = (identity, env) => resolveCanonicalPeoplePlayProfile(
    identity,
    env.SUPABASE_URL,
    env.SUPABASE_ANON_KEY,
  ),
): ExportedHandler<Env> {
  return {
    async fetch(request, env, ctx): Promise<Response> {
      const url = new URL(request.url);
      const socketMatch = url.pathname.match(ROOM_SOCKET_PATTERN);
      const isNewSocket = url.pathname === NEW_SOCKET_PATH;
      const isExistingSocket = socketMatch !== null;
      const isRooms = url.pathname === ROOMS_PATH;
      const isSocialAvailability = url.pathname === SOCIAL_AVAILABILITY_PATH;
      const isSocialPushDevice = url.pathname === SOCIAL_PUSH_DEVICE_PATH;

      if (!isRooms && !isNewSocket && !isExistingSocket && !isSocialAvailability && !isSocialPushDevice) {
        return jsonResponse({ error: "NOT_FOUND" }, 404);
      }

      if ((isRooms || isNewSocket || isExistingSocket) && request.method !== "GET") {
        return jsonResponse({ error: "METHOD_NOT_ALLOWED" }, 405, { allow: "GET" });
      }
      if (isSocialAvailability && request.method !== "GET" && request.method !== "PUT") {
        return jsonResponse({ error: "METHOD_NOT_ALLOWED" }, 405, { allow: "GET, PUT" });
      }
      if (isSocialPushDevice && request.method !== "PUT" && request.method !== "DELETE") {
        return jsonResponse({ error: "METHOD_NOT_ALLOWED" }, 405, { allow: "PUT, DELETE" });
      }
      if ((isNewSocket || isExistingSocket) && !isWebSocketUpgrade(request)) {
        return jsonResponse({ error: "UPGRADE_REQUIRED" }, 426, { upgrade: "websocket" });
      }

      const authenticated = await authenticate(request, env);
      if (!authenticated) return jsonResponse({ error: "AUTH_REQUIRED" }, 401);

      const socialRegistry = env.SOCIAL_REGISTRY.getByName(SOCIAL_REGISTRY_NAME);
      if (isSocialAvailability) {
        if (request.method === "GET") {
          try {
            const slotStarts = parseSocialSlotStarts(url.searchParams.getAll("slotStart"));
            return jsonResponse({
              slots: await socialRegistry.getAvailability(authenticated.userId, slotStarts),
            });
          } catch (error) {
            return socialMutationError(error);
          }
        }

        try {
          const mutation = parseAvailabilityMutation(await parseJsonObject(request));
          validateSocialSlotForWrite(mutation.slotStart, mutation.enabled);
          const slot = await socialRegistry.setAvailability(
            authenticated.userId,
            mutation.slotStart,
            mutation.enabled,
          );
          return jsonResponse({ slot });
        } catch (error) {
          return socialMutationError(error);
        }
      }

      if (isSocialPushDevice) {
        try {
          const { token } = parsePushDeviceMutation(await parseJsonObject(request));
          if (request.method === "PUT") {
            await socialRegistry.registerPushDevice(authenticated.userId, token);
          } else {
            await socialRegistry.unregisterPushDevice(authenticated.userId, token);
          }
          return jsonResponse({ ok: true });
        } catch (error) {
          return socialMutationError(error);
        }
      }

      const registry = env.ROOM_REGISTRY.getByName(REGISTRY_NAME);
      if (isRooms) {
        try {
          const rooms: LobbyProjection[] = await registry.listRooms();
          return jsonResponse({ rooms });
        } catch {
          return jsonResponse({ error: "INTERNAL_ERROR" }, 500);
        }
      }

      const profileOrResponse = await resolveProfileForSocket(authenticated, env, resolveProfile);
      if (profileOrResponse instanceof Response) return profileOrResponse;
      const identity: InternalIdentity = {
        userId: authenticated.userId,
        displayName: profileOrResponse.displayName,
        avatarId: deriveAvatarId(authenticated.userId),
      };

      if (isNewSocket) {
        const timeControl = parseTimeControl(url.searchParams.get("timeControl"));
        if (!timeControl) return jsonResponse({ error: "BAD_TIME_CONTROL" }, 400);
        let roomId: string | null = null;
        let room: DurableObjectStub<Room> | null = null;
        try {
          roomId = await registry.allocateRoomId();
          room = env.ROOM.getByName(roomId);
          const headers = new Headers({
            upgrade: "websocket",
            [INTERNAL_IDENTITY_HEADER]: internalIdentityHeader(identity),
          });
          const internalUrl = new URL("https://room.internal/create");
          internalUrl.searchParams.set("roomId", roomId);
          internalUrl.searchParams.set("timeControl", timeControl);
          const response = await room.fetch(new Request(internalUrl, { method: "GET", headers }));
          if (response.status === 101) {
            const notificationWork = socialRegistry
              .recordRoomCreated(roomId, authenticated.userId, Date.now())
              .catch(() => 0);
            ctx.waitUntil(notificationWork.then(() => undefined));
            return response;
          }
        } catch {
          // Cleanup below deliberately hides internal errors and credentials.
        }
        if (roomId) {
          try { await room?.abortCreate(roomId); } catch { /* best effort */ }
          try { await registry.closeRoom(roomId); } catch { /* best effort */ }
        }
        return jsonResponse({ error: "ROOM_CREATE_FAILED" }, 503);
      }

      let roomId: string;
      try {
        roomId = decodeURIComponent(socketMatch?.[1] ?? "");
      } catch {
        return jsonResponse({ error: "ROOM_NOT_FOUND" }, 404);
      }
      if (!UUID_PATTERN.test(roomId)) return jsonResponse({ error: "ROOM_NOT_FOUND" }, 404);
      try {
        const resolution = await registry.resolveJoinTarget(roomId);
        if (resolution.status === "unknown") return jsonResponse({ error: "ROOM_NOT_FOUND" }, 404);
        if (resolution.status === "closed") return jsonResponse({ error: "ROOM_CLOSED" }, 410);
        const headers = new Headers({
          upgrade: "websocket",
          [INTERNAL_IDENTITY_HEADER]: internalIdentityHeader(identity),
        });
        const internalUrl = new URL("https://room.internal/join");
        internalUrl.searchParams.set("roomId", roomId);
        const response = await env.ROOM.getByName(roomId).fetch(new Request(internalUrl, { method: "GET", headers }));
        if (response.status === 101) return response;
        if (response.status === 410) return jsonResponse({ error: "ROOM_CLOSED" }, 410);
        if (response.status === 404) return jsonResponse({ error: "ROOM_NOT_FOUND" }, 404);
        return jsonResponse({ error: "ROOM_UNAVAILABLE" }, 503);
      } catch {
        return jsonResponse({ error: "ROOM_UNAVAILABLE" }, 503);
      }
    },
  };
}

export { Room, RoomRegistry, PeopleSocialRegistry };

export default createPeoplePlayHandler();
