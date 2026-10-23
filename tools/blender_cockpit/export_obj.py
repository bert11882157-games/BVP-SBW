#!/usr/bin/env python3
"""Exports an aircraft for cockpit modelling in Blender.

Writes OUTDIR/<vid>/:
  model.obj + model.mtl  every bone of the model (after rebuild.py's strict cut of the old cockpit when --cut is
                         given), one object per bone, with uvs and the vehicle texture; vehicle-local blocks
                         (x left, y up, z forward) so Blender sees the aircraft as the game does (Blender is z-up:
                         the importer's axis options map it, see blender side);
  glass.obj              the canopy glass triangles (CanopyGlass), as their own object;
  meta.json              crew eyes, the canopy glass bounds, the tub measurements (cockpit_lib.Tub) and scale.

Usage: python3 tools/blender_cockpit/export_obj.py OUTDIR VID [--cut]
"""
import json, os, shutil, sys
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'canopy_models'))
sys.path.insert(0, os.path.join(HERE, '..', 'cockpit_models'))
import rebuild as RB  # noqa: E402
L = RB.L


def bones_tris(bones):
    for b in bones:
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys'):
            continue
        P = np.array(pm['positions'], float) * np.array([1, 1, -1]) / 16.0
        U = np.array(pm['uvs'], float) if pm.get('uvs') else np.zeros((1, 2))
        yield b['name'], P, U, pm['polys']


def write_obj(path, mtl_name, bones):
    lines = [f'mtllib {mtl_name}'] if mtl_name else []
    vo = to = 0
    for name, P, U, polys in bones:
        lines.append(f'o {name}')
        lines.append('usemtl skin')
        for p in P:
            lines.append(f'v {p[0]:.5f} {p[1]:.5f} {p[2]:.5f}')
        for u in U:
            lines.append(f'vt {u[0]:.6f} {u[1]:.6f}')
        for poly in polys:
            # the loader mirrors x: the file's faces wind clockwise from the front, so reverse them for Blender
            idx = [(v[0] + 1 + vo, (v[2] if len(v) > 2 else 0) + 1 + to) for v in poly][::-1]
            lines.append('f ' + ' '.join(f'{a}/{b}' for a, b in idx))
        vo += len(P); to += len(U)
    open(path, 'w', encoding='utf-8').write('\n'.join(lines) + '\n')


def write_glass(path, g):
    with open(path, 'w') as f:
        f.write('o canopy_glass\n')
        for t in g:
            for p in t:
                f.write(f'v {p[0]:.5f} {p[1]:.5f} {p[2]:.5f}\n')
        for i in range(len(g)):
            f.write(f'f {3 * i + 3} {3 * i + 2} {3 * i + 1}\n')


def main(argv):
    out, vid = argv[0], argv[1]
    if '--glass-ref' in argv:
        # only the canopy glass, from another branch (e.g. the cloud's rebuilt glass): glass.obj is replaced, the
        # previous one kept as glass_prev.obj
        ref = argv[argv.index('--glass-ref') + 1]
        d = os.path.join(out, vid)
        g = RB.load(vid, ref).glass
        if os.path.isfile(os.path.join(d, 'glass.obj')) and not os.path.isfile(os.path.join(d, 'glass_prev.obj')):
            shutil.copy(os.path.join(d, 'glass.obj'), os.path.join(d, 'glass_prev.obj'))
        write_glass(os.path.join(d, 'glass.obj'), g)
        print('glass', vid, 'from', ref, len(g), 'triangles')
        return
    cut = '--cut' in argv
    d = os.path.join(out, vid)
    os.makedirs(d, exist_ok=True)
    if cut:
        RB.DETAILED = True
        ac, rm, well, rep = RB.interior_strict(vid, dry=True)
        # drop the generated cockpit and stick: only the original model, minus the old cockpit, is exported
        bones = [b for b in ac.bones if not b['name'].endswith('__cockpit')]
    else:
        ac = RB.load(vid, 'origin/main')
        bones = ac.bones
    tex_src = L.texture_path(vid)
    shutil.copy(tex_src, os.path.join(d, 'texture.png'))
    open(os.path.join(d, 'model.mtl'), 'w').write('newmtl skin\nKd 1 1 1\nmap_Kd texture.png\n')
    write_obj(os.path.join(d, 'model.obj'), 'model.mtl', bones_tris(bones))
    orig = RB.load(vid, 'origin/main')
    g = orig.glass
    with open(os.path.join(d, 'glass.obj'), 'w') as f:
        f.write('o canopy_glass\n')
        for t in g:
            for p in t:
                f.write(f'v {p[0]:.5f} {p[1]:.5f} {p[2]:.5f}\n')
        for i in range(len(g)):
            f.write(f'f {3 * i + 3} {3 * i + 2} {3 * i + 1}\n')
    data = orig.data
    eyes = [data['Attachments'][s['CameraPos']['EyeAttachment']]['Position'] for s in data.get('Seats', [])
            if (s.get('CameraPos') or {}).get('EyeAttachment') in data.get('Attachments', {})]
    fs = RB.F.FLEET['aircraft'].get(vid, {})
    era = RB.C.era_of(vid) if vid in RB.F.FLEET['aircraft'] else None
    meta = dict(vid=vid, eyes=eyes, glass_min=g.reshape(-1, 3).min(0).tolist() if len(g) else None,
                glass_max=g.reshape(-1, 3).max(0).tolist() if len(g) else None, era=era, fleet=fs, cut=cut)
    if era:
        kw = dict(fs.get('tub', {})); kw.setdefault('hinge_frac', L.ERAS[era].get('hinge_frac', 0.5))
        orig.drop_sight_glass(orig.eye(0), fs.get('tub', {}).get('s', 1.0))
        t = L.Tub(orig, 0, **kw)
        meta['tub'] = dict(W=t.W, Wg=t.Wg, s=t.s, sill=t.sill, ws_y=t.ws_y, ws_z=t.ws_z, top_c=t.top_c, Z=t.Z,
                           floor_y=t.floor_y, console_y=t.console_y, back_z=t.back_z)
    json.dump(meta, open(os.path.join(d, 'meta.json'), 'w'), indent=1)
    print('exported', vid, 'to', d, '(cut)' if cut else '')


if __name__ == '__main__':
    main(sys.argv[1:])
