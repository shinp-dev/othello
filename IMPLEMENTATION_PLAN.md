# Implementation Plan

1. **Phase 1 — boundaries and contracts**
   - Create Gradle modules and dependency rules.
   - Implement architecture/domain contracts, state machine, SQL migration/RLS/RPC, and CI checks.
2. **Phase 2 — Game Core**
   - Implement immutable 8x8 board, legal move generation, flipping, pass, terminal result and deterministic hash.
   - Add unit/property-style random legal game tests.
3. **Phase 3/4 — local MVP shell**
   - Add Compose home and match screens.
   - Wire local two-player game through a small ViewModel; keep UI free of game rules.
   - Provide ports for auth, matchmaking and P2P without requiring network configuration.
4. **Phase 5–9 — replaceable infrastructure**
   - Add Supabase implementations, WebRTC signaling/DataChannel, result finalization, records/review, JNI Edax and credential admin behind the documented interfaces.
5. **Verification**
   - Run unit tests, Android build/lint where the local SDK is available, and dependency boundary checks.

## Hardening completed in the current slice

- Official-rating matchmaking RPC with queue TTL, cancellation, heartbeat, atomic candidate locking, and random color assignment.
- Participant-only idempotent result submission and locked, explicit black/white finalization with server-side rating updates.
- Additive `server_status`, canonical move storage path, per-user 50-record / 100-rating-history retention, and public profile projection.
- Auth bootstrap trigger, atomic federation review RPC, Worker-side evidence deletion, remote-disc/fingerprint P2P validation, forced pass, and typed analysis scores.
- Review follow-up: one-user active-match reservation/lease, participant-only abandon, atomic submit/finalize, terminal retention cleanup, Storage owner validation, retryable evidence cleanup, strict result payloads, deny-by-default internal RPCs, and pgTAP coverage.

## MVP assumptions

- Local two-player mode is the first runnable vertical slice and is explicitly labeled in the UI.
- No Supabase URL/key is committed; network implementations are placeholders until environment configuration exists.
- Edax JNI is intentionally not bundled; `AnalysisEngine` is testable through the deterministic adapter.
