# CHANRIVA release match hardening

Status: implementation branch only (`release-hardening`). The migration and Worker changes in
this branch are **not deployed** by this work. This document is the contract for protocol v2.

## Why a versioned contract

The closed-test APK speaks protocol v1. It calls the unversioned matchmaking/result RPCs and
writes `match_signaling` directly. Replacing those objects in place would break installed APKs.
Protocol v2 is therefore additive and is matched only with protocol v2. The compatibility layer
is temporary; the cutover and deletion criteria are listed below.

The pre-change root causes were:

- ICE `DISCONNECTED` was diagnostic text only; a process restart had no active-match lookup or
  local checkpoint. Started matches could retain a 24-hour reservation.
- result authority was two equal client strings. A legal replay, terminal board, result and hash
  were not recomputed before rating.
- one submission waited for the other process; expiry discarded the result. A finish packet
  failure could prevent even the loser's own submission.
- `abandon_match` could erase an active or result-pending match.
- cleanup functions had no deployed caller.
- signaling was an unbounded participant `INSERT`, and clients repeatedly selected all rows.
- a DataChannel enqueue was treated as peer application; there was no ACK/retry/resync.
- matchmaking used one global lock and consumed a claim notification before returning it.

## Authoritative match state machine

`matches.release_status` is the v2 authority. It is nullable only while v1 coexists. A database
transition guard rejects every transition not listed here and rejects all transitions out of a
terminal state.

| State | Meaning | Allowed next states |
|---|---|---|
| `MATCHED` | Assignment exists; P2P/signaling not attested | `ACTIVE`, `ABANDONED` |
| `ACTIVE` | Both participants acknowledged the current-epoch DataChannel | `RECONNECTING`, `RESULT_PENDING`, `CONFIRMED`, `FORFEIT`, `EXPIRED`, `DISPUTED` |
| `RECONNECTING` | A participant reported its peer missing; 45-second grace | `ACTIVE`, `FORFEIT`, `EXPIRED`, `DISPUTED` |
| `RESULT_PENDING` | A non-authoritative finish claim awaits bounded evidence | `CONFIRMED`, `FORFEIT`, `EXPIRED`, `DISPUTED` |
| `CONFIRMED` | Server-verified normal result | terminal |
| `FORFEIT` | Server-authorized resignation/timeout/disconnect loss | terminal |
| `EXPIRED` | No safe rated result could be established | terminal |
| `DISPUTED` | Contradictory non-authoritative evidence | terminal |
| `ABANDONED` | Pre-start assignment cancelled or expired | terminal |

`server_status` remains a temporary v1 compatibility projection. V2 `CONFIRMED`/`FORFEIT`
projects to `CONFIRMED` only after GameRecord and rating rows exist, so the existing Research
capture trigger still derives from the same confirmed fact. `EXPIRED`/`ABANDONED` projects to
`ABANDONED`, and `DISPUTED` to `DISPUTED`.

Leases use server time:

- `MATCHED`: 2 minutes. This covers non-trickle SDP gathering and a bounded retry without
  reserving both users indefinitely.
- `ACTIVE`: 15 minutes. A 5-minute clock for each player permits at most about 10 minutes of
  thinking; five extra minutes cover connection/start overhead. This is a crash safety net, not
  a gameplay heartbeat.
- reconnect/result grace: 45 seconds. It is long enough for a mobile network handover and fresh
  non-trickle negotiation, while producing a meaningful outcome promptly.

### Disconnect behavior

- Graceful leave/resignation: the leaving caller submits an adverse claim about itself. The
  server can authorize that fact without trusting the opponent and finalizes it idempotently.
- Temporary network loss / ICE `DISCONNECTED`: gameplay and the local monotonic clock pause; the
  survivor reports the opponent missing and enters `RECONNECTING`. A returning participant calls
  `resume_match_v2`, negotiates a fresh connection and exchanges a transcript snapshot.
- `FAILED`, process death, force stop or power loss: the surviving process follows the same
  45-second path. A relaunched process obtains its assignment with `claim_active_match_v2`, loads
  the app-private checkpoint, and starts a new signaling negotiation.
- Reconnection is a match-wide budget, not a per-process retry counter. Epoch 0 is the initial
  negotiation and epochs 1–3 are the only reconnect epochs. A fourth `ACTIVE` reconnect request
  leaves epoch 3 unchanged and terminalizes the match as `EXPIRED`/unrated.
- One missing peer at deadline: a one-sided liveness allegation becomes `EXPIRED`, unrated. A
  client cannot prove its opponent's absence, so that allegation never creates a winner.
- Both peers report each other missing: the server cannot distinguish a partition from two
  failures, so it produces `EXPIRED` with no rating, GameRecord or Research input.
