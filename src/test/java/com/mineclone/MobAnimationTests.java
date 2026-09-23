package com.mineclone;

import com.mineclone.render.*;
import com.mineclone.net.*;
import com.mineclone.world.entity.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.util.Random;

final class MobAnimationTests {
    static void runAll(NetworkTests.Runner r) {
        r.run("all eight mob rigs keep grounded feet and falling corpses above the floor", MobAnimationTests::contact);
        r.run("ears muzzles and beaks stay rigidly attached to turning heads", MobAnimationTests::heads);
        r.run("mob foot correction never pulls legs out of their sockets", MobAnimationTests::sockets);
        r.run("rabbit hops in pairs while quadrupeds alternate diagonal legs", MobAnimationTests::gaits);
        r.run("dead mobs stop flapping breathing walking and attacking", MobAnimationTests::dead);
        r.run("remote mob movement interpolates and wraps through the short angle", MobAnimationTests::interpolation);
        r.run("mob snapshot codec rejects nonfinite data in every pose field", MobAnimationTests::codec);
    }
    private static Mob mob(MobType t) { Mob m = new Mob(t,0,0,0,new Random(1)); m.onGround=true; return m; }
    private static void check(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }
    private static float bottom(Matrix4f[] pose) {
        float min=Float.POSITIVE_INFINITY;
        for (Matrix4f a:pose) {
            check(a.isFinite(), "finite matrix");
            min=Math.min(min,a.m31()-(Math.abs(a.m01())+Math.abs(a.m11())+Math.abs(a.m21()))*.5f);
        }
        return min;
    }
    private static void contact() {
        for (MobType t:MobType.values()) for (int i=0;i<80;i++) {
            Mob m=mob(t); m.walkAmount=1; m.walkedDistance=i*.06f; m.animationTime=i*.1f;
            check(Math.abs(bottom(MobRenderer.pose(m)))<1e-5, t+" grounded contact");
            m.dead=true; m.topple=i/79f*1.9f; m.yaw=i*.2f;
            m.deathAxisX=(float)Math.sin(i); m.deathAxisZ=(float)Math.cos(i);
            check(Math.abs(bottom(MobRenderer.pose(m)))<1e-5,t+" corpse floor contact");
        }
    }
    private static void heads() {
        for (MobType t:new MobType[]{MobType.RABBIT,MobType.WOLF,MobType.BIRD}) {
            Mob m=mob(t);
            Matrix4f[] rest=MobRenderer.pose(m);
            int end=t==MobType.WOLF?5:t==MobType.RABBIT?4:3;
            for(int i=0;i<30;i++) {
                m.lookYaw=(float)Math.sin(i)*.65f; m.grazeAmount=i/29f;
                Matrix4f[] pose=MobRenderer.pose(m);
                for(int part=2;part<end;part++) {
                    Matrix4f a=new Matrix4f(rest[1]).invert().mul(rest[part]);
                    Matrix4f b=new Matrix4f(pose[1]).invert().mul(pose[part]);
                    check(a.equals(b,1e-5f),t+" detached head part "+part);
                }
            }
        }
    }
    private static void sockets() {
        for(MobType t:MobType.values()) {
            Mob m=mob(t);
            Matrix4f[] rest=MobRenderer.pose(m);
            int start=switch(t){case RABBIT->4;case WOLF->5;case BIRD->6;default->2;};
            int count=switch(t){case COW,PIG,SHEEP,RABBIT,WOLF->4;default->2;};
            for(int frame=0;frame<40;frame++) {
                m.walkAmount=1; m.walkedDistance=frame*.08f; m.legOffsetA=.28f; m.legOffsetB=-.32f;
                Matrix4f[] pose=MobRenderer.pose(m);
                for(int i=start;i<start+count;i++) {
                    Vector3f a=new Matrix4f(rest[0]).invert().transformPosition(rest[i].transformPosition(new Vector3f(0,.5f,0)));
                    Vector3f b=new Matrix4f(pose[0]).invert().transformPosition(pose[i].transformPosition(new Vector3f(0,.5f,0)));
                    check(a.distance(b)<1e-5,t+" detached leg "+i);
                }
            }
        }
    }
    private static void gaits() {
        Mob rabbit=mob(MobType.RABBIT); rabbit.airborneAmount=1; rabbit.velocity.y=4;
        check(MobAnimation.leg(rabbit,false,false)==MobAnimation.leg(rabbit,false,true),"rear feet push together");
        check(MobAnimation.leg(rabbit,true,false)==MobAnimation.leg(rabbit,true,true),"forefeet reach together");
        for(MobType t:new MobType[]{MobType.COW,MobType.PIG,MobType.SHEEP}) {
            Mob m=mob(t); m.walkAmount=1; m.walkedDistance=.21f;
            check(Math.abs(MobAnimation.leg(m,true,false)+MobAnimation.leg(m,true,true))<1e-6,"opposite forelegs");
            check(MobAnimation.leg(m,true,false)==MobAnimation.leg(m,false,true),"diagonal support");
        }
        Mob bird=mob(MobType.BIRD); bird.airborneAmount=1; bird.animationTime=.07f;
        check(MobAnimation.wing(bird)>1.5,"flight opens wing above horizontal");
        bird.airborneAmount=0; check(MobAnimation.wing(bird)<.12,"folded wing on landing");
        Mob wolf=mob(MobType.WOLF); float rest=MobAnimation.headPitch(wolf);
        wolf.attackSwing=Mob.ATTACK_SWING_TIME*.34f;
        check(MobAnimation.headPitch(wolf)<rest-.25,"bite dips the muzzle");
    }
    private static void dead() {
        for(MobType t:MobType.values()) {
            Mob m=mob(t); m.dead=true; m.topple=1.57f;
            Matrix4f[] a=MobRenderer.pose(m);
            m.animationTime+=2; m.walkedDistance+=5; m.walkAmount=1; m.attackSwing=.2f;
            Matrix4f[] b=MobRenderer.pose(m);
            for(int i=0;i<a.length;i++) check(a[i].equals(b[i],1e-6f), t+" corpse still animates");
        }
    }
    private static void interpolation() {
        Mob source=mob(MobType.WOLF), seen=mob(MobType.WOLF);
        source.yaw=3.1f;
        RemoteMob remote=new RemoteMob(seen); remote.accept(MobSnapshot.capture(1,source));
        source.position.x=1; source.yaw=-3.1f; source.lookYaw=.6f;
        source.attackSwing=.3f; source.airborneAmount=.8f; source.velocity.y=3;
        remote.accept(MobSnapshot.capture(1,source));
        check(seen.position.x==0,"snapshot must not teleport");
        remote.update(.02f);
        check(seen.position.x>0 && seen.position.x<1,"position between snapshots");
        check(Math.abs(seen.yaw)>3,"no full rotation across PI");
        remote.update(.1f);
        check(Math.abs(seen.position.x-1)<1e-5 && Math.abs(seen.lookYaw-.6f)<1e-5,"pose reaches target");
        check(seen.attackSwing>0 && seen.airborneAmount==.8f && seen.velocity.y==3,"action state received");
        remote.update(1); check(seen.attackSwing==0,"attack expires after packet loss");
        source.position.x=100; remote.accept(MobSnapshot.capture(1,source));
        check(seen.position.x==100,"teleports snap without streaking across world");
    }
    private static void codec() {
        Mob source=mob(MobType.BIRD); source.animationTime=7; source.airborneAmount=.7f;
        MobSnapshot s=MobSnapshot.capture(1,source); PacketBuf out=new PacketBuf(); s.write(out);
        check(s.equals(MobSnapshot.read(PacketBuf.reading(out.toBytes()))),"lossless visual snapshot");
        for(int field=0;field<20;field++) {
            byte[] data=out.toBytes(); int at=5+field*4;
            data[at]=0x7f; data[at+1]=(byte)0xc0; data[at+2]=0; data[at+3]=0;
            check(!MobSnapshot.read(PacketBuf.reading(data)).valid(),"NaN field "+field);
        }
    }
}
