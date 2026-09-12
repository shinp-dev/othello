# Standard audio design

Standard-mode audio is intentionally isolated from Advanced mode.

## Flow

```text
Standard game / AI decision
  -> StandardPresentationEvent
  -> StandardPresentationEngine
  -> StandardSoundCue
  -> AndroidStandardSoundOutput
  -> SoundPool + bundled res/raw audio
```

`StandardPresentationEngine` remains semantic and Android-API-free. `AndroidStandardSoundOutput` owns Android playback details only. The Standard match screen is the sole production composition root that creates this sound output.

The Standard gacha uses a separate `AndroidStandardGachaEffects` owner. It preloads three short fanfares with `SoundPool`, selects one from the drawn card's rarity, and releases the pool when the gacha screen leaves composition. Common, rare, and special draws therefore sound distinct without loading match audio into the gacha screen.

## Bundling

All Standard audio is packaged in the APK/AAB under `app/src/main/res/raw/`. No audio download, network permission, cache, or first-run preparation is required.

The capsule break also calls `View.performHapticFeedback` at the burst boundary. It uses the system `CONFIRM` effect on Android 11 and newer and `CONTEXT_CLICK` on Android 8–10, so it follows the user's system touch-feedback preference and does not require the `VIBRATE` permission.

## Heartbeat

A single generated heartbeat loop is reused for both tension levels:

- `TENSE`: quieter and slightly slower.
- `CRITICAL`: louder and faster.
- `CALM`, move placement, result, reset, or leaving the Standard match stops the heartbeat.

The presentation engine remains responsible for cue ordering, so terminal result cues cannot be replaced by a late move cue.

## Lifecycle

`AndroidStandardSoundOutput` pauses its `SoundPool` when the host stops, resumes when the host starts, and releases the pool when the Standard match composition is disposed. Stale one-shot effects are not intentionally replayed after returning from the background.

## Advanced-mode boundary

Do not instantiate `AndroidStandardSoundOutput` from shared board code, `MainActivity`, Advanced navigation, or Advanced AI settings. Keep Standard audio composition under `StandardAiMatchScreen` / `rememberStandardPresentationEngine` so Advanced remains behaviorally unchanged.

## Asset provenance

See `THIRD_PARTY_AUDIO.md`. Current Standard audio is synthesized by this project with no third-party samples and is released under CC0 1.0 Universal.
