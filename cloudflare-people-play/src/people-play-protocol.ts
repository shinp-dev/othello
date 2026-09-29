import type { GameOverMessage, RoomSnapshot } from "./room-state.js";

export type ReportedResult = "BLACK_WIN" | "WHITE_WIN" | "DRAW" | "NOT_FINISHED";
export type DesyncReason = "SNAPSHOT_MISMATCH" | "RESULT_MISMATCH";

export type PeoplePlayClientMessage =
  | { protocolVersion: 1; type: "TAKE_SEAT"; roomId: string }
  | { protocolVersion: 1; type: "LEAVE_SEAT"; roomId: string }
  | {
    protocolVersion: 1;
    type: "MOVE_SNAPSHOT";
    roomId: string;
    ply: number;
    move: { row: number; column: number };
    board: number[];
    nextTurn: "BLACK" | "WHITE" | null;
    terminalCandidate: boolean;
  }
  | { protocolVersion: 1; type: "DESYNC"; roomId: string; observedPly: number; reason: DesyncReason }
  | { protocolVersion: 1; type: "RESULT_REPORT"; roomId: string; ply: number; result: ReportedResult }
  | { protocolVersion: 1; type: "TIMEOUT_SELF"; roomId: string };

export type PeoplePlayErrorCode =
  | "BAD_MESSAGE"
  | "UNSUPPORTED_VERSION"
  | "NOT_MEMBER"
  | "NOT_PLAYER"
  | "NOT_YOUR_TURN"
  | "WRONG_PHASE"
  | "ALREADY_SEATED"
  | "NO_EMPTY_SEAT"
  | "NOT_SEATED"
  | "BAD_PLY"
  | "RESULT_PENDING";

export class PeoplePlayProtocolError extends Error {
  constructor(
    readonly code: "BAD_MESSAGE" | "UNSUPPORTED_VERSION",
    readonly rejectedType: string,
  ) {
    super(code);
    this.name = "PeoplePlayProtocolError";
  }
}

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

function hasExactKeys(value: Record<string, unknown>, keys: readonly string[]): boolean {
  const actual = Object.keys(value);
  return actual.length === keys.length && actual.every((key) => keys.includes(key));
}

const KNOWN_CLIENT_TYPES = new Set([
  "TAKE_SEAT", "LEAVE_SEAT", "MOVE_SNAPSHOT", "DESYNC", "RESULT_REPORT", "TIMEOUT_SELF",
]);

function protocolError(code: "BAD_MESSAGE" | "UNSUPPORTED_VERSION", rejectedType: string): never {
  throw new PeoplePlayProtocolError(code, rejectedType);
}

function parseMove(value: Record<string, unknown>, roomId: string): PeoplePlayClientMessage {
  if (!hasExactKeys(value, [
    "protocolVersion", "type", "roomId", "ply", "move", "board", "nextTurn", "terminalCandidate",
  ])) protocolError("BAD_MESSAGE", "MOVE_SNAPSHOT");
  if (!Number.isSafeInteger(value.ply)) protocolError("BAD_MESSAGE", "MOVE_SNAPSHOT");
  if (!isRecord(value.move) || !hasExactKeys(value.move, ["row", "column"])
    || !Number.isInteger(value.move.row) || (value.move.row as number) < 0 || (value.move.row as number) > 7
    || !Number.isInteger(value.move.column) || (value.move.column as number) < 0 || (value.move.column as number) > 7) {
    protocolError("BAD_MESSAGE", "MOVE_SNAPSHOT");
  }
  if (!Array.isArray(value.board) || value.board.length !== 64
    || value.board.some((cell) => !Number.isInteger(cell) || (cell !== 0 && cell !== 1 && cell !== 2))) {
    protocolError("BAD_MESSAGE", "MOVE_SNAPSHOT");
  }
  if (typeof value.terminalCandidate !== "boolean"
    || (value.terminalCandidate && value.nextTurn !== null)
    || (!value.terminalCandidate && value.nextTurn !== "BLACK" && value.nextTurn !== "WHITE")) {
    protocolError("BAD_MESSAGE", "MOVE_SNAPSHOT");
  }
  return {
    protocolVersion: 1,
    type: "MOVE_SNAPSHOT",
    roomId,
    ply: value.ply as number,
    move: { row: value.move.row as number, column: value.move.column as number },
    board: [...value.board] as number[],
    nextTurn: value.nextTurn as "BLACK" | "WHITE" | null,
    terminalCandidate: value.terminalCandidate,
  };
}

