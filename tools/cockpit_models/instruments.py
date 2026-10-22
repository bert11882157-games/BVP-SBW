"""Stage 2 of the hand-built cockpits: the instruments on the plates that structures/<vid>.py registered.

Everything here is placed in a plate's own frame (a metres to the pilot's right of the plate centre, y metres up its
face), so an aircraft file reads like the panel it describes. The game paints the live parts itself (dial faces,
display pages, annunciator lamps) from the records appended to S.gauges / S.displays; the mesh gets what the game
does not draw: the recesses the dials sit in, the keys round a display, switches, knobs, guards, handles, and the
painted placards, keypads and text windows (tiles in the aircraft's texture atlas).

space_report() checks that every instrument and plate corner is inside the side skin and under the canopy glass,
before the clip that finish() applies.
"""
import math
import numpy as np
from PIL import ImageFont

import cockpit_lib as L
import structure as K

PPM = 1600                                    # texels per metre on painted tiles
FONT = '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'
FONT_B = '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'
FONT_M = '/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf'
WHITE = (228, 228, 220)
BLACK = (12, 12, 13)
PANEL = (56, 58, 60)
GREEN_TXT = (120, 236, 130)
AMBER = (214, 140, 30)
RED = (196, 36, 30)
GREEN = (60, 150, 70)

_fonts = {}


def font(px, bold=False, mono=False):
    key = (int(px), bold, mono)
    if key not in _fonts:
        try:
            _fonts[key] = ImageFont.truetype(FONT_M if mono else (FONT_B if bold else FONT), max(4, int(px)))
        except OSError:
            _fonts[key] = ImageFont.load_default()
    return _fonts[key]


def text(d, xy, s, px, fill=WHITE, anchor='mm', bold=False, mono=False):
    d.text(xy, s, font=font(px, bold, mono), fill=tuple(fill) + (255,), anchor=anchor)


def lamp(d, box, colour, label=None, lit=False, px=None, fg=(20, 20, 20)):
    """a painted (dead) lamp: its lens in [colour], dimmed unless lit, the legend across it"""
    x0, y0, x1, y1 = box
    base = tuple(int(c * (1.0 if lit else 0.42)) for c in colour)
    d.rectangle(box, fill=base + (255,), outline=(10, 10, 10, 255), width=max(1, int((y1 - y0) * 0.06)))
    d.rectangle([x0 + 2, y0 + 2, x1 - 2, y0 + (y1 - y0) * 0.30], fill=tuple(min(255, c + 24) for c in base) + (255,))
    if label:
        lines = label.split('|')
        px = px or (y1 - y0) * (0.42 if len(lines) == 1 else 0.30)
        for k, line in enumerate(lines):
            text(d, ((x0 + x1) / 2, (y0 + y1) / 2 + (k - (len(lines) - 1) / 2) * px * 1.1), line, px, fill=fg,
                 bold=True)


# ---------------------------------------------------------------- painted tiles on a plate

class Tile:
    """a painted rect in the atlas, w x h metres, with uv(s, t) over it and sub-rect uvs for key tops"""

    def __init__(self, S, w, h, rect):
        self.S, self.w, self.h, self.rect = S, w, h, rect

    def uv(self, s, t):
        return self.S.uv(self.rect, s, t)

    def quad(self, s0, t0, s1, t1):
        return [self.uv(s0, t0), self.uv(s1, t0), self.uv(s1, t1), self.uv(s0, t1)]


def paint(S, w, h, fn, bg=BLACK):
    """a tile w x h metres painted by fn(draw, image, W, H) at PPM (the image is supersampled: use W, H)"""
    if S.atlas is None:
        return None
    W, H = max(8, int(round(w * PPM))), max(8, int(round(h * PPM)))
    return Tile(S, w, h, S.tile(W, H, lambda d, im: fn(d, im, im.width, im.height), bg=tuple(bg) + (255,)))


def face(S, p, a, y, w, h, tile, lift=0.0, sub=None, colour='black'):
    """the tile (or its sub-rect (s0, t0, s1, t1)) on the plate, lift proud of its face"""
    pts = [p.at(a - w / 2, y - h / 2, lift), p.at(a + w / 2, y - h / 2, lift), p.at(a + w / 2, y + h / 2, lift),
           p.at(a - w / 2, y + h / 2, lift)]
    if tile is None:
        S.m.flat(pts, S.c[colour], normal=p.N)
    else:
        S.m.face(pts, tile.quad(*(sub or (0, 0, 1, 1))), normal=p.N)


