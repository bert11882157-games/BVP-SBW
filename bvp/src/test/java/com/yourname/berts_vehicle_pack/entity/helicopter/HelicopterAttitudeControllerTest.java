package com.yourname.berts_vehicle_pack.entity.helicopter;

import net.minecraft.world.phys.Vec3;

/** Deterministic rotor/attitude/force contract; no Minecraft world is created. */
public final class HelicopterAttitudeControllerTest {
    private static int checks;
    private static final double G = 9.80665D;

    public static void main(String[] args) throws Exception {
        boolean coreOnly = args.length == 1 && args[0].equals("--core-only");
        HelicopterFlightProfile[] forces = {HelicopterFlightProfile.mi24a(),
                HelicopterFlightProfile.mi24d(), HelicopterFlightProfile.mi26(),
                HelicopterFlightProfile.ah1f()};
        HelicopterAttitudeProfile[] attitudes = {
                new HelicopterAttitudeProfile(18, 16, 28, 3, 60, 75, 8),
                new HelicopterAttitudeProfile(20, 18, 30, 3.2, 60, 75, 8),
                new HelicopterAttitudeProfile(8, 8, 12, 1.5, 35, 50, 8),
                new HelicopterAttitudeProfile(28, 28, 42, 4, 65, 80, 8)};
        for (int index = 0; index < forces.length; index++) {
            if (!coreOnly) {
                Object actual = forces[index].physicalControls().getClass()
                        .getMethod("attitudeProfile").invoke(forces[index].physicalControls());
                require(attitudes[index].equals(actual), "typed profile binding");
            }
            controls(attitudes[index]);
            integrated(forces[index], attitudes[index]);
        }
        for (HelicopterFlightProfile legacy : new HelicopterFlightProfile[] {
                HelicopterFlightProfile.mi24v(), HelicopterFlightProfile.mi28n(),
                HelicopterFlightProfile.ka50(), HelicopterFlightProfile.ah6j(),
                HelicopterFlightProfile.ah1gCobra()}) {
            require(legacy.physicalControls() == null, "legacy opt-out");
        }
        System.out.println("PASS helicopter rotor-coupled attitude: " + checks
                + " assertions; 4 x 1200 integrated force ticks; "
                + (coreOnly ? "source binding/host compile pending" : "typed binding verified")
                + "; no world-contact certification");
    }

    private static boolean step(HelicopterAttitudeController c, HelicopterAttitudeProfile p,
                                long tick, double y, double x, double z, double rotor,
                                boolean enabled, boolean ground, boolean hover, boolean tail,
                                double mx, double my, boolean left, boolean right,
                                double pitchScale, double yawScale, double rollScale) {
        return c.step(p, tick, y, x, z, rotor, enabled, ground, hover, tail,
                mx, my, left, right, pitchScale, yawScale, rollScale);
    }

