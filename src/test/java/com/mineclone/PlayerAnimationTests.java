package com.mineclone;

import com.mineclone.render.BodyRotation;
import com.mineclone.render.PlayerRenderer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Checks the actual colour/shadow matrices without creating an OpenGL window. */
final class PlayerAnimationTests {
    static void runAll(NetworkTests.Runner r) {
        r.run("third-person tools stay in the animated palm at every heading and swing", PlayerAnimationTests::thirdPersonGrip);
        r.run("backpedalling keeps the torso facing the gaze", PlayerAnimationTests::backwards);
        r.run("attack hand reaches forward at every body heading", PlayerAnimationTests::attack);
        r.run("looking up and down keeps the neck attached", PlayerAnimationTests::neck);
        r.run("walking limbs stay attached and alternate", PlayerAnimationTests::limbs);
        r.run("tool display keeps the grip stable and lowers on unequip", PlayerAnimationTests::toolGrip);
        r.run("all registered tools share their class silhouette and opaque material pixels", PlayerAnimationTests::toolTemplates);
        r.run("extruded item sprites preserve holes and omit internal walls", PlayerAnimationTests::spriteGeometry);
        r.run("every tool asset puts opaque handle geometry inside the grip", PlayerAnimationTests::toolAssets);
    }

    private static void thirdPersonGrip() throws Exception {
        for (var tool:com.mineclone.item.Items.get().all()) {
            if(tool.tool==null) continue;
            var held=new com.mineclone.world.ItemStack(tool,1);
            for(float yaw:new float[]{0,1.4f,-3.1f}) for(float swing:new float[]{0,.3f,.8f,1}) {
                Matrix4f palm=PlayerRenderer.handPose(new Vector3f(),yaw,.32f,.9f,swing);
                Vector3f grip=com.mineclone.render.ThirdPersonItemRenderer.itemPose(palm,held)
                        .transformPosition(new Vector3f(com.mineclone.render.HeldToolTemplate.GRIP_X,
                                com.mineclone.render.HeldToolTemplate.GRIP_Y,0));
                Vector3f hand=matrix(PlayerRenderer.RIGHT_ARM,yaw,0,.32f,.9f,swing).transformPosition(new Vector3f(0,-.36f,-.2f));
                check("actual tool handle must occupy the animated hand",grip.distance(hand)<1e-5);
                check("socket must not squash the tool",Math.abs(palm.determinant()-1)<1e-5);
            }
        }
    }

    private static void backwards() {
        for (float yaw : new float[] {0f, 1.2f, -3.1f}) {
            BodyRotation body = new BodyRotation();
            body.snap(yaw, 0f);
            for (int i = 0; i < 120; i++)
                body.update(1f / 60f, yaw, 0f, -(float) Math.sin(yaw) * 4f,
                        (float) Math.cos(yaw) * 4f);
            check("walking backwards must not twist the neck", Math.abs(body.headOffset()) < 0.02f);
        }
    }

    private static void toolGrip() {
        for (float equip : new float[] {0f, 0.3f, 0.7f, 1f})
            for (float swing : new float[] {0f, 0.25f, 0.5f, 0.8f, 1f})
                for (float inspect : new float[] {0f, 0.5f, 1f}) {
                    Vector3f grip = com.mineclone.render.HeldItemRenderer.toolPose(equip, swing,
                            1.3f, true, inspect, 0.7f).transformPosition(new Vector3f(0.47f, -0.47f, 0));
                    Vector3f spunGrip = com.mineclone.render.HeldItemRenderer.toolPose(equip, swing,
                            1.3f, true, inspect, 2.5f).transformPosition(new Vector3f(0.47f, -0.47f, 0));
                    check("inspection rotates around the grip", grip.distance(spunGrip) < 1e-5f);
                    check("tool stays in front of camera", grip.z < -0.8f);
                }
        Vector3f up = com.mineclone.render.HeldItemRenderer.toolPose(1, 0, 0, false)
                .transformPosition(new Vector3f(0.47f, -0.47f, 0));
        Vector3f down = com.mineclone.render.HeldItemRenderer.toolPose(0, 0, 0, false)
                .transformPosition(new Vector3f(0.47f, -0.47f, 0));
        check("unequipping exits through the bottom", down.y < up.y - 1f);
    }

    private static void toolTemplates() {
        var reference = new java.util.EnumMap<com.mineclone.item.ToolClass, float[]>(com.mineclone.item.ToolClass.class);
        var atlas = com.mineclone.render.TextureAtlas.assemble();
        int count = 0;
        for (var tool : com.mineclone.item.Items.get().all()) {
            if (tool.tool == null) continue;
            count++;
            var mesh = com.mineclone.render.HeldToolTemplate.mesh(tool);
            var previous = reference.putIfAbsent(tool.tool.toolClass(), mesh.positions());
            if (previous != null)
                check(tool.id + " must use the shared silhouette", java.util.Arrays.equals(previous, mesh.positions()));
            for (int i = 0; i < mesh.uvs().length; i += 2) {
                int x = (int) (mesh.uvs()[i] * atlas.getWidth());
                int y = (int) (mesh.uvs()[i + 1] * atlas.getHeight());
                check(tool.id + " must not have transparent holes in its material",
                        (atlas.getRGB(x, y) >>> 24) >= 128);
            }
        }
        check("all current material variants covered", count >= 18);
    }

