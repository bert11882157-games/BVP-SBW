#!/usr/bin/env python3
"""The icons of the Scud-B (9K72 TEL) (scud_b): tusk_icon.py's renderer on the Scud-B's geometry and texture.

    python3 tools/vehgen/scud_icon.py        (delete tools/icon_flags/originals/.../scud_b.png first when re-running,
                                                then tools/icon_flags/flags.py --write scud_b)
"""
import os

import tusk_icon

VID = 'scud_b'
tusk_icon.VID = VID
tusk_icon.OUT = os.path.join(tusk_icon.TEX, 'vehicle_icon', VID + '.png')
tusk_icon.OUT_ITEM = os.path.join(tusk_icon.TEX, 'item', 'vehicle_icons', VID + '.png')

if __name__ == '__main__':
    tusk_icon.main()
