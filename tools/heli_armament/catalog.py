#!/usr/bin/env python3
"""Procedural models of the helicopter launchers, pods, rails and munitions, at their real dimensions (metres =
blocks). Every builder returns a Part in the store frame of meshlib (x left, y up, z forward, origin at the point
that touches the pylon or rail):

  muzzles   tube mouths of a pod (native rocket or gun channels fire from them, in order)
  stations  where each copy of a racked munition is placed (launcher stations, RackAdapter)
  axis      body axis point below the origin (MountAxis: launches leave from it)

Dimensions come from the War Thunder datamine (calibres and lengths, tools/heli_armament/wt_slots.json) and public
reference drawings of each launcher; where a figure was not found the shape is proportioned from photographs, which
is noted on the builder.
"""
import math
from functools import partial
from dataclasses import dataclass, field
from meshlib import Mesh, box, cbox, lathe, cylinder, tube, fins, fin, strut, rot_z

LUG_US = 0.356 / 2      # 14 in suspension lugs
LUG_RU = 0.250 / 2      # 250 mm lugs of the Soviet BD3/DZ racks


@dataclass
class Part:
    mesh: Mesh
    muzzles: list = field(default_factory=list)
    stations: list = field(default_factory=list)
    axis: tuple = (0.0, 0.0, 0.0)
    length: float = 0.0


def lugs(m, spacing, colour='steel', h=0.035, w=0.03):
    for z in (-spacing, spacing):
        cbox((0, -h / 2, z), (w, h, 0.05), colour, m)
    return m


def hex_cluster(count, pitch):
    """tube centres (x, y) of a round pod: rings of 1, 6, 12 or the B-8/UB-32 patterns"""
    rings = {7: [(0, 1), (1, 6)], 19: [(0, 1), (1, 6), (2, 12)], 20: [(1, 6), (2, 14)], 32: [(1, 6), (2, 12),
             (3, 14)], 5: [(0, 1), (1, 4)], 4: [(1, 4)]}[count]
    pts = []
    for r, n in rings:
        if r == 0:
            pts.append((0.0, 0.0)); continue
        for k in range(n):
            a = math.pi / 2 + 2 * math.pi * k / n
            pts.append((r * pitch * math.cos(a), r * pitch * math.sin(a)))
    return pts


def rocket_pod(length, radius, count, tube_r, pitch, colour, nose=0.10, tail=0.10, lug=LUG_US, front_plate='dark',
               nose_cap=False, n=16):
    """cylindrical rocket pod hanging from its lugs: body axis at y = -(0.035 + radius)"""
    m = Mesh()
    lugs(m, lug)
    ay = -(0.035 + radius)
    z0, z1 = -length / 2, length / 2
    body = [(z0, radius * 0.72), (z0 + tail, radius), (z1 - nose, radius), (z1, radius * 0.94)]
    lathe(body, colour, (0, ay), n, m=m, cap_front=False)
    # front face: a plate of dark tube mouths set slightly back in the fairing
    lathe([(z1 - 0.012, radius * 0.94), (z1 - 0.011, 0.0)], front_plate, (0, ay), n, m=m, cap_back=False)
    muzzles = []
    for x, y in hex_cluster(count, pitch):
        tube(z1 - 0.05, z1, tube_r * 1.12, tube_r, 'steel', 'tube_inner', (x, ay + y), 8, m, depth=0.04,
             back_closed=False)
        muzzles.append((x, ay + y, z1))
    if nose_cap:
        lathe([(z1, radius * 0.94), (z1 + 0.18, radius * 0.6), (z1 + 0.26, 0.0)], colour, (0, ay), n, m=m,
              cap_back=False)
    lathe([(z0 - 0.001, radius * 0.72), (z0 - 0.002, 0.0)], 'dark', (0, ay), n, m=m, cap_back=False)
    return Part(m, muzzles, axis=(0, ay, 0), length=length)


