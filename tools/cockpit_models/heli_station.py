"""One helicopter crew station built by hand (as ka50.py, made a parametric piece): a three-facet panel with its
instruments, the coaming forward to the windscreen, a HUD or a sight head, side consoles, the seat, the cyclic, the
collective and the pedals. Every position and size comes from the helicopter's own file (measured off its model with
sections.py and the cuts); nothing here measures anything.

A station is a dict, distances in metres from that crewman's eye (x to his left, y up, z forward):
  seat (index), E (eye, vehicle-local), S (the model's scale round the cockpit: seat, controls and pedals grow by it),
  PANEL_Z, LEAN (deg), HINGE, WING (deg), WALL_X, TOP_C, TOP_W, BOT_C, BOT_W, FRONT_Z, FRONT_Y_C, FRONT_Y_W, COAMING_X,
  CONSOLE_Y, CONSOLE_IN, CONSOLE_OUT, CONSOLE_FRONT, CONSOLE_FOOT, CONSOLE_BACK, CUSHION_Y, SEAT_Z, BACK_TOP (the head
  pad's top), seat_kind ('ejection' or 'armoured'), seat_label, STICK_Z, STICK_FOOT, COLLECTIVE (True/False),
  PEDAL_Z, PEDAL_Y, PEDAL_HANG (pedals hung from the panel instead of standing on the floor), layout (list as in
  ka50.py), labels (placards painted on the centre facet: [(a, y, text)]), consoles ((left rows, right rows)),
  hud (None, or dict(z, w, h, lean, body_top)), sight (None, or dict(z, y, w, h, d): a sight head with its eyepiece
  hood toward the crewman), colours (panel, panel_d, wall).
"""
import math
import numpy as np
from PIL import Image

import cockpit_lib as L
import handbuilt as H

X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
BLACK = (24, 25, 26)
DGREY = (44, 46, 48)
MGREY = (88, 92, 94)
METAL = (128, 132, 134)
RED = (196, 36, 30)
FRAME = (62, 66, 62)
CUSHION = (82, 84, 70)


class Station:
    def __init__(self, st):
        self.st = st
        g = st.get
        self.E = np.array(st['E'], float)
        self.lean = math.radians(g('LEAN', 15))
        self.wing = math.radians(g('WING', 30))
        self.wing_len = (st['WALL_X'] - st['HINGE']) / math.cos(self.wing)
        self.Rc = np.array([-1.0, 0, 0])
        self.Uc = np.array([0, math.cos(self.lean), math.sin(self.lean)])
        self.Nc = np.array([0, math.sin(self.lean), -math.cos(self.lean)])
        o = self.E + np.array([0, (st['TOP_C'] + st['BOT_C']) / 2, st['PANEL_Z']])
        self.fac = {0: H.Facet(o, self.Rc, self.Uc, self.Nc)}
        for side in (1, -1):
            R = self.Rc * side * math.cos(self.wing) + self.Nc * math.sin(self.wing)
            N = self.Nc * math.cos(self.wing) - self.Rc * side * math.sin(self.wing)
            self.fac[side] = H.Facet(o + self.Rc * side * st['HINGE'], R, self.Uc, N)
        self.ext = {0: (-st['HINGE'], st['HINGE']), 1: (0.0, self.wing_len), -1: (0.0, self.wing_len)}

    def top(self, side, a):
        st = self.st
        return st['TOP_C'] if side == 0 else st['TOP_C'] + (st['TOP_W'] - st['TOP_C']) * a / self.wing_len

    def bot(self, side, a):
        st = self.st
        return st['BOT_C'] if side == 0 else st['BOT_C'] + (st['BOT_W'] - st['BOT_C']) * a / self.wing_len

    def at(self, side, a, y):
        return self.fac[side].at(a, self.E[1] + y)

    def P(self, x, y, z):
        return self.E + np.array([x, y, z], float)


# ---------------------------------------------------------------- painting

def panel_paint(S, side, W_, H_, a0, a1, yb, yt):
    st = S.st
    col, col_d = st['colours']['panel'], st['colours']['panel_d']

    def fn(d, im):
        SS = H.A.Atlas.SS
        H.noisy(d, im, col, 5, seed=3 + side + 7 * st['seat'])

        def to_px(a, y):
            s = (a - a0) / (a1 - a0)
            if side == -1:
                s = 1 - s
            return s * W_ * SS, (1 - (y - yb) / (yt - yb)) * H_ * SS
        for k in (0.2, 0.5, 0.8):
            y = yt + (yb - yt) * k
            x0, yy = to_px(a0, y); x1, _ = to_px(a1, y)
            d.line([x0, yy, x1, yy], fill=tuple(col_d) + (255,), width=SS * 2)
        for a, y in ((a0 + 0.012, yb + 0.012), (a1 - 0.012, yb + 0.012), (a0 + 0.012, yt - 0.02), (a1 - 0.012, yt - 0.02)):
            x, yy = to_px(a, y); r = SS * 2.2
            d.ellipse([x - r, yy - r, x + r, yy + r], fill=MGREY + (255,), outline=(30, 30, 30, 255))
        px_m = W_ * SS / (a1 - a0)
        for name, f, a, y, spec, _ in st['layout']:
            if f != side:
                continue
            x, yy = to_px(a, y)
            if spec[0] in ('game', 'paint_disc'):
                dia = spec[-1]
                r = (dia / 2 * (1 + H.RIM) + 0.004) * px_m
                d.ellipse([x - r, yy - r, x + r, yy + r], fill=(20, 24, 26, 255))
                if dia > 0.05:
                    pw, ph = dia * 0.55 * px_m, 0.008 * px_m
                    py = yy + r + ph * 0.9
                    d.rectangle([x - pw / 2, py - ph / 2, x + pw / 2, py + ph / 2], fill=(18, 18, 18, 255))
            else:
                w = spec[2] if spec[0] == 'display' else spec[1]
                h = spec[2]
                sx, sy = (w / 2 + 0.008) * px_m, (h / 2 + 0.008) * px_m
                d.rectangle([x - sx, yy - sy, x + sx, yy + sy], fill=(20, 24, 26, 255))
        if side == 0:
            for a, y, lab in st.get('labels', ()):
                x, yy = to_px(a, y)
                d.rectangle([x - 50, yy - 12, x + 50, yy + 12], fill=(18, 18, 18, 255))
                H.text(d, (x, yy), lab, 16)
    return fn


