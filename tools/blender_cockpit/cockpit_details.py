"""Cockpit detail pass for the structure generator (and the F-16C): what fills a real cockpit beyond the big parts.

  - switch panels along both side consoles, each with toggles, rotary knobs or a small display window;
  - circuit-breaker strips on the cockpit walls above the consoles;
  - a caution-light panel at the right console's front, master caution / warning lamps on the glareshield lip;
  - toggle switches on the main panel's switch plates;
  - the seat harness (shoulder straps, lap belt, crotch strap);
  - rudder pedal arms, the canopy handle on the sill, the oxygen regulator;
  - WWII and early jets: the standby compass on the coaming, the trim wheel and flap lever on the left console.

Small parts are merged into one mesh per group (one object per kind and side), so a cockpit stays a few dozen
objects. Everything is laid on surfaces the structure pass already fitted inside the hull; the fit and checks run
after this, as for every other part.
"""
import math, random
import bmesh
from mathutils import Vector, Matrix


class Group:
    """many small solids gathered into one mesh"""

    def __init__(self, ctx, name, material):
        self.ctx, self.name, self.material = ctx, name, material
        self.bm = bmesh.new()
        self.n = 0

    def _basis(self, c, axes):
        ar, au, an = [a.normalized() for a in axes]
        m = Matrix((ar, au, an)).transposed().to_4x4()
        m.translation = c
        return m

    def box(self, c, axes, half):
        k = self.ctx['k']
        m = self._basis(c, axes) @ Matrix.Diagonal((half[0] * 2 * k, half[1] * 2 * k, half[2] * 2 * k, 1.0))
        bmesh.ops.create_cube(self.bm, size=1.0, matrix=m)
        self.n += 1

    def cyl(self, c, axis, radius, half_len, segs=10):
        k = self.ctx['k']
        rot = Vector((0, 0, 1)).rotation_difference(axis.normalized()).to_matrix().to_4x4()
        m = Matrix.Translation(c) @ rot
        bmesh.ops.create_cone(self.bm, cap_ends=True, segments=segs, radius1=radius * k, radius2=radius * k,
                              depth=2 * half_len * k, matrix=m)
        self.n += 1

    def done(self, bevel=0.0):
        if self.n == 0:
            self.bm.free()
            return None
        bmesh.ops.recalc_face_normals(self.bm, faces=self.bm.faces)
        return self.ctx['obj'](self.name, self.bm, self.material, bevel=bevel)


def toggle(g_base, g_bat, c, n, up, k, tilt=20):
    """a toggle switch: a round base on the surface and the bat leaning `tilt` degrees"""
    g_base.cyl(c + n * 0.002 * k, n, 0.0055, 0.002)
    bat = (n * math.cos(math.radians(tilt)) + up * math.sin(math.radians(tilt))).normalized()
    g_bat.cyl(c + n * 0.004 * k + bat * 0.009 * k, bat, 0.0018, 0.009, segs=6)


