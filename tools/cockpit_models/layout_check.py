#!/usr/bin/env python3
"""Checks a hand-built cockpit's LAYOUT against its panel: every instrument (with its bezel) must lie wholly on its
facet (under the facet's top edge, which slopes on the wings, above its bottom, clear of its sides) and clear of the
other instruments on that facet.

With --fix it settles each facet: overlapping instruments are pushed apart by the least that clears them (each
pair half and half, along the axis that needs less), every one kept on its facet; a facet that still does not fit
has its instruments scaled down 4 % at a time. The module's LAYOUT lines are rewritten in place, so the result can be
read and adjusted by hand.

Usage: layout_check.py VID [VID...] [--fix]
"""
import importlib, math, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
MARGIN = 0.006
GAP = 0.002
RIM = 0.16


def half_size(spec, s=1.0):
    """(half width, half height) of an item with its bezel, metres, its size scaled by s"""
    k = spec[0]
    if k == 'game':
        r = spec[2] * s / 2 * (1 + RIM) + 0.001
        return r, r
    if k == 'paint_disc':
        r = spec[1] * s / 2 + 0.004
        return r, r
    if k == 'display':
        return spec[2] * s / 2 + 0.020, spec[2] * s / 2 + 0.020
    return spec[1] * s / 2 + 0.005, spec[2] * s / 2 + 0.005


def scaled(spec, s):
    k = spec[0]
    if s == 1.0:
        return spec
    if k in ('game', 'display'):
        return spec[:2] + (round(spec[2] * s, 3),)
    if k == 'paint_disc':
        return (k, round(spec[1] * s, 3))
    return (k, round(spec[1] * s, 3), round(spec[2] * s, 3))


class Item:
    def __init__(self, name, side, a, y, spec):
        self.name, self.side, self.a, self.y, self.spec, self.s = name, side, a, y, spec, 1.0
        self.a0, self.y0 = a, y


def clamp(mod, it):
    ext = {0: (-mod.HINGE, mod.HINGE), 1: (0.0, mod.WING_LEN), -1: (0.0, mod.WING_LEN)}
    hw, hh = half_size(it.spec, it.s)
    hy = hh * math.cos(mod.LEAN)
    a0, a1 = ext[it.side]
    bot = mod.BOT_C if it.side == 0 else mod.BOT_W
    it.a = min(max(it.a, a0 + hw + MARGIN), a1 - hw - MARGIN)
    top = min(mod.top_of(it.side, it.a - hw), mod.top_of(it.side, it.a + hw))
    it.y = max(min(it.y, top - hy - MARGIN), bot + hy + MARGIN)
    return it.y + hy <= top - MARGIN + 1e-6 and it.y - hy >= bot + MARGIN - 1e-6


def pair_overlap(mod, p, q):
    c = math.cos(mod.LEAN)
    pw, ph = half_size(p.spec, p.s); qw, qh = half_size(q.spec, q.s)
    dx = (pw + qw + GAP) - abs(p.a - q.a)
    dy = (ph + qh) * c + GAP - abs(p.y - q.y)
    return dx, dy


def settle(mod, items):
    """push apart, keep on the facet; True when the facet fits"""
    for _ in range(400):
        moved = False
        for i in range(len(items)):
            for j in range(i + 1, len(items)):
                p, q = items[i], items[j]
                if p.side != q.side:
                    continue
                dx, dy = pair_overlap(mod, p, q)
                if dx > 0 and dy > 0:
                    moved = True
                    if dx < dy:
                        sgn = 1 if p.a >= q.a else -1
                        p.a += sgn * dx / 2 + sgn * 1e-4; q.a -= sgn * dx / 2 + sgn * 1e-4
                    else:
                        sgn = 1 if p.y >= q.y else -1
                        p.y += sgn * dy / 2 + sgn * 1e-4; q.y -= sgn * dy / 2 + sgn * 1e-4
        for it in items:
            clamp(mod, it)
        if not moved:
            break
    ok = all([clamp(mod, it) for it in items])
    for i in range(len(items)):
        for j in range(i + 1, len(items)):
            if items[i].side == items[j].side:
                dx, dy = pair_overlap(mod, items[i], items[j])
                if dx > 0.001 and dy > 0.001:
                    ok = False
    return ok


def report(mod):
    items = [Item(n, s, a, y, sp) for n, s, a, y, sp, _ in mod.LAYOUT]
    out = []
    for it in items:
        a, y = it.a, it.y
        clamp(mod, it)
        if abs(it.a - a) > 2e-3 or abs(it.y - y) > 2e-3:
            out.append(f'  off its facet: {it.name}')
    for i in range(len(items)):
        for j in range(i + 1, len(items)):
            if items[i].side == items[j].side:
                dx, dy = pair_overlap(mod, items[i], items[j])
                if dx > 0.001 and dy > 0.001:
                    out.append(f'  overlap: {items[i].name} / {items[j].name}')
    return out


def fix(mod):
    items = [Item(n, s, a, y, sp) for n, s, a, y, sp, _ in mod.LAYOUT]
    for side in (0, 1, -1):
        group = [it for it in items if it.side == side]
        for k in range(7):                 # gauges no smaller than 0.75; displays keep their size
            for it in group:
                it.a, it.y = it.a0, it.y0
            if settle(mod, group):
                break
            for it in group:
                if it.spec[0] != 'display':
                    it.s = 0.96 ** (k + 1)
    return items


def rewrite(path, items, layout_name='LAYOUT'):
    t = open(path, encoding='utf-8').read()
    i0 = t.index(layout_name + ' = [')
    i1 = t.index('\n]\n', i0)
    block = t[i0:i1]
    for it in items:
        pat = re.compile(r"(\('" + re.escape(it.name) + r"',\s*-?\d,\s*)(-?[\d.]+)(,\s*)(-?[\d.]+)(,\s*)\(([^()]*)\)")
        sp = scaled(it.spec, it.s)
        spec_txt = ', '.join(repr(v) if isinstance(v, str) else f'{v:.3f}' for v in sp)
        block, n = pat.subn(lambda m: f"{m.group(1)}{it.a: .3f}{m.group(3)}{it.y:.3f}{m.group(5)}({spec_txt})", block,
                            count=1)
        if n != 1:
            print('   could not rewrite', it.name)
    open(path, 'w', encoding='utf-8', newline='\n').write(t[:i0] + block + t[i1:])


def main():
    do_fix = '--fix' in sys.argv
    for vid in [a for a in sys.argv[1:] if not a.startswith('--')]:
        mod = importlib.import_module(vid)
        stations = list(getattr(mod, 'STATIONS', {}) or [None])
        for st in stations:
            if st is not None:
                mod.use(st)
            name = 'LAYOUT' if st is None else ('LAYOUT_FRONT' if st == 'front' else 'LAYOUT_REAR')
            if do_fix:
                items = fix(mod)
                rewrite(mod.__file__, items, name)
                mod = importlib.reload(mod)
                if st is not None:
                    mod.use(st)
                small = [f'{it.name} x{it.s:.2f}' for it in items if it.s < 1]
                if small:
                    print(f'  {vid}: scaled to fit: {", ".join(small)}')
            out = report(mod)
            print(f'{vid}{" " + st if st else ""}: {"ok" if not out else str(len(out)) + " problems"}')
            for line in out:
                print(line)


if __name__ == '__main__':
    main()
