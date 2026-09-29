import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import {
  applyMoveSnapshot,
  applyResultReport,
  disconnectEnding,
  evaluateDesync,
  evaluateTimeout,
} from "../dist-test/src/game-relay.js";
import {
  parsePeoplePlayClientMessage,
  serializeGameOver,
  serializePeoplePlayError,
  serializeResultCheck,
} from "../dist-test/src/people-play-protocol.js";
import {
  INITIAL_BOARD,
  buildGameOver,
  createClosedRoomState,
  createInitialRoomState,
  parsePersistedRoomState,
  takeSeat,
} from "../dist-test/src/room-state.js";

const fixture = async (relative) => JSON.parse(await readFile(new URL(`../../protocol/people-play/v1/${relative}`, import.meta.url), "utf8"));
const participant = (memberId, prefix, avatarId = "BOY") => ({
  memberId,
  userId: `${prefix}-0000-4000-8000-000000000001`,
  displayName: `Player ${memberId}`,
  avatarId,
});
const attachment = (value) => ({
  schemaVersion: 1,
  memberId: value.memberId,
  userId: value.userId,
  displayName: value.displayName,
  avatarId: value.avatarId,
  acceptedAt: 10,
  seatHint: null,
  playerColorHint: null,
});

function playingState(roomId = "room-fixture-001") {
  const black = participant("member-black", "00000000");
  const white = participant("member-white", "00000001", "MAGIC_BOOK");
  const initial = createInitialRoomState(roomId, "TEN_MINUTES", black);
  return { state: takeSeat(initial, white, () => 0).state, black, white };
}

function moveCommand(roomId, overrides = {}) {
  return {
    protocolVersion: 1,
    type: "MOVE_SNAPSHOT",
    roomId,
    ply: 1,
    move: { row: 2, column: 3 },
    board: Array.from({ length: 64 }, (_, index) => index % 3),
    nextTurn: "WHITE",
    terminalCandidate: false,
    ...overrides,
  };
}

const resultCommand = (roomId, ply, result) => ({ protocolVersion: 1, type: "RESULT_REPORT", roomId, ply, result });

test("all six Phase 1 client fixtures parse with strict Phase 4 shapes", async () => {
  for (const name of ["take-seat", "leave-seat", "move-snapshot", "desync", "result-report", "timeout-self"]) {
    const value = await fixture(`fixtures/client/${name}.json`);
    assert.deepEqual(parsePeoplePlayClientMessage(JSON.stringify(value), value.roomId), value);
    assert.throws(() => parsePeoplePlayClientMessage(JSON.stringify({ ...value, extra: true }), value.roomId));
  }
  const move = await fixture("fixtures/client/move-snapshot.json");
  for (const invalid of [
    { ...move, board: move.board.slice(0, 63) },
    { ...move, board: [...move.board.slice(0, 63), 3] },
    { ...move, move: { row: 8, column: 0 } },
    { ...move, nextTurn: null, terminalCandidate: false },
    { ...move, nextTurn: "BLACK", terminalCandidate: true },
    { ...move, ply: 1.5 },
  ]) assert.throws(() => parsePeoplePlayClientMessage(JSON.stringify(invalid), move.roomId));
});

test("Phase 1 server fixtures retain exact RESULT_CHECK, GAME_OVER and ERROR wire shapes", async () => {
  const resultCheck = await fixture("fixtures/server/result-check.json");
  assert.deepEqual(JSON.parse(serializeResultCheck(resultCheck.roomId, resultCheck.ply)), resultCheck);
  for (const name of [
    "game-over-normal-black", "game-over-normal-draw", "game-over-timeout", "game-over-disconnect", "game-over-desync",
  ]) {
    const value = await fixture(`fixtures/server/${name}.json`);
    assert.deepEqual(JSON.parse(serializeGameOver(value)), value);
  }
  const errorFixture = await fixture("fixtures/server/error.json");
  assert.deepEqual(JSON.parse(serializePeoplePlayError(
    errorFixture.roomId,
    errorFixture.code,
    errorFixture.rejectedType,
    errorFixture.currentPly,
    errorFixture.rejectedPly,
  )), errorFixture);
});

