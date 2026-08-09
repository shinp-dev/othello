# Two-device WebRTC test

This is the remaining manual test plan before calling online play production-ready.

## Required setup

1. Create one Supabase project and apply all migrations in order. Configure Auth with two test accounts (A/B).
2. Put only the project URL and publishable/anon key in each developer machine's `local.properties`:
   `supabase.url=...` and `supabase.anonKey=...`. Never use a service-role key in the app.
3. Confirm the `verification` bucket is private. Use the default public STUN first; configure a TURN server through a local/private build property when testing restrictive networks.
4. Install the same debug build on two Android 26+ devices. Keep `applicationId` unchanged for this pre-release build.

## Acceptance sequence

1. Sign in as A and B. Tap `対局する` on both devices. The first device remains `WAITING`; the second enters `SIGNALING`.
2. Confirm the signaling diagnostics show only the expected offer/answer exchange. Full SDP, credentials, and service keys must not appear in logs.
3. Confirm the DataChannel reaches `OPEN`, Realtime is unsubscribed, and both clients call `ack_match_started` once. The server should set `p2p_started_at` only after both acks.
4. Verify black moves first. Play one move, a complete normal game, a resignation with zero moves, and a timeout/disconnect case. Both devices must have the same canonical moves and final state hash.
5. Submit on both sides. Verify `CONFIRMED` produces one GameRecord and rating update; a deliberately mismatched submission produces `DISPUTED` with no rating change.
6. Repeat on Wi-Fi/Wi-Fi, Wi-Fi/mobile, and carrier/carrier. During a game, toggle background/foreground and lock/unlock. A live P2P session may be reported as disconnected after process death; it must never silently resume with an incorrect state.

## Expected diagnostics

`matchId`, user/disc/opponent, ICE/PeerConnection/DataChannel states, signaling timestamps,
offer/answer-set flags, sent/received packet counts, ply, state hash, error, and start-ack
status are safe. Do not log complete SDP, tokens, passwords, or evidence paths.

## Remaining product work

- Provide a vetted Edax source/license and enable the JNI adapter only after reproducible NDK builds.
- Configure production Supabase Auth, Realtime authorization, Storage limits, TURN credentials, crash reporting, privacy/retention policy, and Play Console signing.
- Before Play pre-release: choose the permanent application ID, add privacy policy/data-safety declarations, account deletion flow, release signing, OSS notices, crash/ANR monitoring, and staged rollout testing.