def placard(S, p, a, y, w, h, label, fg=WHITE, bg=BLACK, px=None, lift=0.0012, bold=False, mono=False):
    """a lettered strip on the plate: label lines split on |, sized to the strip"""
    lines = label.split('|')

    def fn(d, im, W, H):
        size = px * PPM * K.Atlas.SS if px else H * (0.66 if len(lines) == 1 else 0.78 / len(lines))
        for k, line in enumerate(lines):
            text(d, (W / 2, H / 2 + (k - (len(lines) - 1) / 2) * size * 1.15), line, size, fill=fg, bold=bold,
                 mono=mono)
    t = paint(S, w, h, fn, bg)
    face(S, p, a, y, w, h, t, lift, colour='black')
    return t


def raised(S, p, a, y, w, h, proud, tile=None, sub=None, side='dgrey', top='black', lift=0.0):
    """a raised key / block on the plate: four sides in [side], its top painted from the tile (or in [top])"""
    m = S.m
    lo = [p.at(a - w / 2, y - h / 2, lift), p.at(a + w / 2, y - h / 2, lift), p.at(a + w / 2, y + h / 2, lift),
          p.at(a - w / 2, y + h / 2, lift)]
    hi = [q + p.N * proud for q in lo]
    for i in range(4):
        j = (i + 1) % 4
        mid = (lo[i] + lo[j]) / 2
        m.flat([lo[i], lo[j], hi[j], hi[i]], S.c[side], toward=mid + (mid - p.at(a, y, lift)) * 3)
    if tile is None:
        m.flat(hi, S.c[top], normal=p.N)
    else:
        m.face(hi, tile.quad(*(sub or (0, 0, 1, 1))), normal=p.N)


# ---------------------------------------------------------------- the live instruments (the game paints them)

def dial(S, p, a, y, kind, d, lift=0.0, recess=True):
    """a round instrument of [d] metres face; the game draws its face and bezel standing GAUGE_LIFT proud; the
    plate gets the dark recess it sits in. -> the footprint width (bezel included)"""
    fp = d * (1 + L.RIM)
    if recess:
        S.m.disc(p.at(a, y, lift + 0.0006), p.N, p.U, fp / 2 + 0.0015, S.c['black'], seg=24)
    S.gauges.append({'Seat': S.t.seat, 'Kind': kind, 'Center': L.r5(p.at(a, y, lift + L.GAUGE_LIFT)),
                     'Normal': L.r5(p.N), 'Up': L.r5(p.U), 'Diameter': round(float(d), 4), 'Depth': 0.03})
    return fp


def display(S, p, a, y, kind, w, style='LCD', osb=5, lift=0.0, keys='mgrey'):
    """a display of [w] metres screen (the game draws the screen and its housing DISPLAY_BEZEL wide); [osb] option
    select keys along each edge of the housing. -> the housing width"""
    s = S.s
    hw = w * (1 + 2 * L.DISPLAY_BEZEL) / 2
    S.displays.append({'Seat': S.t.seat, 'Center': L.r5(p.at(a, y, lift + L.DISPLAY_LIFT)), 'Normal': L.r5(p.N),
                       'Up': L.r5(p.U), 'Width': round(float(w), 4), 'Depth': 0.035, 'Kind': kind, 'Style': style})
    if osb:
        kw, kh = 0.011 * s, 0.0065 * s                                 # a key: long along the edge, shallow
        for k in range(osb):
            f = (k - (osb - 1) / 2) / osb * w * 1.35
            for sg in (-1, 1):
                S.box(p.at(a + f, y + sg * (hw + kh / 2 + 0.0025), lift + 0.003), (p.R, p.U, p.N),
                      (kw / 2, kh / 2, 0.003), keys)
                S.box(p.at(a + sg * (hw + kh / 2 + 0.0025), y + f, lift + 0.003), (p.R, p.U, p.N),
                      (kh / 2, kw / 2, 0.003), keys)
    return 2 * hw


def annunciator(S, p, a, y, w, h, lamps, columns, lift=0.0, depth=0.02):
    """a lamp panel the game draws (housing and lamps): lamps = [(caption, condition, colour)], | breaks a
    caption into two lines"""
    S.gauges.append({'Seat': S.t.seat, 'Kind': 'ANNUNCIATOR', 'Center': L.r5(p.at(a, y, lift + 0.002)),
                     'Normal': L.r5(p.N), 'Up': L.r5(p.U), 'Diameter': round(float(w), 4), 'Width': round(float(w), 4),
                     'Height': round(float(h), 4), 'Depth': depth, 'Columns': int(columns),
                     'Lamps': [{'Text': t, 'Condition': c, 'Colour': k} for t, c, k in lamps]})


