"""Second detail pass over a modelled cockpit (cockpit_lib.build's result), from the same tub measurements:

  * displays: a row of option-select push buttons along each side of every multi-function display (the bezel
    buttons pilots press), and a rocker pair at its top corners;
  * consoles: each switch block gets a legend strip, rotary knobs (cap and skirt) and toggles in guards; the aft end
    of each console a row of circuit breakers;
  * ejection seat: guide rails up the bulkhead behind it, the shoulder and lap harness, the side handles (yellow and
    black), the drogue-gun / parachute box on the headrest, the oxygen and comms hose to the right console;
  * bucket seat: harness straps over the armour plate;
  * walls and sills: a grab handle on each canopy sill, the canopy lock handle on the left, a map case on the right
    wall;
  * floor: heel plates under the pedals; the rear bulkhead: two structure ribs.
Colours come from the palette's uniform texel blocks (cockpit_lib.Swatches), as the base cockpit's do.
"""
import math
import numpy as np
import cockpit_lib as L

X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])


def colours(sw, pal_name):
    c = {k: sw.uv(v) for k, v in L.PALETTES[pal_name].items()}
    c.update({k: sw.uv(v) for k, v in L.ACCENT.items()})
    return c


def add(m, t, c, era_name, displays):
    era = L.ERAS[era_name]
    s, E = t.s, t.E
    n0 = len(m.faces)
    for d in displays:
        if d.get('Seat', 0) == t.seat:
            osb(m, d, c, s)
    xin = 0.58 * t.W
    zb = t.back_z + 0.1 * s
    for side in (1, -1):
        console(m, t, c, side, xin, zb)
        sill(m, t, c, side)
    if era['seat'] == 'ejection':
        ejection_detail(m, t, c)
    else:
        bucket_detail(m, t, c)
    # map case on the right wall, behind the console's mid point
    xw = E[0] - t.W
    m.box([xw + 0.012, (t.console_y + t.sill) / 2 - 0.02, E[2] - 0.18 * s], (X, Y, Z),
          (0.012, 0.05 * s, 0.09 * s), c['dgrey'])
    # heel plates and the rear bulkhead's ribs
    for sg in (1, -1):
        m.box([E[0] + sg * 0.12 * s, t.floor_y + 0.004, t.front_z - 0.22 * s], (X, Y, Z),
              (0.05 * s, 0.004, 0.07 * s), c['metal'])
        m.box([E[0] + sg * 0.28 * t.W, (t.console_y + t.sill) / 2, t.back_z + 0.012], (X, Y, Z),
              (0.012, (t.sill - t.console_y) / 2, 0.012), c['shade'])
    return len(m.faces) - n0


def osb(m, d, c, s):
    """option-select buttons round a display: 5 per side, rockers at the top corners"""
    centre, n, up = (np.array(d[k], float) for k in ('Center', 'Normal', 'Up'))
    n = L.unit(n); up = L.unit(up - n * (up @ n)); r = np.cross(up, n)
    w = float(d['Width'])
    bez = w / 2 + 0.012                        # just outside the screen, on the housing face
    lift = 0.006
    bw = min(0.012, w * 0.09)
    for k in range(5):
        a = (k - 2) * w * 0.18
        for p, ax in ((centre + up * bez + r * a, r), (centre - up * bez + r * a, r),
                      (centre + r * bez + up * a, up), (centre - r * bez + up * a, up)):
            m.box(p + n * lift, (r, up, n), (bw / 2 if ax is r else 0.004, 0.004 if ax is r else bw / 2, 0.003),
                  c['mgrey'])
    for sg in (1, -1):
        m.box(centre + up * (bez + 0.004) + r * sg * (w / 2 + 0.006) + n * lift, (r, up, n), (0.006, 0.006, 0.003),
              c['lgrey'])


