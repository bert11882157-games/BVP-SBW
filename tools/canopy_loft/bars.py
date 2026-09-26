import ortho as O, numpy as np, sys, os
from PIL import Image, ImageDraw
T=O.T
def bars(vid):
    _,geo=T.load_geo(vid); tex=T.load_texture(vid)
    out=[]
    for eye in T.crew_eyes(vid):
        tris,uvs=T.triangles(geo)
        depth=T.render_depth(tris,uvs,tex,eye); caster=T.Caster(tris,uvs,tex,eye)
        for bone,pis,edges in T.bar_quads(geo,tex,depth,eye,caster):
            for a,b in edges: out.append((a+b)/2)
        for members,centre,axis,u1,u2,half in T.bar_boxes(geo,tex,depth,eye,caster):
            for m in members:
                out.extend(list(m[3]))
    return np.array(out).reshape(-1,3)
if __name__=='__main__':
    S=sys.argv[1]; os.makedirs(S,exist_ok=True)
    for vid in sys.argv[2:]:
        P=bars(vid); tris,uvs,tex=O.model(vid); eye=T.eyes(vid)[0]
        c=np.array([0,eye[1]+0.3,eye[2]-0.3]); hw,hh,st=2.6,0.9,0.01
        ims=[]
        for view,(w,h) in (('left',(hw,hh)),('top',(hw,1.2))):
            col,dep,_=O.render(tris,uvs,tex,view,c if view=='left' else np.array([0,eye[1],eye[2]-0.3]),w,h,st)
            im=O.image(col,dep,1); dr=ImageDraw.Draw(im)
            cc=c if view=='left' else np.array([0,eye[1],eye[2]-0.3])
            for p in P:
                x,y=O.to_pixel(p,view,cc,w,h,st); dr.ellipse([x-2,y-2,x+2,y+2],fill=(255,0,255))
            ims.append(im)
        s=Image.new('RGB',(ims[0].width+ims[1].width,max(ims[0].height,ims[1].height)))
        s.paste(ims[0],(0,0)); s.paste(ims[1],(ims[0].width,0)); s.save(f'{S}/{vid}_bars.png'); print(vid,len(P))
