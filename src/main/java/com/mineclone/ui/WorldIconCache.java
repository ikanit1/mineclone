package com.mineclone.ui;

import com.mineclone.save.SaveManager;
import org.lwjgl.BufferUtils;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;

/**
 * Текстуры превью миров для списка. Грузятся лениво, при первом показе
 * строки, и живут, пока открыт экран: список прокручивают туда-сюда, и
 * читать PNG с диска на каждое появление строки незачем.
 */
final class WorldIconCache {

    /** Нет превью — ноль, а не повторные попытки чтения каждый кадр. */
    private static final int MISSING = 0;

    private final SaveManager save;
    private final Map<String, Integer> textures = new HashMap<>();

    WorldIconCache(SaveManager save) {
        this.save = save;
    }

    /** Текстура превью мира или 0, если его нет. */
    int texture(SaveManager.WorldInfo w) {
        Integer tex = textures.get(w.id);
        if (tex != null)
            return tex;
        int id = MISSING;
        if (w.hasIcon) {
            BufferedImage img = save.loadIcon(w.id);
            if (img != null)
                id = upload(img);
        }
        textures.put(w.id, id);
        return id;
    }

    /** Мир изменился (копия, удаление) — забыть его текстуру. */
    void forget(String worldId) {
        Integer tex = textures.remove(worldId);
        if (tex != null && tex != MISSING)
            glDeleteTextures(tex);
    }

    void clear() {
        for (int tex : textures.values())
            if (tex != MISSING)
                glDeleteTextures(tex);
        textures.clear();
    }

    private static int upload(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
        // Строки сверху вниз, как в атласе: v = 0 — верх картинки.
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                buf.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) 0xff);
            }
        buf.flip();
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        return tex;
    }
}
