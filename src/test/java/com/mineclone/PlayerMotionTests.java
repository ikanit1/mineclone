package com.mineclone;

import com.mineclone.net.RemotePlayer;
import com.mineclone.render.PlayerAnimation;
import com.mineclone.render.PlayerRenderer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Behavioural checks on the live pose, including the exact matrices used by both GPU passes. */
final class PlayerMotionTests {
    static void runAll(NetworkTests.Runner r) {
        r.run("player locomotion is independent of render FPS", PlayerMotionTests::frameRates);
        r.run("player idles naturally and settles after stopping", PlayerMotionTests::idleAndStop);
        r.run("player jump, swimming and flight blend without snapping", PlayerMotionTests::transitions);
        r.run("player attack winds up smoothly and carries the actual tool grip", PlayerMotionTests::attackAndGrip);
        r.run("player support foot meets the floor throughout walking and strafing", PlayerMotionTests::feet);
        r.run("player landing compresses and settles; teleport clears motion", PlayerMotionTests::landing);
        r.run("network movement at 12 Hz does not pulse the player's gait", PlayerMotionTests::network);
        r.run("player head and shoulders remain attached while the torso leans", PlayerMotionTests::attachments);
    }

    private static PlayerAnimation animation() {
        var a = new PlayerAnimation();
        a.reset(new Vector3f(), 0, true, false, false);
        return a;
    }

    private static PlayerAnimation walk(int fps, float speed) {
        var a = animation();
        for (int i = 1; i <= fps; i++)
            a.update(1f / fps, new Vector3f(0, 0, -speed * i / fps), 0, true, false, false, false);
        return a;
    }

    private static void frameRates() {
        var reference = walk(60, 4.8f);
        for (int fps : new int[] {30, 144, 240}) {
            var a = walk(fps, 4.8f);
            near("same speed gives same stride amount", reference.walkAmount(), a.walkAmount(), 0.001f);
            near("same travel gives same phase", reference.phase(), a.phase(), 0.001f);
            near("same gait at all FPS", reference.pose().legA, a.pose().legA, 0.001f);
        }
        check("slow walk does not use a full running stride", walk(144, 0.15f).walkAmount() < 0.04f);
    }

    private static void idleAndStop() {
        var idle = animation();
        float arm = idle.pose().armA;
        idle.update(0.1f, new Vector3f(), 0, true, false, false, false);
        check("breathing moves the arms at rest", Math.abs(idle.pose().armA - arm) > 0.001f);
        var a = walk(60, 4.8f);
        float phase = a.phase(), amount = a.walkAmount();
        Vector3f stop = new Vector3f(0, 0, -4.8f);
        a.update(1f / 60, stop, 0, true, false, false, false);
        check("stopping fades the stride", a.walkAmount() < amount && a.walkAmount() > 0.7f);
        for (int i = 0; i < 90; i++) a.update(1f / 60, stop, 0, true, false, false, false);
        check("legs settle beneath body", Math.abs(a.pose().legA) < 0.001f);
        near("no in-place treadmill", phase, a.phase(), 0.0001f);
    }

    private static void transitions() {
        for (int mode = 0; mode < 3; mode++) {
            var a = walk(60, 4.8f);
            float before = a.pose().legA;
            var p = new Vector3f(0, 0.05f, -4.8f);
            a.update(1f / 60, p, 0, false, mode == 1, mode == 2, false);
            check("state transition must not snap leg", Math.abs(a.pose().legA - before) < 0.22f);
            float phase = a.phase();
            for (int i = 0; i < 90; i++) a.update(1f / 60, p, 0, false, mode == 1, mode == 2, false);
            near("air/water/flight do not advance ground stride", phase, a.phase(), 0.0001f);
            check("ground stride fades out", a.walkAmount() < 0.001f);
            float first = a.pose().armA;
            for (int i = 0; i < 15; i++) a.update(1f / 60, p, 0, false, mode == 1, mode == 2, false);
            if (mode == 1) check("swimming strokes continue without walking", Math.abs(a.pose().armA - first) > 0.05f);
        }
    }

    private static void attackAndGrip() {
        var a = animation();
        float rest = a.pose().armB;
        a.startSwing();
        near("swing event itself does not snap pose", rest, a.pose().armB, 0.0001f);
        a.update(1f / 240, new Vector3f(), 0, true, false, false, false);
        check("wind-up starts gently", Math.abs(a.pose().armB - rest) < 0.02f);
        float peak = 0;
        var held = new com.mineclone.world.ItemStack(com.mineclone.item.Items.get().get("iron_pickaxe"), 1);
        for (int frame = 0; frame < 120; frame++) {
            a.update(1f / 240, new Vector3f(), 0, true, false, false, false);
            peak = Math.max(peak, a.pose().armB);
            for (float yaw : new float[] {0, 1.4f, -3.1f}) {
                var palm = PlayerRenderer.handPose(new Vector3f(), yaw, a.pose());
                Vector3f hand = PlayerRenderer.partPose(PlayerRenderer.RIGHT_ARM, new Vector3f(), yaw, yaw, 0, a.pose(), new Matrix4f())
                        .transformPosition(new Vector3f(0, -0.36f, -0.2f));
                near("palm follows live shoulder and arm", palm.getTranslation(new Vector3f()).distance(hand), 0, 0.00001f);
                near("palm never scales held items", palm.determinant(), 1, 0.00001f);
                var grip = com.mineclone.render.ThirdPersonItemRenderer.itemPose(palm, held)
                        .transformPosition(new Vector3f(com.mineclone.render.HeldToolTemplate.GRIP_X,
                                com.mineclone.render.HeldToolTemplate.GRIP_Y, 0));
                near("tool stays in the live palm", grip.distance(hand), 0, 0.00001f);
            }
        }
        check("attack has a readable forward extension", peak > 1.4f);
        check("arm returns after follow-through", Math.abs(a.pose().armB) < 0.1f);
    }

