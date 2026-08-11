export const RESEARCH_VALIDATOR_VERSION = 1;

export interface ResearchValidationInput {
  canonicalMoves: string;
  result: "BLACK_WIN" | "WHITE_WIN" | "DRAW";
  finishReason: "NORMAL" | "RESIGNATION" | "TIMEOUT" | "DISCONNECT";
  finalPositionHash: string;
  rulesetVersion: number;
}

export type ResearchValidationResult =
  | { accepted: true; blackDecisionCount: number; whiteDecisionCount: number }
  | { accepted: false; rejectionCode: string };

export interface ResearchAggregationContributor {
  researchSubjectId: string;
  disc: "BLACK" | "WHITE";
  outcome: "WIN" | "DRAW" | "LOSS";
}

export interface ResearchDecision {
  research_subject_id: string;
  black_hex: string;
  white_hex: string;
  side: "BLACK" | "WHITE";
  legal_move_mask_hex: string;
  move_index: number;
  outcome: "WIN" | "DRAW" | "LOSS";
  child_black_hex?: string;
  child_white_hex?: string;
  child_side?: "BLACK" | "WHITE";
  child_legal_move_mask_hex?: string;
}

const EMPTY = 0;
const BLACK = 1;
const WHITE = 2;
const FNV_OFFSET = -3750763034362895579n;
const FNV_PRIME = 1099511628211n;
const DIRECTIONS: ReadonlyArray<readonly [number, number]> = [
  [-1, -1], [-1, 0], [-1, 1],
  [0, -1], [0, 1],
  [1, -1], [1, 0], [1, 1],
];

interface State {
  cells: number[];
  currentPlayer: typeof BLACK | typeof WHITE;
  consecutivePasses: number;
  ply: number;
}

export function validateResearchGame(input: ResearchValidationInput): ResearchValidationResult {
  if (input.rulesetVersion !== 1) return rejected("UNSUPPORTED_RULESET");
  if (!/^(?:--|[a-h][1-8])*$/.test(input.canonicalMoves) || input.canonicalMoves.length > 240 || input.canonicalMoves.length % 2 !== 0) {
    return rejected("INVALID_CANONICAL_FORMAT");
  }
  if (input.finishReason === "NORMAL" && input.canonicalMoves.length === 0) return rejected("NORMAL_EMPTY_LINE");
  if (input.finishReason !== "NORMAL" && input.result === "DRAW") return rejected("NON_NORMAL_DRAW");

  const state = initialState();
  let blackDecisionCount = 0;
  let whiteDecisionCount = 0;

  for (let offset = 0; offset < input.canonicalMoves.length; offset += 2) {
    if (isTerminal(state)) return rejected("MOVE_AFTER_TERMINAL");
    const token = input.canonicalMoves.slice(offset, offset + 2);
    const legal = legalMoves(state);
    if (token === "--") {
      if (legal.length !== 0) return rejected("UNNECESSARY_PASS");
      state.currentPlayer = opponent(state.currentPlayer);
      state.consecutivePasses += 1;
      state.ply += 1;
      continue;
    }
    if (legal.length === 0) return rejected("MISSING_PASS");
    const column = token.charCodeAt(0) - 97;
    const row = token.charCodeAt(1) - 49;
    const move = row * 8 + column;
    if (!legal.includes(move)) return rejected("ILLEGAL_MOVE");
    if (state.currentPlayer === BLACK) blackDecisionCount += 1;
    else whiteDecisionCount += 1;
    applyMove(state, move);
  }

  if (stateHash(state) !== input.finalPositionHash) return rejected("FINAL_HASH_MISMATCH");
  if (input.finishReason === "NORMAL") {
    if (!isTerminal(state)) return rejected("NORMAL_NOT_TERMINAL");
    if (boardResult(state.cells) !== input.result) return rejected("NORMAL_RESULT_MISMATCH");
  }
  return { accepted: true, blackDecisionCount, whiteDecisionCount };
}

export function extractResearchDecisions(
  input: ResearchValidationInput,
  contributors: ResearchAggregationContributor[],
): ResearchDecision[] {
  const validation = validateResearchGame(input);
  if (!validation.accepted) throw new Error(`validated research source required: ${validation.rejectionCode}`);
  const state = initialState();
  const decisions: ResearchDecision[] = [];
  for (let offset = 0; offset < input.canonicalMoves.length; offset += 2) {
    const token = input.canonicalMoves.slice(offset, offset + 2);
    const legal = legalMoves(state);
    if (token === "--") {
      state.currentPlayer = opponent(state.currentPlayer);
      state.consecutivePasses += 1;
      state.ply += 1;
      continue;
    }
    const move = (token.charCodeAt(1) - 49) * 8 + token.charCodeAt(0) - 97;
    const side = playerName(state.currentPlayer);
    const position = positionSnapshot(state, legal);
    applyMove(state, move);
    const childState = cloneState(state);
    while (!isTerminal(childState) && legalMoves(childState).length === 0) {
      childState.currentPlayer = opponent(childState.currentPlayer);
      childState.consecutivePasses += 1;
      childState.ply += 1;
    }
    const child = isTerminal(childState) ? null : positionSnapshot(childState, legalMoves(childState));
    for (const contributor of contributors.filter(value => value.disc === side)) {
      decisions.push({
        research_subject_id: contributor.researchSubjectId,
        ...position,
        move_index: move,
        outcome: contributor.outcome,
        ...(child === null ? {} : {
          child_black_hex: child.black_hex,
          child_white_hex: child.white_hex,
          child_side: child.side,
          child_legal_move_mask_hex: child.legal_move_mask_hex,
        }),
      });
    }
  }
  return decisions;
}

