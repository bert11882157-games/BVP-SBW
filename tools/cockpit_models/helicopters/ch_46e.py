"""CH-46E: the centre instrument panel between the two pilots' panels (engine and transmission dials), closing the
V between their inner wings, where the old textured centre panel was removed."""
import numpy as np

X, Y, Z = np.array([1.0, 0, 0]), np.array([0, 1.0, 0]), np.array([0, 0, 1.0])


def extra(ac, tubs, stations, mesh, c):
    t = tubs[0]
    s = t.s
    z = t.Z + 0.14                             # just behind the inner wings' outer edges
    y0, y1 = t.bot_c + 0.02, t.top_c - 0.02
    hw = 0.26
    yc = (y0 + y1) / 2
    mesh.box([0, yc, z + 0.015], (X, Y, Z), (hw, (y1 - y0) / 2, 0.015), c['panel'], faces={(2, -1): c['panel']})
    # glareshield lip over it
    mesh.box([0, y1 + 0.01, z - 0.02], (X, Y, Z), (hw + 0.01, 0.012, 0.05), c['coaming'])
    # three columns of engine / transmission dials (static)
    n = 3
    for k in range(n):
        y = y1 - 0.06 - k * (y1 - y0 - 0.08) / (n - 1)
        for xx in (-0.11, 0.0, 0.11):
            p = np.array([xx, y, z - 0.001])
            mesh.ring(p, [0, 0, -1], [0, 1, 0], 0.036, 0.044, 0.006, c['mgrey'])
            mesh.disc(p + [0, 0, -0.002], [0, 0, -1], [0, 1, 0], 0.036, c['black'])
            mesh.box(p + [0, 0.012, -0.004], (X, Y, Z), (0.003, 0.016, 0.001), c['white'])
