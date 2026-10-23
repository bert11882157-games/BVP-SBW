#!/usr/bin/env python3
"""Byte-compare two mod JARs entry by entry (equivalence check for behaviour-neutral refactors, docs/development/CONTRACT_TESTS.md).

Every entry is hashed (SHA-256 of its uncompressed bytes) and the two JARs must list the same entries with the same
hashes. Classes and resources are reported separately, so a behaviour-neutral source move can be proven to leave
both unchanged. Zip-level metadata (timestamps, compression, entry order) is ignored; META-INF/MANIFEST.MF is
compared after dropping lines listed in --ignore-manifest (none by default).

Usage:
  python jar_compare.py BASELINE.jar CANDIDATE.jar [--ignore-manifest=Key1,Key2] [--json=report.json] [--allow=glob,...]
  python jar_compare.py --pairs BASE_DIR CAND_DIR      (each *.jar in BASE_DIR against the same-named or same-stem JAR)
Exit status: 0 identical (after --allow), 1 differences, 2 usage error.
"""
import fnmatch, hashlib, json, os, re, sys, zipfile


def entries(path, ignore_manifest):
    out = {}
    with zipfile.ZipFile(path) as z:
        for info in z.infolist():
            if info.is_dir():
                continue
            data = z.read(info)
            if info.filename == 'META-INF/MANIFEST.MF' and ignore_manifest:
                lines = data.decode('utf-8', 'replace').replace('\r\n', '\n').split('\n')
                data = '\n'.join(l for l in lines if l.split(':', 1)[0].strip() not in ignore_manifest).encode()
            out[info.filename] = (hashlib.sha256(data).hexdigest(), len(data))
    return out


def compare(base, cand, ignore_manifest=(), allow=()):
    a, b = entries(base, ignore_manifest), entries(cand, ignore_manifest)
    allowed = lambda n: any(fnmatch.fnmatch(n, g) for g in allow)
    only_a = sorted(n for n in a if n not in b and not allowed(n))
    only_b = sorted(n for n in b if n not in a and not allowed(n))
    changed = sorted(n for n in a if n in b and a[n][0] != b[n][0] and not allowed(n))
    kind = lambda n: 'class' if n.endswith('.class') else 'resource'
    report = {
        'baseline': os.path.abspath(base), 'candidate': os.path.abspath(cand),
        'entries': [len(a), len(b)],
        'identical': not (only_a or only_b or changed),
        'removed': only_a, 'added': only_b, 'changed': changed,
        'summary': {k: {'removed': sum(kind(n) == k for n in only_a), 'added': sum(kind(n) == k for n in only_b),
                        'changed': sum(kind(n) == k for n in changed)} for k in ('class', 'resource')},
    }
    return report


def stem(name):
    # bvp-main-v0.11.122beta.jar -> bvp-main ; bvp_superbwarfare-v0.11.123beta.jar -> bvp_superbwarfare
    return re.sub(r'-v?\d[\w.\-]*\.jar$', '', name)


def main(argv):
    opts = {a.split('=', 1)[0]: (a.split('=', 1)[1] if '=' in a else True) for a in argv if a.startswith('--')}
    args = [a for a in argv if not a.startswith('--')]
    ignore = tuple(x for x in str(opts.get('--ignore-manifest', '')).split(',') if x)
    allow = tuple(x for x in str(opts.get('--allow', '')).split(',') if x)
    pairs = []
    if '--pairs' in opts:
        if len(args) != 2:
            print(__doc__); return 2
        cand = {stem(f): f for f in os.listdir(args[1]) if f.endswith('.jar')}
        for f in sorted(os.listdir(args[0])):
            if f.endswith('.jar'):
                if stem(f) not in cand:
                    print(f'{f}: no candidate JAR with stem {stem(f)}'); return 1
                pairs.append((os.path.join(args[0], f), os.path.join(args[1], cand[stem(f)])))
    elif len(args) == 2:
        pairs.append((args[0], args[1]))
    else:
        print(__doc__); return 2
    reports, ok = [], True
    for base, cand in pairs:
        r = compare(base, cand, ignore, allow)
        reports.append(r)
        ok &= r['identical']
        s = r['summary']
        print(f"{os.path.basename(base)} vs {os.path.basename(cand)}: {'IDENTICAL' if r['identical'] else 'DIFFERENT'} "
              f"({r['entries'][0]} / {r['entries'][1]} entries; classes -{s['class']['removed']} +{s['class']['added']} "
              f"~{s['class']['changed']}; resources -{s['resource']['removed']} +{s['resource']['added']} ~{s['resource']['changed']})")
        for label in ('removed', 'added', 'changed'):
            for n in r[label][:20]:
                print(f'  {label}: {n}')
            if len(r[label]) > 20:
                print(f'  ... {len(r[label]) - 20} more {label}')
    if '--json' in opts:
        with open(opts['--json'], 'w') as fh:
            json.dump(reports, fh, indent=1)
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
