import sys, os, json, numpy as np
from PIL import Image, ImageDraw
import ortho as O, review as R
S=sys.argv[1]; glass=json.load(open(sys.argv[2])); ids=sys.argv[3:] or sorted(glass)
tiles=[]
for vid in ids:
    tris,uvs,tex=O.model(vid); eye=O.T.eyes(vid)[0]
    g=np.array(glass.get(vid,{}).get('Triangles',[]),float).reshape(-1,3,3)
    row=[]
    for view,(w,h),c in (('left',(2.6,0.9),np.array([0,eye[1]+0.3,eye[2]-0.3])),('front',(1.2,0.9),np.array([0,eye[1]+0.3,eye[2]])),('top',(1.0,2.6),np.array([0,eye[1],eye[2]-0.3]))):
        col,dep,_=O.render(tris,uvs,tex,view,c,w,h,0.01)
        im=O.image(col,dep,1); dr=ImageDraw.Draw(im,'RGBA')
        for t in g: dr.polygon([O.to_pixel(p,view,c,w,h,0.01) for p in t],fill=(0,255,255,40))
        if view=='top': im=im.transpose(Image.ROTATE_90)
        row.append(im)
    t=Image.new('RGB',(sum(i.width for i in row),max(i.height for i in row)),(30,30,30)); x=0
    for i in row: t.paste(i,(x,0)); x+=i.width
    ImageDraw.Draw(t).text((3,3),vid,fill=(255,255,0)); tiles.append(t)
os.makedirs(S,exist_ok=True)
for s in range(0,len(tiles),5):
    grp=tiles[s:s+5]; w=max(i.width for i in grp); h=sum(i.height for i in grp)
    sh=Image.new('RGB',(w,h),(30,30,30)); y=0
    for i in grp: sh.paste(i,(0,y)); y+=i.height
    sh.save(f'{S}/sheet{s//5:02d}.png')
print(len(tiles))
