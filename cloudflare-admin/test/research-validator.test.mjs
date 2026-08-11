import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { extractResearchDecisions, validateResearchGame } from "../dist/research-validator.js";

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

test("aggregation extraction emits only the opted-in side and excludes passes", () => {
  const decisions = extractResearchDecisions(normal, [{
    researchSubjectId: "11111111-1111-4111-8111-111111111111",
    disc: "BLACK",
    outcome: "WIN",
  }]);
  assert.equal(decisions.length, Number(properties["normal.blackDecisionCount"]));
  assert.ok(decisions.every(decision => decision.side === "BLACK"));
  assert.ok(decisions.every(decision => /^[0-9a-f]{16}$/.test(decision.black_hex)));
  assert.ok(decisions.every(decision => /^[0-9a-f]{16}$/.test(decision.legal_move_mask_hex)));
  assert.equal(decisions[0].move_index, 19);
  assert.equal(decisions[0].black_hex, "0000000810000000");
  assert.equal(decisions[0].white_hex, "0000001008000000");
  assert.equal(decisions[0].side, "BLACK");
});

test("aggregation child positions resolve forced pass to the next choice", () => {
  const decisions = extractResearchDecisions(normal, [
    { researchSubjectId: "11111111-1111-4111-8111-111111111111", disc: "BLACK", outcome: "WIN" },
    { researchSubjectId: "22222222-2222-4222-8222-222222222222", disc: "WHITE", outcome: "LOSS" },
  ]);
  assert.equal(decisions.length,
    Number(properties["normal.blackDecisionCount"]) + Number(properties["normal.whiteDecisionCount"]));
  const passOffset = normal.canonicalMoves.indexOf("--");
  const moveBeforePass = normal.canonicalMoves.slice(0, passOffset).match(/.{2}/g).filter(token => token !== "--").length - 1;
  const beforePass = decisions[moveBeforePass];
  assert.ok(beforePass.child_side);
  assert.equal(beforePass.child_side, beforePass.side);
  assert.notEqual(beforePass.child_legal_move_mask_hex, "0000000000000000");
});
