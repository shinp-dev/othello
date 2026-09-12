# Audio provenance

## Standard mode audio

The files matching `app/src/main/res/raw/standard_*.ogg` do **not** contain third-party samples or downloaded sound effects.
They were synthesized specifically for Chanriva from deterministic mathematical waveforms by `tools/generate_standard_audio.py` and encoded to OGG/Vorbis for app bundling.

The project dedicates these generated Standard-mode audio assets to **Creative Commons Zero (CC0) 1.0 Universal**:

- License: https://creativecommons.org/publicdomain/zero/1.0/
- Attribution is not required, although preserving this provenance file is recommended.

Current generated assets:

| File | Purpose |
| --- | --- |
| `standard_stone_place.ogg` | Disc placement |
| `standard_opponent_appear.ogg` | Opponent entrance |
| `standard_heartbeat.ogg` | Tension heartbeat; playback rate/volume varies by tension level |
| `standard_human_win.ogg` | Normal player win |
| `standard_human_loss.ogg` | Player loss |
| `standard_draw.ogg` | Draw |
| `standard_level_clear.ogg` | First level clear/unlock |
| `standard_campaign_conquered.ogg` | Lv8 campaign conquest |
| `standard_gacha_common.ogg` | Common-card capsule reveal |
| `standard_gacha_rare.ogg` | Rare-card capsule reveal |
| `standard_gacha_special.ogg` | Special-card capsule reveal |

## Reproduction

Generate WAV masters only:

```bash
python tools/generate_standard_audio.py
```

Generate WAV masters and the Android OGG files when `ffmpeg` with `libvorbis` is available:

```bash
python tools/generate_standard_audio.py \
  --wav-dir build/standard-audio-wav \
  --ogg-dir app/src/main/res/raw
```

The synthesis is deterministic. OGG bytes may differ between encoder/library versions even when the audible source is the same.

## Future third-party audio

If third-party audio is introduced later, record at minimum the file name, creator, original source URL, acquisition date, license, and any attribution requirements here before bundling it in a release.
