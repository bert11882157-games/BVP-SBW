import ortho as O, numpy as np, sys, os, json
from PIL import Image, ImageDraw
import review as R
S=sys.argv[1]; os.makedirs(S,exist_ok=True)
ids=sys.argv[2:] or O.T.FIGHTERS
tiles=[]
for vid in ids:
    tris,uvs,tex=O.model(vid); eye=O.T.eyes(vid)[0]
    c=np.array([0,eye[1]+0.3,eye[2]-0.3]); hw,hh,st=2.6,0.9,0.01
    col,dep,_=O.render(tris,uvs,tex,'left',c,hw,hh,st)
    im=O.image(col,dep,1); dr=ImageDraw.Draw(im,'RGBA')
    for t in R.glass_tris(vid):
        dr.polygon([O.to_pixel(p,'left',c,hw,hh,st) for p in t], outline=(0,255,255,90))
    ex,ey=O.to_pixel(eye,'left',c,hw,hh,st); dr.ellipse([ex-3,ey-3,ex+3,ey+3],outline=(255,0,0,255))
    dr.text((3,3),vid,fill=(255,255,0,255)); tiles.append(im)
for s in range(0,len(tiles),12):
    grp=tiles[s:s+12]; w,h=grp[0].size
    sheet=Image.new('RGB',(w*3,h*4),(30,30,30))
    for k,im in enumerate(grp): sheet.paste(im,((k%3)*w,(k//3)*h))
    sheet.save(f'{S}/sides{s//12}.png')
print(len(tiles))
