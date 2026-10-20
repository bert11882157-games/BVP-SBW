#!/usr/bin/env python3
"""Formed cockpits: a cockpit interior shaped to the space inside the original model.

The space is measured, not assumed. Rays from the pilot's centre line find the nearest surface of what is left of
the model after the old interior is cut (rebuild.py --formed): sideways to the cockpit walls, upward to the canopy
glass and frames, forward to the windscreen and the nose. From those:

  * the main panel is the cockpit's own cross-section at the panel station (between the walls, under the
    glareshield line, down to the knees), leaning back 12 degrees, its face painted with a recess and a placard
    under every instrument;
  * the instruments are packed into that outline in rows (the era's set, basic T in the middle), each row at the
    largest size the outline allows there;
  * the glareshield runs from the panel's top edge to the windscreen base under the glass;
  * the side consoles, the walls and the floor follow the measured walls, so no gap opens onto the fuselage's inside;
  * the seat, stick and throttle are sized to the room left between the walls.

Everything is in metres from the eye (r right, u up, f forward) mapped through authored.lib.Eye, scaled by the tub.
"""
import math
import numpy as np

from authored import lib as A
from authored.lib import Plane, Painter, text, noisy, screws, unit
import cockpit_lib as L

LEAN = 12.0
LABELS = {'ATTITUDE': ('ATT', 'АГ'), 'HEADING': ('HDG', 'КУРС'), 'ALTIMETER': ('ALT', 'ВЫСОТА'),
          'AIRSPEED': ('IAS', 'СКОРОСТЬ'), 'VVI': ('VVI', 'ВАР'), 'MACH': ('MACH', 'М'), 'AOA': ('AOA', 'УГОЛ'),
          'G_METER': ('G', 'ПЕРЕГР'), 'RWR': ('RWR', 'СПО'), 'FUEL_QTY': ('FUEL', 'ТОПЛИВО'),
          'RADAR_ALT': ('RAD ALT', 'РВ'), 'CLOCK': ('CLOCK', 'ЧАСЫ'), 'TURN_SLIP': ('TURN', 'УКАЗ ПОВ'),
          'ENGINE_RPM': ('RPM', 'ОБОРОТЫ'), 'EGT': ('EGT', 'Т ГАЗОВ'), 'AMMO': ('AMMO', 'БОЕЗАП'),
          'THROTTLE': ('THR', 'РУД'), 'FILLER_FUEL': ('FUEL FLOW', 'РАСХОД'), 'FILLER_OIL': ('OIL', 'МАСЛО'),
          'FILLER_RPM': ('NOZ', 'СОПЛО'), 'FILLER_CLOCK': ('TIME', 'ВРЕМЯ'), 'FILLER_TEMP': ('TEMP', 'ТЕМП'),
          'FILLER_VOLTS': ('VOLTS', 'ВОЛЬТ'), 'FILLER_HYDRAULIC': ('HYD', 'ГИДРО'), 'FILLER_OXYGEN': ('OXY', 'КИСЛОР')}

