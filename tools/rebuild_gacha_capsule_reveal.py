"""Rebuild the gacha reveal GIF with an unclipped burst sequence.

The source GIF supplies the intact shake/crack frames. The burst is rebuilt from
the original full shell artwork so no frame can lose pixels at the canvas edge.
"""

from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "tools" / "gacha-assets"
OUTPUT = (
    ROOT
    / "app"
    / "src"
    / "main"
    / "res"
    / "drawable-nodpi"
    / "standard_gacha_capsule_reveal.gif"
)
SOURCE = ASSETS / "standard_gacha_capsule_reveal_source.gif"
LEFT_SHELL = ASSETS / "standard_gacha_capsule_left_shell_art.webp"
RIGHT_SHELL = ASSETS / "standard_gacha_capsule_right_shell_art.png"
BURST_EFFECT = ASSETS / "standard_gacha_burst_effect.png"

CANVAS_SIZE = 320
PRESERVED_FRAME_COUNT = 21
TOTAL_FRAME_COUNT = 31
FRAME_DURATION_MS = 40
FINAL_FRAME_DURATION_MS = 320
MIN_HORIZONTAL_MARGIN = 3


def with_opacity(image: Image.Image, opacity: float) -> Image.Image:
    result = image.copy()
    alpha = result.getchannel("A").point(lambda value: round(value * opacity))
    result.putalpha(alpha)
    return result


def centered_layer(
    source: Image.Image,
    size: int,
    center_x: float,
    center_y: float,
    angle: float = 0,
    opacity: float = 1,
) -> tuple[Image.Image, tuple[int, int]]:
    layer = source.resize((size, size), Image.Resampling.LANCZOS)
    if angle:
        layer = layer.rotate(angle, Image.Resampling.BICUBIC, expand=False)
    if opacity < 1:
        layer = with_opacity(layer, opacity)
    position = (round(center_x - size / 2), round(center_y - size / 2))
    return layer, position


def build_global_palette(frames: list[Image.Image]) -> Image.Image:
    atlas = Image.new("RGB", (CANVAS_SIZE, CANVAS_SIZE * len(frames)))
    for index, frame in enumerate(frames):
        atlas.paste(frame.convert("RGB"), (0, index * CANVAS_SIZE))
    quantized = atlas.quantize(
        colors=255,
        method=Image.Quantize.MEDIANCUT,
        dither=Image.Dither.NONE,
    )
    palette = quantized.getpalette()[: 255 * 3] + [0, 0, 0]
    palette_image = Image.new("P", (1, 1))
    palette_image.putpalette(palette)
    return palette_image


def to_gif_frame(frame: Image.Image, palette: Image.Image) -> Image.Image:
    rgba = frame.convert("RGBA")
    paletted = rgba.convert("RGB").quantize(
        palette=palette,
        dither=Image.Dither.FLOYDSTEINBERG,
    )
    transparent = rgba.getchannel("A").point(lambda value: 255 if value < 12 else 0)
    paletted.paste(255, mask=transparent)
    paletted.info["transparency"] = 255
    paletted.info["disposal"] = 2
    return paletted


def assert_horizontal_padding(frames: list[Image.Image]) -> None:
    for index, frame in enumerate(frames):
        alpha = frame.convert("RGBA").getchannel("A")
        left = alpha.crop((0, 0, MIN_HORIZONTAL_MARGIN, CANVAS_SIZE)).getbbox()
        right = alpha.crop(
            (CANVAS_SIZE - MIN_HORIZONTAL_MARGIN, 0, CANVAS_SIZE, CANVAS_SIZE)
        ).getbbox()
        if left or right:
            raise ValueError(f"frame {index} touches the horizontal canvas edge")


def main() -> None:
    with Image.open(SOURCE) as source_gif:
        source_frames = [
            source_gif.seek(index) or source_gif.convert("RGBA")
            for index in range(source_gif.n_frames)
        ]

    frames = source_frames[:PRESERVED_FRAME_COUNT]
    left_shell = Image.open(LEFT_SHELL).convert("RGBA")
    right_shell = Image.open(RIGHT_SHELL).convert("RGBA")
    burst_effect = Image.open(BURST_EFFECT).convert("RGBA")

    rebuilt_count = TOTAL_FRAME_COUNT - PRESERVED_FRAME_COUNT
    for step in range(rebuilt_count):
        progress = step / (rebuilt_count - 1)
        eased = 1 - (1 - progress) ** 3
        canvas = Image.new("RGBA", (CANVAS_SIZE, CANVAS_SIZE), (0, 0, 0, 0))
        shell_size = round(320 - 20 * eased)

        left, left_position = centered_layer(
            left_shell,
            size=shell_size,
            center_x=80 + 2 * eased,
            center_y=157 + 4 * eased,
            angle=-8 - 8 * eased,
        )
        right, right_position = centered_layer(
            right_shell,
            size=shell_size,
            center_x=240 - 2 * eased,
            center_y=159 + 2 * eased,
            angle=8 + 8 * eased,
        )
        canvas.alpha_composite(left, left_position)
        canvas.alpha_composite(right, right_position)

        effect_size = round(170 + 66 * (1 - abs(2 * progress - 1)))
        effect_opacity = max(0, 1 - progress**1.7)
        effect, effect_position = centered_layer(
            burst_effect,
            size=effect_size,
            center_x=160,
            center_y=158,
            opacity=effect_opacity,
        )
        canvas.alpha_composite(effect, effect_position)
        frames.append(canvas)

    assert len(frames) == TOTAL_FRAME_COUNT
    assert_horizontal_padding(frames)

    palette = build_global_palette(frames)
    gif_frames = [to_gif_frame(frame, palette) for frame in frames]
    durations = [FRAME_DURATION_MS] * TOTAL_FRAME_COUNT
    durations[-1] = FINAL_FRAME_DURATION_MS
    temporary = OUTPUT.with_suffix(".tmp.gif")
    gif_frames[0].save(
        temporary,
        save_all=True,
        append_images=gif_frames[1:],
        duration=durations,
        loop=0,
        disposal=2,
        transparency=255,
        optimize=False,
    )
    temporary.replace(OUTPUT)


if __name__ == "__main__":
    main()
