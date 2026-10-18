"""AH-64D cockpit details: the CPG's TEDAC (TADS Electronic Display and Control).

The Longbow's copilot/gunner looks at the TEDAC in the middle of his panel (it replaced the ORT and its eyepiece hood):
a display under a sunshade with a hand grip either side, the left one for the sensor, the right one for the weapons.
The TEDAC display is the CPG panel's centre-line display (helicopters/ah_64d.json); this adds its shade and the two
grips under it.
"""
import math
import numpy as np
import cockpit_lib as L

X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])


def tedac(mesh, t, c, item):
    """item: the TEDAC display's [kind, a, v, width] in the station's centre panel (real metres)"""
    s, E = t.s, t.E
    f = L.Facet(t, 0)
    a, v, w = item[1] * s, item[2] * s, item[3] * s
    dy = f.top(a) - v * math.cos(t.lean)          # the display's middle
    p = f.at(a, dy)
    hw = 0.7 * w + 0.006 * s                      # just outside the display housing (1.4 x the screen)
    # sunshade: top plate and cheeks standing proud of the panel round the display
    for sd in (1, -1):
        q = p + f.R * sd * hw
        mesh.box(q + f.N * 0.035 * s, (f.R, f.U, f.N), (0.004 * s, hw, 0.035 * s), c['black'])
    mesh.box(p + f.U * hw + f.N * 0.04 * s, (f.R, f.U, f.N), (hw + 0.004 * s, 0.004 * s, 0.04 * s), c['black'])
    # the two hand grips on stubs from the panel, below and outboard of the display, leaning back to the CPG
    up = L.unit([0, 0.9, -0.42])
    fw = np.cross(X, up)
    for sd in (1, -1):
        base = f.at(a + sd * 0.07 * s, dy - hw - 0.045 * s)   # under the display, inboard of the MPDs
        gc = base + f.N * 0.07 * s
        mesh.box((base + gc) / 2, (f.R, f.U, f.N), (0.016 * s, 0.016 * s, 0.035 * s), c['dgrey'])
        mesh.box(gc + up * 0.04 * s, (X, up, fw), (0.017 * s, 0.05 * s, 0.021 * s), c['black'])
        mesh.box(gc + up * 0.095 * s, (X, up, fw), (0.015 * s, 0.006 * s, 0.019 * s), c['mgrey'])
        mesh.box(gc + up * 0.06 * s + fw * 0.024 * s, (X, up, fw), (0.005 * s, 0.011 * s, 0.005 * s), c['red'])
        mesh.box(gc + up * 0.10 * s - fw * 0.006 * s, (X, up, fw), (0.006 * s, 0.004 * s, 0.006 * s), c['yellow'])


def extra(ac, tubs, stations, mesh, c):
    for st, t in zip(stations, tubs):
        if st['seat'] == 1:
            # the TEDAC is the centre panel's display on the centre line
            item = next(it for it in st['panel']['centre'] if it[0].startswith('D:') and abs(it[1]) < 0.05)
            tedac(mesh, t, c, item)
