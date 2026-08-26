import pg from "pg";
import { pathToFileURL } from "node:url";
import {
  RESEARCH_VALIDATOR_VERSION,
  extractResearchDecisions,
  validateResearchGame,
  type ResearchAggregationContributor,
  type ResearchDecision,
} from "./research-validator.js";

const { Client } = pg;

export interface ClaimedResearchGame {
  research_game_id: number;
  lease_token: string;
  canonical_moves: string;
  result: "BLACK_WIN" | "WHITE_WIN" | "DRAW";
  finish_reason: "NORMAL" | "RESIGNATION" | "TIMEOUT" | "DISCONNECT";
  final_position_hash: string;
  ruleset_version: number;
}

export interface AggregationClaim {
  generation_id: number;
  lease_token: string;
  source_watermark: number;
  ruleset_version: number;
  normalization_version: number;
}

export interface AggregationSource {
  research_game_id: number;
  canonical_moves: string;
  result: "BLACK_WIN" | "WHITE_WIN" | "DRAW";
  finish_reason: "NORMAL" | "RESIGNATION" | "TIMEOUT" | "DISCONNECT";
  final_position_hash: string;
  ruleset_version: number;
  contributors: Array<{
    research_subject_id: string;
    disc: "BLACK" | "WHITE";
    outcome: "WIN" | "DRAW" | "LOSS";
  }>;
}

export interface ResearchBatchStore {
  claimValidation(limit: number, leaseSeconds: number): Promise<ClaimedResearchGame[]>;
  completeValidation(game: ClaimedResearchGame, result: ReturnType<typeof validateResearchGame>): Promise<void>;
  claimAggregation(leaseSeconds: number): Promise<AggregationClaim | null>;
  getAggregationSources(claim: AggregationClaim, limit: number): Promise<AggregationSource[]>;
  appendAggregationGame(claim: AggregationClaim, gameId: number, decisions: ResearchDecision[]): Promise<boolean>;
  checkpointAggregation(claim: AggregationClaim): Promise<void>;
  publishAggregation(claim: AggregationClaim): Promise<string>;
  failAggregation(claim: AggregationClaim, failureCode: string): Promise<void>;
}

export interface ResearchBatchOptions {
  validationMaxGames: number;
  validationBatchSize: number;
  validationLeaseSeconds: number;
  aggregationMaxGames: number;
  aggregationPageSize: number;
  aggregationLeaseSeconds: number;
}

export interface ResearchBatchSummary {
  validated: number;
  aggregationStatus: "IDLE" | "CHECKPOINTED" | "PUBLISHED" | "FAILED";
  aggregationProcessed: number;
  generationId: number | null;
}

export type ResearchBatchStage =
  | "configuration"
  | "db_connect"
  | "claim_validation"
  | "complete_validation"
  | "claim_aggregation"
  | "get_aggregation_sources"
  | "append_aggregation"
  | "checkpoint_aggregation"
  | "publish_aggregation"
  | "fail_aggregation"
  | "db_disconnect";

export class ResearchBatchStageError extends Error {
  constructor(
    readonly stage: ResearchBatchStage,
    readonly originalError: unknown,
  ) {
    super(`research batch failed at ${stage}`);
    this.name = "ResearchBatchStageError";
  }
}

interface ResearchBatchFailureDiagnostic {
  event: "research_batch_failed";
  stage: ResearchBatchStage | "unknown";
  errorType: string;
  postgresCode?: string;
  nodeCode?: string;
  severity?: string;
  routine?: string;
  constraint?: string;
  schema?: string;
  table?: string;
  column?: string;
}

export const DEFAULT_RESEARCH_BATCH_OPTIONS: ResearchBatchOptions = {
  validationMaxGames: 200,
  validationBatchSize: 25,
  validationLeaseSeconds: 300,
  aggregationMaxGames: 500,
  aggregationPageSize: 50,
  aggregationLeaseSeconds: 1800,
};

