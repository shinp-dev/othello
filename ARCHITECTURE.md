# ちゃんりば Architecture

## Scope

This repository implements the Android-first Reversi product ちゃんりば. Authenticated local play, Supabase-backed matchmaking/finalization, WebRTC DataChannel play, immutable records, account-deletion processing, and post-game Edax review are implemented behind explicit domain boundaries.

The entire Android app, including local play, requires a signed-in user. Authentication is an application-entry requirement; local game rules and device-local records remain independent of Supabase data access after entry.

## Modules

| Module | Responsibility | Must not know |
| --- | --- | --- |
| `:app` | Compose navigation, dependency wiring, Android entry point | Game rule implementation, DB writes |
| `:core:game` | Immutable Reversi value objects, legal moves, transitions, result | Android, network, users, rating, analysis |
| `:core:network` | Transport and signaling ports, P2P command validation | Compose, rating policy |
| `:core:auth` | Auth/session port | Game rules |
| `:core:designsystem` | Theme and reusable UI primitives | Domain decisions |
| `:feature:matchmaking` | Queue use cases and matchmaking state | Game rules internals, Edax |
| `:feature:match` | Match orchestration state machine and P2P move handling | Rating implementation, Edax |
| `:feature:records` | Immutable game record storage/query port | Edax evaluation values |
| `:feature:review` | Review session, cursor and variations | Match transport |
| `:feature:profile` | Account-deletion request and current-rating read boundaries | Rating calculations and official rating writes |
| `:analysis:api` | `AnalysisEngine`, settings and result contracts | Match implementation |
| `:analysis:edax` | Android-local Edax 4.6 JNI adapter and user-data storage | Match, Supabase |
| `:transport:webrtc` | Android WebRTC SDK wiring and ICE configuration boundary | Game Core rules |
| `:data:supabase` | Android Supabase SDK wiring, Composition Root, and signaling data sources | Game Core rules |

## Dependency direction

```text
app -> feature/* -> core/*
app -> analysis:edax -> analysis:api -> core:game
feature:review -> analysis:api
feature:match -X-> analysis:edax
transport:webrtc -> core:network
data:supabase -> feature ports / core contracts
feature:match -X-> feature:profile
```

`core:game` has no Android dependency. `feature:match` only consumes `GameState` and transport ports. A build-time boundary check fails if match source references `analysis`. Supabase SDK types are private to `:data:supabase`; `SupabaseModule` exposes only application-owned ports. WebRTC SDK types are private to `:transport:webrtc`; callers receive `MatchTransport`, payloads, and primitive diagnostics.

## Application HTTP boundary and startup gates

Android and Web clients' application-owned, outward-facing HTTP APIs are placed in the public-facing Cloudflare Worker by default. This Worker owns application configuration, Web account APIs, and other client-facing HTTP endpoints. Keeping APIs of the same kind in one boundary makes implementation location, operations, investigation, security boundaries, and responsibility explicit.

Supabase owns Auth, PostgreSQL, RLS, RPC, and Realtime. Unless there is a specific architectural reason, application HTTP APIs must not be scattered across Supabase Edge Functions or other Supabase surfaces. The minimum supported Android version therefore comes from the public Cloudflare `GET /api/app-config` endpoint, backed by a non-secret Worker variable, and does not query Supabase.

`cloudflare-admin` is a separate trusted administrative boundary. It handles service-role access, `ADMIN_TOKEN`, and trusted maintenance/admin processing. General client-facing APIs must not be placed there, and the public-facing Worker must not be treated as the trusted admin Worker.

Android process startup uses these gates in order:

```text
VersionGate
  -> Supported: AuthGate
       -> Authenticated: AuthenticatedApp
```

`VersionGate` answers only whether `BuildConfig.VERSION_CODE` is supported. It fails closed while configuration is checking, unavailable, or invalid. `OnlineSessionViewModel`, the Supabase component, and the Auth/session lifecycle are first created after `VersionGate` reaches `Supported`. `AuthGate` then decides whether the current session may enter the app, and `AuthenticatedApp` remains the signed-in product surface.

## Ownership

