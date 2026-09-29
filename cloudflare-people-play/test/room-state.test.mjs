import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { deriveAvatarId } from "../dist-test/src/avatar.js";
import { parsePeoplePlayClientMessage } from "../dist-test/src/people-play-protocol.js";
import {
  INITIAL_BOARD,
  allocateMemberId,
  assignPlayers,
  attachmentRole,
  buildRoomSnapshot,
  createClosedRoomState,
  createInitialRoomState,
  disconnectWaitingMember,
  leaveSeat,
  parsePersistedRoomState,
  reconcileAttachment,
  spectatorSummary,
  takeSeat,
} from "../dist-test/src/room-state.js";

const fixture = async (relative) => JSON.parse(await readFile(new URL(`../../protocol/people-play/v1/${relative}`, import.meta.url), "utf8"));
const userId = (prefix, tail) => `${prefix}-0000-4000-8000-${String(tail).padStart(12, "0")}`;
const participant = (memberId, user = userId("00000000", memberId.length), avatarId = "ADULT_MAN") => ({
  memberId,
  userId: user,
  displayName: `Name ${memberId}`,
  avatarId,
});
const attachment = (memberId, user = userId("00000000", memberId.length), avatarId = "ADULT_MAN", overrides = {}) => ({
  schemaVersion: 1,
  memberId,
  userId: user,
  displayName: `Name ${memberId}`,
  avatarId,
  acceptedAt: 100,
  seatHint: null,
  playerColorHint: null,
  ...overrides,
});

test("avatar derivation matches the shared UUID fixtures", async () => {
  const fixtures = await fixture("avatar-derivation-fixtures.json");
  for (const entry of fixtures) assert.equal(deriveAvatarId(entry.userId), entry.avatarId);
  assert.equal(deriveAvatarId("FFFFFFFF-0000-4000-8000-000000000003"), "GIRL");
});

test("creator starts in seat A and a normal join remains a spectator", () => {
  const creator = participant("creator");
  const state = createInitialRoomState("room-one", "TEN_MINUTES", creator);
  assert.equal(state.phase, "WAITING");
  assert.equal(state.seats.a.memberId, "creator");
  assert.equal(state.seats.b, null);
  assert.equal(state.players, null);
  const joined = attachment("spectator", userId("00000001", "2"), "ADULT_WOMAN");
  assert.deepEqual(attachmentRole(state, joined), { seat: null, playerColor: null, spectator: true });
  assert.equal(buildRoomSnapshot(state, [attachment("creator"), joined]).spectatorCount, 1);
});

test("same UUID sockets keep distinct member authority and member IDs retry collisions", () => {
  const sameUser = userId("00000002", "3");
  const first = attachment("member-one", sameUser);
  const second = attachment("member-two", sameUser);
  assert.notEqual(first.memberId, second.memberId);
  const generated = ["member-one", "member-two", "member-three"];
  assert.equal(allocateMemberId(new Set(["member-one", "member-two"]), () => generated.shift()), "member-three");
});

test("free seating, leave and A-empty/B-occupied state remain explicit", () => {
  const creator = participant("creator");
  const initial = createInitialRoomState("room-free", "FIVE_MINUTES", creator);
  const left = leaveSeat(initial, "creator");
  assert.equal(left.ok, true);
  assert.equal(left.state.seats.a, null);
  const seated = takeSeat(left.state, participant("joiner"), () => 0);
  assert.equal(seated.ok, true);
  assert.equal(seated.state.seats.a.memberId, "joiner");

  const bOnly = { ...initial, seats: { a: null, b: participant("member-b") } };
  assert.equal(parsePersistedRoomState(bOnly).seats.b.memberId, "member-b");
  const fillsA = takeSeat(bOnly, participant("member-a"), () => 1);
  assert.equal(fillsA.ok, true);
  assert.equal(fillsA.state.phase, "PLAYING");
});

test("second seat starts PLAYING atomically and random mapping is used once", () => {
  const initial = createInitialRoomState("room-start", "THREE_MINUTES", participant("a"));
  assert.equal(assignPlayers(participant("a"), participant("b"), 0).black.memberId, "a");
  assert.equal(assignPlayers(participant("a"), participant("b"), 1).black.memberId, "b");
  let randomCalls = 0;
  const started = takeSeat(initial, participant("b"), () => { randomCalls += 1; return 1; });
  assert.equal(started.ok, true);
  assert.equal(started.state.phase, "PLAYING");
  assert.equal(started.state.players.black.memberId, "b");
  assert.equal(started.state.players.white.memberId, "a");
  assert.equal(started.state.currentTurn, "BLACK");
  assert.equal(randomCalls, 1);
  const rejected = takeSeat(started.state, participant("c"), () => { randomCalls += 1; return 0; });
  assert.deepEqual(rejected, { ok: false, error: "WRONG_PHASE" });
  assert.equal(randomCalls, 1);
});