def add(ctx):
    P, R, U, F, k, M = (ctx[x] for x in ('P', 'R', 'U', 'F', 'k', 'M'))
    E = ctx['E']
    rng = random.Random(ctx.get('seed', 'cockpit'))
    era = ctx.get('era', '')
    wwii = ctx.get('wwii', False)
    con_u, CON_R0 = ctx['con_u'], ctx['CON_R0']
    st_back, st_front = ctx['st_back'], ctx['st_front']
    hip_f = (ctx['hip'] - E).dot(F) / k
    side_stick = ctx.get('stick') == 'side'
    wall = ctx['wall']
    sill_u, floor_u = ctx['sill_u'], ctx['floor_u']

    plates = Group(ctx, 'console_plates', M['console'])
    bases = Group(ctx, 'console_switch_bases', M['metal'])
    bats = Group(ctx, 'console_switch_bats', M['metal'])
    knobs = Group(ctx, 'console_knobs', M['grip'])
    windows = Group(ctx, 'console_windows', M['black'])
    lamps = Group(ctx, 'caution_lamps', M['yellow'])
    # ------------------------------------------------------------------ side console switch panels
    for sg in (-1, 1):
        # stations along the console that stay clear of the throttle (left) and the side stick (right)
        keep_out = []
        if sg < 0:
            keep_out.append((hip_f + 0.15, hip_f + 0.42))
        if sg > 0 and side_stick:
            keep_out.append((hip_f + 0.02, hip_f + 0.50))
        f = st_back + 0.04
        i = 0
        while f < st_front - 0.06:
            ln = rng.choice((0.07, 0.09, 0.11))
            if any(a < f + ln and f < b for a, b in keep_out):
                f += 0.03
                continue
            w_out = min(wall(con_u + 0.02, f + ln / 2, sg) - 0.022, 0.40)
            if w_out - CON_R0 < 0.06:
                f += ln + 0.008
                continue
            r_mid = sg * (CON_R0 + w_out) / 2
            hw = (w_out - CON_R0) / 2 - 0.008
            c = P(r_mid, con_u + 0.0025, f + ln / 2)
            plates.box(c, (R, F, U), (hw, ln / 2 - 0.004, 0.002))
            kind = rng.choice(('toggles', 'toggles', 'knobs', 'window', 'mixed'))
            top = c + U * 0.002 * k
            if kind == 'toggles':
                nx = max(2, int(hw * 2 / 0.022))
                for j in range(nx):
                    x = -hw + 0.012 + j * (2 * hw - 0.024) / max(1, nx - 1)
                    toggle(bases, bats, top + R * x * k + F * (rng.choice((-1, 1)) * 0.012) * k, U, F, k,
                           tilt=rng.choice((-22, 22)))
            elif kind == 'knobs':
                for j in range(2):
                    x = (j - 0.5) * hw
                    knobs.cyl(top + R * x * k + U * 0.005 * k, U, 0.010, 0.005)
                    knobs.box(top + R * x * k + U * 0.011 * k, (R, F, U), (0.002, 0.008, 0.001))
            elif kind == 'window':
                windows.box(top + U * 0.0012 * k, (R, F, U), (hw * 0.6, ln * 0.22, 0.0012))
                for j in range(3):
                    toggle(bases, bats, top + R * (j - 1) * hw * 0.5 * k - F * ln * 0.3 * k, U, F, k)
            else:
                knobs.cyl(top + R * (-hw * 0.5) * k + U * 0.005 * k, U, 0.009, 0.005)
                toggle(bases, bats, top + R * (hw * 0.4) * k, U, F, k)
                toggle(bases, bats, top + R * (hw * 0.4) * k + F * 0.025 * k, U, F, k, tilt=-22)
            f += ln + 0.008
            i += 1
        # the caution light panel at the right console's front (jets)
        if sg > 0 and not wwii:
            f = st_front - 0.05
            w_out = min(wall(con_u + 0.02, f, 1) - 0.022, 0.40)
            if w_out - CON_R0 > 0.07:
                c = P((CON_R0 + w_out) / 2, con_u + 0.004, f)
                plates.box(c, (R, F, U), ((w_out - CON_R0) / 2 - 0.008, 0.035, 0.0035))
                for a in range(4):
                    for b in range(3):
                        lamps.box(c + U * 0.004 * k + R * ((a - 1.5) * 0.022) * k + F * ((b - 1) * 0.020) * k,
                                  (R, F, U), (0.009, 0.007, 0.0012))
    plates.done(bevel=0.001); bases.done(); bats.done(); knobs.done(bevel=0.001); windows.done()

    # ------------------------------------------------------------------ circuit breakers on the walls
    cbs = Group(ctx, 'circuit_breakers', M['black'])
    cb_plates = Group(ctx, 'breaker_plates', M['console'])
    u_lo, u_hi = con_u + 0.05, sill_u - 0.05
    if u_hi - u_lo > 0.06:
        for sg in (-1, 1):
            for f in (hip_f - 0.25, hip_f - 0.12):
                if not (st_back + 0.03 < f < st_front - 0.03):
                    continue
                um = (u_lo + u_hi) / 2
                rw = wall(um, f, sg) - 0.018
                if rw < CON_R0 + 0.05:
                    continue
                n_in = R * -sg                       # the wall faces inboard
                c = P(sg * rw, um, f)
                cb_plates.box(c, (F, U, n_in), (0.050, (u_hi - u_lo) / 2, 0.0015))
                rows = max(1, int((u_hi - u_lo) / 0.022))
                for a in range(rows):
                    for b in range(6):
                        q = c + U * ((a - (rows - 1) / 2) * 0.020) * k + F * ((b - 2.5) * 0.016) * k + n_in * 0.002 * k
                        cbs.cyl(q, n_in, 0.0045, 0.003, segs=8)
    cbs.done(); cb_plates.done()

    # ------------------------------------------------------------------ toggles on the main panel's switch plates
    pb, pt = Group(ctx, 'panel_switch_bases', M['metal']), Group(ctx, 'panel_switch_bats', M['metal'])
    pk = Group(ctx, 'panel_knobs', M['grip'])
    for c, ax, w, h in ctx.get('panel_plates', []):
        ar, au, an = ax
        n = max(2, min(5, int(w / 0.024)))
        for j in range(n):
            x = -w / 2 + 0.012 + j * (w - 0.024) / max(1, n - 1)
            toggle(pb, pt, c + ar * x * k + au * (h * 0.2) * k + an * 0.0025 * k, an, au, k)
            pk.cyl(c + ar * x * k - au * (h * 0.25) * k + an * 0.006 * k, an, 0.0065, 0.004)
    pb.done(); pt.done(); pk.done()

    # ------------------------------------------------------------------ master caution / warning on the lip
    if not wwii and ctx.get('lip_pt'):
        mc = Group(ctx, 'master_caution', M['yellow'])
        for sg in (-1, 1):
            q = ctx['lip_pt'](sg * 0.17)
            mc.box(q, (R, U, ctx['pn']), (0.014, 0.010, 0.004))
        mc.done(bevel=0.001)

    # ------------------------------------------------------------------ seat harness
    hip, s_up, s_fw = ctx['hip'], ctx['s_up'], ctx['s_fw']
    hn = Group(ctx, 'seat_harness', M['harness'])
    for sg in (-1, 1):
        a = hip + s_up * 0.60 * k + s_fw * 0.035 * k + R * sg * 0.07 * k
        b = hip + s_up * 0.10 * k + s_fw * 0.12 * k + R * sg * 0.06 * k + F * 0.05 * k
        d = b - a
        hn.box((a + b) / 2, (R, d, d.cross(R)), (0.022, d.length / k / 2, 0.003))
    lap = hip + F * 0.11 * k + U * 0.05 * k
    hn.box(lap, (R, U, F), (0.17, 0.020, 0.004))
    hn.box(lap + R * 0.0 * k + U * 0.0 * k + F * 0.002 * k, (R, U, F), (0.030, 0.030, 0.006))          # the buckle
    hn.box(hip + F * 0.22 * k + U * 0.03 * k, (R, U, F), (0.020, 0.004, 0.10))                          # crotch strap
    hn.done(bevel=0.001)

    # ------------------------------------------------------------------ pedal arms, canopy handle, oxygen regulator
    arms = Group(ctx, 'pedal_arms', M['metal'])
    for sg in (-1, 1):
        top = ctx['pedal_pos'](sg)
        bot = P(sg * 0.10, floor_u + 0.02, (top - E).dot(F) / k + 0.05)
        d = top - bot
        arms.box((top + bot) / 2, (R, d, d.cross(R)), (0.010, d.length / k / 2, 0.008))
    arms.done()
    misc = Group(ctx, 'cockpit_misc', M['grip'])
    if not wwii:
        rw = wall(sill_u - 0.04, hip_f + 0.05, 1) - 0.055
        misc.box(P(rw, sill_u - 0.045, hip_f + 0.05), (R, U, F), (0.012, 0.010, 0.040))       # canopy handle
        orf = hip_f - 0.05
        rw = min(0.30, wall(con_u + 0.03, orf, 1) - 0.05)
        if rw > CON_R0 + 0.04:
            misc.cyl(P(rw, con_u + 0.03, orf), U, 0.025, 0.025, segs=12)                  # oxygen regulator
    misc.done(bevel=0.002)
    hc = Group(ctx, 'yellow_handles', M['yellow'])
    if not wwii:
        rw = wall(sill_u - 0.04, hip_f + 0.05, 1) - 0.055
        hc.box(P(rw - 0.004, sill_u - 0.032, hip_f + 0.05), (R, U, F), (0.008, 0.004, 0.030))
    hc.done()

    # ------------------------------------------------------------------ the older types: compass, trim wheel, flaps
    if wwii or era == 'COLD_WAR_STEAM':
        if ctx.get('lip_pt'):
            q = ctx['lip_pt'](-0.12) + U * 0.03 * k
            g = Group(ctx, 'standby_compass', M['grip'])
            g.cyl(q, U, 0.025, 0.022, segs=14)
            g.done(bevel=0.002)
            gl = Group(ctx, 'standby_compass_face', M['black'])
            gl.cyl(q - F * 0.025 * k, F, 0.017, 0.002, segs=14)
            gl.done()
        wheel_f = hip_f + 0.05
        rw = min(0.32, wall(con_u + 0.06, wheel_f, -1) - 0.03)
        if rw > CON_R0 + 0.05:
            g = Group(ctx, 'trim_wheel', M['black'])
            g.cyl(P(-rw, con_u + 0.06, wheel_f), R, 0.055, 0.008, segs=18)
            g.done(bevel=0.002)
            g = Group(ctx, 'flap_lever', M['metal'])
            g.box(P(-(CON_R0 + 0.04), con_u + 0.05, hip_f + 0.10), (R, U, F), (0.005, 0.045, 0.005))
            g.cyl(P(-(CON_R0 + 0.04), con_u + 0.10, hip_f + 0.10), R, 0.012, 0.008)
            g.done()
