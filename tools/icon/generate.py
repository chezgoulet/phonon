#!/usr/bin/env python3
"""Generate Phonon's launcher icon: a portrait frame around a particle brain.

The brain artwork lives in tools/icon/brain-source.png — a particle swarm shaped
into a side profile, cyan on black. This script only composes it: crop to the
particles, normalise the brightness so downscaling keeps the dots crisp, place it
inside the frame, and write every asset Android and the stores need.

    python3 tools/icon/generate.py
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageEnhance

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app/app/src/main/res"
SRC = Path(__file__).resolve().parent / "brain-source.png"

INK = (215, 227, 223, 255)
BLACK = (0, 0, 0, 255)

# Fractions of the canvas: a portrait frame inside the 72-unit visible area of
# the 108-unit adaptive canvas, with the brain filling it.
FX, FY, FW, FH = 0.30, 0.24, 0.40, 0.52
GLYPH_FRACTION = 0.30
STROKE_FRACTION = 0.011


def master():
    im = Image.open(SRC).convert("RGB")
    grey = im.convert("L")
    box = grey.point(lambda v: 255 if v > 60 else 0).getbbox()
    im = im.crop(box)
    im = ImageEnhance.Brightness(im).enhance(1.45)
    im = ImageEnhance.Contrast(im).enhance(1.5)
    return im


def glyph_at(size, src):
    g = src.copy()
    g.thumbnail((size, size), Image.Resampling.LANCZOS)
    px = g.load()
    for y in range(g.height):
        for x in range(g.width):
            r, gg, b = px[x, y]
            if max(r, gg, b) < 26:
                px[x, y] = (0, 0, 0)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.paste(g, ((size - g.width) // 2, (size - g.height) // 2))
    return canvas


def compose(size, src):
    img = Image.new("RGBA", (size, size), BLACK)
    d = ImageDraw.Draw(img)
    d.rectangle([FX * size, FY * size, (FX + FW) * size, (FY + FH) * size],
                outline=INK, width=max(1, round(STROKE_FRACTION * size)))
    g = glyph_at(int(GLYPH_FRACTION * size), src)
    img.paste(g, ((size - g.width) // 2, (size - g.height) // 2), g)
    return img


def main():
    src = master()
    drawable = RES / "drawable-nodpi"
    drawable.mkdir(parents=True, exist_ok=True)
    # Adaptive foreground: a square PNG, part of the 108-unit canvas equivalent.
    compose(432, src).save(drawable / "ic_launcher_foreground.png")

    for bucket, px in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        d = RES / f"mipmap-{bucket}"
        d.mkdir(parents=True, exist_ok=True)
        art = compose(px, src)
        art.save(d / "ic_launcher.png")
        mask = Image.new("L", art.size, 0)
        ImageDraw.Draw(mask).ellipse([0, 0, px - 1, px - 1], fill=255)
        rounded = art.copy(); rounded.putalpha(mask)
        rounded.save(d / "ic_launcher_round.png")

    old = RES / "drawable/ic_launcher_foreground.xml"
    if old.exists():
        old.unlink()

    store = ROOT / "fastlane/metadata/android/en-US/images"
    store.mkdir(parents=True, exist_ok=True)
    compose(512, src).save(store / "icon.png")
    print("wrote adaptive foreground, mipmaps and store icon")


if __name__ == "__main__":
    main()
