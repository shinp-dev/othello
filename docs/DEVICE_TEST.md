# Android Emulator A/B WebRTC test

The reproducible emulator path is `scripts/run-emulator-e2e.ps1`. It uses two real
Android Emulator processes and the production-shaped Supabase/WebRTC path; Fake
transport tests are not a substitute for this run. Evidence is written below
`build/e2e/`, which is ignored by Git.

## Required setup

1. Start Docker Desktop and install the pinned Supabase CLI (`2.101.0` or compatible). Run `supabase start` and apply all migrations.
2. Create two local Auth users, for example `player-a@example.test` and `player-b@example.test`, without committing their passwords.
3. Build with emulator routing in untracked `local.properties`:

   ```properties
   supabase.url=http://10.0.2.2:54321
   supabase.anonKey=<local-anon-key>
   ```

   Never use a service-role key in the app. `localhost` is the host, not the emulator.
4. Start the run (PowerShell environment variables keep credentials out of the repository):

   ```powershell
   $env:OTHELLO_E2E_PLAYER_A_EMAIL='player-a@example.test'
   $env:OTHELLO_E2E_PLAYER_A_PASSWORD='...'
   $env:OTHELLO_E2E_PLAYER_B_EMAIL='player-b@example.test'
   $env:OTHELLO_E2E_PLAYER_B_PASSWORD='...'
   ./gradlew :app:assembleDebug
   ./scripts/run-emulator-e2e.ps1 -StartSupabase -AutoPlay
   ```

   The script boots `Pixel_8a` as `emulator-5554` and `emulator-5556`, installs the APK,
   clears app data, signs in A/B, and waits for the online diagnostics checkpoints.

## Acceptance sequence

1. Confirm API level/package install and app startup on both emulators. Sign in as A and B; diagnostics must show different user IDs.
2. Tap `対局する` on both devices. The first device remains `WAITING`; the second enters `SIGNALING`.
3. Confirm the signaling diagnostics show only the expected offer/answer exchange. Full SDP, credentials, and service keys must not appear in logs.
4. Confirm the DataChannel reaches `OPEN`, Realtime is unsubscribed, and both clients call `ack_match_started` once. The server should set `p2p_started_at` only after both acks.
5. Verify black moves first. `-AutoPlay` selects the first legal move through `MatchController` on each side; no board state is copied and moves do not use Realtime.
6. Submit on both sides. Verify `CONFIRMED` produces one GameRecord and rating update. Repeat matchmaking for a second match and confirm the prior peer/channel/signaling resources are gone.
7. Exercise duplicate, command-id reuse, wrong ply/hash, wrong match ID, and illegal move through the debug driver/Fake protocol suite. The board must not change and the app must not crash.
8. Repeat on Wi-Fi/Wi-Fi, Wi-Fi/mobile, and carrier/carrier. During a game, toggle background/foreground and lock/unlock. A live P2P session may be reported as disconnected after process death; it must never silently resume with an incorrect state.

## Expected diagnostics

`matchId`, user/disc/opponent, ICE/PeerConnection/DataChannel states, signaling timestamps,
offer/answer-set flags, sent/received packet counts, ply, state hash, error, and start-ack
status are safe. Do not log complete SDP, tokens, passwords, or evidence paths.

## Physical-device-only follow-up

The emulator run covers Android SDK, Supabase, Realtime, ICE, DataChannel, lifecycle,
matchmaking, finalization, and protocol behavior. Physical devices are still needed for
carrier-specific NAT and platform behavior: Wi-Fi↔5G handover, mobile↔mobile,
CGNAT/symmetric NAT, STUN-only success rate/TURN necessity, manufacturer background
limits, thermal/battery behavior, and production Edax native performance.

## Remaining product work

- Provide a vetted Edax source/license and enable the JNI adapter only after reproducible NDK builds.
- Configure production Supabase Auth, Realtime authorization, Storage limits, TURN credentials, crash reporting, privacy/retention policy, and Play Console signing.
- Before Play pre-release: choose the permanent application ID, add privacy policy/data-safety declarations, account deletion flow, release signing, OSS notices, crash/ANR monitoring, and staged rollout testing.