export async function runResearchBatch(
  store: ResearchBatchStore,
  options: ResearchBatchOptions = DEFAULT_RESEARCH_BATCH_OPTIONS,
): Promise<ResearchBatchSummary> {
  validateOptions(options);
  let validated = 0;
  while (validated < options.validationMaxGames) {
    const rows = await atStage(
      "claim_validation",
      () => store.claimValidation(
        Math.min(options.validationBatchSize, options.validationMaxGames - validated),
        options.validationLeaseSeconds,
      ),
    );
    if (rows.length === 0) break;
    for (const row of rows) {
      let result: ReturnType<typeof validateResearchGame>;
      try {
        result = validateResearchGame({
          canonicalMoves: row.canonical_moves,
          result: row.result,
          finishReason: row.finish_reason,
          finalPositionHash: row.final_position_hash,
          rulesetVersion: row.ruleset_version,
        });
      } catch {
        result = { accepted: false, rejectionCode: "VALIDATOR_INTERNAL_ERROR" };
      }
      await atStage("complete_validation", () => store.completeValidation(row, result));
      validated += 1;
    }
  }

  const claim = await atStage(
    "claim_aggregation",
    () => store.claimAggregation(options.aggregationLeaseSeconds),
  );
  if (claim === null) {
    return { validated, aggregationStatus: "IDLE", aggregationProcessed: 0, generationId: null };
  }

  let aggregationProcessed = 0;
  while (aggregationProcessed < options.aggregationMaxGames) {
    const sources = await atStage(
      "get_aggregation_sources",
      () => store.getAggregationSources(
        claim,
        Math.min(options.aggregationPageSize, options.aggregationMaxGames - aggregationProcessed),
      ),
    );
    if (sources.length === 0) {
      await atStage("publish_aggregation", () => store.publishAggregation(claim));
      return {
        validated,
        aggregationStatus: "PUBLISHED",
        aggregationProcessed,
        generationId: claim.generation_id,
      };
    }

    for (const source of sources) {
      let decisions: ResearchDecision[];
      try {
        const contributors: ResearchAggregationContributor[] = source.contributors.map(value => ({
          researchSubjectId: value.research_subject_id,
          disc: value.disc,
          outcome: value.outcome,
        }));
        decisions = extractResearchDecisions({
          canonicalMoves: source.canonical_moves,
          result: source.result,
          finishReason: source.finish_reason,
          finalPositionHash: source.final_position_hash,
          rulesetVersion: source.ruleset_version,
        }, contributors);
      } catch {
        await atStage(
          "fail_aggregation",
          () => store.failAggregation(claim, "ACCEPTED_SOURCE_INVALID"),
        );
        return {
          validated,
          aggregationStatus: "FAILED",
          aggregationProcessed,
          generationId: claim.generation_id,
        };
      }
      await atStage(
        "append_aggregation",
        () => store.appendAggregationGame(claim, source.research_game_id, decisions),
      );
      aggregationProcessed += 1;
    }
  }

  await atStage("checkpoint_aggregation", () => store.checkpointAggregation(claim));
  return {
    validated,
    aggregationStatus: "CHECKPOINTED",
    aggregationProcessed,
    generationId: claim.generation_id,
  };
}

class PostgresResearchBatchStore implements ResearchBatchStore {
  constructor(private readonly client: InstanceType<typeof Client>) {}

  async claimValidation(limit: number, leaseSeconds: number): Promise<ClaimedResearchGame[]> {
    const result = await this.client.query<ClaimedResearchGame>(
      "select * from research_private.batch_claim_validation($1, $2)",
      [limit, leaseSeconds],
    );
    return result.rows.map(normalizeGameIds);
  }

  async completeValidation(game: ClaimedResearchGame, result: ReturnType<typeof validateResearchGame>): Promise<void> {
    await this.client.query(
      "select research_private.batch_complete_validation($1, $2, $3, $4, $5, $6, $7)",
      [
        game.research_game_id,
        game.lease_token,
        RESEARCH_VALIDATOR_VERSION,
        result.accepted,
        result.accepted ? null : result.rejectionCode,
        result.accepted ? result.blackDecisionCount : null,
        result.accepted ? result.whiteDecisionCount : null,
      ],
    );
  }

  async claimAggregation(leaseSeconds: number): Promise<AggregationClaim | null> {
    const result = await this.client.query<AggregationClaim>(
      "select * from research_private.batch_claim_aggregation($1)",
      [leaseSeconds],
    );
    return result.rows.length === 0 ? null : normalizeGenerationIds(result.rows[0]);
  }

  async getAggregationSources(claim: AggregationClaim, limit: number): Promise<AggregationSource[]> {
    const result = await this.client.query<AggregationSource>(
      "select * from research_private.batch_get_aggregation_sources($1, $2, $3)",
      [claim.generation_id, claim.lease_token, limit],
    );
    return result.rows.map(normalizeGameIds);
  }

  async appendAggregationGame(
    claim: AggregationClaim,
    gameId: number,
    decisions: ResearchDecision[],
  ): Promise<boolean> {
    const result = await this.client.query<{ appended: boolean }>(
      "select research_private.batch_append_aggregation_game($1, $2, $3, $4::jsonb) as appended",
      [claim.generation_id, claim.lease_token, gameId, JSON.stringify(decisions)],
    );
    return result.rows[0]?.appended === true;
  }

  async checkpointAggregation(claim: AggregationClaim): Promise<void> {
    await this.client.query(
      "select research_private.batch_checkpoint_aggregation($1, $2)",
      [claim.generation_id, claim.lease_token],
    );
  }

  async publishAggregation(claim: AggregationClaim): Promise<string> {
    const result = await this.client.query<{ status: string }>(
      "select research_private.batch_publish_aggregation($1, $2) as status",
      [claim.generation_id, claim.lease_token],
    );
    return result.rows[0]?.status ?? "UNKNOWN";
  }

  async failAggregation(claim: AggregationClaim, failureCode: string): Promise<void> {
    await this.client.query(
      "select research_private.batch_fail_aggregation($1, $2, $3)",
      [claim.generation_id, claim.lease_token, failureCode],
    );
  }
}

function normalizeGameIds<T extends { research_game_id: number }>(row: T): T {
  return { ...row, research_game_id: numericId(row.research_game_id) };
}