| Data | Owner | Access path |
| --- | --- | --- |
| `profiles` | Internal user identity/tombstone | Trusted server and database constraints |
| `match_queue`, `matches` | Matchmaking / Match | RPC and repositories |
| `match_submissions` | Match finalization | SubmitResult use case |
| `game_records` | Records | RecordRepository |
| `ratings`, `rating_history` | Rating policy/application | Server RPC only for official updates |
| `rating_daily_snapshot` | Latest daily rating position only | Service-role refresh function; owner-scoped authenticated read |
| `account_deletion_requests` | Profile / trusted admin BFF | owner request RPC + service-role processing RPCs |

The Android client never contains a service-role key. It cannot mark a rating or match result as verified.

## Daily rating position

The authenticated account screen, reached from the existing More destination, shows current rating, the previous-day position, and the best previous-day position that this user has actually confirmed by opening that screen on this device. Opening the account screen is the only collection trigger; app launch, foreground resume, and background work do not fetch or update this local reference value. Match and study surfaces do not own account statistics. `refresh_rating_daily_snapshot(date)` replaces the previous rows atomically, so the database retains only the latest snapshot needed by the client: Tokyo snapshot date, tied `RANK()` rank, active-user count, and top percentile. For every active user, ranking uses the latest `rating_history.rating` strictly before the Tokyo cutoff; a post-cutoff change to `ratings.current_rating` therefore cannot alter the previous day's position.

For this feature, “active in ranking” means a non-deleted user with at least one `rating_history` row created by the existing confirmed rating finalization flow in `[cutoff - 30 days, cutoff)`. The existing rating flow has no separate provisional-rating condition, so no new minimum-game or provisional exclusion is added. This ranking activity definition is intentionally separate from the account-lifecycle `profiles.last_active_at` marker. Android accepts the owner-scoped row as the previous-day position only when `snapshot_date` equals the current Asia/Tokyo date minus one day; stale or same-day rows neither display nor update the locally confirmed best. The best percentile/date is keyed by Supabase Auth user UUID, is not an official server achievement, excludes days when the account screen was not opened, and is not synchronized between devices.

The refresh function is `SECURITY DEFINER` with an empty `search_path`. PostgreSQL function ACLs are the caller boundary: `PUBLIC`, `anon`, and `authenticated` have no execute privilege, while `service_role` does. This also permits an explicitly privileged database-owner pg_cron job, where PostgREST JWT claims do not exist, without relying on `auth.role()` as a second and context-dependent authorization check. No production Cron is installed by this migration.

The canonical migration for this boundary is `202608220029_daily_rating_snapshot.sql`; migration number 028 is intentionally and permanently unused because its unshipped candidate was retired after the production-state audit. For the eventual once-daily database-only invocation, Supabase Cron/pg_cron is preferred over Cloudflare or GitHub Actions: it executes the schema-qualified function without an external network hop or an additional production database credential boundary and keeps job/run inspection beside the database. Enabling the extension and creating the job remain separate production operations. See [`docs/DAILY_RATING_SNAPSHOT_ROLLOUT.md`](docs/DAILY_RATING_SNAPSHOT_ROLLOUT.md).

`finalize_match_v2` writes one `rating_history` row for each player only on the confirmed, idempotent rating-update path, including a zero delta. The `(user_id, match_id)` unique index and terminal retry path prevent duplicate rated-game history; disputed and incomplete results do not write it. Existing pruning still retains the latest 100 rows per user. That remains sufficient for the 30-day activity predicate because a user who displaces older rows necessarily has newer rated games, and no broader ranking-history retention is introduced. Cutoff-rating reconstruction assumes the daily refresh runs before one user completes more than 100 post-cutoff rated games; changing retention for that implausible current-scale case is deliberately outside this feature.

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

1. A later queue participant gets a match. The database stores participant-scoped `match_notifications` rows; the waiting Android client observes its private row through Realtime and immediately calls `claim_waiting_match()`. The heartbeat/claim loop remains a fallback if notification delivery is delayed.
2. The matched participant creates a non-trickle WebRTC offer and delivers it through participant-only `match_signaling` Realtime rows.
3. The answer is returned and DataChannel opens. Each participant writes one start ACK; the client enters `PLAYING` only after the server reports both ACKs, then removes the signaling subscription.
4. Each move is sent only on DataChannel. Every move command contains `matchId`, `ply`, `move`, `commandId`, and `previousStateHash`. Resignation/timeout/disconnect use a separate terminal DataChannel control message so both peers submit the same result; it cannot carry a move.
5. Receiver validates the server-assigned remote disc, command fingerprint, idempotency, ply, legality and hash. A reused command id with a different payload is a protocol error.
6. If the next player has no legal move, both sides apply a forced pass locally and append the same `--` canonical token; no pass button is shown in normal UI.

