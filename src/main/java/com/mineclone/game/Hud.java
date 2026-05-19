package com.mineclone.game;

import com.mineclone.render.Font;
import com.mineclone.render.TextRenderer;
import com.mineclone.render.TextureAtlas;
import com.mineclone.render.UiRenderer;
import com.mineclone.world.BlockType;
import org.joml.Vector3f;

/** All 2D interface: F3 debug overlay, hotbar, pause menu, main menu. */
public class Hud {
    public enum MenuAction {
        NONE,
        CONTINUE,           // main menu: load saves/<DEFAULT_WORLD_ID>
        NEW_WORLD,          // main menu: open New World confirm
        NEW_WORLD_CONFIRM,  // confirm dialog: Yes
        CANCEL,             // confirm dialog: No / explicit dismiss
        SAVE,               // pause menu: trigger saveAll() + toast
        MAIN_MENU,          // pause menu: save and return to main menu
        RESUME, SETTINGS, SETTINGS_BACK, QUIT,
        RESPAWN
    }

    private final Font font;
    private final TextRenderer text;
    private final UiRenderer ui;
    private final TextureAtlas atlas;

    public static final class InventoryAction {
        public final int slot;
        public final BlockType paletteItem;
        public final boolean clearCursor;

        private InventoryAction(int slot, BlockType paletteItem, boolean clearCursor) {
            this.slot = slot;
            this.paletteItem = paletteItem;
            this.clearCursor = clearCursor;
        }

        public static InventoryAction none() { return new InventoryAction(-1, null, false); }
        public static InventoryAction slot(int slot) { return new InventoryAction(slot, null, false); }
        public static InventoryAction palette(BlockType item) { return new InventoryAction(-1, item, false); }
        public static InventoryAction clearCursor() { return new InventoryAction(-1, null, true); }
    }

    public Hud(Font font, TextRenderer text, UiRenderer ui, TextureAtlas atlas) {
        this.font = font;
        this.text = text;
        this.ui = ui;
        this.atlas = atlas;
    }

    // ---------------- F3 debug overlay ----------------

    public void drawDebug(int screenW, int screenH, int fps, Vector3f pos,
            int chunkX, int chunkZ, int loadedChunks, int drawnChunks,
            BlockType target, byte targetMeta, boolean wireframe, int skyLight, int blockLight) {
        float lineH = font.getPixelHeight() + 2;
        float y = lineH;
        long used = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
        long total = Runtime.getRuntime().totalMemory() / (1024 * 1024);
        String targetStr = target == null ? "—" : target.name() + "  meta=" + (targetMeta & 0xFF);
        String[] lines = {
                "Mineclone  -  F3 debug  " + (wireframe ? "[WIREFRAME]" : ""),
                "FPS: " + fps,
                String.format("XYZ: %.2f / %.2f / %.2f", pos.x, pos.y, pos.z),
                "Chunk: " + chunkX + " , " + chunkZ + "   Loaded: " + loadedChunks + "  Drawn: " + drawnChunks,
                "Target: " + targetStr,
                "Light  sky=" + skyLight + "  block=" + blockLight,
                "Memory: " + used + " MB / " + total + " MB",
        };
        for (String s : lines) {
            text.drawShadowed(font, s, 8, y, screenW, screenH, 1f, 1f, 1f);
            y += lineH;
        }
    }

    // ---------------- Water overlay ----------------

    public void drawWaterOverlay(int screenW, int screenH) {
        ui.begin(screenW, screenH);
        // Full-screen tint to mask x-ray through water geometry
        ui.quad(0, 0, screenW, screenH, 0.04f, 0.14f, 0.55f, 0.62f);
        // Slightly lighter center — gives subtle "underwater depth" feel
        float cx = screenW * 0.2f, cy = screenH * 0.2f;
        ui.quad(cx, cy, screenW - 2 * cx, screenH - 2 * cy, 0.08f, 0.22f, 0.65f, 0.12f);
        ui.end();
    }

    // ---------------- Console bar ----------------

    public void drawConsole(int screenW, int screenH, String input) {
        float barH = font.getPixelHeight() + 12;
        float y = screenH - barH;
        ui.begin(screenW, screenH);
        ui.quad(0, y, screenW, barH, 0f, 0f, 0f, 0.72f);
        ui.end();
        text.drawShadowed(font, "> " + input + "|", 8, y + 6, screenW, screenH, 1f, 1f, 1f);
    }

    // ---------------- version label ----------------

    public void drawVersionLabel(int screenW, int screenH) {
        String line1 = "In Development";
        String line2 = "v0.9.0 alpha";
        float padding = 8;
        float lineH = font.getPixelHeight() + 2;

        float w1 = font.textWidth(line1);
        float w2 = font.textWidth(line2);

        // top-right aligned
        text.drawShadowed(font, line1, screenW - w1 - padding, lineH, screenW, screenH, 1f, 0.85f, 0.3f);
        text.drawShadowed(font, line2, screenW - w2 - padding, lineH * 2, screenW, screenH, 0.8f, 0.8f, 0.8f);
    }