# ------------------------------------------------------------------------------------------------ US pods
def m261():
    """M261 19-tube 70 mm launcher: 1.68 m long, 0.40 m diameter (Hydra-70 launcher data, US Army TM 9-1055-460)"""
    return rocket_pod(1.68, 0.20, 19, 0.037, 0.077, 'olive', nose=0.08, tail=0.14)


def lau131():
    """LAU-131/A 7-tube 70 mm launcher: 1.65 m long, 0.25 m diameter"""
    return rocket_pod(1.65, 0.125, 7, 0.037, 0.082, 'olive_dark', nose=0.07, tail=0.12)


def aero_6a():
    """Aero 6A 7-tube 2.75 in launcher (AH-1G): 1.2 m long, 0.25 m diameter, tube mouths behind a blunt fairing"""
    return rocket_pod(1.25, 0.125, 7, 0.037, 0.082, 'us_green', nose=0.06, tail=0.20)


def aero_7a():
    """Aero 7A 19-tube 2.75 in launcher (XM159/XM200 family, AH-1G): 1.6 m long, 0.41 m diameter"""
    return rocket_pod(1.60, 0.205, 19, 0.037, 0.079, 'us_green', nose=0.10, tail=0.22)


def fz225():
    """FZ225 (FZ231 tubes) 19 x 68/70 mm launcher of the Tiger: 1.70 m, 0.41 m diameter"""
    return rocket_pod(1.70, 0.205, 19, 0.036, 0.079, 'fr_gray', nose=0.10, tail=0.16)


# ------------------------------------------------------------------------------------------------ Soviet pods
def b8v20():
    """B-8V20A 20 x 80 mm (helicopter pod): 2.0 m long, 0.52 m diameter, ogive tail fairing"""
    p = rocket_pod(2.00, 0.26, 20, 0.042, 0.105, 'ru_green', nose=0.06, tail=0.36, lug=LUG_RU)
    return p


def ub32():
    """UB-32A-24 32 x 57 mm: 2.07 m long, 0.48 m diameter"""
    return rocket_pod(2.07, 0.24, 32, 0.030, 0.064, 'ru_green', nose=0.05, tail=0.40, lug=LUG_RU)


def b13l():
    """B-13L 5 x 122 mm: 3.56 m long, 0.41 m diameter (the long-tube launcher of the S-13)"""
    return rocket_pod(3.56, 0.205, 5, 0.063, 0.138, 'ru_green', nose=0.10, tail=0.45, lug=LUG_RU)


# ------------------------------------------------------------------------------------------------ gun pods
def gun_pod(length, radius, barrels, colour, barrel_len, barrel_r=0.014, lug=LUG_US, nose=0.3, tail=0.25,
            cluster=0.0, n=14, muzzle_y=None):
    """streamlined gun pod with barrels out of the nose; barrels: [(x, y)] offsets of each gun from the axis"""
    m = Mesh()
    lugs(m, lug)
    ay = -(0.035 + radius)
    z0, z1 = -length / 2, length / 2
    lathe([(z0, 0.0), (z0 + tail * 0.3, radius * 0.55), (z0 + tail, radius), (z1 - nose, radius),
           (z1 - nose * 0.3, radius * 0.7), (z1, radius * 0.45)], colour, (0, ay), n, m=m)
    muzzles = []
    for bx, by in barrels:
        if cluster > 0:     # rotary gun: six barrels round the gun axis
            for k in range(6):
                a = 2 * math.pi * k / 6
                cylinder(z1 - 0.05, z1 + barrel_len, barrel_r, 'dark',
                         (bx + cluster * math.cos(a), ay + by + cluster * math.sin(a)), 6, m)
            cylinder(z1 + barrel_len - 0.08, z1 + barrel_len - 0.04, cluster + barrel_r * 1.6, 'dark',
                     (bx, ay + by), 10, m)
        else:
            cylinder(z1 - 0.05, z1 + barrel_len, barrel_r, 'dark', (bx, ay + by), 8, m)
        muzzles.append((bx, ay + by, z1 + barrel_len))
    return Part(m, muzzles, axis=(0, ay, 0), length=length + barrel_len)


