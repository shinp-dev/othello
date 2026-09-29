import {
  AVATAR_IDS,
  TIME_CONTROLS,
  type AvatarId,
  type LobbyProjection,
  type ParticipantSummary,
  type TimeControl,
} from "./registry-core.js";
import type { ReportedResult } from "./people-play-protocol.js";

export type Seat = "A" | "B";
export type PlayerColor = "BLACK" | "WHITE";
export type FinishReason = "NORMAL" | "TIMEOUT" | "DISCONNECT" | "DESYNC";
export type Outcome = "BLACK_WIN" | "WHITE_WIN" | "DRAW" | "NO_CONTEST";

export interface InternalParticipant extends ParticipantSummary {
  userId: string;
}

export interface SocketAttachment {
  schemaVersion: 1;
  memberId: string;
  userId: string;
  displayName: string;
  avatarId: AvatarId;
  acceptedAt: number;
  seatHint: Seat | null;
  playerColorHint: PlayerColor | null;
}

export interface ResultCheckState {
  ply: number;
  black: ReportedResult | null;
  white: ReportedResult | null;
}

export interface ActiveRoomState {
  schemaVersion: 1;
  roomId: string;
  phase: "WAITING" | "PLAYING";
  timeControl: TimeControl;
  seats: { a: InternalParticipant | null; b: InternalParticipant | null };
  players: { black: InternalParticipant; white: InternalParticipant } | null;
  currentPly: number;
  currentTurn: PlayerColor | null;
  latestSnapshot: {
    board: number[];
    move: { row: number; column: number } | null;
    terminalCandidate: boolean;
  };
  resultCheck: ResultCheckState | null;
}

export interface ClosedRoomState {
  schemaVersion: 1;
  roomId: string;
  phase: "CLOSED";
  closedAt: number;
}

export type PersistedRoomState = ActiveRoomState | ClosedRoomState;

export interface RoomSnapshot {
  protocolVersion: 1;
  type: "ROOM_SNAPSHOT";
  roomId: string;
  phase: "WAITING" | "PLAYING";
  timeControl: TimeControl;
  seats: { a: ParticipantSummary | null; b: ParticipantSummary | null };
  players: { black: ParticipantSummary; white: ParticipantSummary } | null;
  currentPly: number;
  board: number[];
  move: { row: number; column: number } | null;
  nextTurn: PlayerColor | null;
  terminalCandidate: boolean;
  resultCheckPly: number | null;
  spectatorCount: number;
  spectatorAvatarPreview: AvatarId[];
}

export interface GameOverMessage {
  protocolVersion: 1;
  type: "GAME_OVER";
  roomId: string;
  ply: number;
  finishReason: FinishReason;
  outcome: Outcome;
  winner: PlayerColor | null;
  decidedAt: number;
  finalSnapshot: RoomSnapshot;
}

export const INITIAL_BOARD: readonly number[] = Object.freeze(Array.from({ length: 64 }, (_, index) => {
  if (index === 27 || index === 36) return 2;
  if (index === 28 || index === 35) return 1;
  return 0;
}));

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const REPORTED_RESULTS: readonly ReportedResult[] = ["BLACK_WIN", "WHITE_WIN", "DRAW", "NOT_FINISHED"];
const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

function hasExactKeys(value: Record<string, unknown>, keys: readonly string[]): boolean {
  const actual = Object.keys(value);
  return actual.length === keys.length && actual.every((key) => keys.includes(key));
}

function isParticipant(value: unknown): value is InternalParticipant {
  return isRecord(value)
    && hasExactKeys(value, ["memberId", "userId", "displayName", "avatarId"])
    && typeof value.memberId === "string" && value.memberId.trim().length > 0
    && typeof value.userId === "string" && UUID_PATTERN.test(value.userId)
    && typeof value.displayName === "string" && value.displayName.trim().length > 0
    && typeof value.avatarId === "string" && AVATAR_IDS.includes(value.avatarId as AvatarId);
}

function isBoard(value: unknown): value is number[] {
  return Array.isArray(value) && value.length === 64
    && value.every((cell) => Number.isInteger(cell) && (cell === 0 || cell === 1 || cell === 2));
}

function isMove(value: unknown): value is { row: number; column: number } {
  return isRecord(value) && hasExactKeys(value, ["row", "column"])
    && Number.isInteger(value.row) && (value.row as number) >= 0 && (value.row as number) <= 7
    && Number.isInteger(value.column) && (value.column as number) >= 0 && (value.column as number) <= 7;
}