function rejected(rejectionCode: string): ResearchValidationResult {
  return { accepted: false, rejectionCode };
}

function initialState(): State {
  const cells = Array<number>(64).fill(EMPTY);
  cells[3 * 8 + 3] = WHITE;
  cells[3 * 8 + 4] = BLACK;
  cells[4 * 8 + 3] = BLACK;
  cells[4 * 8 + 4] = WHITE;
  return { cells, currentPlayer: BLACK, consecutivePasses: 0, ply: 0 };
}

function cloneState(state: State): State {
  return { ...state, cells: [...state.cells] };
}

function playerName(player: typeof BLACK | typeof WHITE): "BLACK" | "WHITE" {
  return player === BLACK ? "BLACK" : "WHITE";
}

function positionSnapshot(state: State, legal: number[]): Pick<ResearchDecision,
  "black_hex" | "white_hex" | "side" | "legal_move_mask_hex"> {
  let black = 0n;
  let white = 0n;
  let legalMask = 0n;
  state.cells.forEach((cell, index) => {
    if (cell === BLACK) black |= 1n << BigInt(index);
    if (cell === WHITE) white |= 1n << BigInt(index);
  });
  legal.forEach(index => { legalMask |= 1n << BigInt(index); });
  const hex = (value: bigint) => value.toString(16).padStart(16, "0");
  return {
    black_hex: hex(black),
    white_hex: hex(white),
    side: playerName(state.currentPlayer),
    legal_move_mask_hex: hex(legalMask),
  };
}

function opponent(player: typeof BLACK | typeof WHITE): typeof BLACK | typeof WHITE {
  return player === BLACK ? WHITE : BLACK;
}

function legalMoves(state: State): number[] {
  const moves: number[] = [];
  for (let index = 0; index < 64; index += 1) {
    if (state.cells[index] === EMPTY && captured(state.cells, index, state.currentPlayer).length > 0) moves.push(index);
  }
  return moves;
}

function captured(cells: number[], move: number, player: typeof BLACK | typeof WHITE): number[] {
  if (cells[move] !== EMPTY) return [];
  const moveRow = Math.floor(move / 8);
  const moveColumn = move % 8;
  const other = opponent(player);
  const result: number[] = [];
  for (const [rowDelta, columnDelta] of DIRECTIONS) {
    const line: number[] = [];
    let row = moveRow + rowDelta;
    let column = moveColumn + columnDelta;
    while (row >= 0 && row < 8 && column >= 0 && column < 8 && cells[row * 8 + column] === other) {
      line.push(row * 8 + column);
      row += rowDelta;
      column += columnDelta;
    }
    if (line.length > 0 && row >= 0 && row < 8 && column >= 0 && column < 8 && cells[row * 8 + column] === player) {
      result.push(...line);
    }
  }
  return result;
}

function applyMove(state: State, move: number): void {
  const flips = captured(state.cells, move, state.currentPlayer);
  state.cells[move] = state.currentPlayer;
  for (const index of flips) state.cells[index] = state.currentPlayer;
  state.currentPlayer = opponent(state.currentPlayer);
  state.consecutivePasses = 0;
  state.ply += 1;
}

function isTerminal(state: State): boolean {
  return state.consecutivePasses >= 2 || !state.cells.includes(EMPTY);
}

function boardResult(cells: number[]): "BLACK_WIN" | "WHITE_WIN" | "DRAW" {
  const black = cells.filter(cell => cell === BLACK).length;
  const white = cells.filter(cell => cell === WHITE).length;
  return black > white ? "BLACK_WIN" : white > black ? "WHITE_WIN" : "DRAW";
}

function stateHash(state: State): string {
  let hash = FNV_OFFSET;
  for (const cell of state.cells) hash = BigInt.asIntN(64, (hash ^ BigInt(cell)) * FNV_PRIME);
  const boardHash = BigInt.asUintN(64, hash).toString(16).padStart(16, "0");
  return `${boardHash}:${state.currentPlayer}:${state.consecutivePasses}:${state.ply}`;
}