def xm18():
    """SUU-11 / XM18 7.62 mm minigun pod: 2.16 m, 0.30 m diameter, the M134 barrels out of a short nose"""
    return gun_pod(2.16, 0.15, [(0.0, -0.03)], 'us_green', 0.10, 0.008, cluster=0.032, nose=0.35, tail=0.35)


def m134d_mount():
    """AH-6 M134D fixed gun: the minigun with its feed chute on a short pylon fairing"""
    m = Mesh()
    lugs(m, LUG_US)
    cbox((0, -0.10, -0.15), (0.16, 0.14, 0.70), 'black', m)
    lathe([(-0.25, 0.07), (0.30, 0.07), (0.40, 0.05)], 'black', (0, -0.22), 12, m=m)
    for k in range(6):
        a = 2 * math.pi * k / 6
        cylinder(0.35, 0.95, 0.008, 'dark', (0.032 * math.cos(a), -0.22 + 0.032 * math.sin(a)), 6, m)
    cylinder(0.86, 0.90, 0.045, 'dark', (0, -0.22), 10, m)
    return Part(m, [(0, -0.22, 0.95)], axis=(0, -0.22, 0), length=1.2)


def gau19():
    """GAU-19/B 12.7 mm pod of the MH/AH-6: a boxy pod with the three-barrel gun ahead and an ammunition drum"""
    m = Mesh()
    lugs(m, LUG_US)
    cbox((0, -0.18, -0.15), (0.30, 0.30, 1.10), 'black', m)
    lathe([(0.40, 0.10), (0.55, 0.09), (0.65, 0.06)], 'black', (0, -0.22), 12, m=m)
    for k in range(3):
        a = math.pi / 2 + 2 * math.pi * k / 3
        cylinder(0.6, 1.30, 0.014, 'dark', (0.04 * math.cos(a), -0.22 + 0.04 * math.sin(a)), 8, m)
    cylinder(1.20, 1.26, 0.06, 'dark', (0, -0.22), 10, m)
    return Part(m, [(0, -0.22, 1.30)], axis=(0, -0.22, 0), length=1.9)


def xm35():
    """XM35 20 mm (M195) of the AH-1G: the six-barrel cannon hangs from the left inner pylon, with its long
    barrel shroud forward and the ammunition fairing faired into the stub wing (here as the store's fairing box)"""
    m = Mesh()
    lugs(m, LUG_US)
    cbox((0, -0.12, -0.30), (0.26, 0.22, 1.20), 'us_green', m)
    lathe([(0.25, 0.09), (0.55, 0.09), (0.65, 0.07)], 'us_green', (0, -0.21), 12, m=m)
    for k in range(6):
        a = 2 * math.pi * k / 6
        cylinder(0.60, 1.55, 0.012, 'dark', (0.045 * math.cos(a), -0.21 + 0.045 * math.sin(a)), 6, m)
    cylinder(1.45, 1.50, 0.065, 'dark', (0, -0.21), 10, m)
    return Part(m, [(0, -0.21, 1.55)], axis=(0, -0.21, 0), length=2.4)


def hmp400():
    """FN HMP400 12.7 mm pod: 1.87 m long, 0.40 m tall, 0.32 m wide (FN Herstal brochure figures)"""
    m = Mesh()
    lugs(m, LUG_US)
    lathe([(-0.95, 0.0), (-0.85, 0.12), (-0.6, 0.16), (0.55, 0.16), (0.80, 0.11), (0.90, 0.06)], 'black',
          (0, -0.20), 12, m=m)
    cylinder(0.85, 1.25, 0.018, 'dark', (0, -0.22), 8, m)
    cylinder(1.15, 1.22, 0.032, 'dark', (0, -0.22), 8, m)
    return Part(m, [(0, -0.22, 1.25)], axis=(0, -0.20, 0), length=2.2)


