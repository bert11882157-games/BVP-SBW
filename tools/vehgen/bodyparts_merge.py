"""Merge per-panel wing boxes into a BodyParts list that AircraftTerrainContact.valid() accepts (at most 16 boxes).

The fit scripts (vulcan_fit.py, h6k_fit.py, vautour_fit.py) cut a wing into many span slabs (one every 0.7-1.2 m). Spawn fails above 16
BodyParts, so each side's slabs, ordered root to tip, are regrouped into the budget by dynamic programming: contiguous groups, each replaced
by its axis-aligned bound, chosen so the summed volume of the merged boxes is least (the root, where the wing is thick and holds the intakes or
nacelles, gets the narrow groups; the thin straight tip gets the wide ones). Merged boxes are bounds of real slabs, so none sticks out past
the old set's outline; only the gaps between swept slabs are filled.

    from bodyparts_merge import merge_body_parts
    tc['BodyParts'] = merge_body_parts(fixed_parts, {'L': left_boxes, 'R': right_boxes})
"""
from collections import OrderedDict

LIMIT = 16


def _vol(lo, hi):
    return (hi[0] - lo[0]) * (hi[1] - lo[1]) * (hi[2] - lo[2])


def _bound(boxes):
    lo = [min(b['Min'][i] for b in boxes) for i in range(3)]
    hi = [max(b['Max'][i] for b in boxes) for i in range(3)]
    return lo, hi


def _partition(boxes, k):
    """Optimal split of the ordered boxes into at most k contiguous groups, least total bound volume."""
    n = len(boxes)
    k = max(1, min(k, n))
    cost = [[0.0] * n for _ in range(n)]
    for i in range(n):
        lo, hi = list(boxes[i]['Min']), list(boxes[i]['Max'])
        for j in range(i, n):
            for a in range(3):
                lo[a] = min(lo[a], boxes[j]['Min'][a])
                hi[a] = max(hi[a], boxes[j]['Max'][a])
            cost[i][j] = _vol(lo, hi)
    inf = float('inf')
    best = [[inf] * (k + 1) for _ in range(n + 1)]   # best[i][g]: first i boxes in g groups
    cut = [[0] * (k + 1) for _ in range(n + 1)]
    best[0][0] = 0.0
    for i in range(1, n + 1):
        for g in range(1, min(k, i) + 1):
            for p in range(g - 1, i):
                c = best[p][g - 1] + cost[p][i - 1]
                if c < best[i][g]:
                    best[i][g], cut[i][g] = c, p
    g = min(range(1, k + 1), key=lambda x: best[n][x])
    groups, i = [], n
    while i > 0:
        p = cut[i][g]
        groups.append((p, i))
        i, g = p, g - 1
    return groups[::-1]


def merge_body_parts(fixed, sides, limit=LIMIT):
    """fixed: boxes kept as they are (fuselage, nose, tail). sides: {name: ordered wing boxes}. Returns the BodyParts list.

    Every side gets the same number of boxes, (limit - len(fixed)) // number of sides, so the result stays mirror-symmetric."""
    per = (limit - len(fixed)) // len(sides)
    if per < 1:
        raise ValueError('no room for wing boxes beside %d fixed parts' % len(fixed))
    out = [OrderedDict([('Min', list(b['Min'])), ('Max', list(b['Max']))]) for b in fixed]
    for name in sides:
        boxes = sides[name]
        for a, b in _partition(boxes, per):
            lo, hi = _bound(boxes[a:b])
            out.append(OrderedDict([('Min', [round(float(v), 5) for v in lo]), ('Max', [round(float(v), 5) for v in hi])]))
    assert len(out) <= limit, len(out)
    return out


def total_volume(parts):
    return sum(_vol(p['Min'], p['Max']) for p in parts)