    private static void controls(HelicopterAttitudeProfile p) {
        HelicopterAttitudeController c = new HelicopterAttitudeController();
        for (int tick = 0; tick < 40; tick++) {
            require(step(c,p,tick,179,12,-15,0,true,false,false,false,512,512,false,true,1,1,1),
                    "zero rotor admitted finite pose");
            near(c.yaw(),179,0,"stopped yaw");
            near(c.pitch(),12,0,"stopped pitch");
            near(c.roll(),-15,0,"stopped roll");
        }
        require(!step(c,p,39,179,12,-15,1,true,false,false,false,8,8,false,true,1,1,1),
                "duplicate tick rejected");
        c.reset();
        require(step(c,p,0,179,0,0,1,true,false,false,false,8,8,false,false,1,1,1),"mouse");
        require(c.yaw()>179 && c.pitch()>0 && c.roll()<0,"native mouse signs");
        double y=c.yaw(), x=c.pitch(), z=c.roll();
        require(step(c,p,1,y,x,z,1,true,true,false,false,8,8,false,true,1,1,1),"ground");
        near(c.pitch(),x,0,"ground pitch unchanged");
        near(c.roll(),z,0,"ground roll unchanged");
        require(c.yaw()-y<=p.yawRateDegreesPerSecond()/400+1e-12,"ground yaw bound");

        for (boolean right : new boolean[] {false,true}) {
            c.reset();
            require(step(c,p,0,0,0,0,1,true,false,false,false,0,0,!right,right,1,1,1),"roll");
            require(c.roll()*(right?1:-1)>0,"keyboard roll sign");
        }
        c.reset();
        require(step(c,p,0,0,0,0,1,true,false,false,true,8,0,false,false,1,1,1),"tail");
        near(c.yaw(),0,0,"damaged tail cannot yaw");
        c.reset();
        require(step(c,p,0,0,0,0,1,true,false,false,false,8,8,false,true,0,0,0),"zero drive");
        near(c.yaw()+c.pitch()+c.roll(),0,0,"zero authority remains zero");

        c.reset();
        float observedYaw=36000F, observedPitch=0F, observedRoll=0F;
        for(int tick=0;tick<400;tick++){
            require(step(c,p,tick,observedYaw,observedPitch,observedRoll,1,true,false,false,false,
                    8,0,false,false,1,1,1),"float entity feedback");
            observedYaw=(float)c.yaw(); observedPitch=(float)c.pitch(); observedRoll=(float)c.roll();
        }
        require(c.yawRateDegreesPerSecond()>0.99D*p.yawRateDegreesPerSecond(),
                "float yaw quantization cannot repeatedly reset the actuator");
        c.reset();
        require(step(c,p,0,0,20,-25,1,true,false,true,false,0,0,false,false,1,1,1),"hover");
        require(c.pitch()<20 && c.roll()>-25,"bounded level assist");
        c.reset();
        require(step(c,p,0,0,90,0,1,true,false,false,false,0,8,false,false,1,1,1),"outside");
        near(c.pitch(),90,0,"no limit snap");
        require(step(c,p,1,0,90,0,1,true,false,false,false,0,-8,false,false,1,1,1),"recover");
        require(c.pitch()<90 && 90-c.pitch()<=p.pitchRateDegreesPerSecond()/20+1e-12,
                "bounded limit recovery");

        c.reset();
        require(step(c,p,0,0,0,0,1,true,false,false,false,8,8,false,true,1,1,1),"seed");
        y=c.yaw(); x=c.pitch(); z=c.roll();
        require(!step(c,p,1,y,x,z,Double.NaN,true,false,false,false,0,0,false,false,1,1,1),
                "invalid rotor");
        near(c.yaw(),y,0,"invalid sample does not install pose");
        require(step(c,p,10,y+70,x,z,1,false,false,false,false,8,8,false,true,1,1,1),
                "gap/new controller");
        near(c.yaw(),y+70,0,"discontinuity preserves supplied pose");
        near(c.pitch(),x,0,"no-pilot pitch");
        near(c.roll(),z,0,"no-pilot roll");
    }

