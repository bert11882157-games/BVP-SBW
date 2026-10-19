#!/usr/bin/env python3
"""Review sheets for the canopy chain: for each aircraft, side, front-quarter, front and seat views of the model
as it is now, and the same side view with the faces the chain removed drawn over it: red for the old canopy (the
canopy.py cut on the original model) and orange for the old interior (fleet.py's modelled cockpit). Nothing in red
or orange should be fuselage skin or the deck under the windscreen; if it is, the cut region (spec.json "cut",
or the hood / windscreen rules) is wrong for that aircraft.

Usage: python3 tools/canopy_build/review.py --orig=DIR OUTDIR id...      (DIR: the committed originals, as
       run.py --orig= copies them; set BVP_TEX_DIR to real textures when the repository's are LFS pointers)
Writes OUTDIR/<id>_{side,quarter,front,seat,cut}.png and OUTDIR/sheet_N.png, six aircraft per sheet.
"""
import json, os, sys
from concurrent.futures import ProcessPoolExecutor
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
for d in ('cockpit_models', 'canopy_panels', 'canopy_loft', 'canopy_build'):
    sys.path.insert(0, os.path.join(HERE, '..', d))
import eyeview as EV        # noqa: E402
import cockpit_lib as L     # noqa: E402

W, H = 560, 385
VIEWS = {'side': (lambda E: [E[0] + 3.4, E[1] + 0.5, E[2] + 0.2], -90, -8, 42),
         'quarter': (lambda E: [E[0] + 2.2, E[1] + 1.4, E[2] + 2.6], -140, -28, 45),
         'front': (lambda E: [E[0] + 0.9, E[1] + 0.9, E[2] + 3.6], -165, -20, 45),
         'seat': (lambda E: [E[0], E[1], E[2]], 0, -12, 85),
         'down': (lambda E: [E[0], E[1], E[2]], 0, -48, 92),                      # the consoles and the panel foot
         'open': (lambda E: [E[0] + 1.1, E[1] + 0.5, E[2] - 0.35], -75, -28, 75)}  # the cockpit with the canopy off


def only(geo, pred):
    g = dict(geo)
    g['minecraft:geometry'] = [dict(geo['minecraft:geometry'][0],
                                    bones=[b for b in geo['minecraft:geometry'][0]['bones'] if pred(b['name'])])]
    return g


def removed(vid, orig_dir):
    """(red geo, orange geo): the original faces the canopy cut takes, and the other faces gone from the model"""
    import canopy as C, panels as P, loft as LF
    orig = json.load(open(os.path.join(orig_dir, vid + '.geo.json')))
    cur = json.load(open(os.path.join(L.GEO_DIR, vid + '.geo.json')))
    key = lambda pm, poly: tuple(sorted(tuple(np.round(pm['positions'][v[0]], 2)) for v in poly))
    have = {}
    for b in cur['minecraft:geometry'][0]['bones']:
        pm = b.get('poly_mesh')
        if pm and pm.get('polys'):
            have[b['name']] = {key(pm, p) for p in pm['polys']}
    specs = json.load(open(P.SPEC))
    loft_cfgs = json.load(open(LF.CONFIG)) if os.path.exists(LF.CONFIG) else {}
    was, L.GEO_DIR = L.GEO_DIR, orig_dir
    os.environ['BVP_GEO_DIR'] = orig_dir
    try:
        ac = L.Aircraft(vid)
        B = P.Builder(vid, specs[vid], loft_cfgs.get(vid, {})); B.build()
        cockpit = next((b['name'] for b in ac.bones if b['name'].endswith('__cockpit')), None)
        rm, _ = C.old_canopy(ac, B, B.env, {cockpit} if cockpit else set())
    finally:
        L.GEO_DIR = was
        os.environ.pop('BVP_GEO_DIR', None)
    red, orange = [], []
    for b in orig['minecraft:geometry'][0]['bones']:
        pm = b.get('poly_mesh')
        if not pm or not pm.get('polys'):
            continue
        sel = rm.get(b['name'], set()); h = have.get(b['name'], set())
        r = [p for i, p in enumerate(pm['polys']) if i in sel]
        o = [p for i, p in enumerate(pm['polys']) if i not in sel and key(pm, p) not in h]
        if r:
            red.append({'name': b['name'], 'poly_mesh': dict(pm, polys=r)})
        if o:
            orange.append({'name': b['name'], 'poly_mesh': dict(pm, polys=o)})
    return {'minecraft:geometry': [{'bones': red}]}, {'minecraft:geometry': [{'bones': orange}]}