# ---------------------------------------------------------------- switches and controls

def toggle(S, p, a, y, tilt=1, lift=0.0, col=None):
    K.toggle(S, p.at(a, y, lift), p.R, p.U, p.N, tilt=tilt, col=col)


def knob(S, p, a, y, r=0.010, h=0.012, lift=0.0, pointer=True):
    K.knob(S, p.at(a, y, lift), p.N, p.U, r=r, h=h, pointer=pointer)


def button(S, p, a, y, w=0.012, h=0.012, colour='mgrey', proud=0.006, lift=0.0, cap=None):
    s = S.s
    S.box(p.at(a, y, lift + proud / 2), (p.R, p.U, p.N), (w * s / 2, h * s / 2, proud / 2), colour,
          faces={(2, 1): cap} if cap else None)


def guard(S, p, a, y, w=0.016, h=0.028, lift=0.0, colour='red'):
    """a hinged guard cover standing over a switch (closed): the cover and its two cheeks"""
    s = S.s
    S.box(p.at(a, y, lift + 0.019), (p.R, p.U, p.N), (w * s / 2, h * s / 2, 0.002), colour)
    for sg in (-1, 1):
        S.box(p.at(a + sg * (w * s / 2 - 0.001), y, lift + 0.010), (p.R, p.U, p.N), (0.001, h * s / 2, 0.010),
              colour)


def gear_handle(S, p, a, y, lift=0.0, size=1.0):
    K.gear_handle(S, p.at(a, y, lift), p.R, p.U, p.N, size=size)


def switch_row(S, p, a0, a1, y, labels, lift=0.0, tilts=None, label_h=0.009, gap=0.004, fg=WHITE, bg=BLACK):
    """toggles spread between a0 and a1 at height y, each with its placard above it"""
    n = len(labels)
    s = S.s
    for k, lab in enumerate(labels):
        a = a0 + (a1 - a0) * (k + 0.5) / n
        toggle(S, p, a, y, tilt=(tilts[k] if tilts else 1), lift=lift)
        if lab:
            pw = min((a1 - a0) / n - gap, max(0.018, 0.0045 * s * max(len(x) for x in lab.split('|'))))
            placard(S, p, a, y + 0.013 * s + label_h / 2 + 0.002, pw, label_h * (1.0 if '|' not in lab else 1.7),
                    lab, fg=fg, bg=bg, lift=lift + 0.0012)


# ---------------------------------------------------------------- keypads and text windows

def ded(S, p, a, y, w, h, lines, lift=0.0, frame=0.004):
    """a data entry display: green text on a black window, sunk in a frame"""
    def fn(d, im, W, H):
        d.rectangle([0, 0, W, H], fill=(8, 12, 9, 255))
        n = max(1, len(lines))
        px = min(H / (n + 0.6), W / (max(len(x) for x in lines) + 1) * 1.65)
        for k, line in enumerate(lines):
            text(d, (W * 0.03, H * (k + 0.5) / n), line, px, fill=GREEN_TXT, anchor='lm', mono=True)
    t = paint(S, w, h, fn, (8, 12, 9))
    face(S, p, a, y, w, h, t, lift + 0.0005)
    S.m.frame_rect(p.at(a, y, lift), p.N, p.U, w, h, frame, 0.003, S.c['dgrey'])
    return t


