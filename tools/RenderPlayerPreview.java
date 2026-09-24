import com.mineclone.net.RemotePlayer;
import com.mineclone.render.PlayerRenderer;
import com.mineclone.render.SceneLighting;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javax.imageio.metadata.IIOMetadataNode;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/** Hidden, real OpenGL player capture; no saves, input or cloud connection. */
public final class RenderPlayerPreview {
    private static final int W = 360, H = 360;

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length == 0 ? "out-test/player-preview" : args[0]);
        Files.createDirectories(output);
        if (!glfwInit()) throw new IllegalStateException("GLFW failed");
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long window = glfwCreateWindow(W, H, "Player animation verification", 0, 0);
        if (window == 0) throw new IllegalStateException("No GL window");
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        PlayerRenderer renderer = new PlayerRenderer();
        try {
            BufferedImage sheet = new BufferedImage(W * 4, H * 2, BufferedImage.TYPE_INT_RGB);
            var g = sheet.createGraphics();
            String[] labels = {"Idle", "Walking A", "Walking B", "Attack (side)",
                    "Look up", "Look down", "Backpedal", "Head turns / torso stays"};
            for (int pose = 0; pose < labels.length; pose++) {
                float body = pose == 3 ? 1.5708f : 0.35f;
                float head = pose == 7 ? body + 0.9f : body;
                float pitch = pose == 4 ? -1.1f : pose == 5 ? 1.1f : 0f;
                float distance = pose == 1 || pose == 6 ? 0.2618f : pose == 2 ? 0.7854f : 0f;
                float amount = pose == 1 || pose == 2 || pose == 6 ? 1f : 0f;
                if (pose == 6) {
                    var rotation = new com.mineclone.render.BodyRotation();
                    rotation.snap(body, 0);
                    for (int frame = 0; frame < 120; frame++)
                        rotation.update(1f / 60f, head, 0, -(float) Math.sin(head) * 4,
                                (float) Math.cos(head) * 4);
                    body = rotation.bodyYaw;
                }
                BufferedImage shot = render(renderer, body, head, pitch, distance, amount, pose == 3 ? 1 : 0);
                g.drawImage(shot, pose % 4 * W, pose / 4 * H, null);
                g.setColor(Color.WHITE);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 17));
                g.drawString(labels[pose], pose % 4 * W + 12, pose / 4 * H + 26);
            }
            g.dispose();
            ImageIO.write(sheet, "png", output.resolve("poses.png").toFile());

            // Drive the network interpolation at 12 Hz and render at 24 Hz.
            // The final second stands still, so gait/swing decay is visible.
            var writer = ImageIO.getImageWritersByFormatName("gif").next();
            try (var stream = ImageIO.createImageOutputStream(output.resolve("remote-walk.gif").toFile())) {
                writer.setOutput(stream);
                writer.prepareWriteSequence(null);
                RemotePlayer player = new RemotePlayer(1, "preview");
                player.accept(0, 0, 0, 0.35f, 0, RemotePlayer.F_ON_GROUND);
                for (int frame = 0; frame < 72; frame++) {
                    if (frame % 2 == 0) {
                        float distance = Math.min(frame, 48) / 24f * 3f;
                        player.accept((float) Math.sin(0.35) * distance, 0,
                                -(float) Math.cos(0.35) * distance, 0.35f, 0, RemotePlayer.F_ON_GROUND);
                    }
                    if (frame == 35) player.startSwing();
                    player.update(1f / 24f);
                    BufferedImage shot = render(renderer, player.bodyYaw(), player.yaw, player.pitch,
                            player.walkedDistance, player.walkAmount, player.swing);
                    var meta = writer.getDefaultImageMetadata(new javax.imageio.ImageTypeSpecifier(shot), null);
                    var root = (IIOMetadataNode) meta.getAsTree(meta.getNativeMetadataFormatName());
                    var control = (IIOMetadataNode) root.getElementsByTagName("GraphicControlExtension").item(0);
                    control.setAttribute("delayTime", "4");
                    if (frame == 0) {
                        var extensions = new IIOMetadataNode("ApplicationExtensions");
                        var loop = new IIOMetadataNode("ApplicationExtension");
                        loop.setAttribute("applicationID", "NETSCAPE");
                        loop.setAttribute("authenticationCode", "2.0");
                        loop.setUserObject(new byte[] {1, 0, 0});
                        extensions.appendChild(loop);
                        root.appendChild(extensions);
                    }
                    meta.setFromTree(meta.getNativeMetadataFormatName(), root);
                    writer.writeToSequence(new javax.imageio.IIOImage(shot, null, meta), null);
                }
                writer.endWriteSequence();
            } finally { writer.dispose(); }
            System.out.println("PASS: 8 poses and 72 network-animation frames; no OpenGL errors");
        } finally {
            renderer.destroy();
            glfwDestroyWindow(window);
            glfwTerminate();
        }
    }

    private static BufferedImage render(PlayerRenderer renderer, float body, float head, float pitch,
                                         float distance, float amount, float swing) {
        glViewport(0, 0, W, H);
        glClearColor(0.13f, 0.18f, 0.22f, 1);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);
        SceneLighting lighting = SceneLighting.firstPerson(1, 1);
        lighting.camPos.set(0, 1.3f, -3.5f);
        lighting.fogStart = 50;
        lighting.fogEnd = 100;
        lighting.linearOut = 0;
        renderer.render(new Matrix4f().perspective((float) Math.toRadians(43), 1, 0.05f, 100),
                new Matrix4f().lookAt(0, 1.3f, -3.5f, 0, 0.95f, 0, 0, 1, 0), new Vector3f(),
                body, head, pitch, distance, amount, swing, lighting, 1, 0);
        var pixels = BufferUtils.createByteBuffer(W * H * 4);
        glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        if (glGetError() != GL_NO_ERROR) throw new IllegalStateException("OpenGL error");
        BufferedImage result = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        int visible = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int i = (y * W + x) * 4;
            int rgb = (pixels.get(i) & 255) << 16 | (pixels.get(i + 1) & 255) << 8 | (pixels.get(i + 2) & 255);
            result.setRGB(x, H - y - 1, rgb);
            if (rgb != ((pixels.get(0) & 255) << 16 | (pixels.get(1) & 255) << 8 | (pixels.get(2) & 255))) visible++;
        }
        if (visible < 1000) throw new IllegalStateException("Player render is blank");
        return result;
    }
}