def rmp_lc():
    """FN RMP-LC: the 12.7 mm gun on the left of a pod carrying three 70 mm rockets on the right"""
    m = Mesh()
    lugs(m, LUG_US)
    cbox((0, -0.20, 0.0), (0.38, 0.28, 1.80), 'black', m)
    lathe([(0.85, 0.07), (1.00, 0.04)], 'black', (0.10, -0.22), 10, m=m, cap_back=False)
    cylinder(0.95, 1.30, 0.018, 'dark', (0.10, -0.22), 8, m)
    muz_gun = (0.10, -0.22, 1.30)
    muzzles = []
    for y in (-0.13, -0.21, -0.29):
        tube(0.80, 0.92, 0.040, 0.036, 'steel', 'tube_inner', (-0.10, y), 8, m, depth=0.05, back_closed=False)
        muzzles.append((-0.10, y, 0.92))
    return Part(m, [muz_gun] + muzzles, axis=(0, -0.20, 0), length=2.2)


def upk23():
    """UPK-23-250: 3.08 m long, 0.43 m diameter, the GSh-23L twin barrels at the nose"""
    return gun_pod(3.08, 0.215, [(0.0, -0.06)], 'ru_gray', 0.25, 0.016, lug=LUG_RU, nose=0.5, tail=0.6)


def guv8700():
    """GUV-8700 (9-A-624 fit): one four-barrel 12.7 mm YakB and two four-barrel 7.62 mm GShG; 2.2 m, 0.48 m"""
    p = gun_pod(2.20, 0.24, [(0.0, -0.05), (0.11, -0.03), (-0.11, -0.03)], 'ru_green', 0.20, 0.010,
                lug=LUG_RU, nose=0.45, tail=0.4, cluster=0.022)
    return p


# ------------------------------------------------------------------------------------------------ bombs
def bomb(length, radius, colour, nose=0.35, tail_cone=0.45, fin_span=None, lug=LUG_RU, band='yellow'):
    m = Mesh()
    lugs(m, lug)
    ay = -(0.035 + radius)
    z0, z1 = -length / 2, length / 2
    lathe([(z0, radius * 0.35), (z0 + tail_cone, radius), (z1 - nose, radius), (z1 - nose * 0.4, radius * 0.75),
           (z1, radius * 0.25)], colour, (0, ay), 14, m=m)
    lathe([(z1 - nose - 0.04, radius * 1.004), (z1 - nose, radius * 1.004)], band, (0, ay), 14, m=m)
    span = fin_span or radius * 0.95
    fins(4, z0 - 0.02, z0 + tail_cone * 0.8, z0, z0 + 0.12, span, 0.008, colour, r0=radius * 0.35,
         centre=(0, ay), start=45.0, m=m)
    return Part(m, axis=(0, ay, 0), length=length)


def ofab250():
    """OFAB-250-270: 1.46 m long, 0.325 m diameter (WT datamine)"""
    return bomb(1.46, 0.1625, 'bomb_green', nose=0.30, tail_cone=0.45)


def fab500_m54():
    """FAB-500M-54: 2.4 m, 0.45 m, ballistic ring tail"""
    return bomb(2.40, 0.225, 'bomb_green', nose=0.55, tail_cone=0.75)


def fab500_m62():
    """FAB-500M-62: 2.4 m, 0.40 m, low-drag ogive"""
    return bomb(2.40, 0.20, 'bomb_gray', nose=0.80, tail_cone=0.70)