def p_disc_red(d, im):
    S_ = im.width
    d.ellipse([0, 0, S_ - 1, S_ - 1], fill=(120, 18, 14, 255))
    d.ellipse([S_ * .18, S_ * .14, S_ * .55, S_ * .4], fill=(220, 90, 80, 255))


def p_buttons(title, labels, lit=0):
    """a push-button / lamp strip: labelled lamps in a row under a title"""
    def fn(d, im):
        S_, Hh = im.width, im.height
        d.rectangle([0, 0, S_, Hh], fill=(20, 22, 22, 255))
        w = S_ / max(1, len(labels))
        for k, lab in enumerate(labels):
            H.lamp(d, [k * w + 5, Hh * .14, (k + 1) * w - 5, Hh * .62], (210, 210, 200), lab, lit=(k == lit), px=Hh * .16)
        H.text(d, (S_ / 2, Hh * .82), title, Hh * .14)
    return fn


def p_gear(d, im):
    S_, Hh = im.width, im.height
    d.rectangle([0, 0, S_, Hh], fill=(20, 22, 22, 255))
    for k in range(3):
        y = Hh * (0.08 + 0.12 * k)
        H.lamp(d, [S_ * .2, y, S_ * .8, y + Hh * .09], (60, 150, 70), None, lit=True)
    H.text(d, (S_ / 2, Hh * .52), 'ШАССИ', S_ * .16)
    d.rectangle([S_ * .4, Hh * .6, S_ * .6, Hh * .95], fill=(140, 140, 140, 255))


def p_hud(d, im):
    S_, Hh = im.width, im.height
    d.rectangle([0, 0, S_, Hh], fill=(30, 32, 34, 255))
    for k in range(4):
        x = S_ * (0.14 + k * 0.24)
        d.rectangle([x - S_ * .09, Hh * .25, x + S_ * .09, Hh * .6], fill=(14, 14, 15, 255), outline=(100, 100, 100, 255),
                    width=2)


def p_eyepiece(d, im):
    """a sight head's face toward the crewman: the rubber eyepiece cups, the reticle brightness and magnification
    knobs"""
    S_, Hh = im.width, im.height
    d.rectangle([0, 0, S_, Hh], fill=(34, 36, 38, 255))
    for cx in (S_ * .32, S_ * .68):
        r = Hh * .28
        d.ellipse([cx - r, Hh * .45 - r, cx + r, Hh * .45 + r], fill=(10, 10, 11, 255), outline=(70, 70, 70, 255), width=3)
        d.ellipse([cx - r * .45, Hh * .45 - r * .45, cx + r * .45, Hh * .45 + r * .45], fill=(30, 50, 62, 255))
    for cx in (S_ * .08, S_ * .92):
        d.ellipse([cx - 9, Hh * .75 - 9, cx + 9, Hh * .75 + 9], fill=(14, 14, 15, 255), outline=(130, 130, 130, 255), width=2)