test("MOVE accepts only the current player and replaces the full snapshot at currentPly plus one", () => {
  const { state, black } = playingState();
  const board = Array(64).fill(2);
  const accepted = applyMoveSnapshot(state, black.memberId, moveCommand(state.roomId, { board }));
  assert.equal(accepted.kind, "accepted");
  assert.equal(accepted.state.currentPly, 1);
  assert.deepEqual(accepted.state.latestSnapshot.board, board);
  assert.deepEqual(accepted.state.latestSnapshot.move, { row: 2, column: 3 });
  assert.equal(accepted.state.currentTurn, "WHITE");
  assert.equal(accepted.state.resultCheck, null);
});

test("MOVE authorization order rejects spectator, wrong turn, result pending and bad ply without mutation", () => {
  const { state, black, white } = playingState();
  assert.deepEqual(applyMoveSnapshot(state, "spectator", moveCommand(state.roomId)), {
    kind: "error", error: { code: "NOT_PLAYER", rejectedPly: null },
  });
  assert.deepEqual(applyMoveSnapshot(state, white.memberId, moveCommand(state.roomId)), {
    kind: "error", error: { code: "NOT_YOUR_TURN", rejectedPly: null },
  });
  for (const ply of [0, 2, 3]) {
    assert.deepEqual(applyMoveSnapshot(state, black.memberId, moveCommand(state.roomId, { ply })), {
      kind: "error", error: { code: "BAD_PLY", rejectedPly: ply },
    });
  }
  const pending = { ...state, resultCheck: { ply: 0, black: "BLACK_WIN", white: null } };
  assert.equal(applyMoveSnapshot(pending, black.memberId, moveCommand(state.roomId)).error.code, "RESULT_PENDING");
  assert.equal(applyMoveSnapshot(pending, white.memberId, moveCommand(state.roomId, { ply: 9 })).error.code, "RESULT_PENDING");
  const terminal = { ...state, currentTurn: null, latestSnapshot: { ...state.latestSnapshot, terminalCandidate: true } };
  assert.equal(applyMoveSnapshot(terminal, black.memberId, moveCommand(state.roomId)).error.code, "RESULT_PENDING");
  assert.equal(state.currentPly, 0);
});

test("game command phase guards take precedence in WAITING", () => {
  const creator = participant("waiting-member", "00000002");
  const waiting = createInitialRoomState("waiting-room", "TEN_MINUTES", creator);
  assert.equal(applyMoveSnapshot(waiting, creator.memberId, moveCommand(waiting.roomId)).error.code, "WRONG_PHASE");
  assert.equal(evaluateDesync(waiting, creator.memberId, {
    protocolVersion: 1, type: "DESYNC", roomId: waiting.roomId, observedPly: 0, reason: "SNAPSHOT_MISMATCH",
  }).error.code, "WRONG_PHASE");
  assert.equal(applyResultReport(waiting, creator.memberId, resultCommand(waiting.roomId, 99, "DRAW")).error.code, "WRONG_PHASE");
  assert.equal(evaluateTimeout(waiting, creator.memberId).error.code, "WRONG_PHASE");
});

test("forced pass keeps the same color and structurally valid illegal-looking boards are relayed", () => {
  const { state, black } = playingState();
  const unnatural = Array(64).fill(1);
  const first = applyMoveSnapshot(state, black.memberId, moveCommand(state.roomId, {
    board: unnatural,
    nextTurn: "BLACK",
  }));
  assert.equal(first.kind, "accepted");
  assert.equal(first.state.currentTurn, "BLACK");
  const second = applyMoveSnapshot(first.state, black.memberId, moveCommand(state.roomId, {
    ply: 2,
    move: { row: 0, column: 0 },
    board: Array(64).fill(0),
    nextTurn: "WHITE",
  }));
  assert.equal(second.kind, "accepted");
  assert.equal(second.state.currentPly, 2);
});

