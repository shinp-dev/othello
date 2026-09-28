import { DurableObject } from "cloudflare:workers";
import { allocateUniqueRoomId, parseLobbyProjection, type LobbyProjection } from "./registry-core.js";

export type RegistryOperationError =
  | "NOT_FOUND"
  | "ROOM_CLOSED"
  | "ALREADY_PUBLISHED"
  | "NOT_PUBLISHED"
  | "INVALID_PROJECTION";

export type RegistryOperationResult =
  | { ok: true }
  | { ok: false; error: RegistryOperationError };

export type ResolveRoomResult = { status: "active" | "closed" | "unknown" };

interface IssuedRoomRow {
  [column: string]: SqlStorageValue;
  closed_at: number | null;
}

interface ProjectionRow {
  [column: string]: SqlStorageValue;
  room_id: string;
  projection_json: string;
}

/** The single public lobby projection and never-reused room ID ledger. */
export class RoomRegistry extends DurableObject<Env> {
  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    ctx.storage.sql.exec(`
      CREATE TABLE IF NOT EXISTS issued_room_ids (
        room_id TEXT PRIMARY KEY,
        issued_at INTEGER NOT NULL,
        closed_at INTEGER NULL
      );
      CREATE TABLE IF NOT EXISTS room_projections (
        room_id TEXT PRIMARY KEY,
        projection_json TEXT NOT NULL,
        updated_at INTEGER NOT NULL
      );
    `);
  }

  async allocateRoomId(): Promise<string> {
    return allocateUniqueRoomId((roomId, issuedAt) => {
      const inserted = this.ctx.storage.sql.exec<{ room_id: string }>(
        `INSERT INTO issued_room_ids (room_id, issued_at, closed_at)
         VALUES (?, ?, NULL)
         ON CONFLICT(room_id) DO NOTHING
         RETURNING room_id`,
        roomId,
        issuedAt,
      ).toArray();
      return inserted.length === 1;
    });
  }

  async publishRoom(roomId: string, candidate: unknown): Promise<RegistryOperationResult> {
    let projection: LobbyProjection;
    try {
      projection = parseLobbyProjection(candidate);
    } catch {
      return { ok: false, error: "INVALID_PROJECTION" };
    }
    if (!roomId.trim() || projection.roomId !== roomId) return { ok: false, error: "INVALID_PROJECTION" };

    return this.ctx.storage.transactionSync(() => {
      const issued = this.findIssuedRoom(roomId);
      if (!issued) return { ok: false, error: "NOT_FOUND" } as const;
      if (issued.closed_at !== null) return { ok: false, error: "ROOM_CLOSED" } as const;
      if (this.hasProjection(roomId)) return { ok: false, error: "ALREADY_PUBLISHED" } as const;
      this.ctx.storage.sql.exec(
        "INSERT INTO room_projections (room_id, projection_json, updated_at) VALUES (?, ?, ?)",
        roomId,
        JSON.stringify(projection),
        Date.now(),
      );
      return { ok: true } as const;
    });
  }

  async updateRoomProjection(roomId: string, candidate: unknown): Promise<RegistryOperationResult> {
    let projection: LobbyProjection;
    try {
      projection = parseLobbyProjection(candidate);
    } catch {
      return { ok: false, error: "INVALID_PROJECTION" };
    }
    if (!roomId.trim() || projection.roomId !== roomId) return { ok: false, error: "INVALID_PROJECTION" };

    return this.ctx.storage.transactionSync(() => {
      const issued = this.findIssuedRoom(roomId);
      if (!issued) return { ok: false, error: "NOT_FOUND" } as const;
      if (issued.closed_at !== null) return { ok: false, error: "ROOM_CLOSED" } as const;
      if (!this.hasProjection(roomId)) return { ok: false, error: "NOT_PUBLISHED" } as const;
      this.ctx.storage.sql.exec(
        "UPDATE room_projections SET projection_json = ?, updated_at = ? WHERE room_id = ?",
        JSON.stringify(projection),
        Date.now(),
        roomId,
      );
      return { ok: true } as const;
    });
  }

  async closeRoom(roomId: string): Promise<RegistryOperationResult> {
    if (!roomId.trim()) return { ok: false, error: "NOT_FOUND" };
    return this.ctx.storage.transactionSync(() => {
      const issued = this.findIssuedRoom(roomId);
      if (!issued) return { ok: false, error: "NOT_FOUND" } as const;
      if (issued.closed_at !== null) return { ok: true } as const;
      this.ctx.storage.sql.exec("DELETE FROM room_projections WHERE room_id = ?", roomId);
      this.ctx.storage.sql.exec(
        "UPDATE issued_room_ids SET closed_at = ? WHERE room_id = ? AND closed_at IS NULL",
        Date.now(),
        roomId,
      );
      return { ok: true } as const;
    });
  }

  async resolveRoom(roomId: string): Promise<ResolveRoomResult> {
    if (!roomId.trim()) return { status: "unknown" };
    const issued = this.findIssuedRoom(roomId);
    if (!issued) return { status: "unknown" };
    return issued.closed_at === null ? { status: "active" } : { status: "closed" };
  }

  async listRooms(): Promise<LobbyProjection[]> {
    const rows = this.ctx.storage.sql.exec<ProjectionRow>(
      `SELECT p.room_id, p.projection_json
       FROM room_projections AS p
       INNER JOIN issued_room_ids AS i ON i.room_id = p.room_id
       WHERE i.closed_at IS NULL`,
    ).toArray();

    return rows.map((row) => {
      const parsed = parseLobbyProjection(JSON.parse(row.projection_json) as unknown);
      if (parsed.roomId !== row.room_id) throw new Error("Stored projection room ID mismatch");
      return parsed;
    });
  }

  private findIssuedRoom(roomId: string): IssuedRoomRow | null {
    return this.ctx.storage.sql.exec<IssuedRoomRow>(
      "SELECT closed_at FROM issued_room_ids WHERE room_id = ?",
      roomId,
    ).toArray()[0] ?? null;
  }

  private hasProjection(roomId: string): boolean {
    return this.ctx.storage.sql.exec<{ room_id: string }>(
      "SELECT room_id FROM room_projections WHERE room_id = ?",
      roomId,
    ).toArray().length === 1;
  }
}
