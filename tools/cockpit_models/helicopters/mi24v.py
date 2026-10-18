"""Mi-24V cockpit details: the operator's sight station in the front cockpit.

The Mi-24V operator (front seat) sits behind a big sighting unit in the middle of his panel: the KPS-53AV gun sight
and the Raduga-Sh missile guidance sight share the station; he looks into a hooded eyepiece and steers the sight (and
the nose turret) with the two grips of the handle unit under it. The pilot's ASP-17V sight is the driver's reflector
sight on the coaming (`gunsight: true`).

operator_sight() is shared with the Mi-24D (mi_24d.py) and, smaller, the Mi-24A (mi_24a.py).
"""
import numpy as np
import cockpit_lib as L

X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])


def operator_sight(mesh, ac, t, c, scale=1.0, collimator=True):
    """the operator's sight standing on the middle of his coaming: body, the eyepiece hood reaching back to him at
    eye level (clear of his line of sight to the panel), the collimator head on top, and the two grips of the handle
    unit on struts from the panel either side of the cyclic"""
    s, E = t.s * scale, t.E
    ex, ey, ez = E
    lip = L.Facet(t, 0).at(0, t.top_c)[2]                   # the top edge of the panel's centre facet
    z0, z1 = lip + 0.01 * s, lip + 0.16 * s                 # on the coaming ahead of it: hides none of the dials
    y0, y1 = t.top_c, ey - 0.02 * s
    mesh.box([ex, (y0 + y1) / 2, (z0 + z1) / 2], (X, Y, Z), (0.075 * s, (y1 - y0) / 2, (z1 - z0) / 2), c['dgrey'],
             faces={(2, -1): c['black']})
    # eyepiece hood (rubber) and its lens
    hz0 = ez + 0.40 * t.s
    hy = ey - 0.065 * s
    mesh.box([ex, hy, (hz0 + z0) / 2], (X, Y, Z), (0.038 * s, 0.03 * s, (z0 - hz0) / 2 + 0.005), c['black'])
    mesh.box([ex, hy, hz0 - 0.002], (X, Y, Z), (0.028 * s, 0.02 * s, 0.003), c['bezel'],
             faces={(2, -1): c['dgrey']})
    # collimator head on top of the body: a small box under the canopy with its glass plate
    if collimator:
        top = ey + 0.06 * s
        g = ac.glass_above(ex, (z0 + z1) / 2, y1)
        if g is not None:
            top = min(top, g - 0.04)
        if top > y1 + 0.03 * s:
            mesh.box([ex, (y1 + top) / 2, (z0 + z1) / 2 + 0.02 * s], (X, Y, Z),
                     (0.045 * s, (top - y1) / 2, (z1 - z0) / 2 - 0.03 * s), c['mgrey'], faces={(1, 1): c['black']})
    # the handle unit: two upright grips with their triggers, each on a strut from the panel face
    up = np.array([0, 0.94, -0.34])
    fw = np.cross(X, up)
    gy, gz = ey - 0.44 * t.s, ez + 0.40 * t.s
    for sd in (1, -1):
        gx = ex + sd * 0.20 * s
        mesh.box([gx, gy - 0.05 * s, (gz + t.Z) / 2], (X, Y, Z), (0.02 * s, 0.02 * s, (t.Z - gz) / 2), c['dgrey'])
        gc = np.array([gx, gy, gz])
        mesh.box(gc, (X, up, fw), (0.018 * s, 0.055 * s, 0.022 * s), c['black'])
        mesh.box(gc + up * 0.06 * s, (X, up, fw), (0.016 * s, 0.006 * s, 0.02 * s), c['mgrey'])
        mesh.box(gc + fw * 0.026 * s + up * 0.02 * s, (X, up, fw), (0.006 * s, 0.012 * s, 0.005 * s), c['red'])
        mesh.box(gc + up * 0.066 * s + fw * 0.008 * s, (X, up, fw), (0.006 * s, 0.004 * s, 0.006 * s), c['yellow'])


def extra(ac, tubs, stations, mesh, c):
    for st, t in zip(stations, tubs):
        if st['era'].startswith('HELO_GUNNER'):
            operator_sight(mesh, ac, t, c)
