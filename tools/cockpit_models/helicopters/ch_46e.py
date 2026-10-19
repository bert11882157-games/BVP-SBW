"""CH-46E: the centre instrument panel between the two pilots' panels, closing the V between their inner wings, where
the old textured centre panel was removed. The live parts of the real centre panel (caution panel, engine and
transmission dials) are on the two inner wings in the spec; this plate shows the rest of its grid of small 2 in
engine and transmission dials (ch_46e_01.jpg) where it shows above the wings' sloping tops and through the gap
between them."""
import numpy as np

X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])


def dial(mesh, c, p):
    """a static 2 in dial facing aft: bezel ring, black face, one white needle"""
    mesh.ring(p, [0, 0, -1], [0, 1, 0], 0.024, 0.029, 0.005, c['dgrey'])
    mesh.disc(p + [0, 0, -0.002], [0, 0, -1], [0, 1, 0], 0.024, c['black'])
    mesh.box(p + [0.004, 0.006, -0.004], (X, Y, Z), (0.0015, 0.011, 0.001), c['white'])


def extra(ac, tubs, stations, mesh, c):
    t = tubs[0]
    z = t.Z + 0.14                             # just behind the inner wings' outer edges
    y0, y1 = t.bot_c + 0.02, t.top_c - 0.02
    hw = 0.26
    yc = (y0 + y1) / 2
    mesh.box([0, yc, z + 0.015], (X, Y, Z), (hw, (y1 - y0) / 2, 0.015), c['panel'], faces={(2, -1): c['panel']})
    # glareshield lip over it
    mesh.box([0, y1 + 0.01, z - 0.02], (X, Y, Z), (hw + 0.01, 0.012, 0.05), c['coaming'])
    # the two top rows of the dial grid across the plate (seen over the wings' tops) ...
    for k in range(2):
        y = y1 - 0.04 - k * 0.062
        for i in range(8):
            dial(mesh, c, np.array([-0.217 + i * 0.062, y, z - 0.001]))
    # ... and the middle columns further down, seen through the gap between the wings
    for k in range(2, 6):
        y = y1 - 0.04 - k * 0.062
        if y < y0 + 0.04:
            break
        for x in (-0.031, 0.031):
            dial(mesh, c, np.array([x, y, z - 0.001]))