export function parsePeoplePlayClientMessage(text: string, expectedRoomId: string): PeoplePlayClientMessage {
  let value: unknown;
  try {
    value = JSON.parse(text) as unknown;
  } catch {
    protocolError("BAD_MESSAGE", "UNKNOWN");
  }
  if (!isRecord(value)) protocolError("BAD_MESSAGE", "UNKNOWN");
  const rejectedType = typeof value.type === "string" && KNOWN_CLIENT_TYPES.has(value.type)
    ? value.type
    : "UNKNOWN";
  if (value.protocolVersion !== 1) {
    protocolError(typeof value.protocolVersion === "number" ? "UNSUPPORTED_VERSION" : "BAD_MESSAGE", rejectedType);
  }
  if (typeof value.type !== "string" || !KNOWN_CLIENT_TYPES.has(value.type)) protocolError("BAD_MESSAGE", "UNKNOWN");
  if (value.roomId !== expectedRoomId) protocolError("BAD_MESSAGE", rejectedType);

  switch (value.type) {
    case "TAKE_SEAT":
    case "LEAVE_SEAT":
    case "TIMEOUT_SELF":
      if (!hasExactKeys(value, ["protocolVersion", "type", "roomId"])) protocolError("BAD_MESSAGE", value.type);
      return { protocolVersion: 1, type: value.type, roomId: expectedRoomId };
    case "MOVE_SNAPSHOT":
      return parseMove(value, expectedRoomId);
    case "DESYNC":
      if (!hasExactKeys(value, ["protocolVersion", "type", "roomId", "observedPly", "reason"])
        || !Number.isSafeInteger(value.observedPly)
        || (value.reason !== "SNAPSHOT_MISMATCH" && value.reason !== "RESULT_MISMATCH")) {
        protocolError("BAD_MESSAGE", "DESYNC");
      }
      return {
        protocolVersion: 1,
        type: "DESYNC",
        roomId: expectedRoomId,
        observedPly: value.observedPly as number,
        reason: value.reason,
      };
    case "RESULT_REPORT":
      if (!hasExactKeys(value, ["protocolVersion", "type", "roomId", "ply", "result"])
        || !Number.isSafeInteger(value.ply)
        || !["BLACK_WIN", "WHITE_WIN", "DRAW", "NOT_FINISHED"].includes(value.result as string)) {
        protocolError("BAD_MESSAGE", "RESULT_REPORT");
      }
      return {
        protocolVersion: 1,
        type: "RESULT_REPORT",
        roomId: expectedRoomId,
        ply: value.ply as number,
        result: value.result as ReportedResult,
      };
    default:
      protocolError("BAD_MESSAGE", "UNKNOWN");
  }
}

export function serializeRoomSnapshot(snapshot: RoomSnapshot): string {
  return JSON.stringify(snapshot);
}

export function serializeResultCheck(roomId: string, ply: number): string {
  return JSON.stringify({ protocolVersion: 1, type: "RESULT_CHECK", roomId, ply });
}

export function serializeGameOver(message: GameOverMessage): string {
  return JSON.stringify(message);
}

export function serializePeoplePlayError(
  roomId: string,
  code: PeoplePlayErrorCode,
  rejectedType: string,
  currentPly: number,
  rejectedPly: number | null = null,
): string {
  return JSON.stringify({
    protocolVersion: 1,
    type: "ERROR",
    roomId,
    code,
    rejectedType,
    currentPly,
    rejectedPly,
  });
}