def p_console(rows, mirror, colour):
    """a console top from above (tile top = forward): black sub-panels with titles, each its own kind of controls"""
    def paint(d, im):
        S_, Hh = im.width, im.height
        d.rectangle([0, 0, S_, Hh], fill=tuple(colour) + (255,))
        y = Hh * 0.01
        for share, title, kind in rows:
            y1 = y + share * Hh
            d.rectangle([S_ * .07, y + 3, S_ * .93, y1 - 3], fill=(22, 24, 24, 255), outline=(70, 80, 84, 255), width=2)
            H.text(d, (S_ * .5, y + Hh * .014), title, min(S_ * .08, Hh * .012))
            cy, top = (y + y1) / 2 + Hh * .006, y + Hh * .026
            if kind == 'guarded':
                for j in range(3):
                    x = S_ * (0.24 + 0.26 * j)
                    d.rectangle([x - S_ * .07, cy - Hh * .016, x + S_ * .07, cy + Hh * .016], fill=(170, 30, 26, 255),
                                outline=(60, 10, 8, 255), width=2)
            elif kind == 'rotary':
                for j in range(2):
                    x, r = S_ * (0.3 + 0.4 * j), S_ * .1
                    d.ellipse([x - r, cy - r, x + r, cy + r], fill=(10, 10, 11, 255), outline=(110, 110, 110, 255), width=2)
                    d.line([x, cy, x - r * .8 * math.sin(.5 * (j + 1)), cy - r * .8 * math.cos(.5 * (j + 1))],
                           fill=(235, 235, 225, 255), width=3)
            elif kind == 'freq':
                d.rectangle([S_ * .18, top, S_ * .82, top + Hh * .02], fill=(8, 8, 8, 255), outline=(90, 90, 90, 255))
                H.text(d, (S_ * .5, top + Hh * .01), '124.00', Hh * .013, fill=(230, 200, 120))
                for x in (.3, .7):
                    d.ellipse([S_ * x - S_ * .08, cy + Hh * .014 - S_ * .08, S_ * x + S_ * .08, cy + Hh * .014 + S_ * .08],
                              fill=(14, 14, 15, 255), outline=(120, 120, 120, 255), width=2)
            elif kind == 'keys':
                d.rectangle([S_ * .14, top, S_ * .86, top + Hh * .022], fill=(8, 8, 8, 255), outline=(90, 90, 90, 255))
                for r_ in range(3):
                    for j in range(4):
                        x0, yy = S_ * (0.16 + 0.18 * j), top + Hh * (0.034 + 0.03 * r_)
                        d.rectangle([x0, yy, x0 + S_ * .13, yy + Hh * .022], fill=(60, 62, 60, 255), outline=(14, 14, 14, 255))
            elif kind == 'toggles':
                nrow = max(1, int((y1 - top) / (Hh * .03)))
                for r_ in range(nrow):
                    yy = top + (y1 - top) * (r_ + .5) / nrow
                    for j in range(4):
                        x = S_ * (0.2 + 0.2 * j)
                        d.ellipse([x - 5, yy - 5, x + 5, yy + 5], fill=(120, 122, 118, 255))
                        d.rectangle([x - 2, yy - Hh * .011, x + 2, yy], fill=(205, 205, 200, 255))
            elif kind == 'breakers':
                for r_ in range(3):
                    yy = top + (y1 - top) * (r_ + .5) / 3
                    for j in range(5):
                        x = S_ * (0.17 + 0.165 * j)
                        d.ellipse([x - 6, yy - 6, x + 6, yy + 6], fill=(6, 6, 6, 255), outline=(150, 150, 140, 255), width=2)
            elif kind == 'lamps':
                for r_ in range(2):
                    for j in range(3):
                        x0 = S_ * (0.14 + 0.25 * j); yy = top + (y1 - top) * (r_ * .45 + .1)
                        H.lamp(d, [x0, yy, x0 + S_ * .2, yy + (y1 - top) * .33], (210, 190, 120) if (j + r_) % 2 else (90, 170, 90), None)
            y = y1
        if mirror:
            im.paste(im.transpose(Image.FLIP_LEFT_RIGHT))
    return paint


def p_cushion(is_back, colour=CUSHION):
    def fn(d, im):
        S_, Hh = im.width, im.height
        d.rectangle([0, 0, S_, Hh], fill=tuple(colour) + (255,))
        dark = tuple(c - 18 for c in colour) + (255,)
        for k in range(1, 3):
            d.line([S_ * k / 3, 0, S_ * k / 3, Hh], fill=dark, width=3)
        for k in range(1, 5 if is_back else 3):
            y = Hh * k / (5 if is_back else 3)
            d.line([0, y, S_, y], fill=tuple(c - 9 for c in colour) + (255,), width=2)
        d.rectangle([2, 2, S_ - 3, Hh - 3], outline=dark, width=3)
        if is_back:
            for x in (S_ * .3, S_ * .7):
                d.rectangle([x - S_ * .05, 0, x + S_ * .05, Hh * .7], fill=(92, 96, 80, 255))
            d.rectangle([S_ * .42, Hh * .6, S_ * .58, Hh * .72], fill=(150, 150, 146, 255))
    return fn


def p_plate(label, colour=(92, 96, 88)):
    def fn(d, im):
        S_, Hh = im.width, im.height
        H.noisy(d, im, colour, 5, seed=60)
        d.rectangle([S_ * .1, Hh * .1, S_ * .9, Hh * .9], outline=(60, 62, 58, 255), width=6)
        H.text(d, (S_ * .5, Hh * .5), label, Hh * .17, fill=(40, 40, 40), bold=True)
    return fn


def p_stripes(d, im):
    S_, Hh = im.width, im.height
    d.rectangle([0, 0, S_, Hh], fill=(220, 180, 28, 255))
    for k in range(-Hh, S_, 18):
        d.polygon([(k, Hh), (k + 9, Hh), (k + 9 + Hh, 0), (k + Hh, 0)], fill=(18, 18, 18, 255))


# ---------------------------------------------------------------- build