function normalizeGenerationIds<T extends { generation_id: number; source_watermark: number }>(row: T): T {
  return {
    ...row,
    generation_id: numericId(row.generation_id),
    source_watermark: numericId(row.source_watermark),
  };
}

function numericId(value: number | string): number {
  const result = typeof value === "number" ? value : Number(value);
  if (!Number.isSafeInteger(result) || result < 0) throw new Error("invalid database identifier");
  return result;
}

function validateOptions(options: ResearchBatchOptions): void {
  const positiveIntegers = Object.values(options).every(value => Number.isInteger(value) && value > 0);
  if (!positiveIntegers
      || options.validationBatchSize > 50
      || options.validationLeaseSeconds > 900
      || options.aggregationPageSize > 100
      || options.aggregationLeaseSeconds > 3600) {
    throw new Error("invalid research batch options");
  }
}

function optionFromEnvironment(name: string, fallback: number): number {
  const raw = process.env[name];
  if (raw === undefined || raw.length === 0) return fallback;
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value <= 0) throw new Error(`invalid ${name}`);
  return value;
}

async function atStage<T>(stage: ResearchBatchStage, action: () => Promise<T>): Promise<T> {
  try {
    return await action();
  } catch (error) {
    if (error instanceof ResearchBatchStageError) throw error;
    throw new ResearchBatchStageError(stage, error);
  }
}

function safeErrorRecord(error: unknown): Record<string, unknown> | null {
  return typeof error === "object" && error !== null ? error as Record<string, unknown> : null;
}

function safeErrorType(error: unknown): string {
  const fallback = typeof error === "object" ? "UnknownError" : typeof error;
  try {
    const name = error instanceof Error ? error.constructor.name : undefined;
    return typeof name === "string" && /^[A-Za-z][A-Za-z0-9_.-]{0,63}$/.test(name) ? name : fallback;
  } catch {
    return fallback;
  }
}

function safeField(
  error: Record<string, unknown> | null,
  name: string,
  pattern: RegExp,
): string | undefined {
  try {
    const value = error?.[name];
    return typeof value === "string" && pattern.test(value) ? value : undefined;
  } catch {
    return undefined;
  }
}

const SAFE_NODE_ERROR_CODES = new Set([
  "ECONNREFUSED",
  "ECONNRESET",
  "EHOSTUNREACH",
  "ENETUNREACH",
  "ENOTFOUND",
  "EPIPE",
  "ETIMEDOUT",
]);

export function researchBatchFailureDiagnostic(error: unknown): ResearchBatchFailureDiagnostic {
  const stageError = error instanceof ResearchBatchStageError ? error : null;
  const originalError = stageError?.originalError ?? error;
  const record = safeErrorRecord(originalError);
  const code = safeField(record, "code", /^[A-Z0-9]{1,16}$/);
  const diagnostic: ResearchBatchFailureDiagnostic = {
    event: "research_batch_failed",
    stage: stageError?.stage ?? "unknown",
    errorType: safeErrorType(originalError),
  };
  if (code && /^[A-Z0-9]{5}$/.test(code)) diagnostic.postgresCode = code;
  else if (code && SAFE_NODE_ERROR_CODES.has(code)) diagnostic.nodeCode = code;

  const severity = safeField(record, "severity", /^(?:ERROR|FATAL|PANIC|WARNING|NOTICE|DEBUG|INFO|LOG)$/);
  if (severity) diagnostic.severity = severity;
  const databaseIdentifier = /^[A-Za-z_][A-Za-z0-9_$.-]{0,127}$/;
  for (const name of ["routine", "constraint", "schema", "table", "column"] as const) {
    const value = safeField(record, name, databaseIdentifier);
    if (value) diagnostic[name] = value;
  }
  return diagnostic;
}

async function main(): Promise<void> {
  const connectionString = process.env.RESEARCH_BATCH_DATABASE_URL;
  if (!connectionString) {
    throw new ResearchBatchStageError("configuration", new Error("missing database URL"));
  }
  const client = new Client({ connectionString, application_name: "chanriba-research-batch" });
  await atStage("db_connect", () => client.connect());
  try {
    const summary = await runResearchBatch(new PostgresResearchBatchStore(client), {
      ...DEFAULT_RESEARCH_BATCH_OPTIONS,
      validationMaxGames: optionFromEnvironment(
        "RESEARCH_VALIDATION_MAX_GAMES",
        DEFAULT_RESEARCH_BATCH_OPTIONS.validationMaxGames,
      ),
      aggregationMaxGames: optionFromEnvironment(
        "RESEARCH_AGGREGATION_MAX_GAMES",
        DEFAULT_RESEARCH_BATCH_OPTIONS.aggregationMaxGames,
      ),
    });
    console.log(JSON.stringify(summary));
    if (summary.aggregationStatus === "FAILED") process.exitCode = 1;
  } finally {
    await atStage("db_disconnect", () => client.end());
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch(error => {
    console.error(JSON.stringify(researchBatchFailureDiagnostic(error)));
    process.exitCode = 1;
  });
}
