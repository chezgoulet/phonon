#!/usr/bin/env python3
"""Generate Phonon's launcher icon: a starburst drawn with the avatar's dots.

No frame, no glyph: twelve rays of particles around a dense core, cyan on black,
the same vocabulary as the being on the Serve screen. Run from the repo root:

    python3 tools/icon/generate.py

Everything is procedural and seeded, so the same star is produced every time.
"""
import math
import random
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app/app/src/main/res"

CYAN = (0, 229, 255)
BLACK = (0, 0, 0, 255)
S = 1024                      # master render
RAYS = 12
REACH = 0.62                  # ray length as a fraction of the half-canvas
CORE = 0.10
DOTS = 28


def star(seed=3):
    random.seed(seed)
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    cx = cy = S / 2
    R = S / 2

    for _ in range(90):
        a = random.uniform(0, 2 * math.pi)
        r = random.uniform(0, CORE * R)
        x, y = cx + math.cos(a) * r, cy + math.sin(a) * r
        rad = random.uniform(2.0, 5.0) * (1 - 0.5 * r / (CORE * R))
        d.ellipse([x - rad, y - rad, x + rad, y + rad],
                  fill=CYAN + (random.randint(180, 255),))

    for k in range(RAYS):
        base = 2 * math.pi * k / RAYS
        for i in range(DOTS):
            t = i / (DOTS - 1)
            r = CORE * R + t * (REACH - CORE) * R
            jitter = random.uniform(-1, 1) * (2.5 + 4.0 * t)
            a = base + random.uniform(-0.012, 0.012)
            x = cx + math.cos(a) * r - math.sin(a) * jitter
            y = cy + math.sin(a) * r + math.cos(a) * jitter
            rad = (5.2 * (1 - 0.72 * t)) * random.uniform(0.85, 1.15)
            d.ellipse([x - rad, y - rad, x + rad, y + rad],
                      fill=CYAN + (int(255 * (1 - 0.68 * t)),))
    return img


def at(master, size, background):
    art = master.resize((size, size), Image.Resampling.LANCZOS)
    if background:
        base = Image.new("RGBA", (size, size), BLACK)
        base.paste(art, (0, 0), art)
        return base
    return art


def main():
    master = star()
    drawable = RES / "drawable-nodpi"
    drawable.mkdir(parents=True, exist_ok=True)
    # Transparent foreground; the adaptive background stays black.
    at(master, 432, background=False).save(drawable / "ic_launcher_foreground.png")

    for bucket, px in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        d = RES / f"mipmap-{bucket}"
        d.mkdir(parents=True, exist_ok=True)
        art = at(master, px, background=True)
        art.save(d / "ic_launcher.png")
        mask = Image.new("L", art.size, 0)
        ImageDraw.Draw(mask).ellipse([0, 0, px - 1, px - 1], fill=255)
        rounded = art.copy(); rounded.putalpha(mask)
        rounded.save(d / "ic_launcher_round.png")

    store = ROOT / "fastlane/metadata/android/en-US/images"
    store.mkdir(parents=True, exist_ok=True)
    at(master, 512, background=True).save(store / "icon.png")
    print("wrote starburst foreground, mipmaps and store icon")


if __name__ == "__main__":
    main()