def build(m, atlas, st, ppm=400, painters=None):
    """the station into mesh m; returns (gauges, displays, hud)"""
    S = Station(st)
    g = st.get
    seat = st['seat']
    sc = g('S', 1.0)
    cols = st['colours']
    sw = {k: atlas.swatch(c) for k, c in dict(panel=cols['panel'], panel_d=cols['panel_d'], wall=cols.get('wall', cols['panel_d']),
                                                black=BLACK, dgrey=DGREY, mgrey=MGREY, frame=FRAME, cushion=CUSHION,
                                                red=RED, metal=METAL, bezel=(30, 31, 32)).items()}
    P, E = S.P, S.E
    gauges, displays = [], []
    painters = dict(painters or {})
    painters.setdefault('gear', p_gear)

    # -- the panel: three facets, each a 2 cm slab with its painted face
    for side in (0, -1, 1):
        a0, a1 = S.ext[side]
        yt = max(S.top(side, a0), S.top(side, a1)); yb = min(S.bot(side, a0), S.bot(side, a1))
        W_ = int(round((a1 - a0) * ppm)); H_ = int(round((yt - yb) / math.cos(S.lean) * ppm))
        rect = atlas.paint(W_, H_, panel_paint(S, side, W_, H_, a0, a1, yb, yt))
        su = (lambda s: 1 - s) if side == -1 else (lambda s: s)
        n = 6
        for k in range(n):
            s0, s1 = k / n, (k + 1) / n
            aa0, aa1 = a0 + (a1 - a0) * s0, a0 + (a1 - a0) * s1
            b0, b1 = (S.bot(side, aa0) - yb) / (yt - yb), (S.bot(side, aa1) - yb) / (yt - yb)
            t0, t1 = (S.top(side, aa0) - yb) / (yt - yb), (S.top(side, aa1) - yb) / (yt - yb)
            pts = [S.at(side, aa0, S.bot(side, aa0)), S.at(side, aa1, S.bot(side, aa1)), S.at(side, aa1, S.top(side, aa1)),
                   S.at(side, aa0, S.top(side, aa0))]
            m.face(pts, [m.uv(rect, su(s0), b0), m.uv(rect, su(s1), b1), m.uv(rect, su(s1), t1), m.uv(rect, su(s0), t0)],
                   normal=S.fac[side].N)
        back = -S.fac[side].N * 0.02
        corners = [S.at(side, a0, S.bot(side, a0)), S.at(side, a1, S.bot(side, a1)), S.at(side, a1, S.top(side, a1)),
                   S.at(side, a0, S.top(side, a0))]
        m.flat([c + back for c in corners], sw['panel_d'], normal=-S.fac[side].N)
        mid0 = np.mean(corners, 0)
        for i in range(4):
            p, q = corners[i], corners[(i + 1) % 4]
            mid = (p + q) / 2
            m.flat([p, q, q + back, p + back], sw['panel_d'], toward=mid + (mid - mid0))

    # -- instruments
    lcols, lamps = L.lamps_of('cw', g('lamp_palette', 'soviet'))
    for name, side, a, y, spec, _ in st['layout']:
        f = S.fac[side]
        c = S.at(side, a, y)
        kind = spec[0]
        if kind == 'game':
            gauges.append({'Seat': seat, 'Kind': spec[1], 'Center': H.r5(c + f.N * H.GAUGE_LIFT), 'Normal': H.r5(f.N),
                           'Up': H.r5(f.U), 'Diameter': spec[2], 'Depth': 0.03})
        elif kind == 'display':
            w = spec[2]
            displays.append({'Seat': seat, 'Center': H.r5(c + f.N * H.DISPLAY_LIFT), 'Normal': H.r5(f.N), 'Up': H.r5(f.U),
                             'Width': w, 'Depth': 0.035, 'Kind': spec[1], 'Style': spec[3] if len(spec) > 3 else 'CRT'})
            m.frame_rect(c, f.N, f.U, w, w, 0.018, 0.010, sw['black'])
            if g('hoods', True):
                H.hood(m, f, a, E[1] + y, w + 0.036, w + 0.02, 0.06, sw['black'])
        elif kind == 'annunciator':
            w, h = spec[1], spec[2]
            gauges.append({'Seat': seat, 'Kind': 'ANNUNCIATOR', 'Center': H.r5(c + f.N * 0.004), 'Normal': H.r5(f.N),
                           'Up': H.r5(f.U), 'Diameter': w, 'Width': w, 'Height': h, 'Depth': 0.02, 'Columns': lcols,
                           'Lamps': lamps})
            m.frame_rect(c, f.N, f.U, w, h, 0.005, 0.008, sw['bezel'])
        elif kind == 'paint_disc':
            rect = atlas.paint(32, 32, p_disc_red)
            m.disc(c + f.N * 0.008, f.N, f.U, spec[1] / 2, rect, seg=12)
            m.ring(c, f.N, f.U, spec[1] / 2, spec[1] / 2 + 0.004, 0.008, sw['bezel'], seg=12)
        elif kind == 'paint_rect':
            w, h = spec[1], spec[2]
            pw = 200 if w >= h else 60
            ph = max(16, int(round(pw * h / w)))
            rect = atlas.paint(pw, ph, painters[name])
            m.rect_tex(c + f.N * 0.004, f.N, f.U, w, h, rect)
            m.frame_rect(c, f.N, f.U, w, h, 0.004, 0.006, sw['bezel'])
            if name == 'gear':
                m.box(c + f.N * 0.04 - f.U * 0.025, (f.R, f.U, f.N), (0.006, 0.006, 0.035), sw['metal'])
                m.box(c + f.N * 0.075 - f.U * 0.025, (f.R, f.U, f.N), (0.012, 0.012, 0.010), sw['red'])

    # -- coaming: lofted from the panel lip forward to the windscreen base
    lip = []
    for side, aa in ((-1, np.linspace(S.wing_len, 0, 4)), (0, np.linspace(st['HINGE'], -st['HINGE'], 7)),
                     (1, np.linspace(0, S.wing_len, 4))):
        for a in aa:
            p = S.at(side, a, S.top(side, a))
            if lip and np.linalg.norm(lip[-1][0] - p) < 1e-6:
                continue
            lip.append((p, S.fac[side]))
    lip.sort(key=lambda q: -q[0][0])
    reach = g('LIP', 0.045)
    lip_edge = [p + f.N * reach + np.array([0, 0.025, 0]) for p, f in lip]
    cx = st['COAMING_X']
    for le in lip_edge:
        le[0] = E[0] + max(-cx, min(cx, le[0] - E[0]))
    panel_top = [p for p, f in lip]
    rows = []
    for t in (0.0, 0.35, 0.7, 1.0):
        row = []
        for le in lip_edge:
            s = (le[0] - E[0]) / max(abs(lip_edge[0][0] - E[0]), 1e-6)
            xf = E[0] + s * (cx - 0.03)
            yf = E[1] + st['FRONT_Y_C'] + (st['FRONT_Y_W'] - st['FRONT_Y_C']) * s * s
            z = le[2] + (E[2] + st['FRONT_Z'] - le[2]) * t
            x = le[0] + (xf - le[0]) * t
            y = le[1] + (yf - le[1]) * t + 0.02 * math.sin(math.pi * t) * (1 - s * s)
            row.append(np.array([x, y, z]))
        rows.append(row)
    for i in range(len(rows) - 1):
        for j in range(len(rows[i]) - 1):
            m.flat([rows[i][j], rows[i][j + 1], rows[i + 1][j + 1], rows[i + 1][j]], sw['black'], normal=[0, 1, 0])
    for j in range(len(lip_edge) - 1):
        a, b = lip_edge[j], lip_edge[j + 1]
        da, db = a - [0, 0.012, 0], b - [0, 0.012, 0]
        m.flat([a, b, db, da], sw['black'], toward=E)
        m.flat([da, db, panel_top[j + 1], panel_top[j]], sw['black'], normal=[0, -1, 0])
    for j in (0, len(lip_edge) - 1):
        col = [rows[i][j] for i in range(len(rows))]
        for i in range(len(col) - 1):
            p, q = col[i], col[i + 1]
            m.flat([p, q, q - [0, 0.03, 0], p - [0, 0.03, 0]], sw['black'], toward=[E[0], p[1], (p[2] + q[2]) / 2])

    # -- a HUD: the projector body low on the coaming, two posts up to the combiner, its glass centred on the
    # crewman's straight-ahead line (the picture's boresight)
    hud = None
    if g('hud'):
        hd = st['hud']
        g_h, g_hw = hd['h'], hd['w'] / 2
        lean = math.radians(hd.get('lean', 20))
        g_up = np.array([0, math.cos(lean), -math.sin(lean)])
        g_n = H.unit(np.cross(X, g_up))
        g_c = np.array([E[0], E[1], E[2] + hd['z']])
        g_bot = g_c - g_up * g_h / 2
        base_y = E[1] + st['TOP_C'] + 0.06
        body_top = E[1] + hd.get('body_top', -0.235)
        body_c = np.array([E[0], (base_y + body_top) / 2, E[2] + hd['z'] + 0.02])
        strip = atlas.paint(128, 48, p_hud)
        m.box(body_c, (X, Y, Z), (hd.get('body_w', 0.09), (body_top - base_y) / 2, hd.get('body_d', 0.085)), sw['dgrey'],
              front=(2, -1, strip, -X, Y))
        for s in (-1, 1):
            m.box([E[0] + s * (g_hw + 0.012), (body_top + g_bot[1]) / 2, g_bot[2]], (X, Y, Z),
                  (0.007, (g_bot[1] - body_top) / 2, 0.012), sw['dgrey'])
        H.combiner(m, g_bot, g_up, -g_n, 2 * g_hw, g_h, 'rect', 0.0, ('bottom', 'side'), 0.008, sw['black'])
        hud = dict(centre=g_c, normal=-g_n, up=g_up, width=2 * g_hw, height=g_h, seat=seat)
        if hd.get('style'):
            hud['style'] = hd['style']

    # -- an IHADSS-style monocle instead: a small combiner just ahead of the crewman's eye on his straight-ahead line
    # (the picture's boresight), its thin housing and the CRT arm back to his right toward the helmet
    if g('monocle'):
        mo = st['monocle']
        c = P(0, 0, mo['z'])
        w, h = mo['w'], mo['h']
        t = 0.003
        for (cx, cy, hw, hh) in ((0, h / 2 + t / 2, w / 2 + t, t / 2), (0, -h / 2 - t / 2, w / 2 + t, t / 2),
                                 (w / 2 + t / 2, 0, t / 2, h / 2), (-w / 2 - t / 2, 0, t / 2, h / 2)):
            m.box(c + X * cx + Y * cy, (X, Y, Z), (hw, hh, 0.002), sw['black'])
        arm0 = c + X * (-w / 2 - t) + Y * (h / 2)
        arm1 = P(-mo.get('arm_x', 0.07), mo.get('arm_y', 0.03), mo.get('arm_z', -0.02))
        d_ = H.unit(arm1 - arm0); up_ = H.unit(np.cross(d_, X)) if abs(d_ @ X) < 0.95 else Y
        m.box((arm0 + arm1) / 2, (np.cross(up_, d_), up_, d_), (0.005, 0.005, np.linalg.norm(arm1 - arm0) / 2), sw['black'])
        g_bot = c - Y * h / 2
        H.COMBINER.extend([g_bot + X * w / 2 - Z * 0.0, c + X * w / 2 + Y * h / 2, c - X * w / 2 + Y * h / 2,
                           g_bot + X * w / 2, c - X * w / 2 + Y * h / 2, g_bot - X * w / 2])
        hud = dict(centre=c, normal=np.array([0, 0, -1.0]), up=Y.copy(), width=w, height=h, seat=seat)

    # -- a sight head on the coaming: its body, the eyepiece face and the rubber hood round it toward the crewman
    if g('sight'):
        sg = st['sight']
        face = atlas.paint(96, 48, p_eyepiece)
        c = P(0, sg['y'], sg['z'])
        m.box(c, (X, Y, Z), (sg['w'] / 2, sg['h'] / 2, sg['d'] / 2), sw['dgrey'], front=(2, -1, face, -X, Y))
        hood_z = sg['z'] - sg['d'] / 2 - 0.03
        for s in (-1, 1):
            m.box(P(s * (sg['w'] / 2 - 0.006), sg['y'], hood_z), (X, Y, Z), (0.006, sg['h'] / 2, 0.03), sw['black'])
        m.box(P(0, sg['y'] + sg['h'] / 2 - 0.006, hood_z), (X, Y, Z), (sg['w'] / 2, 0.006, 0.03), sw['black'])
        stem_z = sg['z'] + sg['d'] / 2 - 0.03                           # its column down to the panel top, behind it
        m.box(P(0, (sg['y'] - sg['h'] / 2 + st['TOP_C'] + 0.02) / 2, stem_z), (X, Y, Z),
              (sg['w'] * 0.25, max(0.005, (sg['y'] - sg['h'] / 2 - st['TOP_C'] - 0.02) / 2), 0.03), sw['dgrey'])
        for k, (lx, ly) in enumerate(((-1, 1), (1, 1))):                 # the two grip handles either side
            m.box(P(lx * (sg['w'] / 2 + 0.04), sg['y'] - 0.02, sg['z'] - sg['d'] / 2 + 0.02), (X, Y, Z),
                  (0.02, 0.05, 0.02), sw['black'])

    # -- side consoles, their inner, back and front faces down to the console foot
    left_rows, right_rows = st['consoles']
    for side in (1, -1):                                            # +1 = left (x > 0)
        wing = S.fac[-1] if side > 0 else S.fac[1]
        a_in = (st['CONSOLE_IN'] - st['HINGE']) / math.cos(S.wing)
        zw = wing.at(min(max(a_in, 0.0), S.wing_len), E[1] + st['BOT_W'])[2] - 0.02
        zf = min(zw, E[2] + st['CONSOLE_FRONT'])
        xi, xo = E[0] + st['CONSOLE_IN'] * side, E[0] + st['CONSOLE_OUT'] * side
        cy, fy = E[1] + st['CONSOLE_Y'], E[1] + st['CONSOLE_FOOT']
        zb = E[2] + st['CONSOLE_BACK']
        tile = atlas.paint(60, 300, p_console(left_rows if side > 0 else right_rows, side > 0, cols['panel']))
        top = [[xi, cy, zb], [xo, cy, zb], [xo, cy, zf], [xi, cy, zf]]
        m.face(top, [m.uv(tile, 0, 0), m.uv(tile, 1, 0), m.uv(tile, 1, 1), m.uv(tile, 0, 1)], normal=[0, 1, 0])
        m.flat([[xi, fy, zb], [xi, fy, zf], [xi, cy, zf], [xi, cy, zb]], sw['wall'], normal=[-side, 0, 0])
        m.flat([[xi, fy, zb], [xo, fy, zb], [xo, cy, zb], [xi, cy, zb]], sw['wall'], normal=[0, 0, -1])
        m.flat([[xi, fy, zf], [xo, fy, zf], [xo, cy, zf], [xi, cy, zf]], sw['wall'], normal=[0, 0, 1])
    bl, br = S.fac[0].at(st['HINGE'], E[1] + st['BOT_C']), S.fac[0].at(-st['HINGE'], E[1] + st['BOT_C'])
    m.flat([bl, br, br + np.array([0, 0, 0.12]), bl + np.array([0, 0, 0.12])], sw['panel_d'], normal=[0, -1, 0])
    for side in (1, -1):
        w_ = S.fac[side]
        a, b = w_.at(0, E[1] + st['BOT_W']), w_.at(S.wing_len, E[1] + st['BOT_W'])
        m.flat([a, b, b + np.array([0, 0, 0.10]), a + np.array([0, 0, 0.10])], sw['panel_d'], normal=[0, -1, 0])

    # -- pedals
    pz, py = st['PEDAL_Z'], st['PEDAL_Y']
    for s in (1, -1):
        m.box(P(s * 0.13 * sc, py, pz), (X, H.unit([0, 1, -0.35]), H.unit([0, 0.35, 1])), (0.05 * sc, 0.08 * sc, 0.012),
              sw['mgrey'])
        if g('PEDAL_HANG', True):
            m.box(P(s * 0.13 * sc, py + 0.19 * sc, pz + 0.06 * sc), (X, H.unit([0, 1, 0.45]), H.unit([0, -0.45, 1])),
                  (0.012, 0.13 * sc, 0.014), sw['dgrey'])
        else:
            fl = st['FLOOR_Y']
            m.box(P(s * 0.13 * sc, (py + fl) / 2, pz + 0.04), (X, Y, Z), (0.014, max(0.01, (py - fl) / 2), 0.04), sw['dgrey'])
    if g('PEDAL_HANG', True):
        m.box(P(0, py + 0.29 * sc, pz + 0.12 * sc), (X, Y, Z), (0.20 * sc, 0.02, 0.025), sw['dgrey'])

    # -- the seat
    cu, sz = st['CUSHION_Y'], st['SEAT_Z']
    pan = atlas.paint(64, 64, p_cushion(False))
    back = atlas.paint(64, 96, p_cushion(True))
    plate = atlas.paint(64, 48, p_plate(st.get('seat_label', '')))
    lean = math.radians(g('SEAT_LEAN', 12))
    ax_b = (X, np.array([0, math.cos(lean), -math.sin(lean)]), np.array([0, math.sin(lean), math.cos(lean)]))
    base_bot = st.get('SEAT_FOOT', cu - 0.40 * sc)
    m.box(P(0, (cu - 0.035 * sc + base_bot) / 2, sz), (X, Y, Z), (0.21 * sc, max(0.02, (cu - 0.035 * sc - base_bot) / 2),
                                                                   0.23 * sc), sw['frame'])
    m.box(P(0, cu - 0.017 * sc, sz + 0.01), (X, Y, Z), (0.20 * sc, 0.018 * sc, 0.22 * sc), sw['cushion'], front=(1, 1, pan, -X, Z))
    for s in (1, -1):
        m.box(P(s * 0.222 * sc, cu + 0.04 * sc, sz - 0.02), (X, Y, Z), (0.012, 0.09 * sc, 0.23 * sc), sw['frame'])
    top_y = st['BACK_TOP']
    back_len = top_y - cu
    bc = P(0, cu + back_len / 2, sz - 0.22 * sc) + ax_b[2] * 0 - Z * math.tan(lean) * back_len / 2
    m.box(bc, ax_b, (0.20 * sc, back_len / 2 / math.cos(lean), 0.03), sw['cushion'], front=(2, 1, back, X, ax_b[1]))
    m.box(bc - ax_b[2] * 0.065, ax_b, (0.215 * sc, back_len / 2 / math.cos(lean) + 0.02, 0.035), sw['frame'])
    head = P(0, top_y - 0.10 * sc, sz - 0.22 * sc - math.tan(lean) * (back_len - 0.10 * sc) - 0.02)
    if st.get('seat_kind') == 'ejection':
        m.box(head - Z * 0.04, (X, Y, Z), (0.15 * sc, 0.12 * sc, 0.07), sw['frame'], front=(2, 1, plate, X, Y))
        stripes = atlas.paint(64, 12, p_stripes)
        m.box(bc - ax_b[2] * 0.14 + ax_b[1] * 0.05, ax_b, (0.12 * sc, back_len * 0.32, 0.045), sw['dgrey'])
        m.box(bc - ax_b[2] * 0.14 + ax_b[1] * (back_len * 0.37 + 0.05), ax_b, (0.12 * sc, 0.006, 0.045), sw['black'],
              front=(1, 1, stripes, X, -ax_b[2]))
    else:                                                           # an armoured crew seat: side plates and head pad
        m.box(head - Z * 0.02, (X, Y, Z), (0.12 * sc, 0.09 * sc, 0.035), sw['black'], front=(2, 1, plate, X, Y))
        for s in (1, -1):
            m.box(bc + X * s * 0.235 * sc - ax_b[1] * 0.05, ax_b, (0.008, back_len / 2 * 0.8, 0.07 * sc), sw['frame'])
    m.box(P(0, cu + 0.05 * sc, sz + 0.10 * sc), (X, Y, Z), (0.18 * sc, 0.006, 0.02), sw['mgrey'])
    m.box(P(0, cu + 0.057 * sc, sz + 0.10 * sc), (X, Y, Z), (0.03, 0.008, 0.026), sw['metal'])
    for s in (1, -1):
        m.box(bc + X * s * 0.09 * sc + ax_b[2] * 0.035, ax_b, (0.025, back_len / 2 * 0.7, 0.006), sw['mgrey'])

    # -- the cyclic
    if g('STICK_Z') is not None:
        kz, kf = st['STICK_Z'], st['STICK_FOOT']
        grip_y = cu + 0.30 * sc
        m.box(P(0, kf + 0.04, kz), (X, Y, Z), (0.06 * sc, 0.04, 0.06 * sc), sw['black'])
        col_ax = (X, H.unit([0, 1, -0.10]), H.unit([0, 0.10, 1]))
        ln = grip_y - 0.06 * sc - (kf + 0.08)
        m.box(P(0, kf + 0.08 + ln / 2, kz + 0.01), col_ax, (0.014, ln / 2, 0.014), sw['metal'])
        g_ax = (X, H.unit([0, 1, 0.2]), H.unit([0, -0.2, 1]))
        gc = P(0, grip_y, kz - 0.01)
        m.box(gc, g_ax, (0.024, 0.07, 0.03), sw['black'])
        m.box(gc + g_ax[1] * 0.074, g_ax, (0.02, 0.006, 0.025), sw['dgrey'])
        m.box(gc + g_ax[1] * 0.078 + g_ax[2] * 0.012, g_ax, (0.006, 0.004, 0.006), sw['red'])
        m.box(gc + g_ax[2] * 0.034 + g_ax[1] * 0.015, g_ax, (0.008, 0.014, 0.005), sw['metal'])

    # -- the collective on the left
    if g('COLLECTIVE', True):
        cx_ = 0.30 * sc
        y0 = max(cu - 0.12 * sc, st['FLOOR_Y'] + 0.07) if 'FLOOR_Y' in st else cu - 0.12 * sc   # (its pivot over the floor)
        p0, p1 = P(cx_, y0, sz - 0.10 * sc), P(cx_, cu + 0.12 * sc, sz + 0.32 * sc)
        d_ = H.unit(p1 - p0); up_ = H.unit(np.cross(d_, X))
        m.box(p0 - d_ * 0.02, (X, Y, Z), (0.03, 0.04, 0.05), sw['dgrey'])
        m.box((p0 + p1) / 2, (X, up_, d_), (0.014, 0.014, np.linalg.norm(p1 - p0) / 2), sw['metal'])
        m.box(p1 + d_ * 0.04, (X, up_, d_), (0.022, 0.022, 0.05), sw['black'])
        m.box(p1 + d_ * 0.10 + up_ * 0.01, (X, up_, d_), (0.03, 0.03, 0.025), sw['dgrey'])
        m.box(p1 + d_ * 0.10 + up_ * 0.043, (X, up_, d_), (0.006, 0.004, 0.006), sw['red'])
    return gauges, displays, hud


