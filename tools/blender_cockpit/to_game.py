#!/usr/bin/env python3
"""The Blender cockpit structure into the game: geo, vehicle asset and textures.

Reads STRUCTDIR/game_export.json (written by game_export.py at the end of cockpit_build.py / f16c_structure.py) and
the aircraft's committed files, then:
  * drops the model faces the cuts removed (the strict cut of export_obj.py --cut and the generator's interior cut):
    a face stays only if a face with the same centroid is still in the Blender model;
  * builds the cockpit bone wreck_<piece>__cockpit (parent hull) from the cockpit's triangles: every colour gets an
    exact 4 x 4 swatch and every painted tile (switch panels, breakers, caution panel, panel plates) its own tile in
    the texture's free rectangles (authored.lib.Atlas), written into the texture and the wreck texture;
  * the stick goes into the aircraft's animated stick bone (pivot at the new stick's foot) where it has a stick rig,
    and stays in the cockpit bone otherwise;
  * CockpitGauges (live dials and annunciator panels), FlightDisplays (MFDs and radar scopes) and HeadUpDisplay are
    written from the instruments' fitted positions; the parts the game draws itself (dial faces and bezels, display
    housings and option keys) are left out of the mesh;
  * the HUD combiner and gunsight glass join CanopyGlass.

Usage: python3 tools/blender_cockpit/to_game.py VID STRUCTDIR [--dry-run]
"""
import json, math, os, re, sys
import numpy as np
from PIL import ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'cockpit_models'))
sys.path.insert(0, os.path.join(HERE, '..', 'cockpit_models', 'authored'))
import cockpit_lib as L  # noqa: E402
import lib as A          # noqa: E402

LIVE = [  # (name pattern, gauge kind), first match wins
    (r'radalt', 'RADAR_ALT'), (r'adi|kpp|agk|horizon|attitude', 'ATTITUDE'),
    (r'hsi|npp|gik|compass|^dg$|heading|central_ind|ark', 'HEADING'), (r'^alt|vd17|alt_hi', 'ALTIMETER'),
    (r'asi|ias|kus', 'AIRSPEED'), (r'mach', 'MACH'), (r'aoa|uua', 'AOA'), (r'vvi|var75|climb', 'VVI'),
    (r'accel|g_meter', 'G_METER'), (r'rpm|tach', 'ENGINE_RPM'), (r'egt|itt|ftit', 'EGT'),
    (r'fuel', 'FUEL_QTY'), (r'clock', 'CLOCK'), (r'turn', 'TURN_SLIP'), (r'rwr|tews|spo', 'RWR'),
    (r'oil|press', 'FILLER_OIL'), (r'hyd', 'FILLER_HYDRAULIC'), (r'oxy', 'FILLER_OXYGEN'), (r'volt', 'FILLER_VOLTS'),
    (r'temp|coolant|carb|cabin', 'FILLER_TEMP'), (r'manifold|suction|engine_unit', 'FILLER_RPM'),
]
ENGINE_CYCLE = ['ENGINE_RPM', 'EGT', 'FILLER_OIL', 'FILLER_FUEL']
SCREENS = ('vsd', 'hdd', 'ci_radar', 'wide', 'lower', 'tv_monitor', 'mpd', 'mpcd', 'mfd', 'radar')
ANNUNC = ('warn', 'caution', 'master_warning')
GLASS_ERAS = ('MODERN_GLASS', 'GEN4_CRT')


def gauge_kind(name, counter):
    n = name.lower()
    if n.startswith('eng') and not re.search(r'rpm|egt|oil', n):
        counter[0] += 1
        return ENGINE_CYCLE[(counter[0] - 1) % len(ENGINE_CYCLE)]
    for pat, kind in LIVE:
        if re.search(pat, n):
            return kind
    return 'FILLER_TEMP'


# ---------------------------------------------------------------- painted tiles

def font(px):
    try:
        return ImageFont.truetype('arialbd.ttf', px)
    except Exception:
        return ImageFont.load_default()


def shade(rgb, f):
    return tuple(int(max(0, min(255, c * f))) for c in rgb)


