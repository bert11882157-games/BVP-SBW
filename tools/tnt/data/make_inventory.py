import json,glob,os,collections,hashlib,sys
OUT=sys.argv[1] if len(sys.argv)>1 else '/home/claude/tnt/munition_inventory.tsv'
os.chdir('/home/claude/bvp-sbw')
B='bvp/src/generated/resources/data/berts_vehicle_pack/sbw'; S='sbw/src/main/resources/data/superbwarfare/sbw'
lang={}
for f in ('bvp/src/generated/resources/assets/berts_vehicle_pack/lang/en_us.json','sbw/src/main/resources/assets/superbwarfare/lang/en_us.json'): lang.update(json.load(open(f)))
L=lambda k,d=None:(lang.get(k) or d or k or '').replace('%1$s','').strip()
def load(p): return json.load(open(p)) if os.path.exists(p) else None
def prof(pid):
    if not pid: return None
    ns,path=pid.split(':',1); return load(f'{B if ns=="berts_vehicle_pack" else S}/projectile_profiles/{path}.json')
def nice(rid): p=rid.split(':')[-1]; return lang.get(f'weapon.berts_vehicle_pack.{p}') or p.replace('_',' ').upper()
FAM={'cannon_shell':'tank_shell','small_cannon_shell':'autocannon','projectile':'bullet','wire_guide_missile':'guided_missile','ataka_missile':'guided_missile','ru_9m336_missile':'sam','s8ko_rocket':'rocket','s13_rocket':'rocket','small_rocket':'rocket','medium_rocket':'rocket','gun_grenade':'grenade','mk_82':'bomb','sc_250':'bomb','sc_50':'bomb','melon_bomb':'bomb','agm_65':'agm','kh_39':'agm','mortar_shell':'mortar','swarm_drone':'loitering_munition','ray':'laser','grapeshot':'grapeshot','javelin_missile':'atgm','igla_9k38_missile':'manpads','rpg_rocket_tbg':'rocket','rpg_rocket_standard':'rocket','super_star_projectile':'special'}
NOPROJ={'type_63':'medium_rocket','sodayo_pick_up_rocket':'medium_rocket','mortar':'mortar_shell'}
HE={'HE','HEAT','HEAT_FS','ATGM','APHE','HESH','CM','WP'}; KIN={'APFSDS','APDS','APCR','AP','APCBC'}
def extn(pr):
    o=[]
    for k,v in ((pr or {}).get('Extensions') or {}).items():
        if k.endswith('heavy_warhead_blast_v1'): o.append(f"heavy_warhead(inner={v['InnerRadius']},outer={v['OuterRadius']},heavy={round(v['HeavyDamageFraction'],3)},light={round(v['LightDamageFraction'],3)})")
        if k.endswith('warhead_fragments_v1'): o.append(f"fragments(n={v['Count']},range={v['Range']},dmg={v['Damage']},maxHits={v['MaxHitsPerTarget']})")
        if k.endswith('ground_vehicle_blast_v1'): o.append('ground_vehicle_blast_v1')
    return o
