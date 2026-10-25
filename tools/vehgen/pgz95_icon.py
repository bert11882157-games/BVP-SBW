#!/usr/bin/env python3
"""The icons of the PGZ-95 (pgz_95): tusk_icon.py's renderer on the PGZ-95's geometry and texture.

    python3 tools/vehgen/pgz95_icon.py        (delete tools/icon_flags/originals/.../pgz_95.png first when re-running,
                                              then tools/icon_flags/flags.py --write pgz_95)
"""
import os

import tusk_icon

VID = 'pgz_95'
tusk_icon.VID = VID
tusk_icon.OUT = os.path.join(tusk_icon.TEX, 'vehicle_icon', VID + '.png')
tusk_icon.OUT_ITEM = os.path.join(tusk_icon.TEX, 'item', 'vehicle_icons', VID + '.png')

if __name__ == '__main__':
    tusk_icon.main()