test("DESYNC accepts either player and any observed ply in one through current", () => {
  const { state, black, white } = playingState();
  const advanced = { ...state, currentPly: 3, latestSnapshot: { board: Array(64).fill(0), move: { row: 1, column: 1 }, terminalCandidate: false } };
  for (const memberId of [black.memberId, white.memberId]) {
    const result = evaluateDesync(advanced, memberId, {
      protocolVersion: 1, type: "DESYNC", roomId: state.roomId, observedPly: 1, reason: "SNAPSHOT_MISMATCH",
    });
    assert.deepEqual(result.ending, { finishReason: "DESYNC", outcome: "NO_CONTEST", winner: null });
  }
  assert.equal(evaluateDesync(advanced, "spectator", { protocolVersion: 1, type: "DESYNC", roomId: state.roomId, observedPly: 1, reason: "RESULT_MISMATCH" }).error.code, "NOT_PLAYER");
  for (const observedPly of [0, 4]) {
    const result = evaluateDesync(advanced, black.memberId, { protocolVersion: 1, type: "DESYNC", roomId: state.roomId, observedPly, reason: "RESULT_MISMATCH" });
    assert.deepEqual(result.error, { code: "BAD_PLY", rejectedPly: observedPly });
  }
});

test("RESULT_REPORT starts a check and targets only the other player color", () => {
  const { state, black } = playingState();
  const first = applyResultReport(state, black.memberId, resultCommand(state.roomId, 0, "BLACK_WIN"));
  assert.equal(first.kind, "result-check");
  assert.equal(first.targetColor, "WHITE");
  assert.deepEqual(first.state.resultCheck, { ply: 0, black: "BLACK_WIN", white: null });
  const duplicate = applyResultReport(first.state, black.memberId, resultCommand(state.roomId, 0, "BLACK_WIN"));
  assert.deepEqual(duplicate, { kind: "no-op" });
  const changed = applyResultReport(first.state, black.memberId, resultCommand(state.roomId, 0, "DRAW"));
  assert.deepEqual(changed.ending, { finishReason: "DESYNC", outcome: "NO_CONTEST", winner: null });

  const reportedOpponentWin = applyResultReport(state, black.memberId, resultCommand(state.roomId, 0, "WHITE_WIN"));
  assert.deepEqual(reportedOpponentWin.state.resultCheck, { ply: 0, black: "WHITE_WIN", white: null });
});

test("isolated NOT_FINISHED is a no-op while terminal or pending NOT_FINISHED is DESYNC", () => {
  const { state, black, white } = playingState();
  assert.deepEqual(applyResultReport(state, black.memberId, resultCommand(state.roomId, 0, "NOT_FINISHED")), { kind: "no-op" });
  const terminal = { ...state, currentTurn: null, latestSnapshot: { ...state.latestSnapshot, terminalCandidate: true } };
  assert.equal(applyResultReport(terminal, black.memberId, resultCommand(state.roomId, 0, "NOT_FINISHED")).ending.finishReason, "DESYNC");
  const pending = applyResultReport(state, black.memberId, resultCommand(state.roomId, 0, "BLACK_WIN")).state;
  assert.equal(applyResultReport(pending, white.memberId, resultCommand(state.roomId, 0, "NOT_FINISHED")).ending.finishReason, "DESYNC");
});

test("matching reports produce all NORMAL outcomes and mismatches produce DESYNC", () => {
  for (const [report, outcome, winner] of [
    ["BLACK_WIN", "BLACK_WIN", "BLACK"],
    ["WHITE_WIN", "WHITE_WIN", "WHITE"],
    ["DRAW", "DRAW", null],
  ]) {
    const { state, black, white } = playingState();
    const first = applyResultReport(state, black.memberId, resultCommand(state.roomId, 0, report));
    const second = applyResultReport(first.state, white.memberId, resultCommand(state.roomId, 0, report));
    assert.deepEqual(second.ending, { finishReason: "NORMAL", outcome, winner });
  }
  const { state, black, white } = playingState();
  const first = applyResultReport(state, black.memberId, resultCommand(state.roomId, 0, "BLACK_WIN"));
  assert.deepEqual(
    applyResultReport(first.state, white.memberId, resultCommand(state.roomId, 0, "WHITE_WIN")).ending,
    { finishReason: "DESYNC", outcome: "NO_CONTEST", winner: null },
  );
});

