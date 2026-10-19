#!/usr/bin/env python3
"""Canopy glass built panel by panel, the way the real canopy is made (tools/canopy_panels/spec.json says how).

The canopy envelope (plan width, sill line, spine, side fullness) is still measured on the model by
tools/canopy_loft (Loft: rails, frames, painted plan). This tool then builds the glass from that envelope as the
real aircraft's panels instead of one blown sheet:

  windscreen (from the base on the nose to the windscreen arch):
    curved     one lofted sheet (F-15, Su-27, bubble windscreens);
    flat3      a flat centre panel (the armour-glass block on the Su-25, A-10, MiG-23, F-104) between two posts and
               a flat quarter panel each side, from the posts to the arch;
    flat1      a single flat plate between the posts, the sides filled by the arch (MiG-15 / F-86 style armour
               glass with curved side pieces: sides are lofted);
    integrated windscreen and hood are one frameless piece (F-16): no arch, the sheet runs on;
  hood (from the arch to the last bow): a lofted sheet, cut at every bow; the section can be
    round      a blown dome (the measured fullness);
    flatside   flat side panels up to a shoulder, round top (F-104, MiG-15, Su-25 hood);
    flattop    flat sides and a flat top with chamfered corners (greenhouse hoods);
  rear (behind the last bow): fairing (solid: the glass stops at the bow), glazed (the sheet runs down to the spine
  as on the F-16 / F-15 / Su-27), or taper (a short glazed tail to the rear end).

Panels meet at frame stations: the posts, the arch, the bows and the rear end. Every flat panel is one quad (two
triangles): there is nothing to clip, so no edge is jagged. Curved sections are regular grids between two frame
stations with the same vertex layout, so they share their edges. Frame bars (dark strips, standing a little proud
of the glass) are written for every station the spec lists, to tools/canopy_panels/frames/<id>.json, and
tools/cockpit_models/fleet.py adds them to the cockpit bone.

Output: CanopyGlass in the asset vehicle JSON, as tools/canopy_glass writes it.
Usage: python3 tools/canopy_panels/panels.py [--write] [--out=dir] [--sheet=dir] id...
"""
import json, math, os, sys
import numpy as np
from scipy.interpolate import PchipInterpolator

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'canopy_loft'))
sys.path.insert(0, os.path.join(HERE, '..', 'canopy_frames'))
sys.path.insert(0, os.path.join(HERE, '..', 'canopy_glass'))
import loft as LF          # noqa: E402
import evidence as EV      # noqa: E402
import glass as G          # noqa: E402

SPEC = os.path.join(HERE, 'spec.json')
FRAMES_DIR = os.path.join(HERE, 'frames')
N_ARC = 24            # vertices across a curved section (edge to edge)
DZ_CURVED = 0.07      # station spacing along curved sections
BAR_W = 0.030         # frame bar width
BAR_T = 0.014         # frame bar stands this proud of the glass
BAR_IN = 0.010        # and reaches this far under it
POST_W = 0.028


def unit(v):
    v = np.asarray(v, float); return v / max(np.linalg.norm(v), 1e-9)


