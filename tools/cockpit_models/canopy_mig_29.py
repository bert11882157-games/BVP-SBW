#!/usr/bin/env python3
"""MiG-29 canopy, made by hand: the model had no canopy glass, only a flat roof strip and a windscreen arch piece,
so the shape is set against its sill rails, that roof line and the coaming (sections.py), and the real frame layout.

The MiG-29 canopy (side views and photos of the type): a one-piece windscreen with no posts, held by its base frame on
the nose and its arch at the rear; the canopy one blown transparency hinged at the back, with no bow across it, held
by its frame (the front bow against the windscreen arch, the sill rails, the tail frame where it sits on the spine fairing's front).

Run after the cockpit (mig_29.py), on its output. Usage: python3 tools/cockpit_models/canopy_mig_29.py
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import handbuilt as H            # noqa: E402
import handbuilt_canopy as C     # noqa: E402

VID = 'mig_29'
SOURCE_BONE = 'wreck_fuselage_1__hull'

# the canopy's and windscreen's sections, metres from the eye: z, then the right half from the sill up over the top
SECTIONS = [
    (-0.92, [(0.335, -0.20), (0.335, -0.12), (0.27, -0.03), (0.18, 0.02), (0.08, 0.05), (0.0, 0.06)]),   # 2 cm clear of the spine fairing's front
    (-0.80, [(0.33, -0.24), (0.33, -0.10), (0.29, 0.01), (0.22, 0.08), (0.12, 0.115), (0.0, 0.13)]),
    (-0.60, [(0.36, -0.25), (0.355, -0.08), (0.33, 0.04), (0.27, 0.13), (0.16, 0.19), (0.0, 0.205)]),
    (-0.40, [(0.37, -0.25), (0.365, -0.06), (0.34, 0.07), (0.28, 0.17), (0.17, 0.235), (0.0, 0.255)]),
    (-0.20, [(0.37, -0.25), (0.365, -0.05), (0.34, 0.08), (0.285, 0.18), (0.175, 0.25), (0.0, 0.27)]),
    (0.20, [(0.37, -0.25), (0.365, -0.05), (0.34, 0.08), (0.285, 0.18), (0.175, 0.25), (0.0, 0.27)]),
    (0.40, [(0.37, -0.25), (0.36, -0.06), (0.335, 0.07), (0.28, 0.165), (0.17, 0.225), (0.0, 0.245)]),
    (0.60, [(0.37, -0.26), (0.355, -0.08), (0.325, 0.04), (0.26, 0.12), (0.15, 0.17), (0.0, 0.18)]),
    (0.76, [(0.36, -0.28), (0.34, -0.12), (0.30, -0.02), (0.23, 0.05), (0.13, 0.085), (0.0, 0.095)]),
    (0.80, [(0.355, -0.30), (0.335, -0.14), (0.29, -0.04), (0.22, 0.03), (0.12, 0.06), (0.0, 0.07)]),
    (1.05, [(0.335, -0.44), (0.32, -0.32), (0.27, -0.23), (0.19, -0.17), (0.10, -0.15), (0.0, -0.145)]),
]
SPLIT = 0.76                     # the canopy's front edge, against the windscreen arch


def build(atlas, E, skin):
    m = H.Mesh(atlas)
    out, inner = atlas.swatch(skin), atlas.swatch((40, 42, 44))
    sh = C.Shape(SECTIONS)
    glass = C.glass_triangles(sh, E, -0.92, SPLIT, nz=20) + C.glass_triangles(sh, E, SPLIT + 0.045, 1.05, nz=6)
    # the canopy: its tail frame, its front bow, the sill rails
    C.bow(m, sh, E, -0.90, 0.035, 0.025, out, inner)
    C.bow(m, sh, E, SPLIT - 0.005, 0.03, 0.018, out, inner)
    C.member(m, sh, E, 0.0, -0.92, SPLIT, 0.045, 0.035, out, inner, steps=20)
    # the windscreen: the arch, the base frame on the nose and its side rails
    C.bow(m, sh, E, SPLIT + 0.027, 0.04, 0.022, out, inner)
    C.bow(m, sh, E, 1.035, 0.03, 0.02, out, inner)
    C.member(m, sh, E, 0.0, SPLIT + 0.045, 1.05, 0.045, 0.035, out, inner, steps=6)
    return m, glass


if __name__ == '__main__':
    C.run(VID, SOURCE_BONE, build, sys.argv[1:], colour=(128, 134, 140),   # the airframe grey
          clear=(C.Shape(SECTIONS), 0.30, 1.20, 0.30))                       # the old arch and side plates (not the spine fairing)
