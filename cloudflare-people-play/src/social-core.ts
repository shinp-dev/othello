export const SOCIAL_SLOT_MILLIS = 30 * 60 * 1000;
export const MAX_SOCIAL_SLOTS_PER_READ = 12;
export const SOCIAL_MAX_FUTURE_MILLIS = 24 * 60 * 60 * 1000;
export const SOCIAL_MAX_PAST_READ_MILLIS = 24 * 60 * 60 * 1000;

export interface SocialAvailabilitySlot {
  slotStart: number;
  people: number;
  selected: boolean;
}

export function socialSlotStart(atMillis: number): number {
  if (!Number.isFinite(atMillis)) throw new Error("Invalid time");
  return Math.floor(atMillis / SOCIAL_SLOT_MILLIS) * SOCIAL_SLOT_MILLIS;
}

export function isSocialSlotAligned(slotStart: number): boolean {
  return Number.isSafeInteger(slotStart) && slotStart >= 0 && slotStart % SOCIAL_SLOT_MILLIS === 0;
}

export function validateSocialSlotForRead(slotStart: number, now = Date.now()): void {
  if (!isSocialSlotAligned(slotStart)) throw new Error("BAD_SLOT");
  if (slotStart < now - SOCIAL_MAX_PAST_READ_MILLIS || slotStart > now + SOCIAL_MAX_FUTURE_MILLIS) {
    throw new Error("BAD_SLOT");
  }
}

export function validateSocialSlotForWrite(
  slotStart: number,
  enabled: boolean,
  now = Date.now(),
): void {
  if (!isSocialSlotAligned(slotStart)) throw new Error("BAD_SLOT");
  if (slotStart > now + SOCIAL_MAX_FUTURE_MILLIS) throw new Error("BAD_SLOT");
  if (enabled && slotStart + SOCIAL_SLOT_MILLIS <= now) throw new Error("SLOT_EXPIRED");
  if (!enabled && slotStart < now - SOCIAL_MAX_PAST_READ_MILLIS) throw new Error("BAD_SLOT");
}

export function parseSocialSlotStarts(values: readonly string[], now = Date.now()): number[] {
  if (values.length < 1 || values.length > MAX_SOCIAL_SLOTS_PER_READ) throw new Error("BAD_SLOT");
  const slots = values.map((value) => {
    if (!/^\d{1,16}$/.test(value)) throw new Error("BAD_SLOT");
    const parsed = Number(value);
    validateSocialSlotForRead(parsed, now);
    return parsed;
  });
  if (new Set(slots).size !== slots.length) throw new Error("BAD_SLOT");
  return slots;
}

export function parseAvailabilityMutation(value: unknown): { slotStart: number; enabled: boolean } {
  if (typeof value !== "object" || value === null || Array.isArray(value)) throw new Error("BAD_BODY");
  const record = value as Record<string, unknown>;
  if (Object.keys(record).length !== 2 || !("slotStart" in record) || !("enabled" in record)) {
    throw new Error("BAD_BODY");
  }
  if (typeof record.slotStart !== "number" || !Number.isSafeInteger(record.slotStart) || typeof record.enabled !== "boolean") {
    throw new Error("BAD_BODY");
  }
  return { slotStart: record.slotStart, enabled: record.enabled };
}

export function parsePushDeviceMutation(value: unknown): { token: string } {
  if (typeof value !== "object" || value === null || Array.isArray(value)) throw new Error("BAD_BODY");
  const record = value as Record<string, unknown>;
  if (Object.keys(record).length !== 1 || typeof record.token !== "string") throw new Error("BAD_BODY");
  const token = record.token.trim();
  if (token.length < 16 || token.length > 4096 || /[\r\n]/.test(token)) throw new Error("BAD_BODY");
  return { token };
}