def F(x): return '' if x is None or x=='' else ('%g'%round(x,4) if isinstance(x,float) else str(x))
R=[]
def add(i,n,k,c,f,ed,er,notes): R.append([i,n,k,F(c),f,F(ed),F(er),'; '.join(x for x in notes if x)])
for ns,root in (('berts_vehicle_pack',B+'/vehicles'),('superbwarfare',S+'/vehicles')):
  for f in sorted(glob.glob(root+'/*.json')):
    j=load(f); vid=os.path.basename(f)[:-5]; di=j.get('DestroyInfo') or {}
    if (di.get('ExplosionRadius') or 0)>0: add(f'{ns}:{vid}#destroy',f'{vid} destruction / cook-off','vehicle_destruction','',f,di.get('ExplosionDamage',0),di.get('ExplosionRadius',0),[f"DestroyInfo particle={di.get('ParticleType','MINI')}",'keepBlock','SympatheticDetonation' if di.get('SympatheticDetonation') else ''])
    for wn,w in (j.get('Weapons') or {}).items():
      if not isinstance(w,dict): continue
      for tag,ov in [('base',{})]+[(f'{lk}[{i}]',a['Override']) for lk in ('AmmoType','ProjectileBeltAmmoType') for i,a in enumerate(w.get(lk) if isinstance(w.get(lk),list) else []) if isinstance(a,dict) and 'Override' in a]:
        m={k:v for k,v in w.items() if k not in('AmmoType','ProjectileBeltAmmoType')}; m.update(ov)
        p=m.get('Projectile'); pt,pid,pc=(p.get('Type'),p.get('Profile'),p.get('CaliberMm')) if isinstance(p,dict) else (p,None,None)
        pt=pt or ('superbwarfare:'+NOPROJ[vid] if vid in NOPROJ else None); pr=prof(pid); c=(pr or {}).get('Combat') or {}
        ed=m.get('ExplosionDamage',0) or 0; er=m.get('ExplosionRadius',0) or 0; hdc=c.get('HullDamageClass'); sh=m.get('ShellType'); ex=extn(pr)
        if not(ed>0 or er>0 or hdc in HE or sh in HE or ex): continue
        s=(pt or '').split(':')[-1]; cl=(hdc or sh or '').lower(); cal=c.get('CaliberMm',pc); rid=c.get('RoundId')
        fl=(['FX_ONLY(ED=0)'] if ed==0 and er>0 else [])+(['RADIUS_ZERO'] if ed>0 and er==0 else [])+(['NO_BLAST(ED=ER=0)'] if ed==0 and er==0 else [])+(['KINETIC_WITH_BLAST'] if (hdc in KIN or (hdc is None and sh=='AP')) and ed>0 else [])
        if ns=='berts_vehicle_pack' and cal and s in('small_cannon_shell','projectile') and cal<=30: fl.append('mount_scale=%s'%('0.33' if abs(cal-12.7)<1e-3 else '0.5' if s=='small_cannon_shell' else '1'))
        add(f'{ns}:{vid}/{wn}'+('' if tag=='base' else f'[{tag}]'),(L(ov['Name']) if 'Name' in ov else None) or (nice(rid) if rid else L(m.get('Name'),wn)),FAM.get(s,s or 'unknown')+('_'+cl if cl else ''),cal,f,ed,er,[f'Weapons.{wn}'+('' if tag=='base' else f'.{tag}.Override'),f'proj={pt}',f'profile={pid}' if pid else '',f'round={rid}' if rid else '',f'hdc={hdc}' if hdc else '',f'shell={sh}' if sh else '',f"direct={F(m.get('Damage'))}"]+ex+fl)
for f in sorted(glob.glob(B+'/guns/**/*.json',recursive=True)):
    j=load(f); gid='berts_vehicle_pack:guns/'+os.path.relpath(f,B+'/guns')[:-5]; p=j.get('Projectile') or {}; pid=p.get('Profile'); pr=prof(pid); c=(pr or {}).get('Combat') or {}
    ed=j.get('ExplosionDamage',0) or 0; er=j.get('ExplosionRadius',0) or 0; s=p.get('Type','').split(':')[-1]; cl=(c.get('HullDamageClass') or j.get('ShellType') or '').lower()
    add(gid,j.get('Name'),FAM.get(s,s)+('_'+cl if cl else ''),c.get('CaliberMm'),f,ed,er,[f'gun profile; profile={pid}',f"proj={p.get('Type')}",f"round={c.get('RoundId')}",f"direct={F(j.get('Damage'))}"]+extn(pr)+(['FX_ONLY(ED=0)'] if ed==0 and er>0 else []))