    private static void spriteGeometry() {
        var sprite = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        sprite.setRGB(0, 0, 0xffa08040);
        sprite.setRGB(1, 0, 0xffcccccc);
        int tile = 59;
        var mesh = com.mineclone.render.ItemSpriteMesh.build(sprite, tile);
        check("two joined pixels have ten exposed faces", mesh.indices().length == 60);
        float[] xyz = mesh.positions();
        for (int i = 0; i < xyz.length; i += 3) {
            check("transparent lower half must have no geometry", xyz[i + 1] >= 0f);
            check("sprite has thickness", Math.abs(Math.abs(xyz[i + 2]) - 0.045f) < 1e-6f);
        }
        for (int i = 0; i < mesh.uvs().length; i += 2) {
            float px = mesh.uvs()[i] * com.mineclone.render.TextureAtlas.ATLAS_SIZE
                    - tile % 16 * com.mineclone.render.TextureAtlas.TILE;
            float py = mesh.uvs()[i + 1] * com.mineclone.render.TextureAtlas.ATLAS_SIZE
                    - tile / 16 * com.mineclone.render.TextureAtlas.TILE;
            int color = sprite.getRGB((int) (px * 2 / com.mineclone.render.TextureAtlas.TILE),
                    (int) (py * 2 / com.mineclone.render.TextureAtlas.TILE));
            check("every face samples an opaque source pixel", (color >>> 24) == 255);
        }
    }

    private static void toolAssets() {
        String[] names = com.mineclone.render.TextureAtlas.TILE_NAMES;
        for (int tile = 0; tile < names.length; tile++) {
            if (!names[tile].matches(".*_(pickaxe|axe|shovel)")) continue;
            var mesh = com.mineclone.render.ItemSpriteMesh.buildTool(
                    com.mineclone.render.TextureAtlas.sprite(tile), tile);
            float[] positions = mesh.positions();
            boolean covered = false;
            for (int i = 0; i < positions.length; i += 12) {
                float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
                float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
                for (int v = 0; v < 4; v++) {
                    minX = Math.min(minX, positions[i + v * 3]);
                    maxX = Math.max(maxX, positions[i + v * 3]);
                    minY = Math.min(minY, positions[i + v * 3 + 1]);
                    maxY = Math.max(maxY, positions[i + v * 3 + 1]);
                }
                if (maxX > minX && maxY > minY && minX <= 0.470001f && maxX >= 0.469999f
                        && minY <= -0.469999f && maxY >= -0.470001f) covered = true;
            }
            check(names[tile] + " handle must occupy the palm", covered);
        }
    }

    private static void attack() throws Exception {
        for (float yaw : new float[] {0f, 1.2f, -3.1f}) {
            Vector3f rest = matrix(PlayerRenderer.RIGHT_ARM, yaw, 0f, 0f, 0f, 0f).transformPosition(new Vector3f(0, -0.5f, 0));
            Vector3f fist = matrix(PlayerRenderer.RIGHT_ARM, yaw, 0f, 0f, 0f, 1f).transformPosition(new Vector3f(0, -0.5f, 0));
            Vector3f forward = new Vector3f((float) Math.sin(yaw), 0, -(float) Math.cos(yaw));
            check("fist must swing in front of the player", fist.sub(rest).dot(forward) > 0.4f);
        }
    }

    private static void neck() throws Exception {
        Vector3f rest = matrix(1, 0, 0, 0, 0, 0).transformPosition(new Vector3f(0, -0.5f, 0));
        for (float pitch : new float[] {-1.4f, -0.6f, 0.6f, 1.4f}) {
            Vector3f neck = matrix(1, 0, pitch, 0, 0, 0).transformPosition(new Vector3f(0, -0.5f, 0));
            check("head must pivot at its base", neck.distance(rest) < 1e-5f);
        }
    }

    private static void limbs() throws Exception {
        for (int part = 2; part < 6; part++) {
            Vector3f anchor = matrix(part, 0, 0, 0, 0, 0).transformPosition(new Vector3f(0, 0.5f, 0));
            for (int frame = 0; frame < 24; frame++) {
                Vector3f moved = matrix(part, 0, 0, frame * 0.12f, 1, 0)
                        .transformPosition(new Vector3f(0, 0.5f, 0));
                check("limb joint stays attached", moved.distance(anchor) < 1e-5f);
            }
        }
        float phase = (float) Math.PI / 12f;
        float leg = matrix(2, 0, 0, phase, 1, 0).transformPosition(new Vector3f(0, -0.5f, 0)).z;
        float arm = matrix(4, 0, 0, phase, 1, 0).transformPosition(new Vector3f(0, -0.5f, 0)).z;
        check("same-side arm and leg alternate", leg * arm < 0f);
    }

    private static Matrix4f matrix(int index, float yaw, float pitch, float distance, float amount,
                                   float swing) throws Exception {
        var parts = PlayerRenderer.class.getDeclaredField("BODY");
        parts.setAccessible(true);
        Object part = java.lang.reflect.Array.get(parts.get(null), index);
        var method = PlayerRenderer.class.getDeclaredMethod("partMatrix", Vector3f.class,
                float.class, float.class, float.class, float.class, float.class, float.class,
                part.getClass(), Matrix4f.class);
        method.setAccessible(true);
        Matrix4f result = new Matrix4f();
        method.invoke(null, new Vector3f(), yaw, yaw, pitch, distance, amount, swing, part, result);
        return result;
    }

    private static void check(String message, boolean ok) {
        if (!ok) throw new AssertionError(message);
    }
}