function parseResultCheck(value: unknown, currentPly: number): ResultCheckState | null {
  if (value === null) return null;
  if (!isRecord(value) || !hasExactKeys(value, ["ply", "black", "white"])
    || value.ply !== currentPly
    || (value.black !== null && !REPORTED_RESULTS.includes(value.black as ReportedResult))
    || (value.white !== null && !REPORTED_RESULTS.includes(value.white as ReportedResult))
    || (value.black === null && value.white === null)) {
    throw new Error("Invalid result check");
  }
  return { ply: currentPly, black: value.black as ReportedResult | null, white: value.white as ReportedResult | null };
}

export function isSocketAttachment(value: unknown): value is SocketAttachment {
  if (!isRecord(value) || !hasExactKeys(value, [
    "schemaVersion", "memberId", "userId", "displayName", "avatarId", "acceptedAt", "seatHint", "playerColorHint",
  ])) return false;
  return value.schemaVersion === 1
    && typeof value.memberId === "string" && value.memberId.trim().length > 0
    && typeof value.userId === "string" && UUID_PATTERN.test(value.userId)
    && typeof value.displayName === "string" && value.displayName.trim().length > 0
    && typeof value.avatarId === "string" && AVATAR_IDS.includes(value.avatarId as AvatarId)
    && typeof value.acceptedAt === "number" && Number.isSafeInteger(value.acceptedAt) && value.acceptedAt >= 0
    && (value.seatHint === null || value.seatHint === "A" || value.seatHint === "B")
    && (value.playerColorHint === null || value.playerColorHint === "BLACK" || value.playerColorHint === "WHITE");
}

export function createInitialRoomState(
  roomId: string,
  timeControl: TimeControl,
  creator: InternalParticipant,
): ActiveRoomState {
  if (!roomId.trim() || !TIME_CONTROLS.includes(timeControl) || !isParticipant(creator)) {
    throw new Error("Invalid initial room state");
  }
  return {
    schemaVersion: 1,
    roomId,
    phase: "WAITING",
    timeControl,
    seats: { a: creator, b: null },
    players: null,
    currentPly: 0,
    currentTurn: "BLACK",
    latestSnapshot: { board: [...INITIAL_BOARD], move: null, terminalCandidate: false },
    resultCheck: null,
  };
}

export function createClosedRoomState(roomId: string, closedAt: number): ClosedRoomState {
  return { schemaVersion: 1, roomId, phase: "CLOSED", closedAt };
}

export function allocateMemberId(
  existingIds: ReadonlySet<string>,
  createId: () => string = () => crypto.randomUUID(),
): string {
  for (let attempt = 0; attempt < 8; attempt += 1) {
    const candidate = createId();
    if (!existingIds.has(candidate)) return candidate;
  }
  throw new Error("MEMBER_ID_COLLISION_LIMIT");
}

export function assignPlayers(
  a: InternalParticipant,
  b: InternalParticipant,
  randomBit: 0 | 1,
): { black: InternalParticipant; white: InternalParticipant } {
  return randomBit === 0 ? { black: a, white: b } : { black: b, white: a };
}

export type SeatTransitionError = "WRONG_PHASE" | "ALREADY_SEATED" | "NO_EMPTY_SEAT" | "NOT_SEATED";
export type SeatTransitionResult =
  | { ok: true; state: ActiveRoomState; startedPlaying: boolean }
  | { ok: false; error: SeatTransitionError };

export function takeSeat(
  state: ActiveRoomState,
  participant: InternalParticipant,
  nextRandomBit: () => 0 | 1,
): SeatTransitionResult {
  if (state.phase !== "WAITING") return { ok: false, error: "WRONG_PHASE" };
  if (state.seats.a?.memberId === participant.memberId || state.seats.b?.memberId === participant.memberId) {
    return { ok: false, error: "ALREADY_SEATED" };
  }
  if (state.seats.a && state.seats.b) return { ok: false, error: "NO_EMPTY_SEAT" };
  const seats = state.seats.a === null
    ? { a: participant, b: state.seats.b }
    : { a: state.seats.a, b: participant };
  if (seats.a && seats.b) {
    return {
      ok: true,
      startedPlaying: true,
      state: {
        ...state,
        phase: "PLAYING",
        seats,
        players: assignPlayers(seats.a, seats.b, nextRandomBit()),
        currentTurn: "BLACK",
      },
    };
  }
  return { ok: true, startedPlaying: false, state: { ...state, seats } };
}

