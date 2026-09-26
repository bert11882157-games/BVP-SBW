import sys, os, numpy as np
from PIL import Image, ImageDraw
import ortho as O, evidence as E
from scipy import ndimage
S=sys.argv[1]; os.makedirs(S,exist_ok=True)
for vid in sys.argv[2:]:
    ev=E.Evidence(vid); eye=ev.eye
    tris,uvs,tex=O.model(vid)
    c=np.array([0,eye[1]+0.3,eye[2]-0.3]); hw,hh,st=2.8,1.0,0.01
    col,dep,_=O.render(tris,uvs,tex,'left',c,hw,hh,st); side=O.image(col,dep,1); d1=ImageDraw.Draw(side)
    ct=np.array([0,eye[1],c[2]])
    tcol,tdep,_=O.render(tris,uvs,tex,'top',ct,1.2,hw,st)
    top=O.image(tcol,tdep,1).transpose(Image.ROTATE_90)   # nose to the left, +x (left wing) down
    d2=ImageDraw.Draw(top)
    def tp(x,z):
        u,v=O.to_pixel((x,0,z),'top',ct,1.2,hw,st)   # (col along -x, row along -z) before rotation
        return (v, top.height-1-u)
    for f in ev.frames:
        for x,y,z in f[::3]: d2.point(tp(x,z),fill=(0,255,0))
    for j,z in enumerate(ev.zs):
        for s_ in (1,-1): d2.point(tp(s_*ev.crest[j,0],z),fill=(255,200,0) if ev.open[j] else (0,120,255))
    for zz in ev.pit_z: d2.line([tp(-1.2,zz),tp(1.2,zz)],fill=(255,0,0))
    jj,ii=np.nonzero(ev.foot & ~ndimage.binary_erosion(ev.foot))
    for j,i in zip(jj,ii): d2.point(tp(ev.xs[i],ev.zs[j]),fill=(255,255,0))
    s=Image.new('RGB',(max(side.width,top.width),side.height+top.height),(30,30,30))
    s.paste(side,(0,0)); s.paste(top,(0,side.height)); ImageDraw.Draw(s).text((3,3),vid,fill=(255,255,0))
    s.save(f'{S}/{vid}_ev.png'); print(vid, 'foot', np.round(ev.foot_z,2), 'pit', np.round(ev.pit_z,2), 'frames', len(ev.frames))
