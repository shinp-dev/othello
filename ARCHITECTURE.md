# Othello Online MVP Architecture

## Scope

This repository is an Android-first Othello MVP. The first executable slice is a local two-player board with a production-shaped domain boundary. Online matchmaking, P2P transport, result confirmation, and credential review are represented by ports and database contracts so they can be implemented without moving game rules into infrastructure or UI.

The current MVP assumption is that a user can start a local game without Supabase credentials. Network features remain opt-in and are not faked as authoritative local state.

## Modules

| Module | Responsibility | Must not know |
| --- | --- | --- |
| `:app` | Compose navigation, dependency wiring, Android entry point | Game rule implementation, DB writes |
| `:core:game` | Immutable Othello value objects, legal moves, transitions, result | Android, network, users, rating, analysis |
| `:core:network` | Transport and signaling ports, P2P command validation | Compose, rating policy |
| `:core:auth` | Auth/session port | Game rules |
| `:core:designsystem` | Theme and reusable UI primitives | Domain decisions |
| `:feature:matchmaking` | Queue use cases and matchmaking state | Game rules internals, Edax |
| `:feature:match` | Match orchestration state machine and P2P move handling | Rating implementation, Edax |
| `:feature:records` | Immutable game record storage/query port | Edax evaluation values |
| `:feature:review` | Review session, cursor and variations | Match transport |
| `:feature:profile` | Profile presentation/query boundary | Rating calculations |
| `:feature:credential` | Federation credential submission/review boundary | Rating |
| `:analysis:api` | `AnalysisEngine`, settings and result contracts | Match implementation |
| `:analysis:edax` | Android-local Edax adapter; JNI can replace the unavailable production adapter | Match, Supabase |
| `:transport:webrtc` | Android WebRTC SDK wiring and ICE configuration boundary | Game Core rules |
| `:data:supabase` | Android Supabase SDK wiring and signaling data sources | Game Core rules |

## Dependency direction

```text
app -> feature/* -> core/*
app -> analysis:edax -> analysis:api -> core:game
feature:review -> analysis:api
feature:match -X-> analysis:edax
transport:webrtc -> core:network
data:supabase -> feature ports / core contracts
feature:match -X-> feature:profile / feature:credential
```

`core:game` has no Android dependency. `feature:match` only consumes `GameState` and transport ports. A build-time boundary check fails if match source references `analysis`.

## Ownership

| Data | Owner | Access path |
| --- | --- | --- |
| `profiles` | Profile | ProfileRepository |
| `match_queue`, `matches` | Matchmaking / Match | RPC and repositories |
| `match_submissions` | Match finalization | SubmitResult use case |
| `game_records` | Records | RecordRepository |
| `ratings`, `rating_history` | Rating policy/application | Server RPC only for official updates |
| `federation_credentials`, `verification_submissions` | Credential / admin BFF | RLS + Cloudflare Worker |

The Android client never contains a service-role key. It cannot mark a rating, match, or credential as verified.

## Game Core

`Board` is a compact immutable `IntArray` value object exposed only through safe operations. `GameState.apply(Move)` is a pure transition: it rejects a non-legal move, flips all captured lines, advances the turn, handles one pass, and ends after consecutive passes or a full board. `PositionHash` is deterministic and is used by the P2P contract.

## Client Session State vs Server Persisted Match State

The Android state machine (`IDLE`, `WAITING`, `SIGNALING`, `P2P_CONNECTED`, `PLAYING`, `FINISHING`, `CONFIRMED`, `PENDING_RESULT`, `DISPUTED`) describes one device's session. Supabase cannot observe the P2P channel continuously, so it does not mirror these states. The additive migration adds `matches.server_status` with only `CREATED`, `PENDING_RESULT`, `CONFIRMED`, `DISPUTED`, and `ABANDONED`; the legacy `matches.status` column is retained for rollback compatibility and is not authoritative for new clients.

## Client match state machine

```text
IDLE -> WAITING -> SIGNALING -> P2P_CONNECTED -> PLAYING -> FINISHING -> CONFIRMED
                         |             |              |              |
                  SIGNALING_FAILED  DISCONNECTED  DISCONNECTED  PENDING_RESULT/DISPUTED
```