# ------------------------------------------------------------------------------------------------ missiles
def missile(length, radius, colours, nose='ogive', wings=None, tails=None, canards=None, origin='axis',
            seeker=None, bands=(), n=12, bore=None):
    """body of a missile or rocket on the z axis, nose at +z.
    origin 'axis': the origin is on the axis at mid-length (tube-launched rounds, placed in their tubes);
    origin 'lug': the origin is on top of the body (rail-hung rounds), the axis at y = -radius.
    bore: radius of the launch tube the round is stored in; its wings and fins are drawn folded inside it (the
    flight model, built without a bore, has them deployed)."""
    m = Mesh()
    ay = 0.0 if origin == 'axis' else -radius
    z0, z1 = -length / 2, length / 2
    body_c, nose_c = colours
    if nose == 'ogive':
        prof = [(z0, radius * 0.9), (z0 + 0.03, radius), (z1 - radius * 3.2, radius), (z1 - radius * 1.6,
                radius * 0.78), (z1 - radius * 0.5, radius * 0.38), (z1, 0.0)]
    elif nose == 'blunt':
        prof = [(z0, radius * 0.9), (z0 + 0.03, radius), (z1 - radius * 0.9, radius), (z1 - radius * 0.2,
                radius * 0.75), (z1, 0.0)]
    elif nose == 'dome':
        prof = [(z0, radius * 0.9), (z0 + 0.03, radius), (z1 - radius * 1.0, radius), (z1 - radius * 0.35,
                radius * 0.8), (z1, 0.0)]
    else:
        prof = nose(z0, z1, radius)
    cols = [body_c] * (len(prof) - 1)
    cols[-1] = seeker or nose_c
    cols[-2] = nose_c
    lathe(prof, cols, (0, ay), n, m=m)
    for zb, wb, c in bands:     # from the tail
        lathe([(z0 + zb, radius * 1.006), (z0 + zb + wb, radius * 1.006)], c, (0, ay), n, m=m)
    for spec in (wings, tails, canards):
        if spec:
            count, rz0, rz1, tz0, tz1, span, start = spec
            if bore is not None:
                span = max(0.004, min(span, bore - radius * 0.95 - 0.004))
            fins(count, z0 + rz0, z0 + rz1, z0 + tz0, z0 + tz1, span, 0.006, body_c, r0=radius * 0.95,
                 centre=(0, ay), start=start, m=m)
    return Part(m, axis=(0, ay, 0), length=length)


def stinger(bore=None):
    """FIM-92 / ATAS round: 1.52 m, 70 mm, four tail fins and two canards (WT us_aim92_stinger)"""
    return partial(missile, bore=bore)(1.52, 0.035, ('olive_dark', 'olive_dark'), nose='dome', seeker='glass',
                   tails=(4, 0.0, 0.08, 0.02, 0.08, 0.05, 45.0), canards=(2, 1.30, 1.36, 1.32, 1.36, 0.03, 0.0))


def igla(bore=None):
    """9M39 Igla: 1.68 m, 72 mm, pointed aerodynamic spike over the seeker, tail fins"""
    p = partial(missile, bore=bore)(1.68, 0.036, ('ru_green', 'ru_green'), nose='dome', seeker='glass',
                tails=(4, 0.0, 0.08, 0.02, 0.08, 0.05, 45.0), canards=(2, 1.45, 1.50, 1.46, 1.50, 0.03, 0.0))
    cylinder(0.84, 0.95, 0.004, 'steel', (0, 0), 4, p.mesh)
    return p


def tow(tow2=False, bore=None):
    """BGM-71 TOW (127 mm, 1.53 m) and TOW-2 (152 mm, 1.61 m with the extensible probe): WT datamine"""
    if tow2:
        p = partial(missile, bore=bore)(1.61, 0.076, ('olive', 'olive'), nose='blunt', wings=(4, 0.55, 0.70, 0.60, 0.70, 0.11, 45.0),
                    tails=(4, 0.05, 0.13, 0.08, 0.13, 0.05, 0.0))
        cylinder(0.80, 0.92, 0.02, 'steel', (0, 0), 8, p.mesh)
        return p
    return partial(missile, bore=bore)(1.53, 0.0635, ('olive', 'olive'), nose='blunt', wings=(4, 0.55, 0.70, 0.60, 0.70, 0.11, 45.0),
                   tails=(4, 0.05, 0.13, 0.08, 0.13, 0.05, 0.0))