    // ---------------- hotbar ----------------

    public void drawHotbar(int screenW, int screenH, BlockType[] hotbar, int selected) {
        int n = Math.min(9, hotbar.length);
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

    public void drawHeldItem(int screenW, int screenH, BlockType held,
            float equipProgress, float swingProgress, float walkDistance, boolean underwater) {
        float swing = Math.max(0f, Math.min(1f, swingProgress));
        float equip = Math.max(0f, Math.min(1f, equipProgress));
        float bob = (float) Math.sin(walkDistance * 5.2f) * 4f;
        float swingArc = (float) Math.sin((1f - swing) * Math.PI);

        float armW = Math.max(70f, screenW * 0.070f);
        float armH = Math.max(150f, screenH * 0.245f);
        float armX = screenW - armW - 38f + swingArc * 46f;
        float armY = screenH - armH + (1f - equip) * 70f + bob + swingArc * 20f;

        ui.begin(screenW, screenH);
        ui.quad(armX + 8f, armY + 16f, armW, armH, 0.34f, 0.20f, 0.14f, 0.95f);
        ui.quad(armX, armY, armW, armH - 14f, 0.74f, 0.51f, 0.35f, 1f);
        ui.quad(armX, armY, armW, 8f, 0.92f, 0.70f, 0.50f, 1f);
        ui.quad(armX + armW - 10f, armY + 10f, 10f, armH - 24f, 0.50f, 0.31f, 0.22f, 1f);
        ui.quad(armX + 8f, armY + armH - 34f, armW - 16f, 26f, 0.54f, 0.34f, 0.23f, 1f);
        if (underwater)
            ui.quad(armX, armY, armW, armH, 0.08f, 0.22f, 0.65f, 0.20f);

        if (held != null && held != BlockType.AIR) {
            int tile = held == BlockType.GRASS ? held.topTile : held.sideTile;
            float[] uv = TextureAtlas.uv(tile);
            float size = Math.max(58f, Math.min(86f, screenH * 0.105f));
            float ix = armX - size * 0.50f - swingArc * 22f;
            float iy = armY + armH * 0.16f + (1f - equip) * 20f - swingArc * 24f;
            int tid = atlas.getTextureId();
            ui.quad(ix - 5f, iy + 7f, size + 10f, size + 10f, 0f, 0f, 0f, 0.30f);
            ui.texQuad(ix + size * 0.18f, iy + size * 0.24f, size, size, tid,
                    uv[0], uv[1], uv[2], uv[3], 0.50f, 0.50f, 0.50f, 1f);
            ui.texQuad(ix, iy, size, size, tid,
                    uv[0], uv[1], uv[2], uv[3], 1f, 1f, 1f, 1f);
            ui.quad(ix, iy, size, 3f, 1f, 1f, 1f, 0.25f);
            ui.quad(ix + size - 3f, iy, 3f, size, 0f, 0f, 0f, 0.22f);
        }
        ui.end();
    }

    // ---------------- health hearts ----------------

    private static final int HEART_EMPTY_TILE = 34;
    private static final int HEART_FULL_TILE  = 35;
    private static final int HEART_HALF_TILE  = 36;

    public void drawHearts(int screenW, int screenH, float health) {
        // Mirror hotbar geometry so hearts sit flush above its left edge
        float slot = 52f, pad = 4f;
        float totalW = 9 * slot + 8 * pad;   // 9 hotbar slots
        float hotbarX = screenW / 2f - totalW / 2f;
        float hotbarY = screenH - slot - 16f;

        float hs  = 14f;  // heart sprite display size (px)
        float gap = 2f;   // gap between hearts
        float x0  = hotbarX;
        float y0  = hotbarY - 6f - hs - 5f;  // just above hotbar backing quad

        float[] uvE = TextureAtlas.uv(HEART_EMPTY_TILE);
        float[] uvF = TextureAtlas.uv(HEART_FULL_TILE);
        float[] uvH = TextureAtlas.uv(HEART_HALF_TILE);
        int tid = atlas.getTextureId();

        ui.begin(screenW, screenH);
        for (int i = 0; i < 10; i++) {
            float x = x0 + i * (hs + gap);
            // Empty container behind every slot
            ui.texQuad(x, y0, hs, hs, tid, uvE[0], uvE[1], uvE[2], uvE[3], 1f, 1f, 1f, 1f);
            float hp = health - i * 2f;
            if (hp >= 2f) {
                ui.texQuad(x, y0, hs, hs, tid, uvF[0], uvF[1], uvF[2], uvF[3], 1f, 1f, 1f, 1f);
            } else if (hp >= 1f) {
                ui.texQuad(x, y0, hs, hs, tid, uvH[0], uvH[1], uvH[2], uvH[3], 1f, 1f, 1f, 1f);
            }
        }
        ui.end();
    }

    // ---------------- death screen ----------------

    public MenuAction drawDeathScreen(int screenW, int screenH,
                                      double mx, double my, boolean clicked) {
        float panelW = Math.min(460f, screenW - 48f);
        float panelH = 230f;
        float panelX = screenW / 2f - panelW / 2f;
        float panelY = screenH / 2f - panelH / 2f;
        float bw = 300, bh = 50;
        float bx = screenW / 2f - bw / 2f;
        float by = panelY + panelH - bh - 28f;
        ui.begin(screenW, screenH);
        ui.quad(0, 0, screenW, screenH, 0.45f, 0f, 0f, 0.72f);
        ui.quad(panelX, panelY, panelW, panelH, 0.03f, 0.03f, 0.04f, 0.78f);
        ui.quad(panelX, panelY, panelW, 2f, 1f, 0.25f, 0.25f, 0.35f);
        ui.quad(panelX, panelY + panelH - 2f, panelW, 2f, 0f, 0f, 0f, 0.55f);
        boolean hover = stoneButton(bx, by, bw, bh, "Возродиться",
                screenW, screenH, mx, my, true, false);
        ui.end();
        String title = "Вы умерли";
        float tw = font.textWidth(title);
        // Keep the old localized string out of view; the ASCII label below is
        // reliable with the bundled bitmap font and avoids mojibake.
        text.drawShadowed(font, title, screenW / 2f - tw / 2f,
                -1000f, screenW, screenH, 1f, 0.3f, 0.3f);

        String clearTitle = "You died!";
        float clearTitleW = font.textWidth(clearTitle);
        text.drawShadowed(font, clearTitle, screenW / 2f - clearTitleW / 2f,
                panelY + 52f, screenW, screenH, 1f, 0.25f, 0.25f);

        String line1 = "Your inventory is safe in this build.";
        String line2 = "Respawn at your world spawn to continue.";
        float line1W = font.textWidth(line1);
        float line2W = font.textWidth(line2);
        text.drawShadowed(font, line1, screenW / 2f - line1W / 2f,
                panelY + 96f, screenW, screenH, 1f, 1f, 1f);
        text.drawShadowed(font, line2, screenW / 2f - line2W / 2f,
                panelY + 126f, screenW, screenH, 0.85f, 0.85f, 0.85f);

        String respawn = "Respawn";
        float respawnW = font.textWidth(respawn);
        text.drawShadowed(font, respawn, bx + (bw - respawnW) / 2f,
                by + bh / 2f + font.getPixelHeight() * 0.34f,
                screenW, screenH, 1f, 1f, 1f);
        if (clicked && hover) return MenuAction.RESPAWN;
        return MenuAction.NONE;
    }

    // ---------------- menus ----------------

    /**
     * @param hasSave  if false, the Continue button is greyed out and unclickable.
     */
    public MenuAction drawMainMenu(int screenW, int screenH, double mx, double my,
                                   boolean clicked, boolean hasSave) {
        drawTitle(screenW, screenH, "MINECLONE", 80f);
        String[] labels   = { "Continue", "New World", "Settings", "Quit" };
        MenuAction[] acts = { MenuAction.CONTINUE, MenuAction.NEW_WORLD,
                              MenuAction.SETTINGS, MenuAction.QUIT };
        boolean[] enabled = { hasSave, true, true, true };
        return stoneMenu(screenW, screenH, labels, acts, enabled,
                mx, my, clicked, /*dimWorld=*/false, /*titleArt=*/true);
    }

    public MenuAction drawPauseMenu(int screenW, int screenH, double mx, double my, boolean clicked) {
        String[] labels   = { "Back to Game", "Save", "Settings", "Main Menu", "Quit" };
        MenuAction[] acts = { MenuAction.RESUME, MenuAction.SAVE,
                              MenuAction.SETTINGS, MenuAction.MAIN_MENU,
                              MenuAction.QUIT };
        boolean[] enabled = { true, true, true, true, true };
        return stoneMenu(screenW, screenH, labels, acts, enabled,
                mx, my, clicked, /*dimWorld=*/true, /*titleArt=*/false);
    }

    public void drawLoading(int screenW, int screenH, float progress, float time) {
        float p = Math.max(0f, Math.min(1f, progress));
        float panelW = Math.min(520f, screenW - 48f);
        float panelH = 170f;
        float panelX = screenW / 2f - panelW / 2f;
        float panelY = screenH / 2f - panelH / 2f;
        float barX = panelX + 42f;
        float barY = panelY + 98f;
        float barW = panelW - 84f;
        float barH = 24f;

        ui.begin(screenW, screenH);
        ui.quad(0, 0, screenW, screenH, 0f, 0f, 0f, 0.58f);
        ui.quad(panelX, panelY, panelW, panelH, 0.08f, 0.08f, 0.10f, 0.88f);
        ui.quad(panelX, panelY, panelW, 2f, 1f, 1f, 1f, 0.18f);
        ui.quad(panelX, panelY + panelH - 2f, panelW, 2f, 0f, 0f, 0f, 0.55f);

        float[] uv = TextureAtlas.uv(BlockType.STONE.sideTile);
        float cell = 24f;
        int cols = Math.max(1, Math.round(barW / cell));
        for (int i = 0; i < cols; i++) {
            float x = barX + i * (barW / cols);
            ui.texQuad(x, barY, barW / cols, barH, atlas.getTextureId(),
                    uv[0], uv[1], uv[2], uv[3], 0.55f, 0.55f, 0.55f, 1f);
        }

        ui.quad(barX + 3f, barY + 3f, Math.max(0f, (barW - 6f) * p), barH - 6f,
                0.42f, 0.78f, 0.34f, 0.95f);
        float sweepX = barX + 3f + ((time * 90f) % Math.max(1f, barW - 6f));
        ui.quad(sweepX, barY + 4f, 18f, barH - 8f, 0.90f, 1.00f, 0.70f, 0.20f);
        ui.quad(barX, barY, barW, 2f, 1f, 1f, 1f, 0.35f);
        ui.quad(barX, barY + barH - 2f, barW, 2f, 0f, 0f, 0f, 0.55f);
        ui.end();

        String dots = ".".repeat(((int) (time * 3f) % 4));
        String title = "Preparing world" + dots;
        float titleW = font.textWidth(title);
        text.drawShadowed(font, title, screenW / 2f - titleW / 2f,
                panelY + 48f, screenW, screenH, 1f, 0.95f, 0.55f);

        String pct = Math.round(p * 100f) + "%";
        float pctW = font.textWidth(pct);
        text.drawShadowed(font, pct, screenW / 2f - pctW / 2f,
                barY + barH + 30f, screenW, screenH, 0.82f, 0.95f, 0.75f);
    }

    // ---- Minecraft-style slider settings ----------------------------------------
    // values[] layout: [0]=renderDist(2-16), [1]=fov(50-120),
    // [2]=brightness(0-2), [3]=volume(0-1)

    private static final float KNOB_W = 10f;
    private static final float[] RANGE_MIN = { 2f, 50f, 0f, 0f };
    private static final float[] RANGE_MAX = { 16f, 120f, 2f, 1f };

    private int draggingSlider = -1;

    /**
     * @param mouseDown  LMB currently held (for dragging)
     * @param mouseClick LMB just pressed this frame (for Done button)
     * @param values     in/out: renderDist, fov, brightness, volume
     */
    public MenuAction drawSettings(int sw, int sh, double mx, double my,
            boolean mouseDown, boolean mouseClick,
            float[] values) {
        float pw = 460f, ph = 360f;
        float px = sw / 2f - pw / 2f, py = sh / 2f - ph / 2f;

        float slW = pw - 60f; // slider track width
        float slX = px + 30f;
        float slH = 20f;
        float rowStep = slH + 24f; // vertical gap between sliders
        float sl0Y = py + 90f;

        float[] slY = new float[4];
        for (int i = 0; i < 4; i++)
            slY[i] = sl0Y + i * rowStep;

        // ── drag logic ────────────────────────────────────────────────────────
        if (!mouseDown && draggingSlider >= 0)
            draggingSlider = -1;
        for (int i = 0; i < 4; i++) {
            if (mouseDown && draggingSlider < 0 && hov(mx, my, slX, slY[i], slW, slH))
                draggingSlider = i;
            if (draggingSlider == i && mouseDown) {
                float t = (float) Math.max(0.0, Math.min(1.0,
                        (mx - slX - KNOB_W / 2f) / (slW - KNOB_W)));
                values[i] = RANGE_MIN[i] + t * (RANGE_MAX[i] - RANGE_MIN[i]);
            }
        }

        // ── Done button ───────────────────────────────────────────────────────
        float bW = 200f, bH = 40f;
        float bX = sw / 2f - bW / 2f, bY = py + ph - 52f;
        boolean hBack = hov(mx, my, bX, bY, bW, bH);

        // ── knob positions ────────────────────────────────────────────────────
        float[] kx = new float[4];
        for (int i = 0; i < 4; i++) {
            float t = Math.max(0f, Math.min(1f,
                    (values[i] - RANGE_MIN[i]) / (RANGE_MAX[i] - RANGE_MIN[i])));
            kx[i] = slX + t * (slW - KNOB_W);
        }

        // ── render quads ──────────────────────────────────────────────────────
        ui.begin(sw, sh);
        ui.quad(0, 0, sw, sh, 0f, 0f, 0f, 0.60f); // dim
        ui.quad(px, py, pw, ph, 0.11f, 0.11f, 0.14f, 0.96f); // panel

        for (int i = 0; i < 4; i++) {
            boolean active = draggingSlider == i;
            boolean hover = hov(mx, my, slX, slY[i], slW, slH);

            // Track — sunken dark groove
            ui.quad(slX, slY[i], slW, slH, 0.19f, 0.19f, 0.19f, 1f);
            ui.quad(slX, slY[i], slW, 1f, 0.10f, 0.10f, 0.10f, 1f); // top shadow
            ui.quad(slX, slY[i] + slH - 1, slW, 1f, 0.35f, 0.35f, 0.35f, 1f); // bottom shine

            // Knob — raised Minecraft button style
            float kc = (active || hover) ? 0.78f : 0.67f;
            ui.quad(kx[i], slY[i], KNOB_W, slH, kc, kc, kc, 1f);
            ui.quad(kx[i], slY[i], KNOB_W, 2f, kc + 0.18f, kc + 0.18f, kc + 0.18f, 1f); // top shine
            ui.quad(kx[i], slY[i] + slH - 2, KNOB_W, 2f, kc - 0.22f, kc - 0.22f, kc - 0.22f, 1f); // bottom shadow
        }

        // Done button
        float bc = hBack ? 0.52f : 0.28f;
        ui.quad(bX, bY, bW, bH, bc, bc, bc + 0.06f, 0.95f);
        ui.quad(bX, bY, bW, 2, 1f, 1f, 1f, 0.22f);
        ui.end();

        // ── render text ───────────────────────────────────────────────────────
        float lhOff = font.getPixelHeight() * 0.34f;

        String titleStr = "Settings";
        float titleW = font.textWidth(titleStr);
        text.drawShadowed(font, titleStr, sw / 2f - titleW / 2f, py + 28f, sw, sh, 1f, 0.95f, 0.55f);

        String[] labels = { "Render Distance", "FOV", "Brightness", "Sound Volume" };
        String[] valStrs = {
                String.valueOf(Math.round(values[0])),
                Math.round(values[1]) + "°",
                Math.round(values[2] * 100f) + "%",
                Math.round(values[3] * 100f) + "%"
        };
        for (int i = 0; i < 4; i++) {
            String s = labels[i] + ": " + valStrs[i];
            float sW = font.textWidth(s);
            text.drawShadowed(font, s, slX + (slW - sW) / 2f,
                    slY[i] + slH / 2f + lhOff, sw, sh, 1f, 1f, 1f);
        }

        String doneStr = "Done";
        float doneW = font.textWidth(doneStr);
        text.drawShadowed(font, doneStr, sw / 2f - doneW / 2f,
                bY + bH / 2f + lhOff, sw, sh, 1f, 1f, 1f);

        if (mouseClick && hBack)
            return MenuAction.SETTINGS_BACK;
        return MenuAction.NONE;
    }

    private static boolean hov(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    /** Stone-tiled beveled button. Returns true if hovered. */
    private boolean stoneButton(float x, float y, float w, float h, String label,
                                int sw, int sh, double mx, double my,
                                boolean enabled, boolean drawText) {
        boolean hover = enabled && hov(mx, my, x, y, w, h);

        // Stone background — tile the atlas STONE sprite across the button.
        // Tile by ~32-px cells so width can be any multiple without UV stretch.
        // Tint dims when disabled, brightens on hover (Minecraft bevel-style).
        float tint = !enabled ? 0.45f : (hover ? 1.15f : 0.85f);
        float[] uv = TextureAtlas.uv(BlockType.STONE.sideTile);
        float cell = 32f;
        int cols = Math.max(1, Math.round(w / cell));
        int rows = Math.max(1, Math.round(h / cell));
        float cw = w / cols, ch = h / rows;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                ui.texQuad(x + c * cw, y + r * ch, cw, ch, atlas.getTextureId(),
                        uv[0], uv[1], uv[2], uv[3], tint, tint, tint, 1f);
            }
        }
        // Bevel: 2-px light top/left, 2-px dark bottom/right.
        float lt = enabled ? 1f : 0.5f;
        float dk = enabled ? 0.10f : 0.18f;
        ui.quad(x, y, w, 2f, lt, lt, lt, 0.50f);              // top highlight
        ui.quad(x, y, 2f, h, lt, lt, lt, 0.40f);              // left highlight
        ui.quad(x, y + h - 2f, w, 2f, dk, dk, dk, 0.55f);     // bottom shadow
        ui.quad(x + w - 2f, y, 2f, h, dk, dk, dk, 0.45f);     // right shadow

        if (drawText) {
            float lw = font.textWidth(label);
            float baseline = y + h / 2f + font.getPixelHeight() * 0.34f;
            float a = enabled ? 1f : 0.45f;
            text.drawShadowed(font, label, x + (w - lw) / 2f, baseline, sw, sh, a, a, a);
        }
        return hover;
    }