The state machine is a pure reducer. UI observes state; it does not decide transitions with scattered conditionals.

## P2P flow

1. A later queue participant gets a match and creates a non-trickle WebRTC offer.
2. The offer is delivered to the waiting participant through private Supabase Realtime signaling only.
3. The answer is returned, DataChannel opens, and the signaling subscription is removed.
4. Each move is sent only on DataChannel. Every command contains `matchId`, `ply`, `move`, `commandId`, and `previousStateHash`.
5. Receiver validates the server-assigned remote disc, command fingerprint, idempotency, ply, legality and hash. A reused command id with a different payload is a protocol error.
6. If the next player has no legal move, both sides apply a forced pass locally and append the same `--` canonical token; no pass button is shown in normal UI.

## Finalization and rating

Both players submit immutable move history, result, final hash and finish reason. A server-side RPC compares submissions. Only matching submissions become `CONFIRMED`; then one idempotent transaction creates the record and applies the versioned `RatingPolicy`. Mismatches become `DISPUTED` and do not change rating. A single submission is `PENDING_RESULT`.

`RatingPolicy` and `StableRatingPolicy` are replaceable interfaces. Peak is monotonic. Stable band uses recent completed ratings and returns `CALCULATING` until enough observations exist.

## Review / Edax

`ReviewSession` owns cursor and variations and never mutates `GameRecord`. `AnalysisEngine` lives behind `analysis:api`; the review feature can ask it to evaluate every legal move. `EvaluationScore` carries perspective and `EXACT`/`HEURISTIC`/`UNAVAILABLE` kind. The heuristic engine is test/debug-only; production returns `解析を利用できません` until Edax JNI is bundled. Evaluation values are never persisted. `BookSource.ImportedBook` models an app-private imported Edax Book path without forcing Android Storage Access Framework into pure Kotlin.

## Federation verification

Users may self-declare a credential. A verification submission uploads evidence to Supabase Storage and is reviewed only through Cloudflare Worker -> Supabase. Public profiles show only `段級位確認済み`; evidence and legal names are private and removable after approval.

## Security and forbidden dependencies

- No moves or clocks are written to Supabase during a game.
- Realtime is signaling-only and is unsubscribed after P2P connection.
- No service-role key is shipped to Android or browser.
- Android cannot update rating, peak, official result, or verification status directly.
- Match cannot reference Edax; review alone can reference `AnalysisEngine`.
- Game records are immutable after finalization.
- SQL enables RLS and exposes narrow RPCs instead of client-owned status updates.
- Matchmaking snapshots official `ratings.current_rating`, uses TTL queue rows and `FOR UPDATE SKIP LOCKED`; client rating input is not accepted.
- `active_match_participants.user_id` is the database invariant for one active match per user. CREATED matches have a five-minute lease; `abandon_match` and stale cleanup release reservations without changing rating.
- `submit_match_result` stores idempotently and auto-finalizes when both submissions exist; `finalize_match_v2` remains participant-scoped reconciliation. Payload enums, hashes, clock size, and canonical token format are checked in SQL.
- Credential evidence must be a `verification/<auth.uid()>/...` Storage-owned object. Review is idempotent/conflict-safe; the Worker receives cleanup paths from the DB and can retry deletion before the path is nulled.
- Terminal matches, records, and submissions have retention/cleanup RPCs. Internal pruning and cleanup functions have no execute permission for `anon`, `authenticated`, or `PUBLIC`; SECURITY DEFINER functions use an empty search path.
- DataChannel commands carry real moves only. `TurnResolver` deterministically adds `--` locally on both peers; command IDs reserve their first payload even when that payload is rejected.

## Change log

- 2026-08-09: Initial architecture recorded before implementation. Empty repository assumption documented. Local two-player mode is the safe executable MVP while Supabase/WebRTC/Edax ports remain replaceable.
- 2026-08-09: Additive hardening recorded. Server persisted match state is separated from device session state; official-Rating matchmaking, result submission, idempotent finalization, bounded records, public profile projection, and credential admin RPC were added without destructive schema conversion.
