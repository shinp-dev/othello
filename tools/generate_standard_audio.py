#!/usr/bin/env python3
"""Generate Chanriva Standard-mode sound effects using deterministic math only.

No samples or third-party audio are used. WAV files are generated with Python's
standard library. When ffmpeg is available, --ogg-dir also creates the compact OGG
assets used by the Android app.
"""
from __future__ import annotations

import argparse
import math
import random
import shutil
import struct
import subprocess
import wave
from pathlib import Path

SAMPLE_RATE = 22_050
TAU = math.tau
SEED = 0x4348414E52495641


def env_exp(t: float, decay: float) -> float:
    return math.exp(-max(t, 0.0) * decay)


def smoothstep(x: float) -> float:
    x = max(0.0, min(1.0, x))
    return x * x * (3.0 - 2.0 * x)


def bell(t: float, freq: float, decay: float = 5.0) -> float:
    if t < 0:
        return 0.0
    e = env_exp(t, decay)
    return e * (
        math.sin(TAU * freq * t)
        + 0.38 * math.sin(TAU * freq * 2.01 * t)
        + 0.16 * math.sin(TAU * freq * 3.97 * t)
    ) / 1.54


def thump(t: float, freq: float = 70.0, decay: float = 18.0) -> float:
    if t < 0:
        return 0.0
    phase = TAU * (freq * t + 28.0 * (1.0 - math.exp(-8.0 * t)) / 8.0)
    return math.sin(phase) * env_exp(t, decay)


def note_mix(t: float, notes: list[tuple[float, float, float, float]]) -> float:
    return sum(gain * bell(t - start, freq, decay) for start, freq, gain, decay in notes)


def write_wav(path: Path, seconds: float, fn, rng: random.Random) -> None:
    count = int(SAMPLE_RATE * seconds)
    samples: list[float] = []
    peak = 0.0
    for i in range(count):
        t = i / SAMPLE_RATE
        value = float(fn(t, rng))
        edge = min(smoothstep(t / 0.008), smoothstep((seconds - t) / 0.012))
        value *= edge
        samples.append(value)
        peak = max(peak, abs(value))

    normalization = 0.88 / max(peak, 1e-9)
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(SAMPLE_RATE)
        frames = bytearray()
        for value in samples:
            sample = int(max(-1.0, min(1.0, value * normalization)) * 32_767)
            frames += struct.pack("<h", sample)
        wav.writeframes(frames)


def stone(t: float, rng: random.Random) -> float:
    noise = (rng.random() * 2.0 - 1.0) * env_exp(t, 55.0)
    body = 0.95 * thump(t, 125.0, 32.0) + 0.42 * bell(t, 620.0, 26.0)
    return 0.35 * noise + body


def appear(t: float, rng: random.Random) -> float:
    sweep = 0.0
    if t < 0.30:
        progress = t / 0.30
        frequency = 260.0 + 520.0 * progress * progress
        sweep = (
            0.12
            * (rng.random() * 2.0 - 1.0)
            * math.sin(TAU * frequency * t)
            * math.sin(math.pi * progress)
        )
    return sweep + note_mix(t, [(0.18, 523.25, 0.70, 7.5), (0.30, 783.99, 0.72, 7.5)])


def win(t: float, _rng: random.Random) -> float:
    return note_mix(t, [
        (0.00, 523.25, 0.62, 6.8),
        (0.11, 659.25, 0.62, 6.5),
        (0.22, 783.99, 0.68, 6.2),
        (0.36, 1046.50, 0.82, 5.4),
    ])


def loss(t: float, _rng: random.Random) -> float:
    return note_mix(t, [
        (0.00, 392.00, 0.66, 6.3),
        (0.16, 311.13, 0.64, 6.0),
        (0.33, 261.63, 0.82, 5.2),
    ]) + 0.16 * thump(t - 0.31, 58.0, 12.0)


def draw(t: float, _rng: random.Random) -> float:
    return note_mix(t, [
        (0.00, 440.00, 0.55, 7.0),
        (0.16, 523.25, 0.55, 7.0),
        (0.32, 440.00, 0.45, 7.0),
    ])


def level_clear(t: float, _rng: random.Random) -> float:
    notes = [
        (0.00, 523.25, 0.48, 6.3),
        (0.10, 659.25, 0.50, 6.1),
        (0.20, 783.99, 0.54, 5.9),
        (0.30, 987.77, 0.58, 5.7),
        (0.43, 1046.50, 0.82, 4.8),
    ]
    sparkle = 0.0
    if 0.38 <= t <= 0.95:
        progress = (t - 0.38) / 0.57
        sparkle = (
            0.10
            * math.sin(TAU * (1400.0 + 1100.0 * progress) * t)
            * math.sin(math.pi * progress)
        )
    return note_mix(t, notes) + sparkle