def hot3(bore=None):
    """HOT 3: 1.30 m, 150 mm, folded wings (WT euro_hot3)"""
    return partial(missile, bore=bore)(1.30, 0.075, ('fr_green', 'fr_green'), nose='blunt', wings=(4, 0.05, 0.25, 0.10, 0.25, 0.10,
                   45.0))


def shturm(bore=None):
    """9M114 Shturm: 1.63 m, 130 mm, nose canards, folding tail wings"""
    return partial(missile, bore=bore)(1.613, 0.065, ('ru_green', 'ru_green'), nose='ogive', canards=(4, 1.30, 1.40, 1.33, 1.40, 0.04,
                   0.0), tails=(4, 0.03, 0.18, 0.08, 0.18, 0.12, 45.0))


def ataka(bore=None):
    """9M120M Ataka: 1.93 m, 130 mm (WT su_9M120M)"""
    return partial(missile, bore=bore)(1.93, 0.065, ('ru_green', 'ru_green'), nose='ogive', canards=(4, 1.62, 1.72, 1.65, 1.72, 0.04,
                   0.0), tails=(4, 0.03, 0.18, 0.08, 0.18, 0.12, 45.0))


def vikhr(bore=None):
    """9K121 Vikhr (9M127): 2.75 m, 130 mm (WT su_9M127)"""
    return partial(missile, bore=bore)(2.75, 0.065, ('ru_gray', 'ru_gray'), nose='ogive', canards=(4, 2.40, 2.52, 2.44, 2.52, 0.05,
                   0.0), tails=(4, 0.03, 0.22, 0.10, 0.22, 0.14, 45.0))


def apkws(bore=None):
    """APKWS II: a Hydra-70 with the WGU-59/B guidance section and its four mid-body wings (1.87 m)"""
    p = partial(missile, bore=bore)(1.87, 0.035, ('olive_dark', 'white'), nose='ogive', seeker='glass',
                tails=(4, 0.0, 0.06, 0.02, 0.06, 0.07, 0.0), canards=(4, 1.10, 1.20, 1.14, 1.20, 0.05, 45.0),
                bands=((1.0, 0.22, 'off_white'),))
    return p


def s24():
    """S-24B 240 mm unguided rocket: 2.33 m, 235 kg (WT su_s_24_rocket), rail-hung"""
    p = missile(2.33, 0.12, ('ru_gray', 'ru_gray'), nose='ogive', origin='lug',
                tails=(4, 0.0, 0.35, 0.05, 0.35, 0.18, 45.0))
    return p


# ------------------------------------------------------------------------------------------------ racks
def m299(rails=4):
    """M299 four-rail (M272 in WT) and M279 two-rail Hellfire launchers: a spine under the pylon with arms to
    rails 0.40 m apart; the upper rails 0.13 m below the lugs, the lower pair 0.42 m lower (AGM-114 launcher
    drawings: rail length 1.4 m, missile fins 0.33 m span)."""
    m = Mesh()
    lugs(m, LUG_US)
    L0, L1 = -0.75, 0.62
    cbox((0, -0.12, -0.05), (0.14, 0.18, 1.30), 'olive', m)                 # spine under the pylon
    rows = [-0.21] if rails == 2 else [-0.21, -0.63]
    stations = []
    for i, y in enumerate(rows):
        if i == 1:
            cbox((0, (y + 0.21) / 2 - 0.15, -0.05), (0.08, abs(y + 0.21) + 0.12, 0.9), 'olive', m)
        cbox((0, y + 0.02, -0.06), (0.52, 0.06, 1.25), 'olive', m)           # cross arm
        for x in (0.20, -0.20):
            cbox((x, y - 0.025, -0.06), (0.05, 0.05, L1 - L0), 'olive_dark', m)  # rail
            stations.append((x, y - 0.05, 0.30))
    return Part(m, stations=stations, axis=(0, -0.3, 0), length=1.4)