- No reports before the 15-minute active lease: `EXPIRED`, also unrated.
- A peer returning after a terminal deadline cannot reopen the match.

The foreground participant performs one deadline reconciliation. The existing free Cloudflare
scheduled Worker calls the same service-only bounded maintenance RPC every ten minutes as a
backstop. No paid service and no per-move server heartbeat are introduced.

## Result authority and one result pipeline

Clients submit a match ID, idempotency request ID, canonical transcript, finish reason, optional
loser disc and bounded clock diagnostics. Client-computed result and final hash are retained in
Android only for UX/cross-checking; they are not authoritative RPC inputs.

The PostgreSQL replay validator starts from the standard initial board and deterministically
checks each token, legal captures, required and unnecessary passes, double-pass/full-board
terminality, and moves after terminal. It derives the final board, black/white counts, winner and
the v1 FNV state hash used by Game Core and the Research validator.

Authority rules:

- `NORMAL`: the first valid terminal transcript enters `RESULT_PENDING`. Only a second claim by
  the other participant, in the same negotiation epoch and with exactly the same canonical line,
  becomes `CONFIRMED`. A mismatch becomes `DISPUTED`; a missing second claim becomes `EXPIRED`
  after 45 seconds. Both outcomes are unrated and create no GameRecord/Research input.
- `RESIGNATION`, `TIMEOUT`, explicit disconnect leave: a caller may immediately establish only
  its own loss. A peer's unsupported resignation/timeout claim is bounded then expires unrated.
- unexpected disconnect: a survivor may report its opponent only to start bounded recovery.
  The report expires unrated if the peer never returns. It cannot become a rated forfeit.
- malformed, illegal, truncated, post-terminal or mismatching evidence is rejected before any
  rating write. A participant cannot name an arbitrary user or match.

`match_results_v2(match_id primary key)` is the single confirmed fact. In one transaction and
under the match/rating row locks, finalization:

1. inserts the immutable result once;
2. inserts one GameRecord and both bounded user references;
3. writes at most one rating-history row per `(user, match)` and updates both ratings;
4. changes the compatibility `server_status` to `CONFIRMED` last;
5. lets the existing idempotent Research capture trigger observe the complete record/history.

Retries and lost RPC responses return the existing result. They cannot rerun rating, GameRecord
or Research capture. A SHA-256 evidence digest binds ruleset, match, participants, canonical
line, derived result/hash and finish authority. This prevents payload replay across matches.

Two colluding accounts can still simulate the same *legal* P2P game and submit the same line
because normal moves deliberately do not traverse the server. Preventing that completely would
require server-observed gameplay and conflict with the P2P/cost requirement. A single account
cannot choose a rated winner: NORMAL needs bilateral identical evidence, and non-normal immediate
authority is limited to the caller losing its own side. Repeat-opponent abuse remains a monitoring
concern.

Whether a server-valid completed game that only one peer could submit should later be retained as
an explicitly non-authoritative local/archive record is unresolved. The current contract creates
no server GameRecord for it. Any future archival path must remain separate from verified
GameRecords, rating, and Research eligibility.

### `canonical_moves`

Protocol-v2 result claims and results are `NOT NULL`; empty text is the valid zero-ply
non-normal line and is distinct from unknown data. The shared `game_records.canonical_moves`
column remains nullable during closed-test coexistence because legacy rows may be unknown. V2
finalization always writes a non-null validated value. No unverifiable legacy row is fabricated.
After v1 drain, audit `NULL` rows, quarantine/delete them by product policy, validate the
constraint, then make the shared column `NOT NULL` in the cleanup migration.

## P2P delivery protocol v2

Normal moves stay on the ordered reliable WebRTC DataChannel. No move is written to Supabase.

- A move carries match ID, command ID, ply, previous state hash and protocol version.
- The sender keeps at most one pending move and its expected post-move ply/hash.
- The receiver replays the move, durably checkpoints the compact line, then sends `MoveAck` with
  command ID and resulting ply/hash.
- A lost ACK causes bounded resend of the same command ID. A duplicate accepted move is not
  applied twice and receives the same ACK again.
- After retry exhaustion or a sequence/hash gap, either peer sends `SyncMessage.REQUEST`.
  `SNAPSHOT` contains at most the 240-character canonical line plus its ply/hash.
- A snapshot is replayed through Game Core. Equal/prefix histories converge; a divergent or
  invalid history is never guessed into place and moves to reconnect/expiry handling.
- ICE `DISCONNECTED` is distinct from `FAILED`; a recovered DataChannel resynchronizes before
  play. A DataChannel open waits at most 10 seconds, synchronization at most 3 seconds, and every
  Supabase release RPC at most 10 seconds. Ordinary coroutine cancellation is rethrown rather
  than displayed as a network failure.

The extra normal-play cost is one small peer ACK per real move and zero Supabase requests.

