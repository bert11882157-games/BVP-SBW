#!/usr/bin/env python3
"""The whole canopy chain for an aircraft, from its committed originals:

  1. the original geo and asset files are restored from REV into the working tree (not staged), and the original
     geo is copied aside;
  2. tools/canopy_panels/panels.py --write   the panel glass and frame stations (measured on the original);
  3. tools/cockpit_models/fleet.py strip     the tub measured on the original interior, the old interior removed;
  4. tools/canopy_build/canopy.py --orig=    the old canopy cut out of the bare tub, the new frames and glass built,
                                             with the envelope measured on the copied original, so frames and
                                             glass agree;
  5. tools/cockpit_models/fleet.py build     the modelled cockpit, from the tub measured in step 3.

The interior goes before the canopy is cut so that neither cut takes the other's faces, and the cockpit is built
last, inside the finished canopy.

REV defaults to the merge base of HEAD and origin/main (the models as the pack ships them). Set BVP_TEX_DIR to a
folder of real textures when the repository's are Git LFS pointers.

With --no-canopy steps 2 and 4 are skipped: the original canopy stays, the cockpit is still stripped and rebuilt
(clipped to the original canopy), and CanopyGlass is removed from the asset, so the vehicle draws no glass at all.

Usage: python3 tools/canopy_build/run.py [--rev=REV] [--orig=DIR] [--jobs=N] [--no-canopy] id...   (no ids: every aircraft in spec.json)
"""
import json, os, shutil, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
GEN = 'bvp/src/generated/resources/assets/berts_vehicle_pack'
sys.path.insert(0, os.path.join(HERE, '..', 'canopy_panels'))
sys.path.insert(0, os.path.join(HERE, '..', 'cockpit_models'))
sys.path.insert(0, HERE)


def git(*args):
    return subprocess.run(['git', *args], cwd=ROOT, check=True, capture_output=True, text=True).stdout


def one(vid, orig_dir, canopy=True):
    import panels as P, fleet as F, canopy as C, cockpit_lib as L
    lines = []
    try:
        import io, contextlib
        if canopy:
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                P.main(['--write', vid])
            lines.append(buf.getvalue().rstrip())
        cockpit = vid in F.FLEET['aircraft']
        state = os.path.join(orig_dir, vid + '.tub.json')
        if cockpit:
            _, _, rep = F.apply(vid, stage='strip', state=state)
            lines.append(rep)
        if canopy:
            _, rep = C.apply(vid, orig=orig_dir)
            lines.append(rep)
        if cockpit:
            _, _, rep = F.apply(vid, stage='build', state=state)
            lines.append(rep)
        if not canopy:
            lines.append(drop_glass(vid, L))
    except Exception as failure:
        import traceback
        lines.append(f'{vid}: FAILED {failure!r}\n' + traceback.format_exc())
    return '\n'.join(lines)


def drop_glass(vid, L):
    """removes CanopyGlass from the vehicle's asset"""
    path = os.path.join(ROOT, f'{GEN}/sbw/vehicles/{vid}.json')
    asset = json.load(open(path))
    cg = asset.pop('CanopyGlass', None)
    if cg is not None:
        L.write_asset(path, asset)
    return f'{vid}: CanopyGlass removed ({len(cg.get("Triangles", [])) // 9} triangles)' if cg else f'{vid}: no CanopyGlass'


def main(argv):
    canopy = '--no-canopy' not in argv
    rev = next((a[6:] for a in argv if a.startswith('--rev=')), None)
    orig = next((a[7:] for a in argv if a.startswith('--orig=')), None)
    jobs = int(next((a[7:] for a in argv if a.startswith('--jobs=')), os.cpu_count() or 1))
    if not rev:
        try:
            rev = git('merge-base', 'HEAD', 'origin/main').strip()
        except subprocess.CalledProcessError:
            rev = 'HEAD'
    import panels as P
    specs = json.load(open(P.SPEC))
    ids = [a for a in argv if not a.startswith('--')] or [k for k in specs if not k.startswith('_')]
    orig_dir = orig or tempfile.mkdtemp(prefix='canopy_orig_')
    os.makedirs(orig_dir, exist_ok=True)
    for vid in ids:                                   # git first, one process: the index takes one writer
        geo, asset = f'{GEN}/custom_geo/{vid}.geo.json', f'{GEN}/sbw/vehicles/{vid}.json'
        git('restore', '--source=' + rev, '--worktree', '--', geo, asset)   # the working tree only, not the index
        shutil.copy(os.path.join(ROOT, geo), os.path.join(orig_dir, vid + '.geo.json'))
    if jobs > 1 and len(ids) > 1:
        from concurrent.futures import ProcessPoolExecutor
        with ProcessPoolExecutor(jobs) as ex:
            for report in ex.map(one, ids, [orig_dir] * len(ids), [canopy] * len(ids)):
                print(report, flush=True)
    else:
        for vid in ids:
            print(one(vid, orig_dir, canopy), flush=True)
    if not orig:
        shutil.rmtree(orig_dir, ignore_errors=True)


if __name__ == '__main__':
    main(sys.argv[1:])