# instrument rows per era, top to bottom, most important first; ('D:<page>', width) displays, ('A:<set>', w, h)
# lamp panels, otherwise (kind, diameter). Sizes are real metres before the fit scales them.
ROWS = {
    'WWII_STEAM': [[('A:ww2', .110, .040)],
                   [('AIRSPEED', .080), ('ATTITUDE', .084), ('ALTIMETER', .080)],
                   [('TURN_SLIP', .080), ('HEADING', .084), ('VVI', .080)],
                   [('ENGINE_RPM', .066), ('THROTTLE', .066), ('FILLER_OIL', .057), ('FILLER_TEMP', .057)],
                   [('FUEL_QTY', .057), ('CLOCK', .057), ('FILLER_VOLTS', .050), ('AMMO', .050)]],
    'COLD_WAR_STEAM': [[('A:cw', .200, .040)],
                       [('AIRSPEED', .080), ('ATTITUDE', .086), ('ALTIMETER', .080)],
                       [('MACH', .066), ('HEADING', .086), ('VVI', .066)],
                       [('ENGINE_RPM', .062), ('EGT', .062), ('FUEL_QTY', .062), ('G_METER', .057)],
                       [('AOA', .052), ('TURN_SLIP', .052), ('CLOCK', .050), ('FILLER_OIL', .050),
                        ('FILLER_HYDRAULIC', .050)]],
    'COLD_WAR_RADAR': [[('A:cw', .200, .040)],
                       [('AIRSPEED', .076), ('D:RADAR', .140), ('ALTIMETER', .076)],
                       [('MACH', .062), ('ATTITUDE', .086), ('VVI', .062), ('RWR', .062)],
                       [('AOA', .054), ('HEADING', .080), ('ENGINE_RPM', .060), ('EGT', .060)],
                       [('G_METER', .050), ('FUEL_QTY', .054), ('RADAR_ALT', .050), ('CLOCK', .046)]],
    'GEN4_CRT': [[('A:eyebrow_l', .100, .040), ('A:eyebrow_r', .100, .040)],
                 [('D:RADAR', .150), ('AIRSPEED', .056), ('ALTIMETER', .056), ('D:STORES', .150)],
                 [('RWR', .062), ('ATTITUDE', .066), ('VVI', .050), ('FUEL_QTY', .052)],
                 [('AOA', .046), ('HEADING', .066), ('ENGINE_RPM', .050), ('EGT', .050)],
                 [('G_METER', .044), ('RADAR_ALT', .044), ('CLOCK', .042), ('FILLER_HYDRAULIC', .042)]],
    'MODERN_GLASS': [[('A:eyebrow_l', .100, .040), ('A:eyebrow_r', .100, .040)],
                     [('D:RADAR', .150), ('D:PFD', .150), ('D:STORES', .150)],
                     [('AIRSPEED', .050), ('ATTITUDE', .058), ('ALTIMETER', .050), ('RWR', .056)],
                     [('ENGINE_RPM', .046), ('EGT', .046), ('FUEL_QTY', .046), ('CLOCK', .042)]],
}


# the instruments that fill a row out to the panel's edges, in turn
FILL = ['FILLER_FUEL', 'FILLER_OIL', 'FILLER_HYDRAULIC', 'FILLER_VOLTS', 'FILLER_OXYGEN', 'FILLER_TEMP',
        'FILLER_CLOCK', 'FILLER_RPM']


