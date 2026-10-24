#!/usr/bin/env python3
"""The icons of the GAZ Tigr (gaz_tigr): tusk_icon.py's renderer on the the Tigr's geometry and texture.

    python3 tools/vehgen/tigr_icon.py        (delete tools/icon_flags/originals/.../gaz_tigr.png first when re-running,
                                                then tools/icon_flags/flags.py --write gaz_tigr)
"""
import os

import tusk_icon

VID = 'gaz_tigr'
tusk_icon.VID = VID
tusk_icon.OUT = os.path.join(tusk_icon.TEX, 'vehicle_icon', VID + '.png')
tusk_icon.OUT_ITEM = os.path.join(tusk_icon.TEX, 'item', 'vehicle_icons', VID + '.png')

if __name__ == '__main__':
    tusk_icon.main()
