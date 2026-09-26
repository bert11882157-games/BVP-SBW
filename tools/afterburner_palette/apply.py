#!/usr/bin/env python3
"""Per-engine afterburner flame colours ("Palette" in AfterburnerPresentation), written into the vehicle JSONs.

Flame colour comes from two light sources: incandescent soot (yellow-orange-red, strongest in rich, older
afterburners) and chemiluminescence of the combustion radicals (blue-violet, visible in lean, well-mixed modern
reheat, especially at night). Photographs vary with light and camera, so these are families, not measurements:

  EARLY   1950s turbojets with simple spray-bar reheat (J40, J47, J57, VK-1F, RD-9, Atar 101, Avon, AL-7F):
          sooty orange-red, dim core, weak diamonds.
  TURBOJET  1960s turbojets and early reheat turbofans (J79, J85, Atar 9, R-25, R-35, AL-21F, TF30, RM8):
          bright yellow-orange with strong yellow-white diamonds.
  MIXED   1970s-90s turbofans (F100, F110, F101, F404/F414, RB199, EJ200, M88, RD-33): white-pink core,
          salmon-pink body going orange at the tail, pink-violet diamonds at night.
  BLUE    Lyulka/Saturn AL-31F / 117S / AL-41F1 and the NK-25: blue-violet body with a pale core and an
          orange-pink fringe toward the tail.

Idempotent: an existing Palette line is replaced. Usage: python3 tools/afterburner_palette/apply.py
"""
import json, os, re

HERE = os.path.dirname(os.path.abspath(__file__))
VEHICLES = os.path.join(HERE, '../../bvp/src/generated/resources/assets/berts_vehicle_pack/sbw/vehicles')

PALETTES = {
    'EARLY':    {'Core': [1.0, 0.82, 0.52], 'Flame': [1.0, 0.50, 0.16], 'Tail': [0.88, 0.24, 0.05], 'Diamonds': [1.0, 0.66, 0.36]},
    'TURBOJET': {'Core': [1.0, 0.93, 0.72], 'Flame': [1.0, 0.68, 0.26], 'Tail': [0.96, 0.40, 0.10], 'Diamonds': [1.0, 0.86, 0.56]},
    'MIXED':    {'Core': [1.0, 0.92, 0.94], 'Flame': [1.0, 0.56, 0.52], 'Tail': [1.0, 0.48, 0.20], 'Diamonds': [1.0, 0.72, 0.86]},
    'BLUE':     {'Core': [0.88, 0.92, 1.0], 'Flame': [0.52, 0.52, 1.0], 'Tail': [0.92, 0.46, 0.42], 'Diamonds': [0.80, 0.76, 1.0]},
}

ENGINES = {
    'f3h': 'EARLY',              # J40 / J71
    'f_86k': 'EARLY',            # J47-GE-17B
    'f_100c': 'EARLY',           # J57-P-21
    'f_8h': 'EARLY',             # J57-P-20A
    'j_5': 'EARLY',              # WP-5 (VK-1F)
    'mig19': 'EARLY',            # RD-9B
    'mig_19s': 'EARLY',          # RD-9B
    'super_mystere': 'EARLY',    # Atar 101G
    'saab_32_lansen': 'EARLY',   # RM6A (Avon)
    'saab_35_draken': 'EARLY',   # RM6C (Avon 300)
    'su_9': 'EARLY',             # AL-7F-1
    'f_104g': 'TURBOJET',        # J79-GE-11A
    'f_4c': 'TURBOJET',          # J79-GE-15
    'f_5a': 'TURBOJET',          # J85-GE-13
    'mirage_5': 'TURBOJET',      # Atar 9C
    'mirage_f1': 'TURBOJET',     # Atar 9K-50
    'mig_21bis': 'TURBOJET',     # R-25-300
    'mig_23mld': 'TURBOJET',     # R-35-300
    'f_14a': 'TURBOJET',         # TF30-P-414A
    'f_111f': 'TURBOJET',        # TF30-P-100
    'saab_37_viggen': 'TURBOJET',  # RM8A/B (JT8D reheat)
    'f_15c': 'MIXED',            # F100-PW-220
    'f_16c': 'MIXED',            # F110-GE-129 / F100-PW-229
    'f_14d': 'MIXED',            # F110-GE-400
    'fa_18e': 'MIXED',           # F414-GE-400
    'saab_jas_39_gripen': 'MIXED',  # RM12 (F404)
    'eurofighter_typhoon': 'MIXED',  # EJ200
    'rafale': 'MIXED',           # M88-2
    'panavia_tornado_ids_marineflieger': 'MIXED',  # RB199
    'mig_29': 'MIXED',           # RD-33
    'b_1b': 'MIXED',             # F101-GE-102
    'su_27': 'BLUE',             # AL-31F
    'j_11a': 'BLUE',             # AL-31F
    'su_35': 'BLUE',             # AL-41F1S (117S)
    'su_57': 'BLUE',             # AL-41F1 (izd. 117)
    'tu_22m': 'BLUE',            # NK-25
}


def main():
    changed = 0
    for name, family in sorted(ENGINES.items()):
        path = os.path.join(VEHICLES, name + '.json')
        text = open(path, encoding='utf-8').read()
        head = re.search(r'\n( *)"AfterburnerPresentation": \{\n', text)
        if not head:
            raise SystemExit(f'{name}: no AfterburnerPresentation')
        indent = head.group(1) + '  '
        while True:   # drop every existing Palette (one line, or spread over lines by another tool's rewrite)
            m = re.search(r'\n *"Palette": \{', text)
            if not m:
                break
            depth, i = 0, m.end() - 1
            while True:
                depth += {'{': 1, '}': -1}.get(text[i], 0)
                if depth == 0:
                    break
                i += 1
            end = i + 1 + (text[i + 1] == ',')
            text = text[:m.start()] + text[end:]
        head = re.search(r'\n( *)"AfterburnerPresentation": \{\n', text)
        line = f'{indent}"Palette": {json.dumps(PALETTES[family], separators=(", ", ": "))},\n'
        text = text[:head.end()] + line + text[head.end():]
        json.loads(text)
        open(path, 'w', encoding='utf-8').write(text)
        changed += 1
    print(f'{changed} vehicles')


if __name__ == '__main__':
    main()
