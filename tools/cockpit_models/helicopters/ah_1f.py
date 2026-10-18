"""AH-1F cockpit details: the gunner's telescopic sight unit (TSU).

The front-seat gunner of the AH-1F looks into the M65 TOW / TSU sight in the middle of his panel: a big body on the
coaming, a rubber eyepiece hood reaching back to his face at eye level, and under it the sight hand control, a
handle with a grip either side (sight slew, TOW/laser triggers). The pilot's head-up display is the driver's `hud`.

gunner_sight() is shared with the AH-1G (ah_1g_cobra.py), whose gunner had the smaller XM28 pantograph-mounted reflex
sight with its two handgrips instead (hood=False).
"""
import numpy as np
import cockpit_lib as L

X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])


def gunner_sight(mesh, ac, t, c, scale=1.0, hood=True):
    """the sight body on the middle of the gunner's coaming, the eyepiece hood back toward his eye (below his line of
    sight over it), and the hand control: a cross bar under the body with an upright grip at each end"""
    s, E = t.s * scale, t.E
    ex, ey, ez = E
    # the body stands on the coaming just ahead of the panel's top edge, so it hides none of the centre dials;
    # under a windscreen sloping down close ahead it comes back over the panel until it is tall enough
    lip = L.Facet(t, 0).at(0, t.top_c)[2]
    y0 = t.top_c - 0.03 * t.s
    for back in np.arange(0, 0.25 * s, 0.02):
        z0, z1 = lip + 0.01 * s - back, lip + 0.22 * s - back
        y1 = min(ey - 0.07 * s, t.top_c + 0.16 * s)
        for zz in np.linspace(z0, z1, 5):                   # under the windscreen sloping down ahead
            g = ac.glass_above(ex, zz, y0)
            if g is not None:
                y1 = min(y1, g - 0.03)
        if y1 - y0 >= (0.12 if hood else 0.05) * s:          # the reflex sight is a small one
            break
    mesh.box([ex, (y0 + y1) / 2, (z0 + z1) / 2], (X, Y, Z), (0.07 * s, (y1 - y0) / 2, (z1 - z0) / 2), c['dgrey'],
             faces={(2, -1): c['black']})
    if hood:
        hz0 = ez + 0.36 * t.s
        hy = y1 - 0.04 * s
        mesh.box([ex, hy, (hz0 + z0) / 2], (X, Y, Z), (0.045 * s, 0.035 * s, (z0 - hz0) / 2 + 0.005), c['black'])
        mesh.box([ex, hy, hz0 - 0.002], (X, Y, Z), (0.03 * s, 0.022 * s, 0.003), c['bezel'],
                 faces={(2, -1): c['dgrey']})
    else:
        # reflex sight: a glass plate on top of the body
        gy = y1 + 0.005
        mesh.box([ex, gy + 0.035 * s, z0 + 0.03 * s], (X, Y, Z), (0.03 * s, 0.035 * s, 0.003), c['mgrey'],
                 faces={(2, -1): c['black'], (2, 1): c['black']})
    # the hand control under the sight
    up = np.array([0, 0.94, -0.34])
    fw = np.cross(X, up)
    gy, gz = ey - 0.40 * t.s, ez + 0.36 * t.s
    mesh.box([ex, gy - 0.05 * s, (gz + t.Z) / 2], (X, Y, Z), (0.02 * s, 0.02 * s, (t.Z - gz) / 2 + 0.01), c['dgrey'])
    mesh.box([ex, gy - 0.05 * s, gz], (X, Y, Z), (0.17 * s, 0.015 * s, 0.018 * s), c['dgrey'])
    for sd in (1, -1):
        gc = np.array([ex + sd * 0.17 * s, gy, gz])
        mesh.box(gc, (X, up, fw), (0.018 * s, 0.05 * s, 0.022 * s), c['black'])
        mesh.box(gc + up * 0.055 * s, (X, up, fw), (0.016 * s, 0.006 * s, 0.02 * s), c['mgrey'])
        mesh.box(gc + fw * 0.026 * s + up * 0.015 * s, (X, up, fw), (0.006 * s, 0.012 * s, 0.005 * s), c['red'])


def extra(ac, tubs, stations, mesh, c):
    for st, t in zip(stations, tubs):
        if st['era'].startswith('HELO_GUNNER'):
            gunner_sight(mesh, ac, t, c)