export function leaveSeat(state: ActiveRoomState, memberId: string): SeatTransitionResult {
  if (state.phase !== "WAITING") return { ok: false, error: "WRONG_PHASE" };
  if (state.seats.a?.memberId === memberId) {
    return { ok: true, startedPlaying: false, state: { ...state, seats: { ...state.seats, a: null } } };
  }
  if (state.seats.b?.memberId === memberId) {
    return { ok: true, startedPlaying: false, state: { ...state, seats: { ...state.seats, b: null } } };
  }
  return { ok: false, error: "NOT_SEATED" };
}

export function attachmentRole(
  state: ActiveRoomState,
  attachment: SocketAttachment,
): { seat: Seat | null; playerColor: PlayerColor | null; spectator: boolean } {
  const seat = state.seats.a?.memberId === attachment.memberId
    ? "A"
    : state.seats.b?.memberId === attachment.memberId ? "B" : null;
  const playerColor = state.players?.black.memberId === attachment.memberId
    ? "BLACK"
    : state.players?.white.memberId === attachment.memberId ? "WHITE" : null;
  return { seat, playerColor, spectator: seat === null && playerColor === null };
}

export function playerColorForMember(state: ActiveRoomState, memberId: string): PlayerColor | null {
  if (state.players?.black.memberId === memberId) return "BLACK";
  if (state.players?.white.memberId === memberId) return "WHITE";
  return null;
}

export function reconcileAttachment(state: ActiveRoomState, attachment: SocketAttachment): SocketAttachment {
  const role = attachmentRole(state, attachment);
  return { ...attachment, seatHint: role.seat, playerColorHint: role.playerColor };
}

export function spectatorSummary(
  state: ActiveRoomState,
  attachments: readonly SocketAttachment[],
): { spectatorCount: number; spectatorAvatarPreview: AvatarId[] } {
  const spectators = attachments
    .filter((attachment) => attachmentRole(state, attachment).spectator)
    .sort((left, right) => left.memberId.localeCompare(right.memberId));
  return {
    spectatorCount: spectators.length,
    spectatorAvatarPreview: spectators.slice(0, 3).map((attachment) => attachment.avatarId),
  };
}

const publicParticipant = ({ memberId, displayName, avatarId }: InternalParticipant): ParticipantSummary =>
  ({ memberId, displayName, avatarId });

export function buildRoomSnapshot(state: ActiveRoomState, attachments: readonly SocketAttachment[]): RoomSnapshot {
  const spectators = spectatorSummary(state, attachments);
  return {
    protocolVersion: 1,
    type: "ROOM_SNAPSHOT",
    roomId: state.roomId,
    phase: state.phase,
    timeControl: state.timeControl,
    seats: {
      a: state.seats.a ? publicParticipant(state.seats.a) : null,
      b: state.seats.b ? publicParticipant(state.seats.b) : null,
    },
    players: state.players ? {
      black: publicParticipant(state.players.black),
      white: publicParticipant(state.players.white),
    } : null,
    currentPly: state.currentPly,
    board: [...state.latestSnapshot.board],
    move: state.latestSnapshot.move ? { ...state.latestSnapshot.move } : null,
    nextTurn: state.currentTurn,
    terminalCandidate: state.latestSnapshot.terminalCandidate,
    resultCheckPly: state.resultCheck?.ply ?? null,
    ...spectators,
  };
}

export function buildGameOver(
  state: ActiveRoomState,
  attachments: readonly SocketAttachment[],
  finishReason: FinishReason,
  outcome: Outcome,
  winner: PlayerColor | null,
  decidedAt: number,
): GameOverMessage {
  return {
    protocolVersion: 1,
    type: "GAME_OVER",
    roomId: state.roomId,
    ply: state.currentPly,
    finishReason,
    outcome,
    winner,
    decidedAt,
    finalSnapshot: buildRoomSnapshot(state, attachments),
  };
}

export function buildLobbyProjection(
  state: ActiveRoomState,
  attachments: readonly SocketAttachment[],
): LobbyProjection {
  const snapshot = buildRoomSnapshot(state, attachments);
  return {
    roomId: snapshot.roomId,
    timeControl: snapshot.timeControl,
    phase: snapshot.phase,
    seats: snapshot.seats,
    players: snapshot.players,
    spectatorCount: snapshot.spectatorCount,
  };
}

export function disconnectWaitingMember(
  state: ActiveRoomState,
  memberId: string,
  remainingMemberCount: number,
  now: number,
): PersistedRoomState {
  if (state.phase !== "WAITING") return state;
  if (remainingMemberCount === 0) return createClosedRoomState(state.roomId, now);
  return {
    ...state,
    seats: {
      a: state.seats.a?.memberId === memberId ? null : state.seats.a,
      b: state.seats.b?.memberId === memberId ? null : state.seats.b,
    },
  };
}

