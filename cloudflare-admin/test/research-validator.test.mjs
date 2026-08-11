import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { validateResearchGame } from "../dist/research-validator.js";

const properties = Object.fromEntries(
  readFileSync(new URL("../../core/game/src/test/resources/research-validator-v1.properties", import.meta.url), "utf8")
    .trim()
    .split(/\r?\n/)
    .map(line => line.split(/=(.*)/s).slice(0, 2)),
);

const normal = {
  canonicalMoves: properties["normal.canonicalMoves"],
  result: properties["normal.result"],
  finishReason: "NORMAL",
  finalPositionHash: properties["normal.finalPositionHash"],
  rulesetVersion: 1,
};

test("shared legal normal fixture including forced passes is accepted", () => {
  const result = validateResearchGame(normal);
  assert.deepEqual(result, {
    accepted: true,
    blackDecisionCount: Number(properties["normal.blackDecisionCount"]),
    whiteDecisionCount: Number(properties["normal.whiteDecisionCount"]),
  });
  assert.match(normal.canonicalMoves, /--/);
});

test("an illegal opening move is rejected", () => {
  assert.deepEqual(
    validateResearchGame({ ...normal, canonicalMoves: "a1" }),
    { accepted: false, rejectionCode: "ILLEGAL_MOVE" },
  );
});

test("an unnecessary pass is rejected", () => {
  assert.deepEqual(
    validateResearchGame({ ...normal, canonicalMoves: "--" }),
    { accepted: false, rejectionCode: "UNNECESSARY_PASS" },
  );
});

test("a missing forced pass is rejected", () => {
  assert.deepEqual(
    validateResearchGame({ ...normal, canonicalMoves: normal.canonicalMoves.replace("--", "") }),
    { accepted: false, rejectionCode: "MISSING_PASS" },
  );
});

test("a final hash mismatch is rejected", () => {
  assert.deepEqual(
    validateResearchGame({ ...normal, finalPositionHash: "0000000000000000:1:0:64" }),
    { accepted: false, rejectionCode: "FINAL_HASH_MISMATCH" },
  );
});

test("zero-ply non-normal finish is accepted with zero decisions", () => {
  assert.deepEqual(
    validateResearchGame({
      canonicalMoves: "",
      result: "BLACK_WIN",
      finishReason: "RESIGNATION",
      finalPositionHash: properties["zeroPly.finalPositionHash"],
      rulesetVersion: 1,
    }),
    { accepted: true, blackDecisionCount: 0, whiteDecisionCount: 0 },
  );
});

test("non-normal draw is rejected as inconsistent with the online finish protocol", () => {
  assert.deepEqual(
    validateResearchGame({
      canonicalMoves: "",
      result: "DRAW",
      finishReason: "TIMEOUT",
      finalPositionHash: properties["zeroPly.finalPositionHash"],
      rulesetVersion: 1,
    }),
    { accepted: false, rejectionCode: "NON_NORMAL_DRAW" },
  );
});
