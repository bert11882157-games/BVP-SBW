"""Procedural sprites for the TNT blast renderer (fire puffs, smoke puffs, flash, flame-ring).

Luminance lives in RGB (premultiplied by alpha, tinted per vertex in game), coverage in alpha. Deterministic seeds.
Run: python3 tools/blast_sprites/generate.py <resources root>/assets/superbwarfare/textures/particle/blast
"""
import sys, os
import numpy as np
from PIL import Image

N = 64


def fbm(seed, octaves=5, base=4):
    rng = np.random.default_rng(seed)
    out = np.zeros((N, N))
    amp, total = 1.0, 0.0
    for o in range(octaves):
        cells = base * 2 ** o
        grid = rng.random((cells + 1, cells + 1))
        # tileable not needed; bicubic-ish upsample via PIL
        img = Image.fromarray((grid * 255).astype(np.uint8)).resize((N, N), Image.BICUBIC)
        out += amp * (np.asarray(img) / 255.0)
        total += amp
        amp *= 0.55
    return out / total


def radial():
    y, x = np.mgrid[0:N, 0:N]
    c = (N - 1) / 2
    return np.hypot(x - c, y - c) / c


def save(path, rgb, alpha):
    # Premultiplied alpha: the blast shader blends with (ONE, ONE_MINUS_SRC_ALPHA) so one sorted pass can mix
    # glowing (additive, alpha ~0) and smoky (over) quads.
    a = np.clip(alpha, 0, 1)
    img = np.dstack([np.clip(rgb, 0, 1) * a] * 3 + [a])
    Image.fromarray((img * 255).round().astype(np.uint8), 'RGBA').save(path)


def puff(seed, softness, erosion, core):
    r = radial()
    n = fbm(seed)
    n2 = fbm(seed + 991, octaves=4, base=6)
    edge = 1.0 - r + (n - 0.5) * erosion
    alpha = np.clip(edge / softness, 0, 1) ** 1.3
    alpha *= 0.75 + 0.5 * (n2 - 0.5)
    lum = np.clip(core * np.clip(1.0 - r, 0, 1) ** 0.8 + 0.55 * n + 0.2 * n2, 0, 1)
    return lum, alpha


def main(out):
    os.makedirs(out, exist_ok=True)
    for i in range(8):  # fire: billowy, bright turbulent core; later frames more eroded
        lum, alpha = puff(100 + i, 0.55 - 0.03 * i, 0.9 + 0.08 * i, 1.0 - 0.06 * i)
        save(f'{out}/fire_{i}.png', 0.35 + 0.65 * lum, alpha)
    for i in range(6):  # smoke: soft, cauliflower edge, flat luminance
        lum, alpha = puff(300 + i, 0.7, 1.1, 0.25)
        save(f'{out}/smoke_{i}.png', 0.55 + 0.45 * lum, alpha * 0.95)
    r = radial()
    save(f'{out}/flash.png', np.ones((N, N)), np.clip(1 - r, 0, 1) ** 2.2)
    # shock diamond / afterburner ring: bright elliptical band
    y, x = np.mgrid[0:N, 0:N]
    c = (N - 1) / 2
    d = np.hypot((x - c) / c, (y - c) / c * 1.0)
    ring = np.exp(-((d - 0.55) / 0.18) ** 2) + 0.6 * np.exp(-(d / 0.35) ** 2)
    save(f'{out}/glow_ring.png', np.ones((N, N)), np.clip(ring, 0, 1))


if __name__ == '__main__':
    main(sys.argv[1])