test("duplicate seating, missing leave and wrong phase are rejected", () => {
  const initial = createInitialRoomState("room-errors", "TWENTY_MINUTES", participant("a"));
  assert.deepEqual(takeSeat(initial, participant("a"), () => 0), { ok: false, error: "ALREADY_SEATED" });
  assert.deepEqual(leaveSeat(initial, "missing"), { ok: false, error: "NOT_SEATED" });
  const playing = takeSeat(initial, participant("b"), () => 0).state;
  assert.deepEqual(leaveSeat(playing, "a"), { ok: false, error: "WRONG_PHASE" });
});

test("initial board and Phase 1 fixtures remain the same wire contract", async () => {
  const waitingFixture = await fixture("fixtures/server/room-snapshot-waiting.json");
  assert.deepEqual(INITIAL_BOARD, waitingFixture.board);
  const takeFixture = await fixture("fixtures/client/take-seat.json");
  const leaveFixture = await fixture("fixtures/client/leave-seat.json");
  assert.equal(parsePeoplePlayClientMessage(JSON.stringify(takeFixture), takeFixture.roomId).type, "TAKE_SEAT");
  assert.equal(parsePeoplePlayClientMessage(JSON.stringify(leaveFixture), leaveFixture.roomId).type, "LEAVE_SEAT");
  for (const invalid of [
    { ...takeFixture, protocolVersion: 2 },
    { ...takeFixture, roomId: "other" },
    { ...takeFixture, extra: true },
    { ...takeFixture, type: "MOVE_SNAPSHOT" },
  ]) assert.throws(() => parsePeoplePlayClientMessage(JSON.stringify(invalid), takeFixture.roomId));
  assert.throws(
    () => parsePeoplePlayClientMessage(JSON.stringify({ ...takeFixture, type: "raw-secret-like-value" }), takeFixture.roomId),
    (error) => error.rejectedType === "UNKNOWN",
  );
});

test("spectator count uses memberId sort and preview is capped at three", () => {
  const state = createInitialRoomState("room-preview", "TEN_MINUTES", participant("seat-a"));
  const spectators = [
    attachment("z-member", userId("00000005", "1"), "MAGIC_BOOK"),
    attachment("a-member", userId("00000001", "2"), "ADULT_WOMAN"),
    attachment("m-member", userId("00000003", "3"), "GIRL"),
    attachment("b-member", userId("00000002", "4"), "BOY"),
  ];
  const summary = spectatorSummary(state, [attachment("seat-a"), ...spectators]);
  assert.equal(summary.spectatorCount, 4);
  assert.deepEqual(summary.spectatorAvatarPreview, ["ADULT_WOMAN", "BOY", "GIRL"]);
});

test("attachment hints reconcile from storage without carrying credentials", () => {
  const initial = createInitialRoomState("room-hints", "TEN_MINUTES", participant("a"));
  const playing = takeSeat(initial, participant("b"), () => 1).state;
  const stale = attachment("a", userId("00000000", "1"), "ADULT_MAN", { seatHint: "B", playerColorHint: "BLACK" });
  const fixed = reconcileAttachment(playing, stale);
  assert.equal(fixed.seatHint, "A");
  assert.equal(fixed.playerColorHint, "WHITE");
  assert.deepEqual(Object.keys(fixed).sort(), [
    "acceptedAt", "avatarId", "displayName", "memberId", "playerColorHint", "schemaVersion", "seatHint", "userId",
  ]);
  assert.equal("accessToken" in fixed, false);
  assert.equal("refreshToken" in fixed, false);
});

test("WAITING last-member departure becomes a minimal CLOSED marker", () => {
  const initial = createInitialRoomState("room-close", "TEN_MINUTES", participant("a"));
  const closed = disconnectWaitingMember(initial, "a", 0, 1234);
  assert.deepEqual(closed, createClosedRoomState("room-close", 1234));
  assert.deepEqual(Object.keys(closed).sort(), ["closedAt", "phase", "roomId", "schemaVersion"]);
  assert.deepEqual(parsePersistedRoomState(JSON.parse(JSON.stringify(closed))), closed);
});
