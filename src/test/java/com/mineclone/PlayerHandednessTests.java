package com.mineclone;

import com.mineclone.net.RemotePlayer;
import com.mineclone.render.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Anatomical side checks: following an arbitrary arm is not enough to prove handedness. */
public final class PlayerHandednessTests {
    static void runAll(NetworkTests.Runner r) {
        r.run("equipment rests on the player's anatomical right at every heading", PlayerHandednessTests::rightSide);
        r.run("local attacks extend the right fist while the left remains free", PlayerHandednessTests::localAttack);
        r.run("remote attack events animate the right hand and its equipment", PlayerHandednessTests::remoteAttack);
    }

    public static void main(String[] args) {
        rightSide(); localAttack(); remoteAttack();
        System.out.println("PASS: anatomical right-hand grip, local and remote strikes");
    }

    private static void rightSide() {
        Vector3f position = new Vector3f(7, 2, -5);
        for (int heading = 0; heading < 16; heading++) {
            float yaw = heading * (float)Math.PI / 8;
            var animation = new PlayerAnimation();
            animation.reset(position, yaw, true, false, false);
            Vector3f right = new Vector3f((float)Math.cos(yaw), 0, (float)Math.sin(yaw));
            Vector3f palm = PlayerRenderer.handPose(position, yaw, animation.pose()).getTranslation(new Vector3f());
            check(new Vector3f(palm).sub(position).dot(right) > .3f,
                    "held socket is on the LEFT of the player at yaw=" + yaw);
            Vector3f legacyPalm = PlayerRenderer.handPose(position, yaw, 0, 0, 0).getTranslation(new Vector3f());
            check(legacyPalm.sub(position).dot(right) > .3f, "legacy/static poses must use the right hand too");
            Vector3f firstPersonGrip = HeldItemRenderer.toolPose(1, 0, 0, false)
                    .transformPosition(new Vector3f(HeldToolTemplate.GRIP_X, HeldToolTemplate.GRIP_Y, 0));
            check(firstPersonGrip.x > 0, "first-person grip must agree with the third-person right hand");
        }
    }

    private static void localAttack() {
        for (int fps : new int[]{30, 60, 144}) for (int heading = 0; heading < 8; heading++) {
            float yaw = heading * (float)Math.PI / 4;
            Vector3f p = new Vector3f(3, 1, 4);
            var a = new PlayerAnimation(); a.reset(p, yaw, true, false, false);
            Vector3f rightRest = fist(5, p, yaw, a.pose()), leftRest = fist(4, p, yaw, a.pose());
            Vector3f forward = new Vector3f((float)Math.sin(yaw), 0, -(float)Math.cos(yaw));
            float maxRight = 0, maxLeft = 0;
            a.startSwing();
            for (int frame = 0; frame < fps; frame++) {
                a.update(1f / fps, p, yaw, true, false, false, false);
                maxRight = Math.max(maxRight, fist(5, p, yaw, a.pose()).sub(rightRest).dot(forward));
                maxLeft = Math.max(maxLeft, fist(4, p, yaw, a.pose()).sub(leftRest).dot(forward));
                Vector3f palm = PlayerRenderer.handPose(p, yaw, a.pose()).getTranslation(new Vector3f());
                Vector3f rightPalm = PlayerRenderer.partPose(5, p, yaw, yaw, 0, a.pose(), new Matrix4f())
                        .transformPosition(new Vector3f(0, -.36f, -.2f));
                check(palm.distance(rightPalm) < 1e-5f, "equipment must follow the striking right arm");
            }
            check(maxRight > .45f, "right fist never strikes at " + fps + " FPS / yaw " + yaw);
            check(maxLeft < .15f, "left fist incorrectly performs the attack");
        }
    }

    private static void remoteAttack() {
        for (float yaw : new float[]{0, 1.4f, -3.1f}) {
            var remote = new RemotePlayer(3, "right-hand regression");
            remote.accept(0, 0, 0, yaw, 0, RemotePlayer.F_ON_GROUND);
            Vector3f rest = fist(5, remote.position, remote.bodyYaw(), remote.animation.pose());
            remote.startSwing();
            float maxReach = 0;
            for (int frame = 0; frame < 30; frame++) {
                remote.update(1f / 60);
                Vector3f forward = new Vector3f((float)Math.sin(remote.bodyYaw()), 0, -(float)Math.cos(remote.bodyYaw()));
                maxReach = Math.max(maxReach, fist(5, remote.position, remote.bodyYaw(), remote.animation.pose()).sub(rest).dot(forward));
            }
            check(maxReach > .45f, "replicated attack must use the right arm");
        }
    }

    private static Vector3f fist(int part, Vector3f p, float yaw, PlayerAnimation.Pose pose) {
        return PlayerRenderer.partPose(part, p, yaw, yaw, 0, pose, new Matrix4f())
                .transformPosition(new Vector3f(0, -.5f, 0));
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