    private static void integrated(HelicopterFlightProfile force, HelicopterAttitudeProfile p) {
        HelicopterPhysicalControls controls=force.physicalControls();
        HelicopterAttitudeController attitude=new HelicopterAttitudeController();
        HelicopterForceModel model=new HelicopterForceModel(force);
        double throttle=0, rotor=0, collective=.5, yaw=179, pitch=0, roll=0, height=0;
        Vec3 motion=new Vec3(0,0,0);
        boolean everAirborne=false;
        for (int tick=0; tick<1200; tick++) {
            boolean damaged=tick>=800;
            if (tick<400) throttle=Math.min(1,throttle+controls.throttlePerSecond()/20);
            if (damaged) throttle=0;
            double target=damaged?0:controls.rotorTarget(throttle,false);
            double previousRotor=rotor;
            rotor=controls.nextRotorPower(rotor,target); // exactly one accepted rotor advance
            require(rotor>=0 && rotor<=1,"rotor range");
            require(Math.abs(rotor-previousRotor)<=Math.max(controls.rotorSpoolUpPerSecond(),
                    controls.rotorSpoolDownPerSecond())/20+1e-12,"single spool bound");
            double collectiveTarget=tick>=200 && tick<420 ? 1 : .5;
            double cs=controls.collectivePerSecond()/20;
            collective=collective<collectiveTarget?Math.min(collectiveTarget,collective+cs):
                    Math.max(collectiveTarget,collective-cs);
            boolean grounded=height<=1e-12;
            double mx=tick>=440 && tick<500?2:0;
            double my=tick>=500 && tick<560?1:0;
            boolean right=tick>=600 && tick<610;
            double beforeYaw=yaw, beforePitch=pitch, beforeRoll=roll;
            require(step(attitude,p,tick,yaw,pitch,roll,rotor,true,grounded,false,false,
                    mx,my,false,right,1,1,1),"integrated pose");
            yaw=attitude.yaw(); pitch=attitude.pitch(); roll=attitude.roll();
            require(Math.abs(yaw-beforeYaw)<=p.yawRateDegreesPerSecond()*rotor/20+1e-10,"yaw rate");
            require(Math.abs(pitch-beforePitch)<=p.pitchRateDegreesPerSecond()*rotor/20+1e-10,"pitch rate");
            require(Math.abs(roll-beforeRoll)<=p.rollRateDegreesPerSecond()*rotor/20+1e-10,"roll rate");
            require(Math.abs(pitch)<=p.maximumPitchDegrees()+1e-10,"pitch limit");
            require(Math.abs(roll)<=p.maximumRollDegrees()+1e-10,"roll limit");
            Vec3[] b=basis(yaw,pitch,roll);
            HelicopterFlightController.Input input=new HelicopterFlightController.Input(
                    motion,motion,b[0],b[1],b[2],b[3],roll,rotor,collective,
                    true,false,false,false,false,right,false);
            HelicopterForceModel.Result result=model.evaluate(input,null); // one force integration
            near(result.rotorPower,rotor,0,"same force/attitude rotor");
            near(result.mainRotorAxis.dot(b[3]),1,1e-12,"same current pose force axis");
            near(result.gravityAccelMps2.y,-G,0,"one physical gravity");
            finite(result.predictedMotion);
            Vec3 proposed=result.predictedMotion;
            double resolvedY=Math.max(-height,proposed.y); // one simple flat-floor move
            height+=resolvedY;
            motion=resolvedY!=proposed.y ? new Vec3(proposed.x,0,proposed.z) : proposed;
            require(height>=-1e-12,"one collision/no sink");
            everAirborne|=height>2;
            if(rotor==0){
                near(attitude.pitchRateDegreesPerSecond(),0,0,"stopped pitch rate");
                near(attitude.rollRateDegreesPerSecond(),0,0,"stopped roll rate");
                near(attitude.yawRateDegreesPerSecond(),0,0,"stopped yaw rate");
                near(result.mainRotorAccelMps2.length(),0,0,"stopped main force");
                near(result.tailRotorAccelMps2.length(),0,0,"stopped tail force");
            }
        }
        require(everAirborne,"spool/collective takeoff");
        near(rotor,0,0,"eventual stopped rotor");
        near(height,0,1e-9,"power loss settles on one floor move");
        near(motion.y,0,0,"no landing pump");
    }

    private static Vec3[] basis(double yaw,double pitch,double roll) {
        double y=Math.toRadians(yaw),p=Math.toRadians(pitch),r=Math.toRadians(roll);
        double sy=Math.sin(y),cy=Math.cos(y),sp=Math.sin(p),cp=Math.cos(p);
        double sr=Math.sin(r),cr=Math.cos(r);
        return new Vec3[]{new Vec3(-sy*cp,-sp,cy*cp),new Vec3(-sy,0,cy),
                new Vec3(-cy,0,-sy),
                new Vec3(-cy*sr-sy*sp*cr,cp*cr,-sy*sr+cy*sp*cr)};
    }
    private static void finite(Vec3 v) {
        require(Double.isFinite(v.x)&&Double.isFinite(v.y)&&Double.isFinite(v.z),"finite motion");
    }
    private static void near(double actual,double expected,double epsilon,String message) {
        require(Double.isFinite(actual)&&Math.abs(actual-expected)<=epsilon,
                message+": "+actual+" != "+expected);
    }
    private static void require(boolean condition,String message) {
        checks++;
        if(!condition)throw new AssertionError(message);
    }
}
