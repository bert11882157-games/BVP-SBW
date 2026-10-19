#!/usr/bin/env python3
"""The whole canopy chain for an aircraft, from its committed originals:

  1. the original geo and asset files are restored from REV (git checkout), and the original geo is copied aside;
  2. tools/canopy_panels/panels.py --write   the panel glass and frame stations (measured on the original);
  3. tools/cockpit_models/fleet.py           the modelled cockpit (measures its tub on the original interior);
  4. tools/canopy_build/canopy.py --orig=    the old canopy cut out, the new frames and glass built, with the
                                             envelope measured on the copied original, so frames and glass agree.

REV defaults to the merge base of HEAD and origin/main (the models as the pack ships them). Set BVP_TEX_DIR to a
folder of real textures when the repository's are Git LFS pointers.

Usage: python3 tools/canopy_build/run.py [--rev=REV] [--orig=DIR] [--jobs=N] id...   (no ids: every aircraft in spec.json)
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


def one(vid, orig_dir):
    import panels as P, fleet as F, canopy as C
    lines = []
    try:
        import io, contextlib
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            P.main(['--write', vid])
        lines.append(buf.getvalue().rstrip())
        if vid in F.FLEET['aircraft']:
            _, _, rep = F.apply(vid)
            lines.append(rep)
        _, rep = C.apply(vid, orig=orig_dir)
        lines.append(rep)
    except Exception as failure:
        import traceback
        lines.append(f'{vid}: FAILED {failure!r}\n' + traceback.format_exc())
    return '\n'.join(lines)


def main(argv):
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
        git('checkout', rev, '--', geo, asset)
        shutil.copy(os.path.join(ROOT, geo), os.path.join(orig_dir, vid + '.geo.json'))
    if jobs > 1 and len(ids) > 1:
        from concurrent.futures import ProcessPoolExecutor
        with ProcessPoolExecutor(jobs) as ex:
            for report in ex.map(one, ids, [orig_dir] * len(ids)):
                print(report, flush=True)
    else:
        for vid in ids:
            print(one(vid, orig_dir), flush=True)
    if not orig:
        shutil.rmtree(orig_dir, ignore_errors=True)


if __name__ == '__main__':
    main(sys.argv[1:])