class Envelope:
    """The canopy envelope from the Loft: width, sill, spine and side fullness along the canopy."""
    def __init__(self, vid, loft_cfg, spec):
        cfg = dict(loft_cfg); cfg.pop('skip', None)
        cfg.update(spec.get('loft', {}))      # envelope overrides, as canopy_loft/loft.json takes them
        self.L = L = LF.Loft(vid, cfg)
        self.ev = L.ev
        self.zf, self.zr, self.zc = L.zf, L.zr, L.zc
        self.z = L.z
        self.us = np.concatenate([[0.0], L.U, [1.0]])
        # the lower edge never hangs under the sill rails (open stations) or the skin at the glass edge (closed
        # stations): glass stops where the canopy meets the airframe
        ev = self.ev
        floor = np.full(len(L.z), -np.inf)
        for k, z in enumerate(L.z):
            j = ev.row(z); w = L.W[k]
            if ev.open[j]:
                floor[k] = ev.crest[j, 1] - 0.015
            elif w > 0.03:
                # a closed station (nose, fairing): the glass edge stays on the top of the fuselage, so the
                # section narrows until the skin at its edge is close under the centre-line skin
                top = ev.skin_at(0.0, z)
                while w > 0.05 and max(ev.skin_at(w, z), ev.skin_at(-w, z)) < top - 0.10:
                    w -= 0.01
                L.W[k] = w
                floor[k] = max(ev.skin_at(w, z), ev.skin_at(-w, z)) - 0.01
        if 'lip_below_eye' in spec:          # a sill the model does not show: this far under the pilot's eye
            floor = np.maximum(floor, ev.eye[1] - spec['lip_below_eye'])
        L.Yb = np.maximum(L.Yb, floor)
        L.S = np.maximum(L.S, L.Yb + 0.01)
        for j in range(L.H.shape[1]):
            L.H[:, j] = np.clip(L.H[:, j], L.Yb + 0.005, L.S - 0.002)

    def W(self, z):
        return float(np.interp(z, self.z, self.L.W))

    def lip(self, z):
        """the glass's lower edge: the loft's rail, capped along the hood (see Builder.cap_sill)"""
        y = float(np.interp(z, self.z, self.L.Yb))
        cap = getattr(self, 'cap', None)
        if cap and cap[0] - 0.05 <= z <= cap[1] + 0.05:
            y = min(y, cap[2])
        return y

    def spine(self, z):
        return float(np.interp(z, self.z, self.L.S))

    def tracks(self, z):
        return np.array([np.interp(z, self.z, self.L.H[:, j]) for j in range(self.L.H.shape[1])])

    def round_profile(self, z, n):
        """points across a blown section at station z, edge to edge: (n, 3)"""
        W, Yb, S = self.W(z), self.lip(z), self.spine(z)
        ys = np.concatenate([[S], self.tracks(z), [Yb]])
        f = PchipInterpolator(self.us, ys)
        half = np.sin(np.linspace(0, np.pi / 2, n // 2 + 1))
        ts = np.concatenate([-half[::-1], half[1:]])
        return np.array([[W * t, float(f(abs(t))), z] for t in ts])

    def flatside_profile(self, z, n, shoulder=0.55, tilt=0.06, top='round'):
        """flat sides from the sill up to a shoulder, then an arc (or a flat top) over: (n, 3) points, the sides as
        single straight segments"""
        W, Yb, S = self.W(z), self.lip(z), self.spine(z)
        y_sh = Yb + shoulder * (S - Yb)
        x_sh = W * (1 - tilt)
        m = n - 4
        if top == 'flat':
            arc = [[x_sh * (1 - 2 * k / (m + 1)), S, z] for k in range(1, m + 1)]
            # chamfer: the corner between the side and the roof
            arc[0][1] = arc[-1][1] = S - 0.35 * (S - y_sh)
            arc[0][0] = x_sh * 0.92; arc[-1][0] = -x_sh * 0.92
        else:
            arc = []
            for k in range(1, m + 1):
                a = math.pi * k / (m + 1)              # from the right shoulder over the top to the left
                arc.append([x_sh * math.cos(a), y_sh + (S - y_sh) * math.sin(a), z])
        return np.array([[W, Yb, z], [x_sh, y_sh, z]] + arc + [[-x_sh, y_sh, z], [-W, Yb, z]])

    def profile(self, z, n, kind, p):
        if kind == 'flatside':
            return self.flatside_profile(z, n, p.get('shoulder', 0.55), p.get('tilt', 0.06))
        if kind == 'flattop':
            return self.flatside_profile(z, n, p.get('shoulder', 0.6), p.get('tilt', 0.04), top='flat')
        return self.round_profile(z, n)


def grid_tris(rows):
    """triangles between consecutive rows of equal length"""
    out = []
    for i in range(len(rows) - 1):
        A, B = rows[i], rows[i + 1]
        for j in range(len(A) - 1):
            a, b, c, d = A[j], B[j], B[j + 1], A[j + 1]
            out += [[a, b, c], [a, c, d]]
    return [t for t in out if np.linalg.norm(np.cross(t[1] - t[0], t[2] - t[0])) > 1e-10]


def quad(a, b, c, d):
    a, b, c, d = (np.asarray(p, float) for p in (a, b, c, d))
    return [[a, b, c], [a, c, d]]


def flatten(pts):
    """project points onto their least-squares plane (so a 4-point panel is truly flat)"""
    P = np.asarray(pts, float)
    c = P.mean(0)
    _, _, vt = np.linalg.svd(P - c)
    n = vt[2]
    return P - np.outer((P - c) @ n, n)


class Builder:
    def __init__(self, vid, spec, loft_cfg):
        self.vid, self.spec = vid, spec
        self.env = env = Envelope(vid, loft_cfg, spec)
        ev = env.ev
        self.eye = ev.eye
        ws = spec.get('windscreen', {})
        hood = spec.get('hood', {})
        rear = spec.get('rear', {})
        # --- stations ---
        # the arch: front of the cockpit opening unless given (fraction 0..1 from the base to the rear end, or z)
        if 'arch' in spec:
            a = spec['arch']
            self.z_arch = a if a > 1.5 else env.zf - a * (env.zf - env.zr)
        else:
            self.z_arch = min(ev.pit_z[0] + ws.get('arch_ahead', 0.05), env.zf - 0.25)
        self.z_arch = float(np.clip(self.z_arch, env.zr + 0.4, env.zf - 0.2))
        # a flat windscreen's base: where a line from the arch top down at the rake angle meets the nose skin
        # (the loft's base runs on down a painted slope; a real armour-glass windscreen is short and steep)
        self.z_base = env.zf
        if 'base' in ws:
            self.z_base = float(ws['base'])            # given outright: the model's own windscreen foot
        elif ws.get('type', 'curved') != 'integrated':
            rake = math.radians(ws.get('rake', 45.0 if ws.get('type', 'curved') != 'curved' else 38.0))
            top = env.spine(self.z_arch) - ws.get('top_drop', 0.0)
            z = self.z_arch
            while z < env.zf - 1e-6 and top - (z - self.z_arch) * math.tan(rake) > ev.skin_at(0.0, z) + 0.02:
                z += 0.02
            self.z_base = float(min(z, env.zf))
        # the hood's last bow / rear end of the hood
        if 'hood_end' in spec:
            h = spec['hood_end']
            self.z_hood = h if h > 1.5 else env.zf - h * (env.zf - env.zr)
        elif rear.get('type', 'fairing') == 'fairing':
            self.z_hood = env.zr                       # the painted glazing ends where the hood's glass ends
        else:
            self.z_hood = env.zr + rear.get('tail', 0.35)   # a glazed tail closes on the spine at the rear end
        self.z_hood = float(np.clip(self.z_hood, env.zr, self.z_arch - 0.2))
        bows = hood.get('bows', [])
        self.bows = [self.z_arch + (self.z_hood - self.z_arch) * b for b in bows]
        self.cap_sill()
        self.tris, self.frames = [], []
        # panel edges that are not frame rings: the windscreen base, the sills along the glass's lower edge, the
        # rear spine and end of a glazed tail (tools/canopy_build draws solid members along them)
        self.edges, self.sill_l, self.sill_r = [], [], []

    # ---------------------------------------------------------------- windscreen
    def windscreen(self):
        env, ev, ws = self.env, self.env.ev, self.spec.get('windscreen', {})
        kind = ws.get('type', 'curved')
        zf, za = self.z_base, self.z_arch
        if kind == 'none':
            return
        n = N_ARC
        if kind in ('curved', 'integrated'):
            zs = np.linspace(zf, za, max(2, int((zf - za) / DZ_CURVED) + 1))
            rows = [env.round_profile(z, n) for z in zs]
            # the base row sits on the nose skin
            rows[0][:, 1] = [max(ev.skin_at(p[0], zf) + 0.012, env.lip(zf)) for p in rows[0]]
            self.tris += grid_tris(rows)
            self.edges.append(('base', rows[0]))
            for r in rows[1:]:
                self.sill_r.append(r[0]); self.sill_l.append(r[-1])
            if kind == 'curved':
                self.frames.append(('arch', env.profile(za, n, self.hood_kind(), self.spec.get('hood', {}))))
            return
        # flat windscreens: the centre panel between two posts
        Wa, Sa, La = env.W(za), env.spine(za), env.lip(za)
        cf = ws.get('centre_frac', 0.42)        # centre panel half-width at the arch, share of the arch half-width
        bf = ws.get('base_frac', 0.75)          # the base is narrower: share of the top half-width
        top_drop = ws.get('top_drop', 0.0)      # the panel top below the spine (a flat panel under a round arch)
        xc = cf * Wa
        yc = Sa - top_drop
        xb = xc * bf
        yb = max(ev.skin_at(xb, zf), ev.skin_at(0.0, zf)) + 0.012
        y0 = ev.skin_at(0.0, zf) + 0.012
        if kind == 'flat1':
            xc = ws.get('centre_frac', 0.62) * Wa; xb = xc * bf
        C = flatten([[xc, yc, za], [-xc, yc, za], [-xb, yb, zf], [xb, yb, zf]])
        self.tris += quad(*C)
        self.frames.append(('post', np.array([C[0], C[3]])))
        self.frames.append(('post', np.array([C[1], C[2]])))
        prof = env.profile(za, n, self.hood_kind(), self.spec.get('hood', {}))
        feet = {}
        for sgn in (1, -1):
            top = C[0] if sgn > 0 else C[1]
            bot = C[3] if sgn > 0 else C[2]
            # the arch side from its foot on the sill up to the shoulder nearest the post top
            side = prof[prof[:, 0] * sgn > 0]
            side = side[np.argsort(side[:, 1])]                     # foot first
            k = int(np.argmin(np.abs(side[:, 1] - yc)))
            side = side[:k + 1]
            feet[sgn] = side[0]
            if kind == 'flat3' or len(side) < 3:
                Q = flatten([top, side[-1], side[0], bot])
                self.tris += quad(*Q)
            else:
                # a curved quarter panel: ruled between the post (bottom to top) and the arch side (foot to shoulder)
                m = len(side)
                post = [bot + (top - bot) * i / (m - 1) for i in range(m)]
                self.tris += grid_tris([np.array(post), side])
        self.frames.append(('arch', prof))
        self.edges.append(('base', np.array([feet[1], C[3], C[2], feet[-1]])))

    def cap_sill(self):
        """A hood's lower edge runs level: it does not climb. The loft's rail does where the model's own canopy
        is painted on as a solid hump merging into the headrest fairing (the rail then rides the hump) or where a
        windscreen block fills the opening, so along the hood the rail is capped a little above its lower part."""
        env = self.env
        zs = np.linspace(self.z_hood, self.z_arch, 40)
        lips = np.array([float(np.interp(z, env.z, env.L.Yb)) for z in zs])
        env.cap = (self.z_hood, self.z_arch, float(np.percentile(lips, 30)) + self.spec.get('sill_rise', 0.08))

    def hood_kind(self):
        return self.spec.get('hood', {}).get('section', 'round')

    # ---------------------------------------------------------------- hood
    def hood(self):
        env, hood = self.env, self.spec.get('hood', {})
        kind, n = self.hood_kind(), N_ARC
        za, zh = self.z_arch, self.z_hood
        stations = [za] + sorted(self.bows, reverse=True) + [zh]
        for z0, z1 in zip(stations[:-1], stations[1:]):
            if kind in ('flatside', 'flattop') and hood.get('flat_along', False):
                zs = [z0, z1]
            else:
                zs = np.linspace(z0, z1, max(2, int((z0 - z1) / DZ_CURVED) + 1))
            rows = [env.profile(z, n, kind, hood) for z in zs]
            self.tris += grid_tris(rows)
            for r in rows:
                self.sill_r.append(r[0]); self.sill_l.append(r[-1])
        for z in self.bows:
            self.frames.append(('bow', env.profile(z, n, kind, hood)))
        self.frames.append(('bow', env.profile(zh, n, kind, hood)))

    # ---------------------------------------------------------------- rear
    def rear(self):
        env, rear = self.env, self.spec.get('rear', {})
        kind = rear.get('type', 'fairing')
        zh, zr = self.z_hood, env.zr
        if kind == 'fairing' or zh - zr < 0.05:
            return
        n = N_ARC
        zs = np.linspace(zh, zr, max(2, int((zh - zr) / DZ_CURVED) + 1))
        rows = [env.profile(z, n, self.hood_kind(), self.spec.get('hood', {})) for z in zs]
        if kind == 'taper':
            # narrows and sinks onto the spine
            for i, z in enumerate(zs):
                f = (zh - z) / max(zh - zr, 1e-6)
                rows[i][:, 0] *= (1 - 0.85 * f)
                lip = env.lip(z)
                rows[i][:, 1] = lip + (rows[i][:, 1] - lip) * (1 - 0.75 * f)
        else:
            rows[-1][:, 0] *= 0.15
        self.tris += grid_tris(rows)
        for r in rows[1:]:
            self.sill_r.append(r[0]); self.sill_l.append(r[-1])
        if kind == 'glazed':
            self.edges.append(('rear', rows[-1]))      # the hoop the fixed rear glazing ends on

    # ---------------------------------------------------------------- frame bars
    def bars(self):
        """dark strips along the frame stations: a ring across an arch or bow (every profile segment), a straight
        bar for a post"""
        out = []
        for kind, pts in self.frames:
            P = np.asarray(pts, float)
            for a, b in zip(P[:-1], P[1:]):
                out.append({'kind': kind, 'a': [round(float(v), 4) for v in a], 'b': [round(float(v), 4) for v in b]})
        return out

    def build(self):
        self.windscreen(); self.hood(); self.rear()
        return self.tris

    def sills(self):
        """(right, left) polylines along the glass's lower edge from the windscreen to the rear end, deduplicated
        and ordered nose to tail. The rail stops where the opening narrows or its edge climbs onto the headrest
        fairing or the spine: there the glass sits on the skin without a sill."""
        out = []
        for pts in (self.sill_r, self.sill_l):
            P = np.array(pts, float) if pts else np.zeros((0, 3))
            if len(P):
                xw = np.abs(P[:, 0]).max()
                lo = P[:, 1].min()
                ok = (np.abs(P[:, 0]) >= 0.5 * xw) & (P[:, 1] <= lo + 0.15)
                P = P[ok]
            if len(P):
                P = P[np.argsort(-P[:, 2], kind='stable')]
                keep = [P[0]]
                for q in P[1:]:
                    if np.linalg.norm(q - keep[-1]) > 0.02:
                        keep.append(q)
                P = np.array(keep)
            out.append(P)
        return out


def write_frames(vid, bars, centre):
    os.makedirs(FRAMES_DIR, exist_ok=True)
    json.dump({'vid': vid, 'width': BAR_W, 'proud': BAR_T, 'under': BAR_IN, 'centre': [round(float(v), 4) for v in centre],
               'bars': bars}, open(os.path.join(FRAMES_DIR, vid + '.json'), 'w'))


def main(argv):
    specs = json.load(open(SPEC))
    loft_cfgs = json.load(open(LF.CONFIG)) if os.path.exists(LF.CONFIG) else {}
    out = next((a[6:] for a in argv if a.startswith('--out=')), None)
    ids = [a for a in argv if not a.startswith('--')] or [k for k in specs if not k.startswith('_')]
    results = {}
    for vid in ids:
        spec = specs.get(vid)
        if spec is None or spec.get('skip'):
            print(f'{vid:34s} no spec' if spec is None else f'{vid:34s} skipped', flush=True); continue
        try:
            B = Builder(vid, spec, loft_cfgs.get(vid, {}))
            tris = B.build()
        except Exception as failure:
            import traceback; traceback.print_exc()
            print(f'{vid:34s} FAILED {failure!r}', flush=True); continue
        results[vid] = LF.block(tris)
        print(f'{vid:34s} {len(tris):5d} tris  base {B.env.zf:.2f} arch {B.z_arch:.2f} hood_end {B.z_hood:.2f} '
              f'rear {B.env.zr:.2f}  ws {spec.get("windscreen", {}).get("type", "curved")} hood {B.hood_kind()}', flush=True)
        if '--write' in argv:
            G.write_block(vid, results[vid])
            # the eye at the canopy's middle, for the frame bars' outward direction
            write_frames(vid, B.bars(), [0.0, B.env.lip(B.env.zc) - 0.3, B.env.zc])
    if out:
        os.makedirs(out, exist_ok=True)
        json.dump(results, open(os.path.join(out, 'glass.json'), 'w'))


if __name__ == '__main__':
    main(sys.argv[1:])