## Process restoration and UX

`OnlineMatchRecoveryStore` keeps one private checkpoint: assignment, compact canonical line,
state hash, clock checkpoint and an optional finish outbox. It contains no token or secret and is
removed at a terminal state. It is not a second server game log: active moves remain local+P2P,
and only the final verified line reaches the result pipeline.

`SharedPreferences.commit()` is an acknowledged durability port. A locally accepted move, a
received move ACK, an adopted sync transcript, or a result outbox does not cross the DataChannel
or result RPC boundary until the checkpoint succeeds. Failure remains visible and retry reuses the
same command/request ID.

On authenticated app launch:

1. claim any server-side v2 assignment (idempotent even after a lost response);
2. match it to the local checkpoint;
3. call `resume_match_v2` once in the Coordinator, adopt the server epoch, and serialize all
   offer/answer work through one mutex;
4. replay and exchange snapshots before enabling play;
5. resend an unfinished self-adverse/normal result with the same payload semantics;
6. if the server is already terminal, show the terminal meaning and clear the checkpoint.

A successful claim that returns no active assignment clears the matching stale local checkpoint.
Timeout, offline/decode failure, coroutine cancellation, or an unavailable repository preserves
it, because those outcomes do not prove that the authoritative assignment is absent.

UI states are exclusive and bounded: connecting, playing, waiting for move confirmation,
synchronizing, reconnecting/opponent grace, sending result, result pending, confirmed, forfeit,
expired/no-result, disputed or cancelled. English and Japanese resources share the same IDs.
Result submission errors expose a retry action. Leaving `FINISHING`/`RESULT_PENDING` never calls
the pre-start abandon RPC.

## Signaling v2

`match_signals_v2` is readable only by its two participants and writable only through
`publish_match_signal_v2`.

- server-derived sender/time/expiry;
- protocol-v2 `MATCHED` or `RECONNECTING` match only;
- BLACK publishes `OFFER`, WHITE publishes `ANSWER`; WHITE may publish one `RESUME` wake-up after
  recovery so BLACK creates the offer;
- every write includes the caller's expected negotiation epoch; a delayed old-epoch write is
  rejected server-side;
- the server accepts only epochs 0–3. Together with the per-role slot caps, this bounds a match to
  eight start ACK rows and at most 36 signal rows even if both participants coordinate retries;
- byte-size limit (not Java/Kotlin character count);
- duplicate payload digests are idempotent; each sender/type/epoch is capped at four
  `OFFER`/`ANSWER` rows and one `RESUME` row;
- two-minute TTL and indexed cleanup;
- terminal matches cannot publish.

The subscriber establishes Realtime, performs one initial snapshot, and uses at most one bounded
reconciliation for a join race. It no longer selects the full history every 500 ms. Realtime is a
wake-up mechanism; the constrained row snapshot remains the recoverable fact.

V1 direct insert stays granted only during coexistence. The v2 table has no authenticated direct
write grant.

## Matchmaking v2

`enqueue_or_match_v2(request_id)` first returns the caller's existing nonterminal assignment.
This makes both enqueue-response and claim-response loss recoverable. The request ID is stable for
one waiting session. Matching selects only protocol-v2 candidates using `FOR UPDATE SKIP LOCKED`;
there is no all-user advisory lock. Queue/active unique keys remain the final double-match guard.

Notifications are durable wake hints and are not consumed before an assignment is returned.
`claim_active_match_v2` can always reconstruct the assignment from the authoritative match.
Cancellation returns a raced assignment in the same RPC. Realtime handles the usual case; the
same enqueue RPC refreshes the two-minute queue lease only once every 75 seconds.

## Supabase request budget

Counts are application PostgREST requests; WebSocket control frames, SQL statements inside one
RPC and P2P move/ACK frames are excluded. Ranges depend on which player waits.

| Phase | Before | V2 target |
|---|---:|---:|
| matchmaking initial | 2 enqueue + 1 claim + notification snapshot | 2 enqueue + 1 waiting-peer notification snapshot + 1 claim (4) |
| waiting fallback | 2 RPC / 10 s (12/min) | 1 idempotent RPC / 75 s (<1/min) |
| signaling | 2 inserts + 2–40 catch-up SELECT | 2 publish RPC + 2 initial SELECT + at most 2 reconciliation SELECT |
| start | normally 4–5 RPC; failure path up to ~62/peer | 2 ACK RPC total + normally 0–2 short state reads; an early client is bounded to 9 reads if its peer is delayed |
| gameplay | 0 Supabase; 1 P2P message/move | 0 Supabase; move + ACK |
| normal result/post-game | about 7 requests | 2 submit RPC + at most 1 bounded pending-peer reconciliation; no automatic profile/rating read |
| one-sided pending | up to 62 resubmits/5 min | 1 submit + 1 early check + 1 deadline reconciliation |

