#!/usr/bin/env python3
"""Generate Phonon's launcher icon: the avatar's particle cloud.

A dense, roughly circular swarm: a bright core slightly off centre, thousands of
points thinning outward into a diffuse edge, with a faint bloom underneath. Cyan
on black, no frame, no glyph.

    python3 tools/icon/generate.py
"""
import math
import random
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app/app/src/main/res"

CYAN = (0, 229, 255)
BLACK = (0, 0, 0, 255)
S = 1024
PARTICLES = 2600
CORE_PARTICLES = 520
RADIUS = 0.46
CORE_RADIUS = 0.16
CORE_OFFSET = (-0.035, 0.02)


def cloud(seed=17):
    random.seed(seed)
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    cx = cy = S / 2
    R = S / 2

    def dot(x, y, rad, alpha):
        d.ellipse([x - rad, y - rad, x + rad, y + rad], fill=CYAN + (alpha,))

    for _ in range(PARTICLES):
        a = random.uniform(0, 2 * math.pi)
        u = random.random() ** 0.55          # dense centre, sparse rim
        r = u * RADIUS * R
        x = cx + math.cos(a) * r
        y = cy + math.sin(a) * r
        rad = random.uniform(0.7, 2.0) * (1.25 - 0.45 * u)
        alpha = int(70 + 165 * (1 - u) ** 1.4)
        dot(x, y, rad, alpha)

    ox, oy = CORE_OFFSET[0] * S, CORE_OFFSET[1] * S
    for _ in range(CORE_PARTICLES):
        a = random.uniform(0, 2 * math.pi)
        u = random.random() ** 0.7
        r = u * CORE_RADIUS * R
        x = cx + ox + math.cos(a) * r
        y = cy + oy + math.sin(a) * r
        dot(x, y, random.uniform(0.7, 1.8), random.randint(170, 255))

    glow = img.filter(ImageFilter.GaussianBlur(radius=S / 260))
    out = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    out.alpha_composite(glow)
    out.alpha_composite(img)
    return out


def at(master, size, background=True):
    art = master.resize((size, size), Image.Resampling.LANCZOS)
    if not background:
        return art
    base = Image.new("RGBA", (size, size), BLACK)
    base.alpha_composite(art)
    return base


def main():
    master = cloud()
    drawable = RES / "drawable-nodpi"
    drawable.mkdir(parents=True, exist_ok=True)
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
    print("wrote particle-cloud foreground, mipmaps and store icon")


if __name__ == "__main__":
    main()