def ear_clip(pts):
    """triangles (index triples) of a simple 2D polygon, either winding"""
    P = [np.array(p, float) for p in pts]
    idx = list(range(len(P)))
    area = sum(P[i][0] * P[(i + 1) % len(P)][1] - P[(i + 1) % len(P)][0] * P[i][1] for i in range(len(P)))
    sgn = 1.0 if area > 0 else -1.0
    cross = lambda o, a, b: (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
    out, guard = [], 0
    while len(idx) > 3 and guard < 10000:
        guard += 1
        for k in range(len(idx)):
            i0, i1, i2 = idx[k - 1], idx[k], idx[(k + 1) % len(idx)]
            a, b, c = P[i0], P[i1], P[i2]
            if sgn * cross(a, b, c) <= 1e-12:
                continue                            # reflex (or flat) corner: not an ear
            if any(sgn * cross(a, b, P[j]) >= 0 and sgn * cross(b, c, P[j]) >= 0 and sgn * cross(c, a, P[j]) >= 0
                   for j in idx if j not in (i0, i1, i2)):
                continue                            # another corner inside it
            out.append((i0, i1, i2)); idx.pop(k)
            break
        else:
            break                                   # degenerate: give up and fan the rest
    for k in range(1, len(idx) - 1):
        out.append((idx[0], idx[k], idx[k + 1]))
    return out


class Cavity:
    """the nearest surfaces round the pilot: what is left of the model (the new cockpit not counted) and the canopy
    glass, as triangles; hit(p, d) is the distance along d to the first of them"""

    def __init__(self, ac, glass):
        T = []
        for b in ac.bones:
            pm = b.get('poly_mesh')
            if not pm or not pm.get('polys') or b['name'].endswith('__cockpit') or not L.is_interior_bone(b['name']):
                continue
            P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
            for poly in pm['polys']:
                V = P[[v[0] for v in poly]]
                for k in range(1, len(V) - 1):
                    T.append(V[[0, k, k + 1]])
        self.T = np.concatenate([np.array(T).reshape(-1, 3, 3), np.asarray(glass, float).reshape(-1, 3, 3)])

    def hit(self, p, d, near=0.01, far=6.0):
        T = self.T
        e1, e2 = T[:, 1] - T[:, 0], T[:, 2] - T[:, 0]
        h = np.cross(d, e2); a = (e1 * h).sum(1)
        ok = np.abs(a) > 1e-12
        f = np.where(ok, 1.0 / np.where(ok, a, 1.0), 0.0)
        sv = p - T[:, 0]; u = f * (sv * h).sum(1)
        q = np.cross(sv, e1); v = f * (q @ d); t = f * (q * e2).sum(1)
        m = ok & (u >= 0) & (u <= 1) & (v >= 0) & (u + v <= 1) & (t > near)
        return float(t[m].min()) if m.any() else far


def build(ac, tub, sw, atlas, era, pal_name, hud=None, side_stick=False):
    """-> (cockpit Mesh, stick Mesh, gauges, displays, glass triangles, HeadUpDisplay dict or None, stick foot)"""
    cyr = pal_name.startswith('soviet') or pal_name == 'ussr_ww2'
    pal = L.PALETTES[pal_name]
    e = A.Eye(tub, tub.s)
    k, E = e.k, e.E
    cav = Cavity(ac, ac.glass)
    want_hud = L.ERAS[era]['hud'] if hud is None else hud
    m, stick = L.Mesh(), L.Mesh()
    gauges, displays, glass = [], [], []
    sid = tub.seat
    col = {n: atlas.flat(atlas.swatch(rgb)) for n, rgb in dict(pal).items()}
    col.update({n: atlas.flat(atlas.swatch(rgb)) for n, rgb in L.ACCENT.items()})
    X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])
    R_, U_, F_ = e.R, e.U, e.F

    # -- the space: walls either side, ceiling (glass and frames) over the centre line, and the windscreen ahead
    skin_w = {}

    def wall(u, f, side):
        """metres from the centre line to the nearest surface on the pilot's right (side +1) or left (-1); where the
        ray finds nothing near (a gear bay, an intake opening) the fuselage's outer skin there, and never more than
        0.50 m"""
        d = cav.hit(e.p(0, u, f), R_ * side, far=9.0) / k
        if d > 0.55:
            p = e.p(0, u, f)
            key = (round(u, 3), round(f, 3))
            if key not in skin_w:
                skin_w[key] = ac.skin_x(p[1], p[2], E[0])
            s = skin_w[key][1 if side > 0 else 0]
            d = min(d, (s if np.isfinite(s) else 0.5 * k) / k - 0.02)
        return float(np.clip(d, 0.12, 0.50))

    def ceiling(r, f, u_from=-0.3):
        """the canopy glass over (r, f), metres from the eye (old structure under it does not count: the panel and
        the glareshield replace it); open sky counts as 0.3 m over the eye"""
        p = e.p(r, u_from, f)
        g = ac.glass_above(p[0], p[2], p[1])
        return 0.3 if g is None else (g - E[1]) / k

    def ahead(u, r=0.0):
        return cav.hit(e.p(r, u, 0.2), F_) / k + 0.2

    # the panel station: 0.62-0.78 m ahead, and at least 0.25 m behind the windscreen base
    ws_f = min(ahead(-0.25), ahead(-0.35))
    f_p = float(np.clip(ws_f - 0.30, 0.60, 0.78))
    lean = math.radians(LEAN)
    up = e.d(0, math.cos(lean), math.sin(lean))
    panel = Plane(e, e.p(0, -0.45, f_p), e.d(1, 0, 0), up)
    # the panel top: under the glareshield line, which stays clear of the glass over the panel
    u_top = min(-0.17, ceiling(0, f_p + 0.06) - 0.10)
    u_bot = -0.64
    if u_top - u_bot < 0.22:
        u_bot = u_top - 0.22
    bb = lambda u: (u + 0.45) / math.cos(lean)
    fu = lambda u: f_p + (u + 0.45) * math.tan(lean)
    # the outline, sampled down the panel: each side 1.5 cm inside the wall (at most 0.42 m out) and in from where
    # the canopy comes down over it; the top edge follows the glass across the width
    def under_glass(r, u):
        return ceiling(r, fu(u), u - 0.25) > u + 0.04
    outline_l, outline_r = [], []
    for u in np.linspace(u_top, u_bot, 9):
        hr = min(0.42, wall(u, fu(u), 1) - 0.015)
        hl = min(0.42, wall(u, fu(u), -1) - 0.015)
        while hr > 0.10 and not under_glass(hr, u):
            hr -= 0.01
        while hl > 0.10 and not under_glass(-hl, u):
            hl -= 0.01
        outline_r.append((hr, bb(u))); outline_l.append((-hl, bb(u)))
    top_edge = []                                   # right to left, under the glass at every point
    for r in np.linspace(outline_r[0][0], outline_l[0][0], 9):
        u = u_top
        while u > u_top - 0.12 and not under_glass(r, u):
            u -= 0.01
        top_edge.append((r, bb(u)))
    pts2 = [outline_r[-1]] + outline_r[-2:0:-1] + top_edge + outline_l[1:]
    a0 = min(p[0] for p in pts2); a1 = max(p[0] for p in pts2)
    b0, b1 = bb(u_bot), bb(u_top)
    b_pack = min(b for r, b in top_edge if abs(r) < 0.16)    # the rows start under the top edge's middle

    def width_at(b):
        """the panel's half widths (left, right) at height b, the top edge's slope included"""
        us = [p[1] for p in outline_r]
        rr = np.interp(b, us[::-1], [p[0] for p in outline_r][::-1])
        ll = np.interp(b, us[::-1], [-p[0] for p in outline_l][::-1])
        for r, bt in top_edge:                      # where the top edge comes down below b, the width stops there
            if bt < b:
                if r > 0:
                    rr = min(rr, max(0.0, r - 0.01) if r < rr else rr)
                else:
                    ll = min(ll, max(0.0, -r - 0.01) if -r < ll else ll)
        return ll, rr

    # -- instruments packed in rows from the top, each row as large as the outline allows
    rows = ROWS[era]
    placed = []
    b = b_pack - 0.012
    gap = 0.010
    for row in rows:
        def fp(it):
            if it[0].startswith('A:'):
                return it[1], it[2]
            if it[0].startswith('D:'):
                return it[1] * 1.24, it[1] * 1.24
            return it[1] * 1.18, it[1] * 1.18
        fps = [fp(it) for it in row]
        h = max(h_ for _, h_ in fps)
        for scale in np.linspace(1.0, 0.70, 13):
            hh = h * scale
            if b - hh < b0 + 0.01:
                continue
            ll, rr = width_at(b - hh / 2)
            ll2, rr2 = width_at(b - hh)
            avail = 2 * min(ll, rr, ll2, rr2) - 0.02
            need = sum(w for w, _ in fps) * scale + gap * (len(row) - 1)
            if need <= avail:
                break
        else:
            continue                                # the row does not fit even shrunk: skip it
        if b - hh < b0 + 0.01:
            break
        x = -need / 2
        yc = b - hh / 2
        for it, (w, _) in zip(row, fps):
            placed.append((it, x + w * scale / 2, yc, scale))
            x += w * scale + gap
        # the rest of the row out to the panel's edges: more instruments, as on a real panel
        if not row[0][0].startswith('A:'):
            d_f = min(0.057, hh / 1.18 * 0.85)
            for sg in (1, -1):
                edge = (rr if sg > 0 else ll)
                edge = min(edge, (rr2 if sg > 0 else ll2)) - 0.012
                xx = need / 2 + gap
                n = 0
                while xx + d_f * 1.18 <= edge:
                    kind = FILL[(len(placed) + n) % len(FILL)]
                    placed.append(((kind, d_f), sg * (xx + d_f * 1.18 / 2), yc, 1.0))
                    xx += d_f * 1.18 + gap
                    n += 1
        b -= hh + gap

    print(f'  formed {ac.vid}: panel {f_p:.2f} m ahead, top {u_top:+.2f} (rows from {b_pack:.3f}), bottom {u_bot:+.2f},'
          f' half widths at top {width_at(b_pack - 0.02)}, mid {width_at((b0 + b1) / 2)}; {len(placed)} items',
          flush=True)
    # the face: painted (dark recess and a placard under every instrument), on a 2.5 cm slab
    big = max(atlas.free, key=lambda r: r[2] * r[3])          # the panel tile fits the largest free area left
    ppm = float(min(900, 0.95 * big[2] / (a1 - a0), 0.95 * big[3] / (b1 - b0)))
    P = Painter(a0, a1, b0, b1, ppm)
    def face(d, im):
        noisy(im, pal['panel'], 3, seed=11)
        screws(d, P.W * A.Atlas.SS, P.H * A.Atlas.SS, P.mm(9), P.mm(2.6))
        for it, a, yb, sc in placed:
            x, y = P.to(a, yb)
            kind = it[0]
            if kind.startswith('A:') or kind.startswith('D:'):
                w = it[1] * sc * (1.24 if kind.startswith('D:') else 1.0)
                hgt = (it[2] if kind.startswith('A:') else it[1] * 1.24) * sc
                d.rectangle(P.box(a, yb, w + 0.008, hgt + 0.008), fill=(16, 17, 18, 255))
                continue
            r = (it[1] * sc / 2 * 1.18 + 0.004) * P.S
            d.ellipse([x - r, y - r, x + r, y + r], fill=(16, 17, 18, 255))
            lab = LABELS.get(kind, (kind, kind))[1 if cyr else 0]
            text(d, (x, y + r + P.mm(5)), lab, P.mm(5.5), fill=(226, 228, 220))
    rect = atlas.paint(P.W, P.H, face)
    poly = [panel.at(a, b_) for a, b_ in pts2]
    for tri in ear_clip(pts2):                      # the outline need not be convex: cut into triangles properly
        m.face([poly[i] for i in tri],
               [atlas.uv(rect, (pts2[i][0] - a0) / (a1 - a0), (pts2[i][1] - b0) / (b1 - b0)) for i in tri],
               normal=panel.N)
    back = -panel.N * e.m(0.025)
    m.flat([q + back for q in poly], col['shade'], normal=-panel.N)
    c0 = np.mean(poly, 0)
    for i in range(len(poly)):
        p_, q_ = poly[i], poly[(i + 1) % len(poly)]
        mid = (p_ + q_) / 2
        m.flat([p_, q_, q_ + back, p_ + back], col['shade'], toward=mid + (mid - c0))

    for it, a, yb, sc in placed:
        kind = it[0]
        c = panel.at(a, yb)
        if kind.startswith('A:'):
            columns, lamps = L.lamps_of(kind[2:], pal_name)
            w, h = e.m(it[1] * sc), e.m(it[2] * sc)
            gauges.append({'Seat': sid, 'Kind': 'ANNUNCIATOR', 'Center': L.r5(c + panel.N * 0.002),
                           'Normal': L.r5(panel.N), 'Up': L.r5(panel.U), 'Diameter': round(w, 4), 'Width': round(w, 4),
                           'Height': round(h, 4), 'Depth': 0.02, 'Columns': columns, 'Lamps': lamps})
        elif kind.startswith('D:'):
            displays.append({'Seat': sid, 'Center': L.r5(c + panel.N * L.DISPLAY_LIFT), 'Normal': L.r5(panel.N),
                             'Up': L.r5(panel.U), 'Width': round(e.m(it[1] * sc), 4), 'Depth': 0.035,
                             'Kind': kind[2:], 'Style': L.ERAS[era]['displays']})
        else:
            d_ = e.m(it[1] * sc)
            gauges.append({'Seat': sid, 'Kind': kind, 'Center': L.r5(c + panel.N * L.GAUGE_LIFT),
                           'Normal': L.r5(panel.N), 'Up': L.r5(panel.U), 'Diameter': round(d_, 4), 'Depth': 0.03})
            m.ring(c, panel.N, panel.U, d_ / 2 * 1.16, d_ / 2 * 1.25, e.m(0.006), col['bezel'])
    # a row of switches in the band left under the instruments, if there is one
    if b - b0 > 0.04:
        yb = (b + b0) / 2
        ll, rr = width_at(yb)
        n_sw = int((ll + rr - 0.06) / 0.03)
        m.box(panel.at(0, yb, e.m(0.003)), panel.axes(), (e.m(ll + rr - 0.04) / 2, e.m(0.016), e.m(0.003)), col['black'])
        for j in range(max(0, n_sw)):
            a = -ll + 0.03 + (j + 0.5) * (ll + rr - 0.06) / max(1, n_sw)
            sp = panel.at(a, yb, e.m(0.006))
            m.box(sp, panel.axes(), (e.m(0.005), e.m(0.005), e.m(0.004)), col['mgrey'])
            lever = unit(panel.U * (1 if j % 3 else -1) * 0.5 + panel.N)
            m.box(sp + panel.N * e.m(0.004) + lever * e.m(0.008), (panel.R, lever, unit(np.cross(panel.R, lever))),
                  (e.m(0.0022), e.m(0.008), e.m(0.0022)), col['lgrey'] if j % 4 else col['red'])

    # -- glareshield: from the panel's top edge forward, under the glass, to the windscreen base
    top_pts = [panel.at(r, b_) for r, b_ in top_edge[::-1]]          # left to right along the top edge
    lip = [q + F_ * e.m(0.04) + U_ * e.m(0.02) for q in top_pts]
    rows_ = []
    for tt in (0.0, 0.33, 0.66, 1.0):
        row = []
        for q in lip:
            r = float(-(q[0] - E[0]) / k)
            u = float((q[1] - E[1]) / k)
            # forward to the windscreen base, but never out from under the glass
            f_lip = (q[2] - E[2]) / k
            f_end = f_lip + 0.02
            while f_end < f_lip + 0.9 and ceiling(r, f_end + 0.03, u - 0.2) < 0.25 and \
                    f_end + 0.03 < ahead(u - 0.02, r) - 0.02:
                f_end += 0.03
            f = f_lip + (f_end - f_lip) * tt
            u2 = min(u + 0.03 * math.sin(math.pi * tt), ceiling(r, f, u - 0.2) - 0.02)
            r2 = r
            wl, wr = wall(u2, f, -1), wall(u2, f, 1)
            r2 = float(np.clip(r2, -wl + 0.01, wr - 0.01))
            row.append(e.p(r2, u2, f))
        rows_.append(row)
    for i in range(len(rows_) - 1):
        for j in range(len(rows_[i]) - 1):
            m.flat([rows_[i][j], rows_[i][j + 1], rows_[i + 1][j + 1], rows_[i + 1][j]], col['coaming'], normal=[0, 1, 0])
    for j in range(len(lip) - 1):
        m.flat([lip[j], lip[j + 1], top_pts[j + 1], top_pts[j]], col['coaming'], toward=E)

    # -- HUD or reflector sight on the glareshield, its combiner centred on the line of sight, under the glass
    hud_out = None
    if want_hud or L.ERAS[era]['seat'] == 'ejection' or True:
        sight = want_hud
        hw = e.m(0.075 if sight else 0.04)
        z0 = E[2] + e.m(fu(-0.45) - 0.02)
        body_top = (lip[len(lip) // 2])[1] + e.m(0.04)
        m.box([E[0], body_top - e.m(0.04), z0 + e.m(0.10)], (X, Y, Z), (hw, e.m(0.04), e.m(0.10)), col['dgrey'],
              faces={(2, -1): col['black']})
        g_lean = math.radians(22 if sight else 30)
        g_up = np.array([0, math.cos(g_lean), -math.sin(g_lean)])
        g_n = unit(np.cross(X, g_up))
        g_bot = np.array([E[0], body_top + 0.004, z0 + e.m(0.06)])
        g_h = max(0.0, min(e.m(0.13 if sight else 0.08), (E[1] + e.m(0.04) - g_bot[1]) / g_up[1]))
        top = ac.glass_above(E[0], g_bot[2] + g_up[2] * g_h, g_bot[1])
        if top is not None:
            g_h = min(g_h, (top - 0.03 - g_bot[1]) / g_up[1])
        if g_h > e.m(0.04):
            g_top = g_bot + g_up * g_h
            ghw = e.m(0.07 if sight else 0.035)
            for s_ in (-1, 1):
                m.box((g_bot + g_top) / 2 + X * s_ * (ghw + 0.004), (X, g_up, g_n), (0.004, g_h / 2, 0.005), col['dgrey'])
            m.box(g_bot, (X, g_up, g_n), (ghw + 0.008, 0.005, 0.012), col['dgrey'])
            gc = [g_bot - X * ghw, g_bot + X * ghw, g_top + X * ghw, g_top - X * ghw]
            glass += [gc[0], gc[1], gc[2], gc[0], gc[2], gc[3]]
            if sight:
                hud_out = dict(centre=(g_bot + g_top) / 2, normal=-g_n, up=g_up, width=2 * ghw, height=g_h)

    # -- consoles, walls, floor: following the measured walls
    u_con, u_floor = -0.62, -1.06
    f_back = -0.40
    f_front = fu(u_bot) - 0.02
    stations = np.linspace(f_back, f_front, 7)
    for side in (1, -1):
        w_con = [max(0.20, wall(u_con + 0.02, f, side) - 0.012) for f in stations]
        w_low = [max(0.18, wall(u_floor + 0.05, f, side) - 0.012) for f in stations]
        w_sill = [max(0.20, wall(min(-0.25, u_con + 0.25), f, side) - 0.012) for f in stations]
        r_in = [max(0.16, w - 0.15) for w in w_con]
        for i in range(len(stations) - 1):
            f0, f1 = stations[i], stations[i + 1]
            # console top
            m.flat([e.p(side * r_in[i], u_con, f0), e.p(side * w_con[i], u_con, f0), e.p(side * w_con[i + 1], u_con, f1),
                    e.p(side * r_in[i + 1], u_con, f1)], col['console'], normal=[0, 1, 0])
            # switch blocks on it
            fc = (f0 + f1) / 2
            for j in range(3):
                rr = side * (r_in[i] + (j + 0.5) * (w_con[i] - r_in[i]) / 3)
                m.box(e.p(rr, u_con + 0.01, fc), (X, Y, Z), (e.m(0.006), e.m(0.009), e.m(0.006)),
                      col['mgrey'] if (i + j) % 3 else col['lgrey'])
            m.box(e.p(side * (r_in[i] + w_con[i]) / 2, u_con + 0.003, fc), (X, Y, Z),
                  (e.m((w_con[i] - r_in[i]) / 2 - 0.01), e.m(0.003), e.m((f1 - f0) / 2 - 0.006)), col['black'])
            # console inner side down to the floor
            m.flat([e.p(side * r_in[i], u_floor, f0), e.p(side * r_in[i + 1], u_floor, f1),
                    e.p(side * r_in[i + 1], u_con, f1), e.p(side * r_in[i], u_con, f0)], col['shade'],
                   normal=e.d(-side, 0, 0))
            # the wall above the console, up to the sill line
            m.flat([e.p(side * w_con[i], u_con, f0), e.p(side * w_con[i + 1], u_con, f1),
                    e.p(side * w_sill[i + 1], min(-0.25, u_con + 0.25), f1),
                    e.p(side * w_sill[i], min(-0.25, u_con + 0.25), f0)], col['wall'], normal=e.d(-side, 0, 0))
            # the lower wall behind the console
            m.flat([e.p(side * w_low[i], u_floor, f0), e.p(side * w_low[i + 1], u_floor, f1),
                    e.p(side * w_con[i + 1], u_con, f1), e.p(side * w_con[i], u_con, f0)], col['wall'],
                   normal=e.d(-side, 0, 0))
        # console front end
        m.flat([e.p(side * r_in[-1], u_floor, f_front), e.p(side * w_low[-1], u_floor, f_front),
                e.p(side * w_con[-1], u_con, f_front), e.p(side * r_in[-1], u_con, f_front)], col['shade'],
               normal=e.d(0, 0, -1))
    # floor, footwell under the panel, rear bulkhead
    fl = [min(wall(u_floor + 0.03, f, -1), 0.30) for f in stations]
    fr = [min(wall(u_floor + 0.03, f, 1), 0.30) for f in stations]
    for i in range(len(stations) - 1):
        m.flat([e.p(-fl[i], u_floor, stations[i]), e.p(fr[i], u_floor, stations[i]), e.p(fr[i + 1], u_floor, stations[i + 1]),
                e.p(-fl[i + 1], u_floor, stations[i + 1])], col['floor'], normal=[0, 1, 0])
    f_foot = min(ahead(u_floor + 0.05) - 0.02, f_p + 0.45)
    m.flat([e.p(-fl[-1], u_floor, f_front), e.p(fr[-1], u_floor, f_front), e.p(fr[-1], u_floor, f_foot),
            e.p(-fl[-1], u_floor, f_foot)], col['floor'], normal=[0, 1, 0])
    m.flat([e.p(-fl[-1], u_floor, f_foot), e.p(fr[-1], u_floor, f_foot), e.p(fr[-1], u_bot, f_foot),
            e.p(-fl[-1], u_bot, f_foot)], col['shade'], normal=e.d(0, 0, -1))
    ll, rr = width_at(b0)
    m.flat([panel.at(-ll, b0), panel.at(rr, b0), e.p(rr, u_bot, f_foot), e.p(-ll, u_bot, f_foot)], col['shade'],
           normal=[0, -1, 0])
    wl0, wr0 = wall(-0.4, f_back, -1), wall(-0.4, f_back, 1)
    m.flat([e.p(-wl0, u_floor, f_back), e.p(wr0, u_floor, f_back), e.p(wr0, -0.25, f_back), e.p(-wl0, -0.25, f_back)],
           col['wall'], normal=e.d(0, 0, 1))
    for sg in (1, -1):                              # rudder pedals
        m.box(e.p(sg * 0.11, u_floor + 0.12, f_foot - 0.10), (X, unit([0, 1, -0.4]), unit([0, 0.4, 1])),
              (e.m(0.045), e.m(0.07), e.m(0.012)), col['mgrey'])

    # -- throttle on the left console, stick, seat
    w_th = max(0.22, wall(u_con + 0.02, 0.25, -1) - 0.012)
    tx = -(max(0.16, w_th - 0.15) + w_th) / 2
    tq = e.p(tx, u_con, 0.25)
    m.box(tq + Y * e.m(0.015), (X, Y, Z), (e.m(0.03), e.m(0.015), e.m(0.09)), col['black'])
    ax = (X, unit([0, 1, 0.3]), unit([0, -0.3, 1]))
    m.box(tq + Y * e.m(0.06) + Z * e.m(0.02), ax, (e.m(0.008), e.m(0.045), e.m(0.008)), col['metal'])
    m.box(tq + Y * e.m(0.11) + Z * e.m(0.035), ax, (e.m(0.028), e.m(0.03), e.m(0.04)), col['black'])
    if side_stick:
        w_ss = max(0.22, wall(u_con + 0.02, 0.4, 1) - 0.012)
        foot = e.p((max(0.16, w_ss - 0.15) + w_ss) / 2, u_con + 0.02, 0.40)
        g_ax = (X, unit([0, 1, 0.25]), unit([0, -0.25, 1]))
        gc = foot + g_ax[1] * e.m(0.07)
        stick.box(gc, g_ax, (e.m(0.018), e.m(0.055), e.m(0.024)), col['black'])
        stick.box(gc + g_ax[1] * e.m(0.06), g_ax, (e.m(0.015), e.m(0.006), e.m(0.02)), col['dgrey'])
    else:
        foot = e.p(0, u_floor + 0.08, 0.42)
        m.box(foot + Y * e.m(0.02), (X, Y, Z), (e.m(0.045), e.m(0.03), e.m(0.045)), col['dgrey'])
        Lc = e.m(-0.48 - (u_floor + 0.08))
        cax = (X, unit([0, 1, -0.07]), unit([0, 0.07, 1]))
        stick.box(foot + cax[1] * Lc / 2, cax, (e.m(0.011), Lc / 2, e.m(0.011)), col['metal'])
        g_ax = (X, unit([0, 1, 0.2]), unit([0, -0.2, 1]))
        gc = foot + cax[1] * Lc + g_ax[1] * e.m(0.055)
        stick.box(gc, g_ax, (e.m(0.021), e.m(0.06), e.m(0.026)), col['black'])
        stick.box(gc + g_ax[1] * e.m(0.065), g_ax, (e.m(0.006), e.m(0.004), e.m(0.006)), col['red'])
    c = {n: col[n] for n in col}
    if L.ERAS[era]['seat'] == 'ejection':
        L.ejection_seat(m, ac, tub, c)
    else:
        L.bucket_seat(m, tub, c)
    return m, stick, gauges, displays, glass, hud_out, foot