    private static void feet() {
        for (boolean strafe : new boolean[] {false, true}) {
            var a = animation();
            for (int i = 1; i <= 180; i++) {
                a.update(1f / 60, new Vector3f(strafe ? i * 0.08f : 0, 0, strafe ? 0 : -i * 0.08f),
                        0, true, false, false, true);
                float lowest = Float.POSITIVE_INFINITY;
                for (int part : new int[] {2, 3}) {
                    Matrix4f m = PlayerRenderer.partPose(part, new Vector3f(), 0, 0, 0, a.pose(), new Matrix4f());
                    for (float x : new float[] {-0.5f, 0.5f}) for (float z : new float[] {-0.5f, 0.5f})
                        lowest = Math.min(lowest, m.transformPosition(new Vector3f(x, -0.5f, z)).y);
                }
                near("one support foot remains on floor, neither penetrates", lowest, 0, 0.00001f);
            }
        }
    }

    private static void landing() {
        var a = animation();
        Vector3f p = new Vector3f(0, 2, 0);
        a.reset(p, 0, false, false, false);
        for (int i = 1; i <= 20; i++) {
            p.y = 2 - i * 0.1f;
            a.update(1f / 60, p, 0, false, false, false, false);
        }
        a.update(1f / 60, p, 0, true, false, false, false);
        check("landing absorbs impact with leg compression", a.pose().legLengthA < 0.99f);
        for (int i = 0; i < 90; i++) a.update(1f / 60, p, 0, true, false, false, false);
        near("landing spring settles", a.pose().legLengthA, 1, 0.001f);
        a.startSwing();
        a.update(1f / 60, new Vector3f(100, 0, 0), 0, true, false, false, false);
        near("teleport doesn't become a long stride", a.walkAmount(), 0, 0.0001f);
        near("teleport clears old attack", a.pose().armA, 0.025f, 0.0001f);
        near("teleport clears right-hand attack", a.pose().armB, -0.025f, 0.0001f);
    }

    private static void network() {
        var p = new RemotePlayer(2, "animation test");
        p.accept(0, 0, 0, 0, 0, RemotePlayer.F_ON_GROUND);
        float low = 1, high = 0;
        for (int i = 1; i < 240; i++) {
            if (i % 5 == 0) p.accept(0, 0, -i * 0.08f, 0, 0, RemotePlayer.F_ON_GROUND);
            p.update(1f / 60);
            if (i > 120) { low = Math.min(low, p.walkAmount); high = Math.max(high, p.walkAmount); }
        }
        check("network stride should stay strong", low > 0.85f);
        check("steady packets do not cause visible stride pulses", high - low < 0.035f);
    }

    private static void attachments() {
        var a = walk(60, 4.8f);
        for (float pitch : new float[] {-1.4f, 0, 1.4f}) {
            Matrix4f torso = PlayerRenderer.partPose(0, new Vector3f(), 0, 0, pitch, a.pose(), new Matrix4f());
            Vector3f expectedNeck = torso.transformPosition(new Vector3f(0, (1.37f - 1.05f) / 0.7f, 0));
            Matrix4f head = PlayerRenderer.partPose(1, new Vector3f(), 0, 0.5f, pitch, a.pose(), new Matrix4f());
            near("neck follows torso without separating", head.transformPosition(new Vector3f(0, -0.5f, 0))
                    .distance(expectedNeck), 0, 0.00001f);
            for (int part : new int[] {4, 5}) {
                float side = part == 4 ? -0.37f : 0.37f;
                Vector3f shoulder = new Matrix4f(torso).transformPosition(new Vector3f(side / 0.5f, (1.38f - 1.05f) / 0.7f, 0));
                Vector3f arm = PlayerRenderer.partPose(part, new Vector3f(), 0, 0, pitch, a.pose(), new Matrix4f())
                        .transformPosition(new Vector3f(0, 0.5f, 0));
                near("shoulder joint remains attached", shoulder.distance(arm), 0, 0.00001f);
            }
        }
    }

    private static void near(String message, float a, float b, float tolerance) {
        check(message + ": " + a + " vs " + b, Math.abs(a - b) <= tolerance);
    }
    private static void check(String message, boolean ok) { if (!ok) throw new AssertionError(message); }
}
