#!/usr/bin/env python3
"""The icons of the M113A3 (m113a3): tusk_icon.py's renderer on the M113A3's geometry and texture.

    python3 tools/vehgen/m113a3_icon.py       (delete tools/icon_flags/originals/.../m113a3.png first when re-running,
                                               then tools/icon_flags/flags.py --write m113a3)
"""
import os

import tusk_icon

VID = 'm113a3'
tusk_icon.VID = VID
tusk_icon.OUT = os.path.join(tusk_icon.TEX, 'vehicle_icon', VID + '.png')
tusk_icon.OUT_ITEM = os.path.join(tusk_icon.TEX, 'item', 'vehicle_icons', VID + '.png')

if __name__ == '__main__':
    tusk_icon.main()
