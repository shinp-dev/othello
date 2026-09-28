import { DurableObject } from "cloudflare:workers";
import {
  Phase3ProtocolError,
  parsePhase3ClientMessage,
  serializePeoplePlayError,
  serializeRoomSnapshot,
  type PeoplePlayErrorCode,
} from "./people-play-protocol.js";
import {
  AVATAR_IDS,
  TIME_CONTROLS,
  type AvatarId,
  type TimeControl,
} from "./registry-core.js";
import {
  allocateMemberId,
  attachmentRole,
  buildLobbyProjection,
  buildRoomSnapshot,
  createClosedRoomState,
  createInitialRoomState,
  disconnectWaitingMember,
  isSocketAttachment,
  leaveSeat,
  parsePersistedRoomState,
  reconcileAttachment,
  takeSeat,
  type ActiveRoomState,
  type InternalParticipant,
  type PersistedRoomState,
  type SocketAttachment,
} from "./room-state.js";

const INTERNAL_HOST = "room.internal";
const INTERNAL_IDENTITY_HEADER = "x-people-play-internal-identity";
const MEMBER_ID_HEADER = "x-people-play-member-id";
const REGISTRY_NAME = "people-play-room-registry";
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

interface RoomStateRow {
  [column: string]: SqlStorageValue;
  state_json: string;
}

interface InternalIdentity {
  userId: string;
  displayName: string;
  avatarId: AvatarId;
}

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const jsonResponse = (body: unknown, status: number): Response => {
  const headers = new Headers({ "content-type": "application/json" });
  return new Response(JSON.stringify(body), { status, headers });
};