for f in sorted(glob.glob(B+'/aircraft_stores/**/*.json',recursive=True)):
    j=load(f); sid='berts_vehicle_pack:aircraft_stores/'+os.path.relpath(f,B+'/aircraft_stores')[:-5]; cat=j.get('Category'); nm=j.get('Name')
    if cat=='VISUAL_ONLY' or (cat=='GUN_POD' and 'GunProfile' not in j): continue
    pr=prof(j.get('ProjectileProfile')); c=(pr or {}).get('Combat') or {}; ms=j.get('MassKg')
    bs=[f'Category={cat}',f'MassKg={F(ms)}' if ms is not None else '',f"profile={j.get('ProjectileProfile')}" if j.get('ProjectileProfile') else '']+extn(pr)
    if 'Bomb' in j:
        b=j['Bomb']; cb=b.get('Cluster')
        add(sid,nm,f"bomb_{b['Mode'].lower()}"+('_cluster' if cb else ''),c.get('CaliberMm'),f,b.get('BlastDamage'),b.get('BlastRadius'),bs+['entity='+('sc_50' if ms<125 else 'sc_250' if ms<1000 else 'mk_82'),f"penetrator={b['Penetrator']}" if b.get('Penetrator') else '','casing inert; bomblets carry damage' if cb else ''])
        if cb: add(sid+'#bomblet',f'{nm} bomblet','cluster_bomblet_'+cb.get('Mode','HE').lower(),'',f,cb.get('BombletDamage'),cb.get('BombletRadius'),[f"count={cb['Count']}",f"profile={cb.get('BombletProfile') or cb.get('SensorProjectileProfile') or 'berts_vehicle_pack:cluster_bomblet'}",'entity=sc_50',f"sensor(radius={cb.get('SensorRadius')},shots={cb.get('SensorShots')})" if cb.get('SensorShots') else ''])
    elif 'Flight' in j: fl=j['Flight']; add(sid,nm,f'missile_{cat.lower()}',c.get('CaliberMm'),f,fl.get('Damage'),fl.get('BlastRadius'),bs+[f"guidance={(j.get('Guidance') or {}).get('Mode')}",'Flight.Damage/BlastRadius handed to external FFA dev.ballistics; damage path not in repo'])
    elif j.get('LaunchGunProfile') or j.get('GunProfile'):
        gp=j.get('LaunchGunProfile') or j.get('GunProfile'); g=load(f"{B}/guns/{gp.split(':')[1]}.json") or {}
        add(sid,nm,cat.lower(),c.get('CaliberMm'),f,g.get('ExplosionDamage'),g.get('ExplosionRadius'),bs+[f'values from gun profile {gp}'])
    elif 'Guidance' in j: add(sid,nm,f'missile_{cat.lower()}',c.get('CaliberMm'),f,'','',bs+['no Flight block: FFA default damage/blast (not in repo)'])
    elif cat in('AIR_TO_AIR','BOMB','ROCKET_POD'): add(sid,nm,f'store_{cat.lower()}_nonlaunchable','',f,'','',bs+['no Bomb/Flight/Guidance: not launchable via store system'])
for f in sorted(glob.glob(S+'/guns/*.json')):
    j=load(f); gid='superbwarfare:'+os.path.basename(f)[:-5]
    for tag,ov in [('base',{})]+[(f'AmmoType[{i}]',a['Override']) for i,a in enumerate(j.get('AmmoType') or []) if isinstance(a,dict) and 'Override' in a]:
        m=dict(j); m.update(ov); ed=m.get('ExplosionDamage',0) or 0; er=m.get('ExplosionRadius',0) or 0
        if not(ed>0 or er>0): continue
        p=m.get('Projectile'); pt=(p.get('Type') if isinstance(p,dict) else p) or ''
        add(gid+('' if tag=='base' else f'[{tag}]'),L('item.'+gid.replace(':','.'),gid),'handheld_'+FAM.get(pt.split(':')[-1],pt.split(':')[-1]),'',f,ed,er,[f'guns {tag}',f'proj={pt}',f"direct={F(m.get('Damage'))}",'RADIUS_ZERO' if er==0 else ''])