def tube_pack(cols, rows, tube_r, tube_len, pitch_x, pitch_y, colour, lug=LUG_US, top=0.06, frame=True,
              z0=None, cap='dark', n=12):
    """a cluster of launch tubes (TOW, HOT, Shturm, Ataka, Vikhr, ATAS, Igla) hung from a top beam: the copies
    ride inside the tubes (stations on the tube axes, mid-length)"""
    m = Mesh()
    lugs(m, lug)
    z0 = -tube_len / 2 if z0 is None else z0
    z1 = z0 + tube_len
    width = (cols - 1) * pitch_x + 2 * tube_r
    if frame:
        cbox((0, -top / 2 - 0.035, (z0 + z1) / 2), (min(width, 0.16), top, tube_len * 0.7), colour, m)
    stations = []
    for r in range(rows):
        for c in range(cols):
            x = ((cols - 1) / 2 - c) * pitch_x
            y = -0.035 - top - tube_r - r * pitch_y
            tube(z0, z1, tube_r, tube_r * 0.86, colour, 'tube_inner', (x, y), n, m, depth=0.10)
            stations.append((x, y, (z0 + z1) / 2))
        if cols > 1:    # clamp bands over the tops of the row, fore and aft
            yb = -0.035 - top - r * pitch_y + 0.005
            cbox((0, yb, z0 + 0.12), (width - tube_r, 0.03, 0.06), colour, m)
            cbox((0, yb, z1 - 0.12), (width - tube_r, 0.03, 0.06), colour, m)
    if rows > 1:    # side plates tying the rows together, set back from the mouths
        for side in (1, -1):
            x = side * ((cols - 1) / 2 * pitch_x + tube_r * 0.7)
            cbox((x, -0.035 - top - tube_r - (rows - 1) * pitch_y / 2, (z0 + z1) / 2 - 0.1),
                 (0.03, (rows - 1) * pitch_y, tube_len * 0.6), colour, m)
    return Part(m, stations=stations, axis=stations[0], length=tube_len)


def tow_launcher(count):
    """M65 TOW launchers of the AH-1F: two tubes side by side (x2) or a 2 x 2 block (x4); tubes 0.22 m, 1.42 m"""
    return tube_pack(2, 1 if count == 2 else 2, 0.11, 1.42, 0.235, 0.235, 'olive')


def hot_launcher(count):
    """HOT 3 launchers of the Tiger: two tubes stacked (x2) or a 2 x 2 block (x4); tubes 0.18 m x 1.30 m"""
    if count == 2:
        return tube_pack(1, 2, 0.09, 1.30, 0.0, 0.19, 'fr_green')
    return tube_pack(2, 2, 0.09, 1.30, 0.20, 0.19, 'fr_green')


def atas(tiger=False):
    """ATAS twin Stinger launcher: two tubes side by side on a launcher beam with the coolant bottle"""
    p = tube_pack(2, 1, 0.045, 1.60, 0.10, 0.0, 'olive_dark' if not tiger else 'fr_green', top=0.06)
    cbox((0, -0.07, -0.70), (0.10, 0.06, 0.20), 'dark', p.mesh)
    return p


def igla_launcher(count):
    """9M39 Igla-V launchers: the twin launcher of the Ka-50 and the four-round block of the Mi-28N"""
    if count == 2:
        return tube_pack(2, 1, 0.045, 1.75, 0.11, 0.0, 'ru_green', lug=LUG_RU)
    return tube_pack(2, 2, 0.045, 1.75, 0.11, 0.11, 'ru_green', lug=LUG_RU)


def shturm_launcher():
    """9M114 launch tubes on the Mi-24V pylons: two transport-launch containers, 0.13 m (container 0.155 m),
    1.83 m, side by side under a beam"""
    return tube_pack(2, 1, 0.078, 1.83, 0.18, 0.0, 'ru_green', lug=LUG_RU)


