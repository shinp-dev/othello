import { DurableObject } from "cloudflare:workers";
import {
  SOCIAL_SLOT_MILLIS,
  type SocialAvailabilitySlot,
  validateSocialSlotForRead,
  validateSocialSlotForWrite,
} from "./social-core.js";

interface CountRow {
  [column: string]: SqlStorageValue;
  slot_start: number;
  people: number;
}

interface SelectedRow {
  [column: string]: SqlStorageValue;
  slot_start: number;
}

interface UserRow {
  [column: string]: SqlStorageValue;
  user_id: string;
}

interface PendingNotificationRow {
  [column: string]: SqlStorageValue;
  id: number;
  target_user_id: string;
  slot_start: number;
  room_id: string;
  token: string;
}

export interface PendingSocialNotification {
  id: number;
  targetUserId: string;
  slotStart: number;
  roomId: string;
  tokens: string[];
}

const NOTIFICATION_CLAIM_LEASE_MILLIS = 5 * 60 * 1000;

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function requireUserId(userId: string): void {
  if (!UUID_PATTERN.test(userId)) throw new Error("BAD_USER");
}

function requireRoomId(roomId: string): void {
  if (!UUID_PATTERN.test(roomId)) throw new Error("BAD_ROOM");
}

/** Ephemeral social availability, push-device registration, and notification dedupe. */
export class PeopleSocialRegistry extends DurableObject<Env> {
  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    ctx.storage.sql.exec(`
      CREATE TABLE IF NOT EXISTS availability (
        user_id TEXT NOT NULL,
        slot_start INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        PRIMARY KEY (user_id, slot_start)
      );
      CREATE INDEX IF NOT EXISTS availability_slot_idx
        ON availability(slot_start);

      CREATE TABLE IF NOT EXISTS push_devices (
        user_id TEXT NOT NULL,
        token TEXT NOT NULL,
        updated_at INTEGER NOT NULL,
        enabled INTEGER NOT NULL DEFAULT 1,
        PRIMARY KEY (user_id, token)
      );
      CREATE INDEX IF NOT EXISTS push_devices_user_idx
        ON push_devices(user_id, enabled);
      CREATE UNIQUE INDEX IF NOT EXISTS push_devices_token_unique_idx
        ON push_devices(token);

      CREATE TABLE IF NOT EXISTS notification_outbox (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        target_user_id TEXT NOT NULL,
        slot_start INTEGER NOT NULL,
        room_id TEXT NOT NULL,
        creator_user_id TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        claimed_at INTEGER NULL,
        delivered_at INTEGER NULL,
        UNIQUE (target_user_id, slot_start)
      );
      CREATE INDEX IF NOT EXISTS notification_outbox_pending_idx
        ON notification_outbox(delivered_at, claimed_at, created_at);
    `);
  }

  async getAvailability(
    userId: string,
    slotStarts: number[],
    now = Date.now(),
  ): Promise<SocialAvailabilitySlot[]> {
    requireUserId(userId);
    if (slotStarts.length === 0 || slotStarts.length > 12 || new Set(slotStarts).size !== slotStarts.length) {
      throw new Error("BAD_SLOT");
    }
    slotStarts.forEach((slot) => validateSocialSlotForRead(slot, now));
    this.cleanupExpired(now);

    const placeholders = slotStarts.map(() => "?").join(",");
    const counts = this.ctx.storage.sql.exec<CountRow>(
      `SELECT slot_start, COUNT(*) AS people
       FROM availability
       WHERE slot_start IN (${placeholders})
       GROUP BY slot_start`,
      ...slotStarts,
    ).toArray();
    const selected = this.ctx.storage.sql.exec<SelectedRow>(
      `SELECT slot_start
       FROM availability
       WHERE user_id = ? AND slot_start IN (${placeholders})`,
      userId,
      ...slotStarts,
    ).toArray();

    const countBySlot = new Map(counts.map((row) => [Number(row.slot_start), Number(row.people)]));
    const selectedSlots = new Set(selected.map((row) => Number(row.slot_start)));
    return slotStarts.map((slotStart) => ({
      slotStart,
      people: countBySlot.get(slotStart) ?? 0,
      selected: selectedSlots.has(slotStart),
    }));
  }

  async setAvailability(
    userId: string,
    slotStart: number,
    enabled: boolean,
    now = Date.now(),
  ): Promise<SocialAvailabilitySlot> {
    requireUserId(userId);
    validateSocialSlotForWrite(slotStart, enabled, now);
    this.cleanupExpired(now);

    if (enabled) {
      this.ctx.storage.sql.exec(
        `INSERT INTO availability (user_id, slot_start, updated_at)
         VALUES (?, ?, ?)
         ON CONFLICT(user_id, slot_start)
         DO UPDATE SET updated_at = excluded.updated_at`,
        userId,
        slotStart,
        now,
      );
    } else {
      this.ctx.storage.transactionSync(() => {
        this.ctx.storage.sql.exec(
          "DELETE FROM availability WHERE user_id = ? AND slot_start = ?",
          userId,
          slotStart,
        );
        this.ctx.storage.sql.exec(
          `DELETE FROM notification_outbox
           WHERE target_user_id = ? AND slot_start = ? AND delivered_at IS NULL`,
          userId,
          slotStart,
        );
      });
    }

    return (await this.getAvailability(userId, [slotStart], now))[0];
  }

  async registerPushDevice(userId: string, token: string, now = Date.now()): Promise<void> {
    requireUserId(userId);
    if (token.length < 16 || token.length > 4096 || /[\r\n]/.test(token)) throw new Error("BAD_TOKEN");
    this.ctx.storage.transactionSync(() => {
      this.ctx.storage.sql.exec(
        "DELETE FROM push_devices WHERE token = ? AND user_id <> ?",
        token,
        userId,
      );
      this.ctx.storage.sql.exec(
        `INSERT INTO push_devices (user_id, token, updated_at, enabled)
         VALUES (?, ?, ?, 1)
         ON CONFLICT(user_id, token)
         DO UPDATE SET updated_at = excluded.updated_at, enabled = 1`,
        userId,
        token,
        now,
      );
    });
  }

  async unregisterPushDevice(userId: string, token: string): Promise<void> {
    requireUserId(userId);
    this.ctx.storage.sql.exec(
      "DELETE FROM push_devices WHERE user_id = ? AND token = ?",
      userId,
      token,
    );
  }

  /**
   * Called only by the trusted room-create path. At most one notification is
   * queued per user per 30-minute slot, regardless of how many rooms open.
   */
  async recordRoomCreated(
    roomId: string,
    creatorUserId: string,
    createdAt = Date.now(),
  ): Promise<number> {
    requireRoomId(roomId);
    requireUserId(creatorUserId);
    this.cleanupExpired(createdAt);
    const targets = this.ctx.storage.sql.exec<UserRow & { slot_start: number }>(
      `SELECT DISTINCT a.user_id, a.slot_start
       FROM availability AS a
       WHERE a.slot_start <= ?
         AND a.slot_start + ? > ?
         AND a.user_id <> ?
         AND EXISTS (
           SELECT 1 FROM push_devices AS d
           WHERE d.user_id = a.user_id AND d.enabled = 1
         )`,
      createdAt,
      SOCIAL_SLOT_MILLIS,
      createdAt,
      creatorUserId,
    ).toArray();

    let queued = 0;
    for (const target of targets) {
      const slotStart = Number(target.slot_start);
      const inserted = this.ctx.storage.sql.exec<{ id: number }>(
        `INSERT INTO notification_outbox
           (target_user_id, slot_start, room_id, creator_user_id, created_at, claimed_at, delivered_at)
         VALUES (?, ?, ?, ?, ?, NULL, NULL)
         ON CONFLICT(target_user_id, slot_start) DO NOTHING
         RETURNING id`,
        target.user_id,
        slotStart,
        roomId,
        creatorUserId,
        createdAt,
      ).toArray();
      queued += inserted.length;
    }
    return queued;
  }

  /**
   * Prepared for the later FCM adapter. Claims are internal-only so clients
   * never receive device tokens.
   */
  async claimPendingNotifications(
    now = Date.now(),
    limit = 50,
  ): Promise<PendingSocialNotification[]> {
    const boundedLimit = Math.max(1, Math.min(100, Math.trunc(limit)));
    const leaseBefore = now - NOTIFICATION_CLAIM_LEASE_MILLIS;
    this.cleanupExpired(now);
    return this.ctx.storage.transactionSync(() => {
      const rows = this.ctx.storage.sql.exec<PendingNotificationRow>(
        `WITH candidates AS (
           SELECT o.id
           FROM notification_outbox AS o
           INNER JOIN availability AS a
             ON a.user_id = o.target_user_id AND a.slot_start = o.slot_start
           WHERE o.delivered_at IS NULL
             AND (o.claimed_at IS NULL OR o.claimed_at < ?)
             AND o.slot_start + ? > ?
             AND EXISTS (
               SELECT 1 FROM push_devices AS d
               WHERE d.user_id = o.target_user_id AND d.enabled = 1
             )
           ORDER BY o.created_at ASC, o.id ASC
           LIMIT ?
         )
         SELECT o.id, o.target_user_id, o.slot_start, o.room_id, d.token
         FROM candidates AS c
         INNER JOIN notification_outbox AS o ON o.id = c.id
         INNER JOIN push_devices AS d
           ON d.user_id = o.target_user_id AND d.enabled = 1
         ORDER BY o.created_at ASC, o.id ASC, d.token ASC`,
        leaseBefore,
        SOCIAL_SLOT_MILLIS,
        now,
        boundedLimit,
      ).toArray();

      const grouped = new Map<number, PendingSocialNotification>();
      for (const row of rows) {
        const id = Number(row.id);
        const existing = grouped.get(id);
        if (existing) {
          if (!existing.tokens.includes(row.token)) existing.tokens.push(row.token);
        } else {
          grouped.set(id, {
            id,
            targetUserId: row.target_user_id,
            slotStart: Number(row.slot_start),
            roomId: row.room_id,
            tokens: [row.token],
          });
        }
      }

      for (const id of grouped.keys()) {
        this.ctx.storage.sql.exec(
          `UPDATE notification_outbox
           SET claimed_at = ?
           WHERE id = ?
             AND delivered_at IS NULL
             AND (claimed_at IS NULL OR claimed_at < ?)`,
          now,
          id,
          leaseBefore,
        );
      }
      return [...grouped.values()];
    });
  }

  async releaseNotificationClaim(id: number): Promise<void> {
    if (!Number.isSafeInteger(id) || id <= 0) return;
    this.ctx.storage.sql.exec(
      "UPDATE notification_outbox SET claimed_at = NULL WHERE id = ? AND delivered_at IS NULL",
      id,
    );
  }

  async markNotificationDelivered(id: number, deliveredAt = Date.now()): Promise<void> {
    if (!Number.isSafeInteger(id) || id <= 0) return;
    this.ctx.storage.sql.exec(
      "UPDATE notification_outbox SET delivered_at = ?, claimed_at = NULL WHERE id = ?",
      deliveredAt,
      id,
    );
  }

  async pendingNotificationCount(): Promise<number> {
    return Number(
      this.ctx.storage.sql.exec<{ count: number }>(
        "SELECT COUNT(*) AS count FROM notification_outbox WHERE delivered_at IS NULL",
      ).toArray()[0]?.count ?? 0,
    );
  }

  private cleanupExpired(now: number): void {
    this.ctx.storage.sql.exec(
      "DELETE FROM availability WHERE slot_start + ? <= ?",
      SOCIAL_SLOT_MILLIS,
      now,
    );
    this.ctx.storage.sql.exec(
      "DELETE FROM notification_outbox WHERE slot_start + ? <= ?",
      SOCIAL_SLOT_MILLIS,
      now,
    );
  }
}
