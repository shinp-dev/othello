import assert from "node:assert/strict";
import test from "node:test";
import { allocateUniqueRoomId, MAX_ROOM_ID_ATTEMPTS, parseLobbyProjection } from "../dist-test/src/registry-core.js";

const participant = (memberId, displayName = memberId) => ({ memberId, displayName, avatarId: "BOY" });
const waiting = (overrides = {}) => ({
  roomId: "room-test-1",
  timeControl: "TEN_MINUTES",
  phase: "WAITING",
  seats: { a: participant("member-a", "Aster"), b: null },
  players: null,
  spectatorCount: 0,
  ...overrides,
});
const playing = (overrides = {}) => ({
  ...waiting(),
  phase: "PLAYING",
  seats: { a: participant("member-a", "Aster"), b: participant("member-b", "Birch") },
  players: { black: participant("member-b", "Birch"), white: participant("member-a", "Aster") },
  ...overrides,
});

test("projection accepts valid WAITING and PLAYING contracts", () => {
  assert.deepEqual(parseLobbyProjection(waiting()), waiting());
  assert.deepEqual(parseLobbyProjection(playing()), playing());
});

test("projection rejects invalid lobby fields and seat/player relationships", () => {
  const invalid = [
    waiting({ roomId: " " }),
    waiting({ timeControl: "ONE_MINUTE" }),
    waiting({ phase: "CLOSED" }),
    waiting({ seats: { a: participant(" "), b: null } }),
    waiting({ seats: { a: participant("a", " "), b: null } }),
    waiting({ seats: { a: participant("same"), b: participant("same") } }),
    waiting({ seats: { a: participant("a"), b: participant("b") } }),
    waiting({ spectatorCount: -1 }),
    waiting({ spectatorCount: 0.5 }),
    playing({ players: null }),
    playing({ players: { black: participant("same"), white: participant("same") } }),
    playing({ players: { black: participant("member-a"), white: participant("other") } }),
    playing({ seats: { a: participant("a"), b: null } }),
  ];
  for (const value of invalid) assert.throws(() => parseLobbyProjection(value));
});

test("projection rejects user identifiers and extra public fields", () => {
  assert.throws(() => parseLobbyProjection({ ...waiting(), userId: "private" }));
  assert.throws(() => parseLobbyProjection({ ...waiting(), seats: { a: { ...participant("a"), email: "private" }, b: null } }));
});

test("allocation retries only primary-key collisions and records issued IDs", async () => {
  const attempts = [];
  const ids = ["collision", "new-room"];
  const id = await allocateUniqueRoomId((roomId, issuedAt) => {
    attempts.push({ roomId, issuedAt });
    return roomId !== "collision";
  }, () => ids.shift(), () => 1234);
  assert.equal(id, "new-room");
  assert.deepEqual(attempts, [
    { roomId: "collision", issuedAt: 1234 },
    { roomId: "new-room", issuedAt: 1234 },
  ]);
});

test("allocation stops after eight ID collisions", async () => {
  let generated = 0;
  await assert.rejects(
    allocateUniqueRoomId(() => false, () => `collision-${++generated}`, () => 0),
    /ROOM_ID_COLLISION_LIMIT/,
  );
  assert.equal(generated, MAX_ROOM_ID_ATTEMPTS);
});

test("non-collision storage errors are not retried", async () => {
  let generated = 0;
  await assert.rejects(
    allocateUniqueRoomId(() => { throw new Error("storage unavailable"); }, () => `id-${++generated}`),
    /storage unavailable/,
  );
  assert.equal(generated, 1);
});