## Finalization and rating

Both players submit immutable move history, result, final hash and finish reason. A server-side RPC compares submissions. Only matching submissions become `CONFIRMED`; then one idempotent transaction creates the record and applies the versioned `RatingPolicy`. Mismatches become `DISPUTED` and do not change rating. A single submission is `PENDING_RESULT`.

New GameRecords persist the verified final-position hash and fixed `5m` product time control. Existing records created before migration 017 may have a null final hash; records remain immutable.

`RatingPolicy` and `StableRatingPolicy` are replaceable interfaces. Peak is monotonic. Stable band uses recent completed ratings and returns `CALCULATING` until enough observations exist.

## Review / Edax

`ReviewSession` owns cursor and variations and never mutates `GameRecord`. `AnalysisEngine` lives behind `analysis:api`; the review feature asks it to evaluate every legal move. `EvaluationScore` carries the side-to-move perspective and `EXACT`/`HEURISTIC`/`BOOK` kind. `analysis:edax` owns JNI, Edax-specific conversion, cancellation, a 32-position identity-aware memory cache, and SAF-to-private-storage data management. Evaluation values and Review variations are never persisted into `GameRecord`.

Edax evaluation data and opening books are user imports, never app assets. The cache key includes canonical board, side to move, Edax level, eval SHA-256 and optional book SHA-256. A data replacement therefore cannot reuse stale scores. One active book slot is represented as a named slot boundary so later multi-book switching does not change `analysis:api`.

## Account deletion

Android can only create an owner-scoped deletion request. The trusted Worker calls a service-role-only DB preparation RPC, unlinks the Research subject, removes the Auth identity through the Auth Admin API, and then marks the request complete. DB preparation removes private ratings and record references while retaining an internal identity tombstone so the opponent's immutable shared record and match foreign keys remain valid. A pending deletion request is excluded from matchmaking.

## Security and forbidden dependencies

- No moves or clocks are written to Supabase during a game.
- Realtime Postgres Changes is used only for participant-scoped match-availability notification (`match_notifications`) and SDP signaling (`match_signaling`). Match notification observation ends after assignment; signaling observation ends after both start ACKs. Moves, clocks, board state, results, and rating never use Realtime.
- No service-role key is shipped to Android or browser.
- Android cannot update rating, peak, or official result directly.
- Match cannot reference Edax; review alone can reference `AnalysisEngine`.
- Live matchmaking, clocks, move transport, result submission and rating never construct or call `AnalysisEngine`.
- Game records are immutable after finalization.
- SQL enables RLS and exposes narrow RPCs instead of client-owned status updates.
- Matchmaking snapshots official `ratings.current_rating`, uses TTL queue rows and `FOR UPDATE SKIP LOCKED`; client rating input is not accepted.
- `active_match_participants.user_id` is the database invariant for one active match per user. CREATED matches have a five-minute lease; `abandon_match` and stale cleanup release reservations without changing rating.
- `submit_match_result` stores idempotently and auto-finalizes when both submissions exist; `finalize_match_v2` remains participant-scoped reconciliation. Payload enums, hashes, clock size, and canonical token format are checked in SQL.
- Terminal matches, records, and submissions have retention/cleanup RPCs. Internal pruning and cleanup functions have no execute permission for `anon`, `authenticated`, or `PUBLIC`; SECURITY DEFINER functions use an empty search path.
- DataChannel move commands carry real moves only. Terminal control messages are a separate wire type. `TurnResolver` deterministically adds `--` locally on both peers; command IDs reserve their first payload even when that payload is rejected.

## Change log

- 2026-08-09: Initial module boundaries and pure Game Core were established.
- 2026-08-10: Online beta, bounded records, WebRTC/start ACK/clock/finalization, account deletion, and Edax post-game analysis were completed without crossing the Match/Analysis boundary. The pre-release federation credential prototype was subsequently removed from the initial product.
- 2026-08-15: Public profiles and free-text display names were retired. Match-start opponent rating snapshots and private profile tombstones became the initial-release identity boundary.
- 2026-08-16: Waiting-match detection began using private Realtime notifications with heartbeat polling as fallback; the home screen began reading the signed-in user's server-managed current rating.