def remove_old(ac, boxes, glass_gap=0.04, min_index=0):
    """interior-bone faces (not the new cockpit) whose centre lies in any (lo, hi, inner_x, sill_y) box, eye-relative
    to E: inside |x| < inner_x, or anywhere across below sill_y (the window frames out at the sides stay), and not
    along the glass; boxes = [(E, lo, hi, inner_x, sill_y)]. min_index: only faces from that index of the bone's
    original list on (a model whose shell and frames come first and its interior parts after them)"""
    n = 0
    for b in ac.bones:
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys') or not L.is_interior_bone(b['name']) or b['name'].endswith('__cockpit'):
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        out = []
        for i, poly in enumerate(pm['polys']):
            if poly and i >= min_index:
                V = P[[v[0] for v in poly]]
                c = V.mean(0)
                hit = False
                for E, lo, hi, inner_x, sill_y in boxes:
                    d = c - np.array(E, float)
                    if np.all(d >= lo) and np.all(d <= hi) and (abs(d[0]) < inner_x or d[1] < sill_y):
                        hit = True
                        break
                if hit and not ac.hugs_glass(V, glass_gap):
                    n += 1
                    continue
            out.append(poly)
        pm['polys'] = out
    return n


def repaint_floor(ac, atlas, colour, boxes):
    """the model's own level floor faces inside (lo, hi) boxes (vehicle-local) given [colour] (they close the
    fuselage, so they stay)"""
    deck = atlas.swatch(colour)
    n = 0
    for b in ac.bones:
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys') or not L.is_interior_bone(b['name']) or b['name'].endswith('__cockpit'):
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        N = np.array(pm['normals'], float)
        for poly in pm['polys']:
            if not poly:
                continue
            c = P[[v[0] for v in poly]].mean(0)
            if abs(N[poly[0][1]][1]) > 0.7 and any(np.all(c >= lo) and np.all(c <= hi) for lo, hi in boxes):
                pm['uvs'].append(atlas.flat(deck))
                k = len(pm['uvs']) - 1
                for q in poly:
                    q[2] = k
                n += 1
    return n


def install(ac, mesh, gauges, displays, hud, eyes):
    """the cockpit bone into the hull, every seat's instruments (replacing whatever the asset held for any seat), the
    HUD for its seat, then the windows' glass (model_glass.glaze) and the HUD's combiner glass; returns (poly mesh,
    glaze summary)"""
    import model_glass as MG
    seat = hud['seat'] if hud else 0
    pm = H.install(ac, mesh, None, 'hull', None, gauges, displays, hud, eyes[seat], seat=seat)
    a = ac.asset
    a['CockpitGauges'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Gauges': gauges}
    if displays:
        a['FlightDisplays'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Displays': displays}
    else:
        a.pop('FlightDisplays', None)
    line, _ = MG.glaze(ac)
    comb = a.pop('CombinerGlass', None) or []
    if comb:
        L.add_glass(a, comb)
    return pm, line