def keypad(S, p, a, y, w, h, keys, cols, rows, key_w, key_h, proud=0.004, bg=(36, 37, 39), lift=0.0,
           key_colour=(70, 72, 74), fg=WHITE, extra=None):
    """a keypad: the back plate painted in one tile (legends under the keys), keys raised with their legends on
    top. keys = [(col, row, legend, colour or None)], columns and rows counted from the top left; extra(d, im, W,
    H) paints anything else on the back plate"""
    cw, rh = w / cols, h / rows

    def fn(d, im, W, H):
        if extra:
            extra(d, im, W, H)
        for col, row, legend, colour in keys:
            x0 = (col + 0.5) * cw / w * W - key_w / w * W / 2
            y0 = (row + 0.5) * rh / h * H - key_h / h * H / 2
            x1 = x0 + key_w / w * W; y1 = y0 + key_h / h * H
            d.rectangle([x0, y0, x1, y1], fill=tuple(colour or key_colour) + (255,))
            ls = legend.split('|')
            px = min((y1 - y0) * (0.46 if len(ls) == 1 else 0.34), (x1 - x0) * 1.5 / max(len(x) for x in ls))
            for k, line in enumerate(ls):
                text(d, ((x0 + x1) / 2, (y0 + y1) / 2 + (k - (len(ls) - 1) / 2) * px * 1.1), line, px, fill=fg,
                     bold=True)
    t = paint(S, w, h, fn, bg)
    face(S, p, a, y, w, h, t, lift + 0.0008)
    for col, row, legend, colour in keys:
        ka = a - w / 2 + (col + 0.5) * cw
        ky = y + h / 2 - (row + 0.5) * rh
        s0 = (ka - key_w / 2 - (a - w / 2)) / w; s1 = s0 + key_w / w
        t0 = (ky - key_h / 2 - (y - h / 2)) / h; t1 = t0 + key_h / h
        raised(S, p, ka, ky, key_w * 0.92, key_h * 0.92, proud, t, (s0, t0, s1, t1), lift=lift + 0.0008)
    return t


# ---------------------------------------------------------------- the space check

def space_report(S, margin=0.002):
    """[(what, fault, point)] for every instrument, display and plate corner outside the side skin or above
    everything that covers the cockpit (the glass, the skin, the canopy frames)"""
    ac, t, E = S.ac, S.t, S.E
    cover = L.cover_tris(ac)
    bad = []

    def check(name, pts):
        for q in pts:
            q = np.asarray(q, float)
            d = ac.skin_x(q[1], q[2], E[0])[0 if q[0] > E[0] else 1]
            if np.isfinite(d) and abs(q[0] - E[0]) > d + margin:
                bad.append((name, f'outside the side skin by {abs(q[0] - E[0]) - d:.3f}', L.r5(q)))
            elif q[1] > t.floor_y + 0.05 and L.cover_y(ac.glass, q[0], q[2], q[1]) is None and \
                    L.cover_y(cover, q[0], q[2], q[1]) is None:
                bad.append((name, 'above the glass', L.r5(q)))

    def quad(c, n, u, hw, hh):
        c, n, u, r = L.frame(c, n, u)
        return [c - r * hw - u * hh, c + r * hw - u * hh, c + r * hw + u * hh, c - r * hw + u * hh]
    for g in S.gauges:
        if g['Kind'] == 'ANNUNCIATOR':
            check('lamps ' + '/'.join(l['Text'] for l in g['Lamps'][:2]),
                  quad(g['Center'], g['Normal'], g['Up'], g['Width'] / 2, g['Height'] / 2))
        else:
            h = g['Diameter'] * (1 + L.RIM) / 2
            check(g['Kind'], quad(g['Center'], g['Normal'], g['Up'], h, h))
    for d in S.displays:
        h = d['Width'] * (1 + 2 * L.DISPLAY_BEZEL) / 2
        check('display ' + d['Kind'], quad(d['Center'], d['Normal'], d['Up'], h, h))
    for p in S.plates.values():
        check('plate ' + p.name, p.corners())
    if S.hud:
        h = S.hud
        check('HUD combiner', quad(h['centre'], h['normal'], h['up'], h['width'] / 2, h['height'] / 2))
    return bad


def describe(S):
    """one line per instrument for the review page"""
    out = []
    for g in S.gauges:
        if g['Kind'] == 'ANNUNCIATOR':
            out.append(f"lamps {g['Width'] * 100:.1f} x {g['Height'] * 100:.1f} cm: "
                       + ', '.join(l['Text'].replace('|', ' ') for l in g['Lamps']))
        else:
            out.append(f"{g['Kind']} {g['Diameter'] * 100:.1f} cm")
    for d in S.displays:
        out.append(f"{d['Kind']} {d['Style']} display {d['Width'] * 100:.1f} cm")
    return out


def dial_row(S, p, y, items, gap=0.008, centre=0.0, lift=0.0):
    """dials [(kind, face diameter)] side by side, centred on [centre] at height y; -> [(kind, a)]"""
    fps = [d * (1 + L.RIM) for _, d in items]
    span = sum(fps) + gap * (len(items) - 1)
    a = centre - span / 2
    out = []
    for (kind, d), fp in zip(items, fps):
        dial(S, p, a + fp / 2, y, kind, d, lift=lift)
        out.append((kind, a + fp / 2))
        a += fp + gap
    return out
