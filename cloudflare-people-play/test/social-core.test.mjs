import assert from "node:assert/strict";
import test from "node:test";
import {
  SOCIAL_SLOT_MILLIS,
  parseAvailabilityMutation,
  parsePushDeviceMutation,
  parseSocialSlotStarts,
  validateSocialSlotForWrite,
} from "../dist-test/src/social-core.js";

test("social slots accept quarter-hour UTC offsets and remain bounded", () => {
  const now = Date.UTC(2026, 9, 3, 10, 5, 0, 0);
  const current = Date.UTC(2026, 9, 3, 10, 0, 0, 0);
  assert.deepEqual(
    parseSocialSlotStarts([String(current), String(current + SOCIAL_SLOT_MILLIS)], now),
    [current, current + SOCIAL_SLOT_MILLIS],
  );
  assert.throws(() => parseSocialSlotStarts([], now), /BAD_SLOT/);
  assert.throws(() => parseSocialSlotStarts([String(current), String(current)], now), /BAD_SLOT/);
  assert.deepEqual(
    parseSocialSlotStarts([String(current + 15 * 60 * 1000)], now),
    [current + 15 * 60 * 1000],
  );
  assert.throws(() => parseSocialSlotStarts([String(current + 60 * 1000)], now), /BAD_SLOT/);
});

test("expired slots cannot be enabled but can be disabled for cleanup", () => {
  const now = Date.UTC(2026, 9, 3, 10, 45, 0, 0);
  const expired = Date.UTC(2026, 9, 3, 10, 0, 0, 0);
  assert.throws(() => validateSocialSlotForWrite(expired, true, now), /SLOT_EXPIRED/);
  assert.doesNotThrow(() => validateSocialSlotForWrite(expired, false, now));
});

test("social mutations require exact safe shapes", () => {
  const slotStart = Date.UTC(2026, 9, 3, 11, 0, 0, 0);
  assert.deepEqual(parseAvailabilityMutation({ slotStart, enabled: true }), { slotStart, enabled: true });
  assert.throws(() => parseAvailabilityMutation({ slotStart, enabled: true, extra: 1 }), /BAD_BODY/);
  assert.deepEqual(parsePushDeviceMutation({ token: "fixture-device-token-123456" }), { token: "fixture-device-token-123456" });
  assert.throws(() => parsePushDeviceMutation({ token: "short" }), /BAD_BODY/);
});
