"""AH-64D cockpit details: the CPG's TEDAC (TADS Electronic Display and Control).

The Longbow's copilot/gunner looks at the TEDAC in the middle of his panel (it replaced the ORT and its eyepiece hood):
a display under a sunshade with a hand grip either side, the left one for the sensor, the right one for the weapons.
The TEDAC display is the CPG panel's centre-line display (helicopters/ah_64d.json); this adds its shade and the two
grips under it, hung on brackets under the panel's bottom edge.
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
    ax = (f.R, f.U, f.N)
    hw = (0.5 + L.DISPLAY_BEZEL) * w + 0.003      # just outside the display housing (1.4 x the screen)
    # sunshade: cheeks either side standing proud of the panel (in the narrow gaps to the MPD housings) and a top
    # plate over the display
    for sd in (1, -1):
        mesh.box(p + f.R * sd * hw + f.N * 0.035 * s, ax, (0.003, hw, 0.035 * s), c['black'])
    mesh.box(p + f.U * (hw + 0.003) + f.N * 0.04 * s, ax, (hw + 0.003, 0.003, 0.04 * s), c['black'])
    # the two hand grips on brackets under the panel's bottom edge, either side of the switch panels under the
    # display and inboard of the consoles, leaning back to the CPG
    up = L.unit([0, 0.9, -0.42])
    fw = np.cross(X, up)
    for sd in (1, -1):
        edge = f.at(a + sd * 0.33 * s, f.bottom())
        # the bracket: its top face against the panel's bottom edge, from the back of the panel out to the grip
        mesh.box(edge + f.N * 0.025 * s - f.U * 0.016 * s, ax, (0.016 * s, 0.016 * s, 0.045 * s), c['dgrey'])
        gc = edge + f.N * 0.06 * s - f.U * 0.01 * s
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