def ataka_launcher(count):
    """9M120 Ataka launchers of the Mi-28N: a 2 x 2 block (x4) or two blocks one above the other (x8); containers
    0.155 m x 2.06 m"""
    if count == 4:
        return tube_pack(2, 2, 0.078, 2.06, 0.17, 0.17, 'ru_green', lug=LUG_RU)
    return tube_pack(2, 4, 0.078, 2.06, 0.17, 0.17, 'ru_green', lug=LUG_RU)


def vikhr_launcher():
    """APU-6 Vikhr launcher of the Ka-50: six 9M127 containers, 0.15 m x 2.9 m, in two rows of three"""
    return tube_pack(3, 2, 0.076, 2.9, 0.165, 0.165, 'ru_gray', lug=LUG_RU, top=0.08)


def falanga_rails():
    """9P124 twin Falanga rails of the Mi-24A/D wingtip: two launch rails one below the other on the endplate
    pylon (as hung under the tip in WT); the round is the existing falanga model"""
    m = Mesh()
    lugs(m, LUG_RU)
    cbox((0, -0.25, 0), (0.08, 0.45, 0.6), 'ru_green', m)
    stations = []
    for y in (-0.10, -0.48):
        cbox((0, y, 0.0), (0.12, 0.05, 1.0), 'ru_green_dark', m)
        stations.append((0.0, y - 0.025, 0.12))
    return Part(m, stations=stations, length=1.0)


def apu60(count):
    """APU-60-1 (one rail) and APU-60-2 (two rails, one each side of the pylon) for the R-60"""
    m = Mesh()
    lugs(m, LUG_RU)
    stations = []
    if count == 1:
        cbox((0, -0.07, 0.0), (0.10, 0.10, 1.8), 'ru_gray', m)
        stations.append((0.0, -0.12, 0.30))
    else:
        cbox((0, -0.07, 0.0), (0.12, 0.10, 1.6), 'ru_gray', m)
        cbox((0, -0.13, 0.0), (0.52, 0.04, 0.40), 'ru_gray', m)
        for x in (0.22, -0.22):
            cbox((x, -0.17, 0.0), (0.08, 0.08, 1.6), 'ru_gray', m)
            stations.append((x, -0.21, 0.30))
    return Part(m, stations=stations, length=1.8)


def apu68():
    """APU-68 rail of the S-24"""
    m = Mesh()
    lugs(m, LUG_RU)
    cbox((0, -0.06, 0.0), (0.10, 0.08, 2.0), 'ru_gray', m)
    return Part(m, stations=[(0.0, -0.10, 0.0)], length=2.0)


def trigat_launcher(count):
    """PARS 3 LR (Trigat LR) launchers of the Tiger: two rails side by side (x2) or a 2 x 2 rail block (x4)"""
    m = Mesh()
    lugs(m, LUG_US)
    rows = [-0.16] if count == 2 else [-0.16, -0.50]
    cbox((0, -0.10, 0.0), (0.12, 0.13, 1.30), 'fr_green', m)
    stations = []
    for i, y in enumerate(rows):
        if i == 1:
            cbox((0, -0.33, 0.0), (0.08, 0.36, 0.8), 'fr_green', m)
        cbox((0, y + 0.02, 0.0), (0.50, 0.05, 1.20), 'fr_green', m)
        for x in (0.19, -0.19):
            cbox((x, y - 0.02, 0.0), (0.05, 0.04, 1.35), 'dark', m)
            stations.append((x, y - 0.04, 0.0))
    return Part(m, stations=stations, length=1.35)


def ltc_dispenser():
    """AH-64D wingtip countermeasure dispenser fairing (the WT 'ltc' alternative to the ATAS)"""
    m = Mesh()
    lugs(m, LUG_US, h=0.02)
    lathe([(-0.45, 0.0), (-0.35, 0.06), (0.30, 0.06), (0.45, 0.0)], 'olive_dark', (0, -0.08), 10, m=m)
    for z in (-0.15, 0.05):
        cbox((0, -0.145, z), (0.08, 0.012, 0.14), 'black', m)
    return Part(m, axis=(0, -0.08, 0), length=0.9)