def console(m, t, c, side, xin, zb):
    """legend strips, rotary knobs and guarded toggles on each switch block; circuit breakers at the aft end"""
    s, E = t.s, t.E
    xw = E[0] + t.W * side
    xi = E[0] + xin * side
    zf = E[2] + 0.30 * s                       # the consoles run forward to about the throttle / stick
    L_ = zf - zb
    if L_ < 0.1:
        return
    y = t.console_y + 0.008
    wx = abs(xw - xi) - 0.02 * s
    n_blocks = max(3, int(L_ / (0.11 * s)))
    for k in range(n_blocks):
        zc = zb + (k + 0.5) * L_ / n_blocks
        hz = L_ / n_blocks / 2 - 0.006 * s
        # legend strip along the block's outboard edge
        m.box([xw - side * 0.016 * s, y + 0.001, zc], (X, Y, Z), (0.006 * s, 0.001, hz * 0.9), c['lgrey'])
        for j in range(2):
            kx = xi + side * (0.03 * s + j * (wx - 0.05 * s) / 2)
            if (k + j) % 2 == 0:
                # rotary knob: a skirt and a smaller cap
                m.box([kx, y + 0.006 * s, zc - hz * 0.3], (X, Y, Z), (0.012 * s, 0.004 * s, 0.012 * s), c['dgrey'])
                m.box([kx, y + 0.014 * s, zc - hz * 0.3], (X, Y, Z), (0.007 * s, 0.006 * s, 0.007 * s), c['black'])
                m.box([kx, y + 0.021 * s, zc - hz * 0.3 + 0.004 * s], (X, Y, Z), (0.002 * s, 0.001, 0.004 * s),
                      c['lgrey'])                                          # pointer line
            else:
                # a toggle in a guard (two cheeks)
                for g in (-1, 1):
                    m.box([kx + g * 0.009 * s, y + 0.008 * s, zc + hz * 0.3], (X, Y, Z),
                          (0.002 * s, 0.008 * s, 0.01 * s), c['red'] if k == 0 else c['dgrey'])
                m.box([kx, y + 0.012 * s, zc + hz * 0.3], (X, L.unit([0, 1, 0.5]), L.unit([0, -0.5, 1])),
                      (0.0025 * s, 0.01 * s, 0.0025 * s), c['lgrey'])
    # circuit breakers: a dark strip of small round heads at the aft end, against the wall
    cz = zb + 0.02 * s
    m.box([(xi + xw) / 2, y + 0.002, cz], (X, Y, Z), (wx / 2, 0.002, 0.012 * s), c['black'])
    n_cb = max(4, int(wx / (0.016 * s)))
    for j in range(n_cb):
        bx = xi + side * (0.01 * s + (j + 0.5) * (wx - 0.02 * s) / n_cb)
        m.box([bx, y + 0.006, cz], (X, Y, Z), (0.004 * s, 0.004, 0.004 * s), c['lgrey'] if j % 3 else c['dgrey'])


def sill(m, t, c, side):
    """a grab handle on the sill, and the canopy lock handle on the left one"""
    s, E = t.s, t.E
    xw = E[0] + side * (t.W - 0.02)
    y = t.sill + 0.03
    z0, z1 = E[2] + 0.15 * s, E[2] + 0.35 * s
    for z in (z0, z1):
        m.box([xw, y - 0.01, z], (X, Y, Z), (0.006, 0.014, 0.006), c['metal'])
    m.box([xw, y + 0.006, (z0 + z1) / 2], (X, Y, Z), (0.006, 0.006, (z1 - z0) / 2 + 0.006), c['metal'])
    if side > 0:
        hz = E[2] - 0.20 * s
        m.box([xw, y - 0.004, hz], (X, Y, Z), (0.01, 0.01, 0.03 * s), c['dgrey'])
        m.box([xw - 0.012, y + 0.006, hz + 0.02 * s], (X, L.unit([0, 1, 0.6]), L.unit([0, -0.6, 1])),
              (0.006, 0.035 * s, 0.008), c['yellow'])