    /**
     * Layered pixel-art title: dark 8-direction outline, hard drop shadow,
     * gold fill, bright top highlight. Uses the existing baked font.
     */
    public void drawTitle(int sw, int sh, String title, float topY) {
        float w = font.textWidth(title);
        float x = sw / 2f - w / 2f;
        float y = topY + font.getPixelHeight();

        // Dark outline — 8 offsets
        float[] dx = { -2,  2,  0,  0, -2, -2,  2,  2 };
        float[] dy = {  0,  0, -2,  2, -2,  2, -2,  2 };
        for (int i = 0; i < dx.length; i++)
            text.draw(font, title, x + dx[i], y + dy[i], sw, sh, 0f, 0f, 0f, 0.95f);

        // Hard drop shadow — 4 px down-right, dark olive
        text.draw(font, title, x + 4, y + 4, sw, sh, 0.15f, 0.12f, 0.04f, 0.85f);

        // Main fill — light gold
        text.draw(font, title, x, y, sw, sh, 1.0f, 0.86f, 0.32f, 1f);

        // Bright top highlight — 1 px up, near-white
        text.draw(font, title, x, y - 1, sw, sh, 1.0f, 0.98f, 0.75f, 0.45f);
    }

    private MenuAction stoneMenu(int w, int h, String[] labels, MenuAction[] actions,
                                 boolean[] enabled,
                                 double mx, double my, boolean clicked,
                                 boolean dimWorld, boolean titleArt) {
        float bw = 300, bh = 50, gap = 12;
        float topInset = titleArt ? 200f : 80f;   // leave room for the title
        float totalH = labels.length * (bh + gap) - gap;
        float startY = Math.max(topInset, h / 2f - totalH / 2f + 20);
        MenuAction result = MenuAction.NONE;

        ui.begin(w, h);
        if (dimWorld) ui.quad(0, 0, w, h, 0f, 0f, 0f, 0.6f);

        // Buttons + click handling. Each button's hit area is its own quad,
        // so the disabled-Continue grey state simply ignores clicks.
        for (int i = 0; i < labels.length; i++) {
            float x = w / 2f - bw / 2f, y = startY + i * (bh + gap);
            boolean hover = stoneButton(x, y, bw, bh, labels[i], w, h, mx, my, enabled[i], true);
            if (hover && clicked) result = actions[i];
        }
        ui.end();
        return result;
    }

