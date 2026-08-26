import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import {
  ResearchBatchStageError,
  researchBatchFailureDiagnostic,
  runResearchBatch,
} from "../dist-batch/research-batch.js";

const properties = Object.fromEntries(
  readFileSync(new URL("../../core/game/src/test/resources/research-validator-v1.properties", import.meta.url), "utf8")
    .trim()
    .split(/\r?\n/)
    .map(line => line.split(/=(.*)/s).slice(0, 2)),
);

const normalSource = id => ({
  research_game_id: id,
  canonical_moves: properties["normal.canonicalMoves"],
  result: properties["normal.result"],
  finish_reason: "NORMAL",
  final_position_hash: properties["normal.finalPositionHash"],
  ruleset_version: 1,
  contributors: [{
    research_subject_id: "11111111-1111-4111-8111-111111111111",
    disc: "BLACK",
    outcome: "WIN",
  }],
});

const validationGame = id => ({
  research_game_id: id,
  lease_token: `00000000-0000-4000-8000-${String(id).padStart(12, "0")}`,
  canonical_moves: "",
  result: "BLACK_WIN",
  finish_reason: "RESIGNATION",
  final_position_hash: properties["zeroPly.finalPositionHash"],
  ruleset_version: 1,
});

class FakeStore {
  constructor({ validation = [], aggregation = [] } = {}) {
    this.validation = [...validation];
    this.aggregation = [...aggregation];
    this.completed = [];
    this.processed = new Set();
    this.checkpoints = 0;
    this.published = false;
    this.failed = null;
    this.throwOnAppend = false;
    this.claim = {
      generation_id: 7,
      lease_token: "77777777-7777-4777-8777-777777777777",
      source_watermark: aggregation.at(-1)?.research_game_id ?? 0,
      ruleset_version: 1,
      normalization_version: 1,
    };
  }

  async claimValidation(limit) {
    return this.validation.splice(0, limit);
  }

  async completeValidation(game, result) {
    this.completed.push({ game, result });
  }

  async claimAggregation() {
    return this.aggregation.length === 0 || this.published || this.failed ? null : this.claim;
  }

  async getAggregationSources(_claim, limit) {
    return this.aggregation.filter(source => !this.processed.has(source.research_game_id)).slice(0, limit);
  }

  async appendAggregationGame(_claim, gameId) {
    if (this.throwOnAppend) {
      this.throwOnAppend = false;
      throw new Error("transient database failure");
    }
    const before = this.processed.size;
    this.processed.add(gameId);
    return this.processed.size !== before;
  }

  async checkpointAggregation() {
    this.checkpoints += 1;
  }

  async publishAggregation() {
    this.published = true;
    return "PUBLISHED";
  }

  async failAggregation(_claim, code) {
    this.failed = code;
  }
}

const options = {
  validationMaxGames: 2,
  validationBatchSize: 1,
  validationLeaseSeconds: 300,
  aggregationMaxGames: 2,
  aggregationPageSize: 1,
  aggregationLeaseSeconds: 1800,
};

test("bounded run checkpoints backlog and a later run resumes without duplicates", async () => {
  const store = new FakeStore({
    validation: [validationGame(1), validationGame(2), validationGame(3)],
    aggregation: [normalSource(1), normalSource(2), normalSource(3)],
  });
  const first = await runResearchBatch(store, options);
  assert.deepEqual(first, {
    validated: 2,
    aggregationStatus: "CHECKPOINTED",
    aggregationProcessed: 2,
    generationId: 7,
  });
  assert.equal(store.validation.length, 1);
  assert.equal(store.processed.size, 2);
  assert.equal(store.checkpoints, 1);

  const second = await runResearchBatch(store, options);
  assert.equal(second.validated, 1);
  assert.equal(second.aggregationStatus, "PUBLISHED");
  assert.equal(second.aggregationProcessed, 1);
  assert.equal(store.processed.size, 3);
  assert.equal(store.published, true);
});

test("failed Actions run leaves the generation resumable after lease recovery", async () => {
  const store = new FakeStore({ aggregation: [normalSource(1)] });
  store.throwOnAppend = true;
  await assert.rejects(runResearchBatch(store, options), error => {
    assert.equal(error instanceof ResearchBatchStageError, true);
    assert.equal(error.stage, "append_aggregation");
    assert.equal(error.originalError?.message, "transient database failure");
    return true;
  });
  assert.equal(store.failed, null);
  assert.equal(store.published, false);
  assert.equal(store.processed.size, 0);

  const resumed = await runResearchBatch(store, options);
  assert.equal(resumed.aggregationStatus, "PUBLISHED");
  assert.equal(store.processed.size, 1);
});

test("public failure diagnostic allow-lists structure and drops sensitive values", () => {
  class DatabaseError extends Error {}
  const databaseError = Object.assign(
    new DatabaseError("password=secret canonical_moves=d3c4 user@example.com"),
    {
      code: "42501",
      severity: "ERROR",
      routine: "aclcheck_error",
      schema: "research_private",
      table: "games",
      column: "validation_status",
      constraint: "games_validation_status_check",
      detail: "match id 00000000-0000-0000-0000-000000000001",
      connectionString: "postgresql://research_batch:secret@example.invalid/postgres",
    },
  );

  const diagnostic = researchBatchFailureDiagnostic(
    new ResearchBatchStageError("claim_validation", databaseError),
  );
  assert.deepEqual(diagnostic, {
    event: "research_batch_failed",
    stage: "claim_validation",
    errorType: "DatabaseError",
    postgresCode: "42501",
    severity: "ERROR",
    routine: "aclcheck_error",
    constraint: "games_validation_status_check",
    schema: "research_private",
    table: "games",
    column: "validation_status",
  });
  const publicLog = JSON.stringify(diagnostic);
  for (const forbidden of ["secret", "canonical_moves", "@", "00000000"]) {
    assert.equal(publicLog.includes(forbidden), false);
  }
});

test("deterministically invalid ACCEPTED source fails only the new generation", async () => {
  const store = new FakeStore({ aggregation: [{ ...normalSource(1), canonical_moves: "a1" }] });
  const result = await runResearchBatch(store, options);
  assert.equal(result.aggregationStatus, "FAILED");
  assert.equal(store.failed, "ACCEPTED_SOURCE_INVALID");
  assert.equal(store.published, false);
  assert.equal(store.processed.size, 0);
});
