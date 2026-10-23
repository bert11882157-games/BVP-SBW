"""Blender: import an export folder and render the pilot's view and an outside view.
blender --background --python bl_test_render.py -- EXPORTDIR OUTDIR"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bl_common as B
from mathutils import Vector

d, out = sys.argv[sys.argv.index('--') + 1:][:2]
os.makedirs(out, exist_ok=True)
B.reset()
model, glass, meta = B.import_aircraft(d)
B.setup_render()
E = B.g2b(meta['eyes'][0])
B.render_view(os.path.join(out, 'seat.png'), E, E + B.g2b([0, -0.35, 1.0]) - B.g2b([0, 0, 0]), lens=14)
B.render_view(os.path.join(out, 'outside.png'), E + B.g2b([2.0, 1.2, 3.0]), E + B.g2b([0, -0.2, 0.2]), lens=30)
print('RENDER_OK', len(model))