    /**
     * Modal Yes/No confirm dialog. Returns {@code confirmAction} when Yes is
     * clicked, NONE otherwise. The caller is responsible for ESC handling and
     * for dismissing the dialog after a confirm.
     */
    public MenuAction drawConfirm(int sw, int sh, String message, String confirmLabel,
                                  double mx, double my, boolean clicked,
                                  MenuAction confirmAction) {
        float pw = 460f, ph = 200f;
        float px = sw / 2f - pw / 2f, py = sh / 2f - ph / 2f;
        float bw = 180f, bh = 50f, gap = 20f;
        float by = py + ph - bh - 24f;
        float yesX = sw / 2f - bw - gap / 2f;
        float noX  = sw / 2f + gap / 2f;

        ui.begin(sw, sh);
        ui.quad(0, 0, sw, sh, 0f, 0f, 0f, 0.72f);                  // full dim
        ui.quad(px, py, pw, ph, 0.11f, 0.11f, 0.14f, 0.97f);       // panel
        ui.quad(px, py, pw, 2f, 1f, 1f, 1f, 0.18f);                // top highlight
        boolean hYes = stoneButton(yesX, by, bw, bh, confirmLabel, sw, sh, mx, my, true, true);
        boolean hNo  = stoneButton(noX,  by, bw, bh, "Cancel",     sw, sh, mx, my, true, true);
        ui.end();

        float mw = font.textWidth(message);
        text.drawShadowed(font, message, sw / 2f - mw / 2f,
                py + 56f + font.getPixelHeight(), sw, sh, 1f, 0.95f, 0.55f);

        if (clicked && hYes) return confirmAction;
        if (clicked && hNo)  return MenuAction.CANCEL;
        return MenuAction.NONE;
    }