def conquer(t: float, _rng: random.Random) -> float:
    notes = [
        (0.00, 392.00, 0.36, 4.6),
        (0.00, 523.25, 0.42, 4.6),
        (0.14, 659.25, 0.48, 4.8),
        (0.28, 783.99, 0.55, 4.8),
        (0.45, 1046.50, 0.75, 4.0),
        (0.68, 1318.51, 0.58, 3.8),
        (0.68, 1567.98, 0.50, 3.8),
    ]
    low = 0.14 * thump(t, 78.0, 9.0) + 0.12 * thump(t - 0.43, 84.0, 10.0)
    return note_mix(t, notes) + low


def heartbeat(t: float, _rng: random.Random) -> float:
    return (
        1.00 * thump(t - 0.08, 64.0, 18.0)
        + 0.72 * thump(t - 0.30, 58.0, 20.0)
        + 0.08 * thump(t - 0.10, 115.0, 28.0)
    )


def gacha_common(t: float, _rng: random.Random) -> float:
    """A compact, warm three-note reward without borrowing a recorded sample."""
    return note_mix(t, [
        (0.00, 659.25, 0.46, 9.0),
        (0.09, 783.99, 0.50, 8.3),
        (0.20, 1046.50, 0.62, 7.4),
    ]) + 0.08 * thump(t, 92.0, 24.0)


def gacha_rare(t: float, _rng: random.Random) -> float:
    """An ascending five-note reveal with a short crystalline tail."""
    notes = [
        (0.00, 587.33, 0.38, 7.6),
        (0.08, 739.99, 0.42, 7.3),
        (0.17, 880.00, 0.48, 7.0),
        (0.28, 1174.66, 0.58, 6.2),
        (0.43, 1479.98, 0.66, 5.4),
    ]
    shimmer = 0.0
    if 0.38 <= t <= 0.92:
        progress = (t - 0.38) / 0.54
        shimmer = (
            0.09
            * math.sin(TAU * (1720.0 + 920.0 * progress) * t)
            * math.sin(math.pi * progress)
        )
    return note_mix(t, notes) + shimmer + 0.08 * thump(t, 104.0, 25.0)


def gacha_special(t: float, _rng: random.Random) -> float:
    """A layered original fanfare with low impact, lift, and a sparkling crown."""
    notes = [
        (0.00, 392.00, 0.26, 5.5),
        (0.00, 523.25, 0.34, 5.5),
        (0.08, 659.25, 0.38, 5.8),
        (0.17, 783.99, 0.42, 5.6),
        (0.28, 1046.50, 0.54, 5.0),
        (0.42, 1318.51, 0.62, 4.7),
        (0.60, 1567.98, 0.72, 4.2),
        (0.78, 2093.00, 0.58, 3.8),
        (0.78, 2637.02, 0.42, 3.8),
    ]
    shimmer = 0.0
    if 0.52 <= t <= 1.34:
        progress = (t - 0.52) / 0.82
        shimmer = (
            0.10
            * math.sin(TAU * (1900.0 + 1450.0 * progress) * t)
            * math.sin(math.pi * progress)
        )
    impact = 0.15 * thump(t, 82.0, 16.0) + 0.09 * thump(t - 0.40, 96.0, 18.0)
    return note_mix(t, notes) + shimmer + impact


SOUNDS = {
    "standard_stone_place": (0.14, stone),
    "standard_opponent_appear": (0.72, appear),
    "standard_human_win": (0.95, win),
    "standard_human_loss": (0.92, loss),
    "standard_draw": (0.68, draw),
    "standard_level_clear": (1.20, level_clear),
    "standard_campaign_conquered": (1.65, conquer),
    "standard_heartbeat": (1.20, heartbeat),
    "standard_gacha_common": (0.72, gacha_common),
    "standard_gacha_rare": (1.08, gacha_rare),
    "standard_gacha_special": (1.52, gacha_special),
}


def convert_to_ogg(wav_path: Path, ogg_path: Path) -> None:
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg is None:
        raise SystemExit("ffmpeg is required when --ogg-dir is specified")
    ogg_path.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        [
            ffmpeg,
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-i",
            str(wav_path),
            "-c:a",
            "libvorbis",
            "-q:a",
            "3",
            str(ogg_path),
        ],
        check=True,
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--wav-dir",
        type=Path,
        default=Path("build/standard-audio-wav"),
        help="Directory for generated WAV masters.",
    )
    parser.add_argument(
        "--ogg-dir",
        type=Path,
        help="Optional directory for OGG/Vorbis copies (requires ffmpeg).",
    )
    args = parser.parse_args()
    rng = random.Random(SEED)

    for stem, (seconds, synth) in SOUNDS.items():
        wav_path = args.wav_dir / f"{stem}.wav"
        write_wav(wav_path, seconds, synth, rng)
        print(f"generated {wav_path}")
        if args.ogg_dir is not None:
            ogg_path = args.ogg_dir / f"{stem}.ogg"
            convert_to_ogg(wav_path, ogg_path)
            print(f"generated {ogg_path}")


if __name__ == "__main__":
    main()