test("RESULT_REPORT rejects stale and future ply after phase and role checks", () => {
  const { state, black } = playingState();
  for (const ply of [-1, 1]) {
    const result = applyResultReport(state, black.memberId, resultCommand(state.roomId, ply, "DRAW"));
    assert.deepEqual(result.error, { code: "BAD_PLY", rejectedPly: ply });
  }
  assert.equal(applyResultReport(state, "spectator", resultCommand(state.roomId, 1, "DRAW")).error.code, "NOT_PLAYER");
});

test("TIMEOUT_SELF loses the sender regardless of turn or result check and rejects spectators", () => {
  const { state, black, white } = playingState();
  assert.deepEqual(evaluateTimeout(state, black.memberId).ending, { finishReason: "TIMEOUT", outcome: "WHITE_WIN", winner: "WHITE" });
  assert.deepEqual(evaluateTimeout(state, white.memberId).ending, { finishReason: "TIMEOUT", outcome: "BLACK_WIN", winner: "BLACK" });
  const pending = { ...state, resultCheck: { ply: 0, black: "DRAW", white: null } };
  assert.equal(evaluateTimeout(pending, white.memberId).kind, "finish");
  assert.equal(evaluateTimeout(state, "spectator").error.code, "NOT_PLAYER");
});

test("player disconnect loses only that color and spectator disconnect creates no result", () => {
  const { state, black, white } = playingState();
  assert.deepEqual(disconnectEnding(state, black.memberId), { finishReason: "DISCONNECT", outcome: "WHITE_WIN", winner: "WHITE" });
  assert.deepEqual(disconnectEnding(state, white.memberId), { finishReason: "DISCONNECT", outcome: "BLACK_WIN", winner: "BLACK" });
  assert.equal(disconnectEnding(state, "spectator"), null);
});

test("resultCheck survives persist and reload without changing either report slot", () => {
  const { state, black } = playingState();
  const checking = applyResultReport(state, black.memberId, resultCommand(state.roomId, 0, "BLACK_WIN")).state;
  assert.deepEqual(parsePersistedRoomState(JSON.parse(JSON.stringify(checking))).resultCheck, {
    ply: 0, black: "BLACK_WIN", white: null,
  });
});

test("GAME_OVER preserves the active board and PLAYING snapshot while CLOSED stays minimal", () => {
  const { state, black, white } = playingState();
  const moved = applyMoveSnapshot(state, black.memberId, moveCommand(state.roomId)).state;
  const now = 123456;
  for (const ending of [
    ["NORMAL", "BLACK_WIN", "BLACK"],
    ["NORMAL", "WHITE_WIN", "WHITE"],
    ["NORMAL", "DRAW", null],
    ["TIMEOUT", "WHITE_WIN", "WHITE"],
    ["DISCONNECT", "BLACK_WIN", "BLACK"],
    ["DESYNC", "NO_CONTEST", null],
  ]) {
    const gameOver = buildGameOver(moved, [attachment(black), attachment(white)], ...ending, now);
    assert.equal(gameOver.decidedAt, now);
    assert.equal(gameOver.finalSnapshot.phase, "PLAYING");
    assert.deepEqual(gameOver.finalSnapshot.board, moved.latestSnapshot.board);
  }
  const closed = createClosedRoomState(state.roomId, now);
  assert.deepEqual(Object.keys(closed).sort(), ["closedAt", "phase", "roomId", "schemaVersion"]);
  assert.equal("board" in closed || "result" in closed || "history" in closed, false);
});