    public InventoryAction drawInventory(int w, int h, double mx, double my,
            boolean clicked, boolean rightClicked,
            BlockType[] inventory, int selectedSlot, BlockType cursorItem) {
        BlockType[] palette = BlockType.values();
        float slot = 42f;
        float gap = 5f;
        float panelW = 9 * slot + 8 * gap + 52f;
        float panelH = 470f;
        float panelX = w / 2f - panelW / 2f;
        float panelY = h / 2f - panelH / 2f;
        float invX = panelX + 22f;
        float titleY = panelY + 34f;
        float paletteLabelY = panelY + 78f;
        float paletteY = panelY + 96f;
        float invLabelY = panelY + 166f;
        float invY = panelY + 186f;
        float hotbarLabelY = panelY + 356f;
        float hotbarY = panelY + 376f;
        float trashX = panelX + panelW - 70f;
        float trashY = panelY + 24f;

        InventoryAction action = InventoryAction.none();
        BlockType hovered = null;
        float hoverX = 0, hoverY = 0;

        ui.begin(w, h);
        ui.quad(0, 0, w, h, 0f, 0f, 0f, 0.65f);
        ui.quad(panelX, panelY, panelW, panelH, 0.74f, 0.74f, 0.70f, 1f);
        ui.quad(panelX, panelY, panelW, 4f, 1f, 1f, 1f, 0.45f);
        ui.quad(panelX, panelY + panelH - 4f, panelW, 4f, 0f, 0f, 0f, 0.45f);
        ui.quad(trashX, trashY, 44f, 44f, 0.28f, 0.12f, 0.12f, 0.95f);
        ui.quad(trashX + 10f, trashY + 12f, 24f, 4f, 0.95f, 0.95f, 0.95f, 0.85f);
        ui.quad(trashX + 13f, trashY + 18f, 18f, 16f, 0.80f, 0.80f, 0.80f, 0.85f);

        int paletteCols = 9;
        float pSlot = 34f;
        float pGap = 5f;
        for (int i = 1; i < palette.length; i++) {
            BlockType b = palette[i];
            int c = (i - 1) % paletteCols;
            int r = (i - 1) / paletteCols;
            float x = invX + c * (pSlot + pGap);
            float y = paletteY + r * (pSlot + pGap);
            boolean hov = hov(mx, my, x, y, pSlot, pSlot);
            drawSlotBack(x, y, pSlot, hov);
            drawItemIcon(b, x + 4f, y + 4f, pSlot - 8f, 1f);
            if (hov) {
                hovered = b;
                hoverX = x;
                hoverY = y;
                if (clicked)
                    action = InventoryAction.palette(b);
            }
        }

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int slotIndex = 9 + row * 9 + col;
                float x = invX + col * (slot + gap);
                float y = invY + row * (slot + gap);
                boolean hov = hov(mx, my, x, y, slot, slot);
                drawSlotBack(x, y, slot, hov);
                drawItemIcon(inventory[slotIndex], x + 6f, y + 6f, slot - 12f, 1f);
                if (hov) {
                    hovered = inventory[slotIndex];
                    hoverX = x;
                    hoverY = y;
                    if (clicked)
                        action = InventoryAction.slot(slotIndex);
                }
            }
        }

        for (int col = 0; col < 9; col++) {
            float x = invX + col * (slot + gap);
            float y = hotbarY;
            boolean hov = hov(mx, my, x, y, slot, slot);
            drawSlotBack(x, y, slot, hov);
            drawItemIcon(inventory[col], x + 6f, y + 6f, slot - 12f, 1f);
            if (col == selectedSlot) {
                ui.quad(x - 3f, y - 3f, slot + 6f, 3f, 1f, 1f, 1f, 0.95f);
                ui.quad(x - 3f, y + slot, slot + 6f, 3f, 1f, 1f, 1f, 0.95f);
                ui.quad(x - 3f, y, 3f, slot, 1f, 1f, 1f, 0.95f);
                ui.quad(x + slot, y, 3f, slot, 1f, 1f, 1f, 0.95f);
            }
            if (hov) {
                hovered = inventory[col];
                hoverX = x;
                hoverY = y;
                if (clicked)
                    action = InventoryAction.slot(col);
            }
        }

        boolean trashHover = hov(mx, my, trashX, trashY, 44f, 44f);
        if ((clicked || rightClicked) && trashHover && cursorItem != null && cursorItem != BlockType.AIR)
            action = InventoryAction.clearCursor();

        if (cursorItem != null && cursorItem != BlockType.AIR) {
            drawItemIcon(cursorItem, (float) mx - 18f, (float) my - 18f, 36f, 1f);
        }
        ui.end();

        String title = "Inventory";
        text.drawShadowed(font, title, panelX + 22f, titleY, w, h, 0.20f, 0.20f, 0.20f);
        String creative = "Blocks";
        text.drawShadowed(font, creative, invX, paletteLabelY, w, h, 0.20f, 0.20f, 0.20f);
        String main = "Inventory";
        text.drawShadowed(font, main, invX, invLabelY, w, h, 0.20f, 0.20f, 0.20f);
        String hotbarText = "Hotbar";
        text.drawShadowed(font, hotbarText, invX, hotbarLabelY, w, h, 0.20f, 0.20f, 0.20f);

        if (hovered != null && hovered != BlockType.AIR) {
            String name = displayName(hovered);
            float twd = font.textWidth(name);
            float tx = Math.min(w - twd - 12f, Math.max(8f, hoverX + 4f));
            text.drawShadowed(font, name, tx, hoverY - 8f, w, h, 1f, 1f, 1f);
        }

        return action;
    }

    private void drawSlotBack(float x, float y, float size, boolean hover) {
        float c = hover ? 0.58f : 0.42f;
        ui.quad(x, y, size, size, c, c, c, 1f);
        ui.quad(x, y, size, 2f, 0.18f, 0.18f, 0.18f, 1f);
        ui.quad(x, y, 2f, size, 0.18f, 0.18f, 0.18f, 1f);
        ui.quad(x + size - 2f, y, 2f, size, 0.88f, 0.88f, 0.88f, 1f);
        ui.quad(x, y + size - 2f, size, 2f, 0.88f, 0.88f, 0.88f, 1f);
    }

    private void drawItemIcon(BlockType b, float x, float y, float size, float alpha) {
        if (b == null || b == BlockType.AIR)
            return;
        int tile = b == BlockType.GRASS ? b.topTile : b.sideTile;
        float[] uv = TextureAtlas.uv(tile);
        ui.quad(x + 3f, y + 4f, size, size, 0f, 0f, 0f, 0.25f * alpha);
        ui.texQuad(x, y, size, size, atlas.getTextureId(),
                uv[0], uv[1], uv[2], uv[3], 1f, 1f, 1f, alpha);
    }

    private static String displayName(BlockType b) {
        String raw = b.name().toLowerCase().replace('_', ' ');
        StringBuilder sb = new StringBuilder(raw.length());
        boolean cap = true;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (cap && ch >= 'a' && ch <= 'z') {
                sb.append((char) (ch - 32));
                cap = false;
            } else {
                sb.append(ch);
                cap = ch == ' ';
            }
        }
        return sb.toString();
    }

    public BlockType drawCreativeMenu(int w, int h, double mx, double my, boolean clicked, BlockType[] hotbar,
            int selectedSlot) {
        BlockType[] all = BlockType.values();
        int cols = 9;
        int rows = (int) Math.ceil((double) all.length / cols);
        float sw = 50f, gap = 8f;
        float tw = cols * sw + (cols - 1) * gap;
        float th = rows * sw + (rows - 1) * gap;
        float startX = w / 2f - tw / 2f;
        float startY = h / 2f - th / 2f;

        ui.begin(w, h);
        ui.quad(0, 0, w, h, 0f, 0f, 0f, 0.8f);

        BlockType picked = null;
        BlockType hovered = null;
        float hx = 0, hy = 0, hw = 0;

        for (int i = 0; i < all.length; i++) {
            BlockType b = all[i];
            int c = i % cols;
            int r = i / cols;
            float x = startX + c * (sw + gap);
            float y = startY + r * (sw + gap);

            boolean hov = hov(mx, my, x, y, sw, sw);
            float bg = hov ? 0.4f : 0.25f;
            ui.quad(x, y, sw, sw, bg, bg, bg, 1f);

            float p = 6f; // padding inside slot
            float[] uv = TextureAtlas.uv(b.sideTile);
            ui.texQuad(x + p, y + p, sw - p * 2, sw - p * 2, atlas.getTextureId(),
                    uv[0], uv[1], uv[2], uv[3], 1f, 1f, 1f, 1f);

            if (hov) {
                hovered = b;
                hx = x;
                hy = y;
                hw = sw;
                if (clicked) {
                    picked = b;
                }
            }
        }

        // Selected hotbar indicator at the bottom to remind player which slot gets
        // replaced
        float stripW = 9 * (50f + 8f);
        float stripX = w / 2f - stripW / 2f;
        float stripY = h - 80f;
        ui.quad(stripX, stripY, stripW, 60f, 0.1f, 0.1f, 0.1f, 0.6f);
        ui.quad(stripX + selectedSlot * 58f, stripY, 58f, 60f, 0.4f, 0.8f, 0.2f, 0.6f);

        ui.end();

        // Draw tooltip
        if (hovered != null) {
            String name = hovered.name();
            float twd = font.textWidth(name);
            text.drawShadowed(font, name, hx + hw / 2f - twd / 2f, hy - 10f, w, h, 1f, 1f, 1f);
        }

        String title = "Creative Inventory (Press E or ESC to close)";
        float titleW = font.textWidth(title);
        text.drawShadowed(font, title, w / 2f - titleW / 2f, startY - 20f, w, h, 1f, 0.9f, 0.6f);

        return picked;
    }
}