The normal path produces one assignment wake row plus the `OFFER`/`ANSWER` rows: three
authoritative Realtime change events. Realtime fan-out/control frames depend on subscription state
and are not application RPCs. The confirming result RPC carries rating summary; the other client
gets the terminal fact in its one bounded reconciliation, with no eager post-result profile read.

With cold subscriptions and no wait, the practical normal-match target is about 15–24 requests
instead of 21–60. The upper end covers a delayed start ACK; the usual simultaneous start is near
the lower end. Most importantly, request count is bounded: it does not grow at 500 ms/5 s while a
peer is gone.

## Cleanup and retention

`run_match_maintenance_v2(limit)` is service-role only and uses bounded batches with
`FOR UPDATE SKIP LOCKED`:

1. terminalize expired `MATCHED`, `ACTIVE`, `RECONNECTING` and `RESULT_PENDING` business state;
2. remove expired signaling rows and queue rows in the same bounded batch;
3. leave existing terminal/GameRecord retention to the established bounded retention policy.

Foreground `reconcile_match_v2(match_id)` applies the same server-time rules to one participant's
match. Thus a visible match can converge without waiting for cron, and cron still repairs process
death where no client remains.

The existing Worker invokes this RPC every ten minutes with `p_limit = 100`, enforces a 10-second
Supabase request timeout, validates/logs the returned terminal/signal/queue counts, warns when the
batch limit is reached, and rejects failed scheduled work after allowing independent maintenance
tasks to finish. Deployment remains a separate cutover step.

## Security boundary

- All client mutation RPCs require `auth.uid()`, resolve the caller server-side, lock the match,
  and verify participant, protocol and allowed source state.
- Every `SECURITY DEFINER` function fixes `search_path = ''`; internal replay/finalize/maintenance
  helpers are revoked from `public`, `anon` and `authenticated`.
- RLS protects snapshots; authenticated roles receive no direct v2 result/signal table writes.
- Request IDs, unique keys and immutable terminal transitions cover retries/replay.
- Signal type, role, byte size, slot count, state and TTL cover signaling amplification.
- The replay validator covers malicious canonical syntax/semantics before rating.
- Client abandon is pre-start only. Started/result-submitted/terminal matches cannot be erased.

## Migration and coordinated cutover

Stage 0 — this branch:

- add migration `202608250030_release_match_hardening.sql`, v2 Android code, tests and Worker call;
- do not apply it to production and do not deploy the Worker.

Stage 1 — DB-first coexistence (separate owner-approved operation):

1. snapshot/back up and audit v1 active/pending matches and null canonical rows;
2. apply the additive migration;
3. verify v1 RPC signatures/direct signaling still work;
4. verify v2 functions, RLS, Realtime publication and maintenance manually;
5. deploy the Worker code only after its RPC exists.

Stage 2 — client rollout:

1. release the v2 APK to closed test;
2. verify two-client normal, process-kill, reconnect, one-sided result and response-loss cases;
3. monitor v1/v2 active counts, terminalization, duplicate constraints, RPC errors and request use;
4. raise the minimum online protocol only when the owner accepts ending v1 online play.

Stage 3 — drain and cleanup migration:

1. wait until v1 active/pending rows are zero for at least one maximum retention window;
2. remove v1 queue/result RPCs, direct `match_signaling` grants/table/sequence and compatibility
   notification paths;
3. resolve legacy null/unverified records without inventing moves, then validate shared
   `canonical_moves NOT NULL`;
4. replace the nullable v2 projection with one non-null lifecycle, remove legacy status/columns
   and document the final schema;
5. retain only the stable v2 names (or rename once in the cleanup migration).

## Verification and manual fault injection

Automated coverage includes Game Core/replay boundaries, ACK loss/duplicate/resync, result
authority/idempotency, state transitions, matchmaking response loss, signaling ACL/caps and
Japanese/English resource parity. The branch validation commands are listed in the final report.

Two-client manual/emulator sequence after applying the migration to a local Supabase only:

1. Start A/B, kill A's process, confirm B enters opponent grace and the match terminalizes.
2. Disable A network, restore inside 45 seconds, confirm new signaling + transcript sync resumes.
3. Repeat without restore; confirm a single survivor report and simultaneous partition reports
   both produce unrated `EXPIRED` and never create GameRecord/rating/Research rows.
4. Kill one process immediately after result RPC commit/response loss; retry and confirm one
   GameRecord and two rating-history rows.
5. Drop an enqueue/claim response, repeat the same request ID, and confirm the same assignment.
6. Drop one move ACK, confirm one board application and same-command retry; inject a sequence gap
   and confirm snapshot convergence.

No production Supabase, Cloudflare, Play Console, release, signing, secret or billing operation is
part of these steps.
