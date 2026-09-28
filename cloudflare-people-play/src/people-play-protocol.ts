import type { RoomSnapshot } from "./room-state.js";

export type Phase3ClientMessage =
  | { protocolVersion: 1; type: "TAKE_SEAT"; roomId: string }
  | { protocolVersion: 1; type: "LEAVE_SEAT"; roomId: string };

export type PeoplePlayErrorCode =
  | "BAD_MESSAGE"
  | "UNSUPPORTED_VERSION"
  | "NOT_MEMBER"
  | "WRONG_PHASE"
  | "ALREADY_SEATED"
  | "NO_EMPTY_SEAT"
  | "NOT_SEATED";

export class Phase3ProtocolError extends Error {
  constructor(
    readonly code: "BAD_MESSAGE" | "UNSUPPORTED_VERSION",
    readonly rejectedType: string,
  ) {
    super(code);
    this.name = "Phase3ProtocolError";
  }
}

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const KNOWN_CLIENT_TYPES = new Set([
  "TAKE_SEAT",
  "LEAVE_SEAT",
  "MOVE_SNAPSHOT",
  "DESYNC",
  "RESULT_REPORT",
  "TIMEOUT_SELF",
]);

export function parsePhase3ClientMessage(text: string, expectedRoomId: string): Phase3ClientMessage {
  let value: unknown;
  try {
    value = JSON.parse(text) as unknown;
  } catch {
    throw new Phase3ProtocolError("BAD_MESSAGE", "UNKNOWN");
  }
  if (!isRecord(value)) throw new Phase3ProtocolError("BAD_MESSAGE", "UNKNOWN");
  const rejectedType = typeof value.type === "string" && KNOWN_CLIENT_TYPES.has(value.type)
    ? value.type
    : "UNKNOWN";
  if (value.protocolVersion !== 1) {
    if (typeof value.protocolVersion === "number") throw new Phase3ProtocolError("UNSUPPORTED_VERSION", rejectedType);
    throw new Phase3ProtocolError("BAD_MESSAGE", rejectedType);
  }
  if (value.type !== "TAKE_SEAT" && value.type !== "LEAVE_SEAT") {
    throw new Phase3ProtocolError("BAD_MESSAGE", rejectedType);
  }
  const keys = Object.keys(value);
  if (keys.length !== 3 || keys.some((key) => !["protocolVersion", "type", "roomId"].includes(key))) {
    throw new Phase3ProtocolError("BAD_MESSAGE", rejectedType);
  }
  if (value.roomId !== expectedRoomId) throw new Phase3ProtocolError("BAD_MESSAGE", rejectedType);
  return { protocolVersion: 1, type: value.type, roomId: expectedRoomId };
}

export function serializeRoomSnapshot(snapshot: RoomSnapshot): string {
  return JSON.stringify(snapshot);
}

export function serializePeoplePlayError(
  roomId: string,
  code: PeoplePlayErrorCode,
  rejectedType: string,
): string {
  return JSON.stringify({
    protocolVersion: 1,
    type: "ERROR",
    roomId,
    code,
    rejectedType,
    currentPly: 0,
    rejectedPly: null,
  });
}