def ejection_detail(m, t, c):
    s, E = t.s, t.E
    ax = (X, L.unit([0, 1, -0.14]), L.unit([0, 0.14, 1]))           # the seat's 8 degree recline
    pan_y = E[1] - 0.85 * s
    # guide rails up the bulkhead behind the seat
    for sg in (1, -1):
        m.box([E[0] + sg * 0.12 * s, E[1] - 0.25 * s, E[2] - 0.43 * s], ax, (0.012 * s, 0.55 * s, 0.012 * s),
              c['metal'])
    # shoulder harness from the headrest over the shoulders to the lap, and the lap belt
    for sg in (1, -1):
        top = np.array([E[0] + sg * 0.07 * s, E[1] - 0.10 * s, E[2] - 0.19 * s])
        low = np.array([E[0] + sg * 0.05 * s, pan_y + 0.10 * s, E[2] + 0.02 * s])
        d = low - top; ln = np.linalg.norm(d); d /= ln
        ax2 = L.unit(np.cross(d, X)); ax1 = np.cross(ax2, d)
        m.box((top + low) / 2 + np.array([0, 0, 0.02 * s]), (ax1, d, ax2), (0.022 * s, ln / 2, 0.004), c['dgrey'])
        m.box([E[0] + sg * 0.11 * s, pan_y + 0.05 * s, E[2] + 0.06 * s], (X, Y, Z),
              (0.06 * s, 0.018 * s, 0.006), c['dgrey'])
    m.box([E[0], pan_y + 0.07 * s, E[2] + 0.07 * s], (X, Y, Z), (0.03 * s, 0.025 * s, 0.008), c['metal'])  # buckle
    # side handles on the seat bucket (yellow with black stripes)
    for sg in (1, -1):
        hx = E[0] + sg * 0.225 * s
        m.box([hx, pan_y + 0.10 * s, E[2] + 0.12 * s], (X, Y, Z), (0.008 * s, 0.03 * s, 0.05 * s), c['yellow'])
        m.box([hx, pan_y + 0.10 * s, E[2] + 0.12 * s], (X, Y, Z), (0.0085 * s, 0.006 * s, 0.051 * s), c['black'])
    # the parachute / drogue box topping the headrest
    m.box([E[0], E[1] + 0.13 * s, E[2] - 0.33 * s], (X, Y, Z), (0.13 * s, 0.035 * s, 0.07 * s), c['dgrey'])
    m.box([E[0], E[1] + 0.168 * s, E[2] - 0.30 * s], (X, Y, Z), (0.03 * s, 0.006 * s, 0.03 * s), c['red'])
    # oxygen / comms hose from the seat's right side to the right console
    a = np.array([E[0] - 0.20 * s, pan_y + 0.06 * s, E[2] - 0.05 * s])
    b = np.array([E[0] - 0.62 * t.W, t.console_y + 0.02, E[2] - 0.10 * s])
    for k in range(4):
        p0 = a + (b - a) * k / 4 - np.array([0, 0.03 * s * math.sin(math.pi * k / 4), 0])
        p1 = a + (b - a) * (k + 1) / 4 - np.array([0, 0.03 * s * math.sin(math.pi * (k + 1) / 4), 0])
        d = p1 - p0; ln = np.linalg.norm(d)
        if ln < 1e-4:
            continue
        d /= ln; ax2 = L.unit(np.cross(d, Y)); ax1 = np.cross(ax2, d)
        m.box((p0 + p1) / 2, (d, ax1, ax2), (ln / 2, 0.008 * s, 0.008 * s), c['green'])


def bucket_detail(m, t, c):
    s, E = t.s, t.E
    ax = (X, L.unit([0, 1, -0.2]), L.unit([0, 0.2, 1]))
    for sg in (1, -1):
        m.box([E[0] + sg * 0.06 * s, E[1] - 0.36 * s, E[2] - 0.185 * s], ax, (0.02 * s, 0.26 * s, 0.004),
              c['cushion'])
