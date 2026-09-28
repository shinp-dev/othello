export const TIME_CONTROLS = [
  "TWENTY_MINUTES",
  "FIFTEEN_MINUTES",
  "TEN_MINUTES",
  "FIVE_MINUTES",
  "THREE_MINUTES",
] as const;

export const AVATAR_IDS = [
  "ADULT_MAN",
  "ADULT_WOMAN",
  "BOY",
  "GIRL",
  "MAGIC_WAND",
  "MAGIC_BOOK",
] as const;

export const ROOM_PHASES = ["WAITING", "PLAYING"] as const;

export type TimeControl = (typeof TIME_CONTROLS)[number];
export type AvatarId = (typeof AVATAR_IDS)[number];
export type RoomPhase = (typeof ROOM_PHASES)[number];

export interface ParticipantSummary {
  memberId: string;
  displayName: string;
  avatarId: AvatarId;
}

export interface Seats {
  a: ParticipantSummary | null;
  b: ParticipantSummary | null;
}

export interface Players {
  black: ParticipantSummary;
  white: ParticipantSummary;
}

export interface LobbyProjection {
  roomId: string;
  timeControl: TimeControl;
  phase: RoomPhase;
  seats: Seats;
  players: Players | null;
  spectatorCount: number;
}

export class ProjectionValidationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ProjectionValidationError";
  }
}

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

function exactKeys(value: Record<string, unknown>, allowed: readonly string[], label: string): void {
  if (Object.keys(value).length !== allowed.length || Object.keys(value).some((key) => !allowed.includes(key))) {
    throw new ProjectionValidationError(`${label} has an invalid shape`);
  }
}

function parseParticipant(value: unknown, label: string): ParticipantSummary | null {
  if (value === null) return null;
  if (!isRecord(value)) throw new ProjectionValidationError(`${label} must be a participant or null`);
  exactKeys(value, ["memberId", "displayName", "avatarId"], label);
  if (typeof value.memberId !== "string" || value.memberId.trim().length === 0) {
    throw new ProjectionValidationError(`${label}.memberId is blank`);
  }
  if (typeof value.displayName !== "string" || value.displayName.trim().length === 0) {
    throw new ProjectionValidationError(`${label}.displayName is blank`);
  }
  if (typeof value.avatarId !== "string" || !AVATAR_IDS.includes(value.avatarId as AvatarId)) {
    throw new ProjectionValidationError(`${label}.avatarId is invalid`);
  }
  return { memberId: value.memberId, displayName: value.displayName, avatarId: value.avatarId as AvatarId };
}

export function parseLobbyProjection(value: unknown): LobbyProjection {
  if (!isRecord(value)) throw new ProjectionValidationError("Projection must be an object");
  exactKeys(value, ["roomId", "timeControl", "phase", "seats", "players", "spectatorCount"], "Projection");

  if (typeof value.roomId !== "string" || value.roomId.trim().length === 0) {
    throw new ProjectionValidationError("roomId is blank");
  }
  if (typeof value.timeControl !== "string" || !TIME_CONTROLS.includes(value.timeControl as TimeControl)) {
    throw new ProjectionValidationError("timeControl is invalid");
  }
  if (typeof value.phase !== "string" || !ROOM_PHASES.includes(value.phase as RoomPhase)) {
    throw new ProjectionValidationError("phase is invalid");
  }
  if (!isRecord(value.seats)) throw new ProjectionValidationError("seats must be an object");
  exactKeys(value.seats, ["a", "b"], "seats");
  const seats: Seats = {
    a: parseParticipant(value.seats.a, "seats.a"),
    b: parseParticipant(value.seats.b, "seats.b"),
  };
  if (seats.a && seats.b && seats.a.memberId === seats.b.memberId) {
    throw new ProjectionValidationError("Seat members must differ");
  }

  let players: Players | null = null;
  if (value.players !== null) {
    if (!isRecord(value.players)) throw new ProjectionValidationError("players must be an object or null");
    exactKeys(value.players, ["black", "white"], "players");
    const black = parseParticipant(value.players.black, "players.black");
    const white = parseParticipant(value.players.white, "players.white");
    if (!black || !white) throw new ProjectionValidationError("PLAYING players must be participants");
    if (black.memberId === white.memberId) throw new ProjectionValidationError("Player members must differ");
    players = { black, white };
  }

  if (typeof value.spectatorCount !== "number" || !Number.isInteger(value.spectatorCount) || value.spectatorCount < 0) {
    throw new ProjectionValidationError("spectatorCount must be a nonnegative integer");
  }

  if (value.phase === "WAITING") {
    if (players !== null) throw new ProjectionValidationError("WAITING players must be null");
    if (seats.a !== null && seats.b !== null) throw new ProjectionValidationError("WAITING cannot have both seats occupied");
  } else {
    if (!players) throw new ProjectionValidationError("PLAYING players are required");
    if (!seats.a || !seats.b) throw new ProjectionValidationError("PLAYING requires both seats");
    const seatMembers = new Set([seats.a.memberId, seats.b.memberId]);
    const playerMembers = new Set([players.black.memberId, players.white.memberId]);
    if (seatMembers.size !== 2 || playerMembers.size !== 2 || [...seatMembers].some((memberId) => !playerMembers.has(memberId))) {
      throw new ProjectionValidationError("PLAYING players must match the two seated members");
    }
  }

  return {
    roomId: value.roomId,
    timeControl: value.timeControl as TimeControl,
    phase: value.phase as RoomPhase,
    seats,
    players,
    spectatorCount: value.spectatorCount,
  };
}

export const MAX_ROOM_ID_ATTEMPTS = 8;

/** Retries only when the storage adapter reports a primary-key collision. */
export async function allocateUniqueRoomId(
  insertIssuedId: (roomId: string, issuedAt: number) => boolean | Promise<boolean>,
  createId: () => string = () => crypto.randomUUID(),
  now: () => number = () => Date.now(),
): Promise<string> {
  for (let attempt = 0; attempt < MAX_ROOM_ID_ATTEMPTS; attempt += 1) {
    const roomId = createId();
    if (await insertIssuedId(roomId, now())) return roomId;
  }
  throw new Error("ROOM_ID_COLLISION_LIMIT");
}