def paint_tile(atlas, name, bg, rng):
    """paints one tile kind; returns its atlas rect"""
    bg = tuple(bg)
    SS = A.Atlas.SS
    if name.startswith('switchpanel'):
        kind = name.split('_')[1]
        W, H = 48, 40

        def fn(d, im):
            d.rectangle([0, 0, W * SS, H * SS], fill=bg + (255,))
            d.rectangle([1 * SS, 1 * SS, (W - 1) * SS, (H - 1) * SS], outline=shade(bg, 1.6) + (255,), width=SS)
            f = font(5 * SS)
            for row in range(2):
                y = (8 + row * 18) * SS
                d.rectangle([4 * SS, y - 5 * SS, (W - 4) * SS, y - 1 * SS], fill=shade(bg, 0.7) + (255,))
                d.text((6 * SS, y - 6 * SS), rng.choice(('NAV', 'IFF', 'COMM', 'ECM', 'LIGHTS', 'FUEL', 'ENG', 'EXT',
                                                          'INT', 'ANTI ICE', 'OXY', 'HOOK', 'TACAN', 'ILS')),
                       fill=(220, 220, 210, 255), font=f)
                for c in range(3):
                    x = (10 + c * 14) * SS
                    if kind in ('knobs',) or (kind == 'mixed' and c == 0):
                        d.ellipse([x - 4 * SS, y + 2 * SS, x + 4 * SS, y + 10 * SS], fill=(20, 20, 20, 255),
                                  outline=(200, 200, 190, 255), width=SS)
                    else:
                        d.ellipse([x - 3 * SS, y + 3 * SS, x + 3 * SS, y + 9 * SS], fill=(140, 140, 140, 255),
                                  outline=(30, 30, 30, 255), width=SS)
                        d.text((x - 4 * SS, y + 10 * SS), rng.choice(('ON', 'OFF', 'AUTO', 'NORM')),
                               fill=(220, 220, 210, 255), font=font(3 * SS))
            if kind == 'window':
                d.rectangle([8 * SS, 14 * SS, (W - 8) * SS, 22 * SS], fill=(10, 14, 10, 255))
                d.text((10 * SS, 14 * SS), '0' + str(rng.randint(100, 999)), fill=(90, 220, 110, 255), font=f)
        return atlas.paint(W, H, fn)
    if name == 'breakers':
        W, H = 40, 40

        def fn(d, im):
            d.rectangle([0, 0, W * SS, H * SS], fill=shade(bg, 0.9) + (255,))
            for r_ in range(5):
                for c in range(6):
                    x, y = (4 + c * 6.3) * SS, (5 + r_ * 7.4) * SS
                    d.ellipse([x, y, x + 4 * SS, y + 4 * SS], fill=(15, 15, 15, 255), outline=(210, 210, 200, 255),
                              width=SS // 2 or 1)
        return atlas.paint(W, H, fn)
    if name == 'caution_panel':
        W, H = 48, 32

        def fn(d, im):
            d.rectangle([0, 0, W * SS, H * SS], fill=(26, 26, 26, 255))
            cols = [(200, 150, 30), (200, 150, 30), (40, 160, 70), (190, 50, 40)]
            for r_ in range(3):
                for c in range(4):
                    x, y = (2 + c * 11.5) * SS, (2 + r_ * 10) * SS
                    d.rectangle([x, y, x + 10 * SS, y + 8 * SS], fill=shade(rng.choice(cols), 0.55) + (255,),
                                outline=(10, 10, 10, 255), width=SS)
        return atlas.paint(W, H, fn)
    if name == 'keypad':
        W, H = 56, 40

        def fn(d, im):
            d.rectangle([0, 0, W * SS, H * SS], fill=(30, 31, 33, 255))
            d.rectangle([4 * SS, 2 * SS, (W - 4) * SS, 9 * SS], fill=(12, 16, 12, 255))
            d.text((6 * SS, 2 * SS), 'UHF 251.0  TCN 37X', fill=(110, 230, 120, 255), font=font(5 * SS))
            for r_ in range(4):
                for c in range(5):
                    x, y = (5 + c * 9.6) * SS, (12 + r_ * 7) * SS
                    d.rectangle([x, y, x + 7 * SS, y + 5.5 * SS], fill=(70, 72, 76, 255), outline=(15, 15, 15, 255),
                                width=SS)
                    d.text((x + 2 * SS, y), '1234567890ABCDEFGHIJ'[r_ * 5 + c], fill=(235, 235, 225, 255),
                           font=font(4 * SS))
        return atlas.paint(W, H, fn)
    if name == 'text_window':
        W, H = 48, 24

        def fn(d, im):
            d.rectangle([0, 0, W * SS, H * SS], fill=(8, 12, 8, 255))
            for r_, line in enumerate(rng.sample(['STPT 12', 'FUEL 8.4', 'ALT 27000', 'TCN 37X', 'BINGO 2.0',
                                                   'MODE NAV', 'UHF 251.0', 'ETA 12:40'], 3)):
                d.text((3 * SS, (2 + r_ * 7) * SS), line, fill=(100, 225, 110, 255), font=font(5 * SS))
        return atlas.paint(W, H, fn)
    if name.startswith('panelplate'):
        W, H = 40, 48

        def fn(d, im):
            d.rectangle([0, 0, W * SS, H * SS], fill=bg + (255,))
            f = font(4 * SS)
            for r_ in range(4):
                y = (4 + r_ * 11) * SS
                d.text((3 * SS, y), rng.choice(('MASTER ARM', 'JETT', 'SEL', 'GUN', 'MSL', 'PWR', 'TEST', 'RDR',
                                                 'LASER', 'CMDS', 'SAFE', 'ARM')), fill=(225, 225, 215, 255), font=f)
                d.ellipse([28 * SS, y, 34 * SS, y + 6 * SS], fill=(130, 130, 130, 255), outline=(20, 20, 20, 255),
                          width=SS)
        return atlas.paint(W, H, fn)
    return atlas.swatch(bg)


# ---------------------------------------------------------------- the conversion

def grid_key(c, q=0.002):
    return tuple(int(math.floor(v / q)) for v in c)


def keeper(cents, q=0.002):
    s = set()
    for c in cents:
        k = grid_key(c, q)
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for dz in (-1, 0, 1):
                    s.add((k[0] + dx, k[1] + dy, k[2] + dz))
    return s


def stick_bones(ac):
    return [b for b in ac.bones if b['name'].endswith('__control_stick_pitch')]


def convert(vid, sdir, dry=False):
    import random
    d = json.load(open(os.path.join(sdir, 'game_export.json')))
    k = float(d.get('blocks_per_m', 1.0))
    ac = L.Aircraft(vid)
    meta = json.load(open(os.path.join(os.path.dirname(sdir.rstrip('/\\')), 'meta.json')))
    era = meta.get('era') or ''
    pal = (meta.get('fleet') or {}).get('palette', 'nato')
    rng = random.Random(vid)

    # -- the old cockpit: faces the cuts removed go
    ac.bones[:] = [b for b in ac.bones if not b['name'].endswith('__cockpit')]
    removed = {}
    for b in ac.bones:
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys') or b['name'] not in d['keep']:
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        ok = keeper(d['keep'][b['name']])
        new = []
        for poly in pm['polys']:
            c = P[[v[0] for v in poly]].mean(0)
            if grid_key(c) in ok:
                new.append(poly)
        if len(new) != len(pm['polys']):
            removed[b['name']] = len(pm['polys']) - len(new)
            pm['polys'] = new
    source = max(removed, key=removed.get) if removed else 'wreck_fuselage_1__hull'
    piece = source.split('__', 1)[0] if source.startswith('wreck_') else 'wreck_fuselage_1'

    # -- which parts the game draws itself, and the instruments' resources
    gauges, displays, n_eng = [], [], [0]
    drawn = set()
    screens = 0
    for it in d['instruments']:
        kind, name, si = it['kind'], it['name'], int(it['station'])
        size = it['size_m']
        sc = min(1.0, float(it.get('scale', 1.0)))
        pref = f's{si}_' if len(d['stations']) > 1 else ''
        c, n, up = np.array(it['center']), np.array(it['normal']), np.array(it['up'])
        if kind in ('gauge', 'scope'):
            nm = name.lower()
            if kind == 'scope' and 'radar' in nm:
                displays.append({'Seat': si, 'Center': L.r5(c + n * 0.003 * k), 'Normal': L.r5(n), 'Up': L.r5(up),
                                 'Width': round(2 * size[0] * sc * k * 0.92, 4), 'Depth': 0.035, 'Kind': 'RADAR',
                                 'Style': 'CRT'})
                drawn.add(pref + name + '_face'); drawn.add(pref + name + '_rim')
                continue
            gk = gauge_kind(nm, n_eng)
            gauges.append({'Seat': si, 'Kind': gk, 'Center': L.r5(c + n * L.GAUGE_LIFT * k), 'Normal': L.r5(n),
                           'Up': L.r5(up), 'Diameter': round(2 * size[0] * sc * k, 4), 'Depth': 0.03})
            drawn.add(pref + name + '_face'); drawn.add(pref + name + '_rim')
            drawn.update(it.get('parts', []))
        elif kind in ('mfd', 'display') and (kind == 'mfd' or any(s in name.lower() for s in SCREENS)):
            w = (size[0] * sc if kind == 'mfd' else size[0] * sc) * 0.80
            screens += 1
            dk = 'RADAR' if screens == 1 else 'STORES' if screens == 2 else 'PFD'
            displays.append({'Seat': si, 'Center': L.r5(c + n * L.DISPLAY_LIFT * k), 'Normal': L.r5(n),
                             'Up': L.r5(up), 'Width': round(w * k, 4), 'Depth': 0.035, 'Kind': dk,
                             'Style': 'LCD' if era in GLASS_ERAS else 'CRT'})
            for sfx in ('_t', '_b', '_l', '_r', '_floor'):
                drawn.add(pref + name + sfx)
            drawn.update(f for f in {fc['part'] for fc in d['faces']} if f.startswith(pref + name + '_osb'))
        elif kind == 'plate' and any(a in name.lower() for a in ANNUNC):
            cols, lamps = L.lamps_of('ww2' if era == 'WWII_STEAM' else 'cw', pal)
            w, h = size[0] * float(it.get('scale', 1.0)), size[1] * float(it.get('scale', 1.0))
            gauges.append({'Seat': si, 'Kind': 'ANNUNCIATOR', 'Center': L.r5(c + n * 0.004 * k), 'Normal': L.r5(n),
                           'Up': L.r5(up), 'Diameter': round(w * k, 4), 'Width': round(w * k, 4),
                           'Height': round(h * k, 4), 'Depth': 0.02, 'Columns': cols, 'Lamps': lamps})

    # -- the mesh: colours as exact swatches, painted tiles where the export says so
    atlas = A.Atlas(vid)
    sw, tiles = {}, {}
    mesh, stick = L.Mesh(), L.Mesh()
    sticks = stick_bones(ac)
    rig = ac.asset.get('AircraftRig')
    rigged = bool(sticks) and rig and rig.get('Schema') == 2 and \
        any(sf.get('Bone') == 'control_stick_pitch' for sf in rig.get('Surfaces', []))
    st0 = d['stations'].get('0') or next(iter(d['stations'].values()))
    stick_parts = re.compile(r'^(s0_)?stick_(grip|column|boot|stem|trigger|pinky)')
    for f in d['faces']:
        if f['part'] in drawn:
            continue
        rgb = tuple(f['c'])
        if 'tile' in f:
            if f['tile'] not in tiles:
                tiles[f['tile']] = paint_tile(atlas, f['tile'], rgb, rng)
            uvs = [atlas.uv(tiles[f['tile']], max(0.0, min(1.0, u)), max(0.0, min(1.0, v))) for u, v in f['uv']]
        else:
            if rgb not in sw:
                sw[rgb] = atlas.flat(atlas.swatch(rgb))
            uvs = [sw[rgb]] * 3
        target = stick if (rigged and stick_parts.match(f['part'])) else mesh
        target.face(f['p'], uvs, normal=f['n'])

    if rigged and st0.get('stick_foot'):
        foot = np.array(st0['stick_foot'])
        px = L.r5(foot * np.array([16.0, 16.0, -16.0]))
        for b in ac.bones:
            if b['name'] in ('control_stick_roll', 'control_stick_pitch') or b['name'].endswith('__control_stick_pitch'):
                b['pivot'] = px
        for b in sticks:
            b['poly_mesh'] = {'normalized_uvs': True, 'positions': [], 'normals': [], 'uvs': [], 'polys': []}
        sticks[0]['poly_mesh'] = stick.poly_mesh()
        for sf in rig['Surfaces']:
            if sf.get('Bone') in ('control_stick_roll', 'control_stick_pitch'):
                sf['Pivot'] = px
                if st0.get('stick') == 'side':
                    sf['MaxDeflectionDegrees'] = 6.0
    else:
        for b in sticks:                       # the old stick goes; the new one is fixed in the cockpit bone
            b['poly_mesh'] = {'normalized_uvs': True, 'positions': [], 'normals': [], 'uvs': [], 'polys': []}

    pm = mesh.poly_mesh()
    hull = ac.bone('hull')
    bone = {'name': piece + '__cockpit', 'parent': 'hull', 'pivot': list(hull['pivot']), 'poly_mesh': pm}
    at = next((i for i, b in enumerate(ac.bones) if b['name'] == source), len(ac.bones) - 1) + 1
    ac.bones.insert(at, bone)

    # -- the asset
    a = ac.asset
    seats = {int(s) for s in d['stations']}
    old_g = [g for g in (a.get('CockpitGauges') or {}).get('Gauges', []) if g.get('Seat', 0) not in seats]
    old_d = [x for x in (a.get('FlightDisplays') or {}).get('Displays', []) if x.get('Seat', 0) not in seats]
    a['CockpitGauges'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Gauges': old_g + gauges}
    fd = a.get('FlightDisplays') or {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS'}
    fd['Displays'] = old_d + displays
    fd.setdefault('Schema', 1); fd.setdefault('Frame', 'VEHICLE_LOCAL_BLOCKS')
    if any(x.get('Kind') == 'STORES' for x in fd['Displays']) and 'Planform' not in fd:
        import importlib.util
        spec_ = importlib.util.spec_from_file_location('cockpit_layout', os.path.join(HERE, '..', 'cockpit_layout',
                                                                                      'layout.py'))
        lay = importlib.util.module_from_spec(spec_); spec_.loader.exec_module(lay)
        fd['Planform'] = lay.planform(vid)
    a['FlightDisplays'] = fd
    if not fd['Displays']:
        a.pop('FlightDisplays')
    if d['hud']:
        h = d['hud'][0]
        eye = d['stations'][str(h['station'])]['eye']
        a['HeadUpDisplay'] = {'Schema': 1, 'Frame': 'VEHICLE_LOCAL_BLOCKS', 'Seat': int(h['station']),
                              'Center': L.r5(h['center']), 'Normal': L.r5(h['normal']), 'Up': L.r5(h['up']),
                              'Width': round(h['width'] * k, 5), 'Height': round(h['height'] * k, 5), 'Eye': L.r5(eye)}
    else:
        a.pop('HeadUpDisplay', None)
    if d['glass']:
        L.add_glass(a, [p for t in d['glass'] for p in t])

    vol = L.loader_volume(pm)
    rep = (f'{vid}: removed {sum(removed.values())} old faces from {len(removed)} bones; {len(pm["polys"])} faces in '
           f'{bone["name"]} (volume {vol:+.0f}), stick {"rigged" if rigged else "fixed"} ({len(stick.faces)} faces); '
           f'{len(gauges)} gauges, {len(displays)} displays, HUD {"yes" if d["hud"] else "no"}, '
           f'{len(d["glass"])} glass triangles; {len(sw)} swatches, {len(tiles)} painted tiles')
    if not dry:
        with open(ac.geo_path, 'w') as fh:
            json.dump(ac.geo, fh, separators=(',', ':'))
        L.write_asset(ac.asset_path, ac.asset)
        tex = os.path.normpath(L.texture_path(vid))
        atlas.write(tex)
        dead = tex.replace(os.sep + 'entity' + os.sep, os.sep + 'entity_dead' + os.sep)
        if os.path.isfile(dead):
            atlas.write(dead, dead=True)
    return rep


if __name__ == '__main__':
    args = [x for x in sys.argv[1:] if not x.startswith('--')]
    print(convert(args[0], args[1], dry='--dry-run' in sys.argv))
