package com.mineclone.game;

import com.mineclone.render.Font;
import com.mineclone.render.TextRenderer;
import com.mineclone.render.TextureAtlas;
import com.mineclone.render.UiRenderer;
import com.mineclone.world.BlockType;
import org.joml.Vector3f;

/** All 2D interface: F3 debug overlay, hotbar, pause menu, main menu. */
public class Hud {
    public enum MenuAction { NONE, START, RESUME, QUIT }

    private final Font font;
    private final TextRenderer text;
    private final UiRenderer ui;
    private final TextureAtlas atlas;

    public Hud(Font font, TextRenderer text, UiRenderer ui, TextureAtlas atlas) {
        this.font = font;
        this.text = text;
        this.ui = ui;
        this.atlas = atlas;
    }

    // ---------------- F3 debug overlay ----------------

    public void drawDebug(int screenW, int screenH, int fps, Vector3f pos,
                          int chunkX, int chunkZ, int loadedChunks, int drawnChunks) {
        float lineH = font.getPixelHeight() + 2;
        float y = lineH;
        String[] lines = {
                "Mineclone  -  F3 debug",
                "FPS: " + fps,
                String.format("XYZ: %.2f / %.2f / %.2f", pos.x, pos.y, pos.z),
                "Chunk: " + chunkX + " , " + chunkZ,
                "Chunks loaded: " + loadedChunks,
                "Chunks drawn: " + drawnChunks,
        };
        for (String s : lines) {
            text.drawShadowed(font, s, 8, y, screenW, screenH, 1f, 1f, 1f);
            y += lineH;
        }
    }

    // ---------------- hotbar ----------------

    public void drawHotbar(int screenW, int screenH, BlockType[] hotbar, int selected) {
        int n = hotbar.length;
        float slot = 52, pad = 4;
        float totalW = n * slot + (n - 1) * pad;
        float x0 = screenW / 2f - totalW / 2f;
        float y0 = screenH - slot - 16;

        ui.begin(screenW, screenH);
        ui.quad(x0 - 6, y0 - 6, totalW + 12, slot + 12, 0f, 0f, 0f, 0.45f);
        for (int i = 0; i < n; i++) {
            float x = x0 + i * (slot + pad);
            ui.quad(x, y0, slot, slot, 0.15f, 0.15f, 0.18f, 0.7f);
            BlockType b = hotbar[i];
            if (b != null && b != BlockType.AIR) {
                int tile = (b == BlockType.GRASS) ? b.topTile : b.sideTile;
                float[] uv = TextureAtlas.uv(tile);
                float inset = 6;
                ui.texQuad(x + inset, y0 + inset, slot - 2 * inset, slot - 2 * inset,
                        atlas.getTextureId(), uv[0], uv[1], uv[2], uv[3], 1f, 1f, 1f, 1f);
            }
            if (i == selected) {
                float t = 3;
                ui.quad(x - t, y0 - t, slot + 2 * t, t, 1f, 1f, 1f, 0.95f);
                ui.quad(x - t, y0 + slot, slot + 2 * t, t, 1f, 1f, 1f, 0.95f);
                ui.quad(x - t, y0, t, slot, 1f, 1f, 1f, 0.95f);
                ui.quad(x + slot, y0, t, slot, 1f, 1f, 1f, 0.95f);
            }
        }
        ui.end();

        for (int i = 0; i < n; i++) {
            float x = x0 + i * (slot + pad);
            text.drawShadowed(font, String.valueOf(i + 1), x + 4, y0 + 16, screenW, screenH, 1f, 1f, 1f);
        }
    }

    // ---------------- menus ----------------

    public MenuAction drawMainMenu(int screenW, int screenH, double mx, double my, boolean clicked) {
        return menu(screenW, screenH, "MINECLONE",
                new String[]{"Start Game", "Quit"},
                new MenuAction[]{MenuAction.START, MenuAction.QUIT},
                mx, my, clicked, false);
    }

    public MenuAction drawPauseMenu(int screenW, int screenH, double mx, double my, boolean clicked) {
        return menu(screenW, screenH, "PAUSED",
                new String[]{"Back to Game", "Quit"},
                new MenuAction[]{MenuAction.RESUME, MenuAction.QUIT},
                mx, my, clicked, true);
    }

    private MenuAction menu(int w, int h, String title, String[] labels, MenuAction[] actions,
                            double mx, double my, boolean clicked, boolean dimWorld) {
        float bw = 300, bh = 50, gap = 16;
        float startY = h / 2f - (labels.length * (bh + gap)) / 2f + 20;
        MenuAction result = MenuAction.NONE;
        boolean[] hover = new boolean[labels.length];

        ui.begin(w, h);
        if (dimWorld) ui.quad(0, 0, w, h, 0f, 0f, 0f, 0.6f);
        for (int i = 0; i < labels.length; i++) {
            float x = w / 2f - bw / 2f, y = startY + i * (bh + gap);
            hover[i] = mx >= x && mx <= x + bw && my >= y && my <= y + bh;
            float c = hover[i] ? 0.52f : 0.28f;
            ui.quad(x, y, bw, bh, c, c, c + 0.06f, 0.95f);
            ui.quad(x, y, bw, 2, 1f, 1f, 1f, 0.25f);
            if (hover[i] && clicked) result = actions[i];
        }
        ui.end();

        float tw = font.textWidth(title);
        text.drawShadowed(font, title, w / 2f - tw / 2f, startY - 36, w, h, 1f, 0.95f, 0.55f);
        for (int i = 0; i < labels.length; i++) {
            float y = startY + i * (bh + gap);
            float lw = font.textWidth(labels[i]);
            text.drawShadowed(font, labels[i], w / 2f - lw / 2f,
                    y + bh / 2f + font.getPixelHeight() * 0.34f, w, h, 1f, 1f, 1f);
        }
        return result;
    }
}
