import sys,json;sys.path.insert(0,'tools/cockpit_models');import eyeview as EV
from PIL import ImageDraw
vid,out,zc,yc,fov=sys.argv[1],sys.argv[2],float(sys.argv[3]),float(sys.argv[4]),float(sys.argv[5])
D=json.load(open(f'bvp/src/generated/resources/data/berts_vehicle_pack/sbw/vehicles/{vid}.json',encoding='utf-8-sig'))
im=EV.render(vid,out,eye=[-8,yc,zc],yaw=90,pitch=0,fov=fov,size=(1000,700),glass=False)
cam=EV.Camera([-8,yc,zc],90,0,fov,1000,700)
d=ImageDraw.Draw(im)
for i,s in enumerate(D['Seats'][:3]):
    e=s.get('CameraPos',{}).get('EyeAttachment')
    if not e: continue
    p=D['Attachments'][e]['Position']
    v=cam.view([[0,p[1],p[2]]]); q=cam.screen(v)[0]
    d.ellipse([q[0]-6,q[1]-6,q[0]+6,q[1]+6],outline=(255,0,0),width=3); d.text((q[0]+8,q[1]),f'{i}',fill=(255,0,0))
# grid
for z in range(int(zc-4),int(zc+5)):
    q=cam.screen(cam.view([[0,yc-2.5,z]]))[0]; d.text((q[0],q[1]),f'z{z}',fill=(255,255,0))
for y in range(int(yc-2),int(yc+3)):
    q=cam.screen(cam.view([[0,y,zc-3.5]]))[0]; d.text((q[0],q[1]),f'y{y}',fill=(255,255,0))
im.save(out)