def one(args):
    vid, orig_dir, out = args
    from PIL import Image
    try:
        E = L.Aircraft(vid).eye(0)
        for k in VIEWS:
            f, yaw, pitch, fov = VIEWS[k]
            kw = {}
            if k == 'open':
                kw = dict(glass=False, skip=('pilot_view_occluder', 'canopy', 'wreck_fuselage_0__canopy',
                                             'wreck_fuselage_1__canopy', 'wreck_fuselage_2__canopy'))
            EV.render(vid, os.path.join(out, f'{vid}_{k}.png'), eye=f(E), yaw=yaw, pitch=pitch, fov=fov, size=(W, H), **kw)
        if orig_dir:
            red, orange = removed(vid, orig_dir)
            cur = json.load(open(os.path.join(L.GEO_DIR, vid + '.geo.json')))
            base = only(cur, lambda n: not n.endswith('__canopy'))
            tex = np.asarray(Image.open(L.texture_path(vid)).convert('RGBA')).astype(np.float32) / 255.0
            tris, uvs, _, _ = EV.model_triangles(base, ('pilot_view_occluder',))
            rt, ruv, _, _ = EV.model_triangles(red); ot, ouv, _, _ = EV.model_triangles(orange)
            f, yaw, pitch, fov = VIEWS['side']
            cam = EV.Camera(f(E), yaw, pitch, fov, W, H); r = EV.Raster(cam)
            keep = np.linalg.norm(tris.mean(1) - np.array(f(E)), axis=1) < 12
            r.draw(tris[keep], uvs[keep], tex)
            if len(ot):
                r.draw(ot, ouv, tex, tint=(1.0, 0.6, 0.1), alpha_test=False)
            if len(rt):
                r.draw(rt, ruv, tex, tint=(1.0, 0.12, 0.12), alpha_test=False)
            r.image().save(os.path.join(out, f'{vid}_cut.png'))
    except Exception as failure:
        import traceback; traceback.print_exc()
        return f'{vid} FAILED {failure!r}'
    return vid


def main(argv):
    from PIL import Image, ImageDraw
    orig_dir = next((a[7:] for a in argv if a.startswith('--orig=')), None)
    jobs = int(next((a[7:] for a in argv if a.startswith('--jobs=')), os.cpu_count() or 1))
    args = [a for a in argv if not a.startswith('--')]
    out, vids = args[0], args[1:]
    os.makedirs(out, exist_ok=True)
    with ProcessPoolExecutor(jobs) as ex:
        for r in ex.map(one, [(v, orig_dir, out) for v in vids]):
            print(r, flush=True)
    cols = ['side', 'cut', 'quarter', 'front', 'seat', 'down', 'open'] if orig_dir else ['side', 'quarter', 'front', 'seat', 'down', 'open']
    per = 6
    for n in range(0, len(vids), per):
        chunk = vids[n:n + per]
        s = Image.new('RGB', (W * len(cols), H * len(chunk)))
        for i, v in enumerate(chunk):
            for j, k in enumerate(cols):
                p = os.path.join(out, f'{v}_{k}.png')
                if os.path.exists(p):
                    im = Image.open(p); ImageDraw.Draw(im).text((8, 8), f'{v} {k}', fill=(255, 255, 0))
                    s.paste(im, (j * W, i * H))
        s.save(os.path.join(out, f'sheet_{n // per + 1}.png'))
    print('sheets', (len(vids) + per - 1) // per)


if __name__ == '__main__':
    main(sys.argv[1:])
