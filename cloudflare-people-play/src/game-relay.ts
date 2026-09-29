import type {
  PeoplePlayClientMessage,
  PeoplePlayErrorCode,
  ReportedResult,
} from "./people-play-protocol.js";
import {
  playerColorForMember,
  type ActiveRoomState,
  type FinishReason,
  type Outcome,
  type PlayerColor,
} from "./room-state.js";

type MoveCommand = Extract<PeoplePlayClientMessage, { type: "MOVE_SNAPSHOT" }>;
type DesyncCommand = Extract<PeoplePlayClientMessage, { type: "DESYNC" }>;
type ResultReportCommand = Extract<PeoplePlayClientMessage, { type: "RESULT_REPORT" }>;

export interface EndingDecision {
  finishReason: FinishReason;
  outcome: Outcome;
  winner: PlayerColor | null;
}

export interface RelayError {
  code: PeoplePlayErrorCode;
  rejectedPly: number | null;
}

export type MoveTransition =
  | { kind: "accepted"; state: ActiveRoomState }
  | { kind: "error"; error: RelayError };

export type ResultTransition =
  | { kind: "no-op" }
  | { kind: "result-check"; state: ActiveRoomState; targetColor: PlayerColor }
  | { kind: "finish"; ending: EndingDecision }
  | { kind: "error"; error: RelayError };

const error = (code: PeoplePlayErrorCode, rejectedPly: number | null = null): { kind: "error"; error: RelayError } =>
  ({ kind: "error", error: { code, rejectedPly } });

export const DESYNC_ENDING: EndingDecision = Object.freeze({
  finishReason: "DESYNC",
  outcome: "NO_CONTEST",
  winner: null,
});

export function applyMoveSnapshot(
  state: ActiveRoomState,
  senderMemberId: string,
  command: MoveCommand,
): MoveTransition {
  if (state.phase !== "PLAYING") return error("WRONG_PHASE");
  const senderColor = playerColorForMember(state, senderMemberId);
  if (!senderColor) return error("NOT_PLAYER");
  if (state.latestSnapshot.terminalCandidate || state.resultCheck !== null) return error("RESULT_PENDING");
  if (state.currentTurn !== senderColor) return error("NOT_YOUR_TURN");
  if (command.ply !== state.currentPly + 1) return error("BAD_PLY", command.ply);
  return {
    kind: "accepted",
    state: {
      ...state,
      currentPly: command.ply,
      currentTurn: command.nextTurn,
      latestSnapshot: {
        board: [...command.board],
        move: { ...command.move },
        terminalCandidate: command.terminalCandidate,
      },
      resultCheck: null,
    },
  };
}

export function evaluateDesync(
  state: ActiveRoomState,
  senderMemberId: string,
  command: DesyncCommand,
): { kind: "finish"; ending: EndingDecision } | { kind: "error"; error: RelayError } {
  if (state.phase !== "PLAYING") return error("WRONG_PHASE");
  if (!playerColorForMember(state, senderMemberId)) return error("NOT_PLAYER");
  if (command.observedPly < 1 || command.observedPly > state.currentPly) return error("BAD_PLY", command.observedPly);
  return { kind: "finish", ending: DESYNC_ENDING };
}

function normalEnding(result: Exclude<ReportedResult, "NOT_FINISHED">): EndingDecision {
  switch (result) {
    case "BLACK_WIN":
      return { finishReason: "NORMAL", outcome: "BLACK_WIN", winner: "BLACK" };
    case "WHITE_WIN":
      return { finishReason: "NORMAL", outcome: "WHITE_WIN", winner: "WHITE" };
    case "DRAW":
      return { finishReason: "NORMAL", outcome: "DRAW", winner: null };
  }
}

export function applyResultReport(
  state: ActiveRoomState,
  senderMemberId: string,
  command: ResultReportCommand,
): ResultTransition {
  if (state.phase !== "PLAYING") return error("WRONG_PHASE");
  const senderColor = playerColorForMember(state, senderMemberId);
  if (!senderColor) return error("NOT_PLAYER");
  if (command.ply !== state.currentPly) return error("BAD_PLY", command.ply);

  const slot = senderColor === "BLACK" ? "black" : "white";
  const otherSlot = senderColor === "BLACK" ? "white" : "black";
  if (state.resultCheck === null) {
    if (command.result === "NOT_FINISHED") {
      return state.latestSnapshot.terminalCandidate
        ? { kind: "finish", ending: DESYNC_ENDING }
        : { kind: "no-op" };
    }
    return {
      kind: "result-check",
      targetColor: senderColor === "BLACK" ? "WHITE" : "BLACK",
      state: {
        ...state,
        resultCheck: {
          ply: state.currentPly,
          black: slot === "black" ? command.result : null,
          white: slot === "white" ? command.result : null,
        },
      },
    };
  }

  const existing = state.resultCheck[slot];
  if (existing !== null) {
    return existing === command.result
      ? { kind: "no-op" }
      : { kind: "finish", ending: DESYNC_ENDING };
  }
  if (command.result === "NOT_FINISHED") return { kind: "finish", ending: DESYNC_ENDING };
  const other = state.resultCheck[otherSlot];
  if (other === command.result) return { kind: "finish", ending: normalEnding(command.result) };
  return { kind: "finish", ending: DESYNC_ENDING };
}

export function evaluateTimeout(
  state: ActiveRoomState,
  senderMemberId: string,
): { kind: "finish"; ending: EndingDecision } | { kind: "error"; error: RelayError } {
  if (state.phase !== "PLAYING") return error("WRONG_PHASE");
  const senderColor = playerColorForMember(state, senderMemberId);
  if (!senderColor) return error("NOT_PLAYER");
  return senderColor === "BLACK"
    ? { kind: "finish", ending: { finishReason: "TIMEOUT", outcome: "WHITE_WIN", winner: "WHITE" } }
    : { kind: "finish", ending: { finishReason: "TIMEOUT", outcome: "BLACK_WIN", winner: "BLACK" } };
}

export function disconnectEnding(
  state: ActiveRoomState,
  memberId: string,
): EndingDecision | null {
  if (state.phase !== "PLAYING") return null;
  const color = playerColorForMember(state, memberId);
  if (color === "BLACK") return { finishReason: "DISCONNECT", outcome: "WHITE_WIN", winner: "WHITE" };
  if (color === "WHITE") return { finishReason: "DISCONNECT", outcome: "BLACK_WIN", winner: "BLACK" };
  return null;
}
