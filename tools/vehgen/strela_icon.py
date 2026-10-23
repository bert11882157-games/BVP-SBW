#!/usr/bin/env python3
"""The icons of the 9K35 Strela-10M2 (strela_10m2): tusk_icon.py's renderer on the Strela's geometry and texture.

    python3 tools/vehgen/strela_icon.py        (delete tools/icon_flags/originals/.../strela_10m2.png first when re-running,
                                                then tools/icon_flags/flags.py --write strela_10m2)
"""
import os

import tusk_icon

VID = 'strela_10m2'
tusk_icon.VID = VID
tusk_icon.OUT = os.path.join(tusk_icon.TEX, 'vehicle_icon', VID + '.png')
tusk_icon.OUT_ITEM = os.path.join(tusk_icon.TEX, 'item', 'vehicle_icons', VID + '.png')

if __name__ == '__main__':
    tusk_icon.main()