for f in sorted(glob.glob(S+'/drone_attachments/*.json')):
    j=load(f); dd=j.get('DropData') or {}; ed=j.get('ExplosionDamage') or dd.get('ExplosionDamage') or 0; er=j.get('ExplosionRadius') or dd.get('Radius') or 0
    if ed or er: add('superbwarfare:drone_attachment/'+os.path.basename(f)[:-5],L('item.'+j['Item'].replace(':','.'),j['Item']),'drone_payload','',f,ed,er,[f"entity={j.get('Entity') or j.get('DisplayEntity')}",'kamikaze' if j.get('IsKamikaze',True) else f"dropped x{j.get('Count')}",f"hit={j.get('HitDamage')}"])
K='sbw/src/main/kotlin/com/atsuishio/superbwarfare/'; EC=K+'config/server/ExplosionConfig.kt'; BJ='bvp/src/main/java/com/yourname/berts_vehicle_pack/entity/projectile/'
for r in [('superbwarfare:hand_grenade','M67 hand grenade','grenade_hand','',EC,120,6,'config m67_grenade_*; HandGrenadeEntity.kt:32, HandGrenade.kt:85'),('superbwarfare:rgo_grenade','RGO hand grenade','grenade_hand','',EC,90,5,'config rgo_grenade_*; RgoGrenadeEntity.kt:65 -> ProjectileTool.causeCustomExplode (+vanilla level.explode 0.5r)'),('superbwarfare:c4','C4','demolition_charge','',EC,300,10,'config c4_*; C4Entity.kt:411'),('superbwarfare:claymore','M18A1 Claymore','mine','',EC,140,4,'config claymore_*; ClaymoreEntity.kt:240 (shot: damage/5 at :228)'),('superbwarfare:blu_43','BLU-43 Dragontooth','mine','',EC,10,2,'config blu_43_*; Blu43Entity.kt:251'),('superbwarfare:edd','EDD entry-denial device','mine','',EC,60,3,'config edd_*; EDDEntity.kt:299'),('superbwarfare:tm_62','TM-62 AT mine','mine','',EC,450,13,'config tm_62_*; Tm62Entity.kt:230'),('superbwarfare:lunge_mine','Lunge mine','melee_charge','',EC,60,4,'config lunge_mine_* (direct 600); LungeMineAttackMessage.kt:48,60'),('superbwarfare:ptkm_1r','PTKM-1R mine body','mine','',EC,100,6,'config ptkm_1r_*; Ptkm1rEntity.kt:292'),('superbwarfare:ptkm_projectile','PTKM-1R EFP submunition','efp','',EC,80,7,'config ptkm_1r_projectile_* (direct 500); PtkmProjectileEntity.kt:161'),('superbwarfare:small_cannon_shell','Small cannon shell (entity default)','autocannon_he','',K+'entity/projectile/SmallCannonShellEntity.kt',80,5,'init default (overwritten by GunData when gun-fired); damageMultiplier 1.25'),('superbwarfare:rpg_rocket_standard','PG-7V (entity default)','rocket_heat','',K+'entity/projectile/RpgRocketStandardEntity.kt',80,5,'init default'),('superbwarfare:rpg_rocket_tbg','TBG-7V thermobaric (entity default)','rocket_thermobaric','',K+'entity/projectile/RpgRocketTBGEntity.kt',200,10,'init default'),('superbwarfare:small_rocket','Small rocket (entity default)','rocket','',K+'entity/projectile/SmallRocketEntity.kt',60,5,'init default'),('superbwarfare:medium_rocket_ap','Medium rocket AP (item)','rocket_ap','',K+'init/ModItems.kt',100,6,'MediumRocketItem(500f,6f,100f,...) :169'),('superbwarfare:medium_rocket_he','Medium rocket HE (item)','rocket_he','',K+'init/ModItems.kt',200,12,'MediumRocketItem(200f,12f,200f,0.2f,40,...) :171'),('superbwarfare:medium_rocket_cm','Medium rocket CM (item)','rocket_cm','',K+'init/ModItems.kt',300,12,'MediumRocketItem(300f,12f,300f,...) :173'),('superbwarfare:swarm_drone','Swarm drone (entity default)','loitering_munition','',K+'entity/projectile/SwarmDroneEntity.kt',80,5,'init default'),('superbwarfare:mortar_shell','Mortar shell (entity default)','mortar','',K+'entity/projectile/MortarShellEntity.kt',100,8,'init default; buildExplosion damageMultiplier 1.25 (:274)'),('superbwarfare:mk_82','Mk 82 (entity default)','bomb','',K+'entity/projectile/Mk82Entity.kt',650,22,'init default; BVP stores >=1000 kg reuse entity via configure()'),('superbwarfare:sc_250','SC 250 (entity default)','bomb','',K+'entity/projectile/Sc250Entity.kt',500,20,'init default; BVP stores 125-999 kg reuse entity'),('superbwarfare:sc_50','SC 50 (entity default)','bomb','',K+'entity/projectile/Sc50Entity.kt',120,11,'init default; BVP stores <125 kg + all cluster bomblets reuse entity'),('superbwarfare:melon_bomb','Melon bomb (entity default)','bomb','',K+'entity/projectile/MelonBombEntity.kt',500,10,'init default'),('superbwarfare:agm_65','AGM-65 (entity default)','agm','',K+'entity/projectile/Agm65Entity.kt',180,12,'init default'),('superbwarfare:kh_39','Kh-39 (entity default)','agm','',K+'entity/projectile/Kh39Entity.kt',180,12,'init default'),('superbwarfare:cannon_shell#cm_submunition','Cannon CM submunition','cluster_submunition','',K+'entity/projectile/CannonShellEntity.kt','5*ED/n','ER/2','CannonShellEntity.kt:253 GunGrenadeEntity x spreadAmount (default 50)'),('superbwarfare:perk/he_bullet','HE Bullet perk','perk','',K+'perk/ammo/HEBullet.kt','1.8*dmg*(1+0.1L)','1.7+0.3L','ProjectileEntity.explosionBullet :1272'),('superbwarfare:perk/micro_missile','Micro Missile perk','perk','',K+'perk/ammo/MicroMissile.kt','ED*(0.8+0.1L)','ER*0.5','scales gun ED/ER'),('superbwarfare:perk/firefly','Firefly perk','perk','',K+'perk/damage/Firefly.kt','6+2L','2+0.5L','headshot burst :31'),('superbwarfare:auto_aimable#air_burst','CIWS projectile-intercept burst','ciws_burst','',K+'entity/vehicle/base/AutoAimableEntity.kt',5,1,':499 keepBlock'),('superbwarfare:vehicle#overkill','Vehicle overkill pop','vehicle_destruction','',K+'entity/vehicle/base/VehicleDestructionLifecycleService.kt',0,0,':85 FX only'),('superbwarfare:turret_wreck#burnout','Turret wreck burnout pop','vehicle_destruction','',K+'entity/vehicle/TurretWreckEntity.kt',0,0,':306 FX only'),('berts_vehicle_pack:s8ko_rocket','S-8KO (entity default)','rocket_heat',80,BJ+'BvpS8KoRocketEntity.java',0,6,'init default; FX_ONLY'),('berts_vehicle_pack:s13_rocket','S-13 (entity default)','rocket_he',122,BJ+'BvpS13RocketEntity.java',115,9,'init default')]: add(*r[:7],[r[7]])
T='\n'.join('\t'.join(str(x).replace('\t',' ') for x in r) for r in [['id','name','kind','caliber_mm','file','explosion_damage','explosion_radius','notes']]+R)+'\n'
if OUT: open(OUT,'w').write(T)
print(len(R),'rows; dup ids:',[k for k,v in collections.Counter(r[0] for r in R).items() if v>1],'sha1',hashlib.sha1(T.encode()).hexdigest()[:12])