export function parsePersistedRoomState(value: unknown): PersistedRoomState {
  if (!isRecord(value) || value.schemaVersion !== 1 || typeof value.roomId !== "string" || !value.roomId.trim()) {
    throw new Error("Invalid room state");
  }
  if (value.phase === "CLOSED") {
    if (!hasExactKeys(value, ["schemaVersion", "roomId", "phase", "closedAt"])
      || typeof value.closedAt !== "number" || !Number.isSafeInteger(value.closedAt) || value.closedAt < 0) {
      throw new Error("Invalid CLOSED room state");
    }
    return { schemaVersion: 1, roomId: value.roomId, phase: "CLOSED", closedAt: value.closedAt };
  }
  if (value.phase !== "WAITING" && value.phase !== "PLAYING") throw new Error("Invalid room phase");
  if (!hasExactKeys(value, [
    "schemaVersion", "roomId", "phase", "timeControl", "seats", "players", "currentPly", "currentTurn", "latestSnapshot", "resultCheck",
  ])) throw new Error("Invalid active room state shape");
  if (typeof value.timeControl !== "string" || !TIME_CONTROLS.includes(value.timeControl as TimeControl)) throw new Error("Invalid time control");
  if (!isRecord(value.seats) || !hasExactKeys(value.seats, ["a", "b"])) throw new Error("Invalid seats");
  const a = value.seats.a;
  const b = value.seats.b;
  if ((a !== null && !isParticipant(a)) || (b !== null && !isParticipant(b))) throw new Error("Invalid seat participant");
  if (a && b && a.memberId === b.memberId) throw new Error("Duplicate seat participant");
  if (!Number.isSafeInteger(value.currentPly) || (value.currentPly as number) < 0) throw new Error("Invalid current ply");
  if (!isRecord(value.latestSnapshot) || !hasExactKeys(value.latestSnapshot, ["board", "move", "terminalCandidate"])
    || !isBoard(value.latestSnapshot.board) || typeof value.latestSnapshot.terminalCandidate !== "boolean") {
    throw new Error("Invalid latest snapshot");
  }
  const currentPly = value.currentPly as number;
  const move = value.latestSnapshot.move;
  if ((currentPly === 0 && move !== null) || (currentPly > 0 && !isMove(move))) throw new Error("Invalid snapshot move");
  const normalizedMove = move === null
    ? null
    : isMove(move) ? { row: move.row, column: move.column } : null;
  if (value.latestSnapshot.terminalCandidate) {
    if (value.currentTurn !== null) throw new Error("Invalid terminal current turn");
  } else if (value.currentTurn !== "BLACK" && value.currentTurn !== "WHITE") {
    throw new Error("Invalid current turn");
  }

  if (value.phase === "WAITING") {
    if (value.players !== null || (a !== null && b !== null) || currentPly !== 0 || value.currentTurn !== "BLACK"
      || move !== null || value.latestSnapshot.terminalCandidate || value.resultCheck !== null) {
      throw new Error("Invalid WAITING state");
    }
    return {
      schemaVersion: 1,
      roomId: value.roomId,
      phase: "WAITING",
      timeControl: value.timeControl as TimeControl,
      seats: { a, b },
      players: null,
      currentPly: 0,
      currentTurn: "BLACK",
      latestSnapshot: { board: [...value.latestSnapshot.board], move: null, terminalCandidate: false },
      resultCheck: null,
    };
  }

  if (!a || !b || !isRecord(value.players) || !hasExactKeys(value.players, ["black", "white"])
    || !isParticipant(value.players.black) || !isParticipant(value.players.white)) throw new Error("Invalid PLAYING state");
  const seatIds = new Set([a.memberId, b.memberId]);
  if (value.players.black.memberId === value.players.white.memberId
    || !seatIds.has(value.players.black.memberId) || !seatIds.has(value.players.white.memberId)) {
    throw new Error("Invalid player assignment");
  }
  const resultCheck = parseResultCheck(value.resultCheck, currentPly);
  return {
    schemaVersion: 1,
    roomId: value.roomId,
    phase: "PLAYING",
    timeControl: value.timeControl as TimeControl,
    seats: { a, b },
    players: { black: value.players.black, white: value.players.white },
    currentPly,
    currentTurn: value.currentTurn as PlayerColor | null,
    latestSnapshot: {
      board: [...value.latestSnapshot.board],
      move: normalizedMove,
      terminalCandidate: value.latestSnapshot.terminalCandidate,
    },
    resultCheck,
  };
}