function decodeBase64UrlUtf8(value: string): string {
  const base64 = value.replaceAll("-", "+").replaceAll("_", "/");
  const padded = base64.padEnd(Math.ceil(base64.length / 4) * 4, "=");
  const bytes = Uint8Array.from(atob(padded), (character) => character.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

function parseInternalIdentity(request: Request): InternalIdentity | null {
  const encoded = request.headers.get(INTERNAL_IDENTITY_HEADER);
  if (!encoded) return null;
  try {
    const value: unknown = JSON.parse(decodeBase64UrlUtf8(encoded));
    if (!isRecord(value) || Object.keys(value).length !== 3
      || typeof value.userId !== "string" || !UUID_PATTERN.test(value.userId)
      || typeof value.displayName !== "string" || value.displayName.trim().length === 0
      || typeof value.avatarId !== "string" || !AVATAR_IDS.includes(value.avatarId as AvatarId)) return null;
    return { userId: value.userId, displayName: value.displayName, avatarId: value.avatarId as AvatarId };
  } catch {
    return null;
  }
}

function readTimeControl(value: string | null): TimeControl | null {
  return value && TIME_CONTROLS.includes(value as TimeControl) ? value as TimeControl : null;
}

export class Room extends DurableObject<Env> {
  private state: PersistedRoomState | null = null;
  private unavailable = false;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    ctx.blockConcurrencyWhile(async () => {
      this.ctx.storage.sql.exec(`
        CREATE TABLE IF NOT EXISTS room_state (
          singleton INTEGER PRIMARY KEY,
          state_json TEXT NOT NULL,
          updated_at INTEGER NOT NULL,
          CHECK (singleton = 1)
        );
      `);
      const row = this.ctx.storage.sql.exec<RoomStateRow>(
        "SELECT state_json FROM room_state WHERE singleton = 1",
      ).toArray()[0];
      if (row) {
        try {
          this.state = parsePersistedRoomState(JSON.parse(row.state_json) as unknown);
        } catch {
          this.unavailable = true;
          this.state = null;
        }
      }
      if (this.state?.phase === "WAITING" || this.state?.phase === "PLAYING") {
        for (const socket of this.ctx.getWebSockets()) {
          const attachment: unknown = socket.deserializeAttachment();
          if (!isSocketAttachment(attachment)) continue;
          const reconciled = reconcileAttachment(this.state, attachment);
          if (reconciled.seatHint !== attachment.seatHint || reconciled.playerColorHint !== attachment.playerColorHint) {
            socket.serializeAttachment(reconciled);
          }
        }
      }
    });
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    if (url.hostname !== INTERNAL_HOST || request.method !== "GET"
      || (url.pathname !== "/create" && url.pathname !== "/join")) {
      return jsonResponse({ error: "NOT_FOUND" }, 404);
    }
    if (request.headers.get("upgrade")?.toLowerCase() !== "websocket") {
      return jsonResponse({ error: "UPGRADE_REQUIRED" }, 426);
    }
    if (this.unavailable) return jsonResponse({ error: "ROOM_UNAVAILABLE" }, 503);
    const identity = parseInternalIdentity(request);
    const roomId = url.searchParams.get("roomId");
    if (!identity || !roomId || !UUID_PATTERN.test(roomId)) return jsonResponse({ error: "BAD_INTERNAL_REQUEST" }, 400);
    if (url.pathname === "/create") {
      const timeControl = readTimeControl(url.searchParams.get("timeControl"));
      if (!timeControl) return jsonResponse({ error: "BAD_INTERNAL_REQUEST" }, 400);
      return this.createRoomSocket(roomId, timeControl, identity);
    }
    return this.joinRoomSocket(roomId, identity);
  }

  async abortCreate(roomId: string): Promise<void> {
    if (this.state && this.state.roomId === roomId && this.state.phase !== "CLOSED") {
      const closed = createClosedRoomState(roomId, Date.now());
      this.persist(closed);
      this.state = closed;
      for (const socket of this.ctx.getWebSockets()) {
        try { socket.close(1011, "Room creation failed"); } catch { /* already closed */ }
      }
    }
  }

  async webSocketMessage(socket: WebSocket, message: string | ArrayBuffer): Promise<void> {
    const attachment: unknown = socket.deserializeAttachment();
    if (!isSocketAttachment(attachment) || !this.state || this.state.phase === "CLOSED") {
      this.sendError(socket, "NOT_MEMBER", "UNKNOWN");
      return;
    }
    if (typeof message !== "string") {
      this.sendError(socket, "BAD_MESSAGE", "UNKNOWN");
      return;
    }
    let command;
    try {
      command = parsePhase3ClientMessage(message, this.state.roomId);
    } catch (error) {
      if (error instanceof Phase3ProtocolError) this.sendError(socket, error.code, error.rejectedType);
      else this.sendError(socket, "BAD_MESSAGE", "UNKNOWN");
      return;
    }

    const participant: InternalParticipant = {
      memberId: attachment.memberId,
      userId: attachment.userId,
      displayName: attachment.displayName,
      avatarId: attachment.avatarId,
    };
    const transition = command.type === "TAKE_SEAT"
      ? takeSeat(this.state, participant, () => this.randomBit())
      : leaveSeat(this.state, attachment.memberId);
    if (!transition.ok) {
      this.sendError(socket, transition.error, command.type);
      return;
    }

    this.persist(transition.state);
    this.state = transition.state;
    this.reconcileAllAttachments(transition.state);
    const connections = this.connections();
    this.broadcastSnapshot(transition.state, connections);
    this.queueProjectionUpdate(transition.state, connections.map(({ attachment: current }) => current));
  }

  async webSocketClose(socket: WebSocket, code: number, reason: string): Promise<void> {
    await this.handleSocketDeparture(socket);
    try { socket.close(code, reason); } catch { /* close handshake already completed */ }
  }

  async webSocketError(socket: WebSocket): Promise<void> {
    await this.handleSocketDeparture(socket);
    try { socket.close(1011, "WebSocket error"); } catch { /* already disconnected */ }
  }

  private async createRoomSocket(
    roomId: string,
    timeControl: TimeControl,
    identity: InternalIdentity,
  ): Promise<Response> {
    if (this.state !== null) return jsonResponse({ error: this.state.phase === "CLOSED" ? "ROOM_CLOSED" : "ROOM_EXISTS" }, 409);
    const memberId = allocateMemberId(new Set(this.connections().map(({ attachment }) => attachment.memberId)));
    const participant: InternalParticipant = { memberId, ...identity };
    const nextState = createInitialRoomState(roomId, timeControl, participant);
    this.persist(nextState);
    this.state = nextState;

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    this.ctx.acceptWebSocket(server);
    server.serializeAttachment(this.newAttachment(participant, "A", null));
    const attachments = this.connections().map(({ attachment }) => attachment);
    const publish = await this.env.ROOM_REGISTRY.getByName(REGISTRY_NAME)
      .publishRoom(roomId, buildLobbyProjection(nextState, attachments));
    if (!publish.ok) {
      const closed = createClosedRoomState(roomId, Date.now());
      this.persist(closed);
      this.state = closed;
      try { server.close(1011, "Room creation failed"); } catch { /* already closed */ }
      await this.env.ROOM_REGISTRY.getByName(REGISTRY_NAME).closeRoom(roomId);
      return jsonResponse({ error: "ROOM_CREATE_FAILED" }, 503);
    }
    this.broadcastSnapshot(nextState, this.connections());
    return new Response(null, { status: 101, webSocket: client, headers: { [MEMBER_ID_HEADER]: memberId } });
  }

  private async joinRoomSocket(roomId: string, identity: InternalIdentity): Promise<Response> {
    if (!this.state) return jsonResponse({ error: "ROOM_NOT_FOUND" }, 404);
    if (this.state.roomId !== roomId) return jsonResponse({ error: "ROOM_NOT_FOUND" }, 404);
    if (this.state.phase === "CLOSED") return jsonResponse({ error: "ROOM_CLOSED" }, 410);
    const memberId = allocateMemberId(new Set(this.connections().map(({ attachment }) => attachment.memberId)));
    const participant: InternalParticipant = { memberId, ...identity };
    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    this.ctx.acceptWebSocket(server);
    server.serializeAttachment(this.newAttachment(participant, null, null));
    const connections = this.connections();
    this.broadcastSnapshot(this.state, connections);
    this.queueProjectionUpdate(this.state, connections.map(({ attachment }) => attachment));
    return new Response(null, { status: 101, webSocket: client, headers: { [MEMBER_ID_HEADER]: memberId } });
  }

  private newAttachment(
    participant: InternalParticipant,
    seatHint: "A" | "B" | null,
    playerColorHint: "BLACK" | "WHITE" | null,
  ): SocketAttachment {
    return {
      schemaVersion: 1,
      memberId: participant.memberId,
      userId: participant.userId,
      displayName: participant.displayName,
      avatarId: participant.avatarId,
      acceptedAt: Date.now(),
      seatHint,
      playerColorHint,
    };
  }

  private connections(excluded?: WebSocket): Array<{ socket: WebSocket; attachment: SocketAttachment }> {
    const result: Array<{ socket: WebSocket; attachment: SocketAttachment }> = [];
    for (const socket of this.ctx.getWebSockets()) {
      if (socket === excluded || socket.readyState !== 1) continue;
      const attachment: unknown = socket.deserializeAttachment();
      if (isSocketAttachment(attachment)) result.push({ socket, attachment });
    }
    return result;
  }

  private reconcileAllAttachments(state: ActiveRoomState): void {
    for (const { socket, attachment } of this.connections()) {
      const reconciled = reconcileAttachment(state, attachment);
      if (reconciled.seatHint !== attachment.seatHint || reconciled.playerColorHint !== attachment.playerColorHint) {
        socket.serializeAttachment(reconciled);
      }
    }
  }

  private broadcastSnapshot(
    state: ActiveRoomState,
    connections: Array<{ socket: WebSocket; attachment: SocketAttachment }>,
  ): void {
    const wire = serializeRoomSnapshot(buildRoomSnapshot(state, connections.map(({ attachment }) => attachment)));
    for (const { socket } of connections) {
      try { socket.send(wire); } catch { /* close/error handler will reconcile membership */ }
    }
  }

  private sendError(socket: WebSocket, code: PeoplePlayErrorCode, rejectedType: string): void {
    const roomId = this.state?.roomId ?? "unknown";
    try { socket.send(serializePeoplePlayError(roomId, code, rejectedType)); } catch { /* disconnected */ }
  }

  private queueProjectionUpdate(state: ActiveRoomState, attachments: SocketAttachment[]): void {
    const projection = buildLobbyProjection(state, attachments);
    this.ctx.waitUntil(
      this.env.ROOM_REGISTRY.getByName(REGISTRY_NAME).updateRoomProjection(state.roomId, projection).then(() => undefined),
    );
  }

  private async handleSocketDeparture(socket: WebSocket): Promise<void> {
    const attachment: unknown = socket.deserializeAttachment();
    if (!isSocketAttachment(attachment) || !this.state || this.state.phase === "CLOSED") return;
    const active = this.state;
    const role = attachmentRole(active, attachment);
    const remaining = this.connections(socket);
    if (active.phase === "WAITING") {
      const next = disconnectWaitingMember(active, attachment.memberId, remaining.length, Date.now());
      this.persist(next);
      this.state = next;
      if (next.phase === "CLOSED") {
        this.ctx.waitUntil(this.env.ROOM_REGISTRY.getByName(REGISTRY_NAME).closeRoom(next.roomId).then(() => undefined));
        return;
      }
      this.reconcileAllAttachments(next);
      this.broadcastSnapshot(next, remaining);
      this.queueProjectionUpdate(next, remaining.map(({ attachment: current }) => current));
      return;
    }
    if (role.spectator) {
      this.broadcastSnapshot(active, remaining);
      this.queueProjectionUpdate(active, remaining.map(({ attachment: current }) => current));
    }
  }

  private persist(state: PersistedRoomState): void {
    this.ctx.storage.sql.exec(
      `INSERT INTO room_state (singleton, state_json, updated_at)
       VALUES (1, ?, ?)
       ON CONFLICT(singleton) DO UPDATE SET state_json = excluded.state_json, updated_at = excluded.updated_at`,
      JSON.stringify(state),
      Date.now(),
    );
  }

  private randomBit(): 0 | 1 {
    return (crypto.getRandomValues(new Uint8Array(1))[0] & 1) as 0 | 1;
  }
}
