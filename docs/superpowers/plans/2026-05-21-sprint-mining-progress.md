# Sprint + Mining Progress Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add sprint (Ctrl / double-tap W) and hold-to-break mining with progressive crack overlay.

**Architecture:** Sprint lives entirely in `Player.java` (state + speed) with one FOV read in `Game.java`. Mining progress replaces the one-frame break with a hold-accumulator in `Game.java`; crack sprites are generated procedurally into PNG files, registered in `TextureAtlas`, and rendered by a new `BlockBreakOverlay` class using a minimal CRACK shader.

**Tech Stack:** Java 21, LWJGL 3 (OpenGL 3.3 core), JOML, Java2D (for sprite generation)

---

## File Map

| File | Action | Responsibility |
|---|---|---|
| `src/main/java/com/mineclone/game/Player.java` | Modify | Sprint state, speed, double-tap W, Ctrl detection |
| `src/main/java/com/mineclone/game/Game.java` | Modify | Sprint FOV; break state fields; mining progress logic; overlay lifecycle |
| `src/main/java/com/mineclone/world/BlockType.java` | Modify | Add `hardness` float field |
| `src/main/java/com/mineclone/render/Shaders.java` | Modify | Add `CRACK_VERTEX` + `CRACK_FRAGMENT` (pos+UV only, no light) |
| `src/main/java/com/mineclone/render/TextureAtlas.java` | Modify | Register crack_0..crack_9 at indices 37–46; add `CRACK_TILE_0` constant |
| `src/main/java/com/mineclone/render/BlockBreakOverlay.java` | Create | 6-face cube quad renderer with polygon offset + alpha blend |
| `CrackSpriteGen.java` (root, temporary) | Create then delete | One-shot generator: writes crack_0..9.png to assets/textures/blocks/ |

---

## Task 1 — Sprint in Player.java

**Files:** Modify `src/main/java/com/mineclone/game/Player.java`

- [ ] Add constants and state fields after existing constants (after `WATER_LEDGE_MAX_STEP`):

```java
public static final float SPRINT_SPEED = WALK_SPEED * 1.3f;  // ≈6.24 m/s
private static final float DOUBLE_TAP_WINDOW = 0.25f;

public boolean isSprinting = false;
private float wDoubleTapTimer = Float.MAX_VALUE; // MAX_VALUE = "W never pressed"
```

- [ ] Add `checkSprintActivation` method before `moveAxis`:

```java
private void checkSprintActivation(com.mineclone.core.Input input, float dt) {
    boolean wPressed = input.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_W);
    boolean wDown    = input.keyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_W);
    boolean ctrlDown = input.keyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL);

    // Double-tap W: second press within DOUBLE_TAP_WINDOW activates sprint
    if (wPressed) {
        if (wDoubleTapTimer < DOUBLE_TAP_WINDOW && !flying && !inWater)
            isSprinting = true;
        wDoubleTapTimer = 0f;
    } else {
        wDoubleTapTimer = Math.min(wDoubleTapTimer + dt, Float.MAX_VALUE / 2);
    }

    // Ctrl + W: immediate activation
    if (ctrlDown && wDown && !flying && !inWater)
        isSprinting = true;

    // Cancel: W not held, or entered water/fly
    if (!wDown || flying || inWater)
        isSprinting = false;
}
```

- [ ] Call `checkSprintActivation` at the top of `update(float dt, World world, Input input, boolean controlsEnabled, float sensitivity, boolean invertY)`, right before the `// toggle fly` block, only when controls are enabled:

```java
if (controlsEnabled)
    checkSprintActivation(input, dt);
```

- [ ] Change speed selection (line `float speed = flying ? flySpeed : WALK_SPEED;`):

```java
float speed = flying ? flySpeed : (isSprinting ? SPRINT_SPEED : WALK_SPEED);
```

- [ ] In `moveAxis`, cancel sprint on horizontal wall collision. Find the two velocity-zeroing blocks for `dx` and `dz`:

```java
// EXISTING (dx > 0 branch):
if (dx > 0) {
    position.x = x - hw - 1e-4f;
    velocity.x = 0;
} else if (dx < 0) {
    position.x = x + 1 + hw + 1e-4f;
    velocity.x = 0;
}
```

Add `isSprinting = false;` after each `velocity.x = 0;` and after each `velocity.z = 0;`:

```java
if (dx > 0) {
    position.x = x - hw - 1e-4f;
    velocity.x = 0;
    isSprinting = false;
} else if (dx < 0) {
    position.x = x + 1 + hw + 1e-4f;
    velocity.x = 0;
    isSprinting = false;
}
// ... later in the same if-block ...
if (dz > 0) {
    position.z = z - hw - 1e-4f;
    velocity.z = 0;
    isSprinting = false;
} else if (dz < 0) {
    position.z = z + 1 + hw + 1e-4f;
    velocity.z = 0;
    isSprinting = false;
}
```

- [ ] Also reset `isSprinting` when `flying` is toggled on (inside the `if (controlsEnabled && input.keyPressed(GLFW_KEY_F)) flying = !flying;` block):

```java
if (controlsEnabled && input.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_F)) {
    flying = !flying;
    if (flying) isSprinting = false;
}
```

- [ ] Also reset `wDoubleTapTimer` and `isSprinting` in `respawn()` (after `wasOnGround = false;`):

```java
isSprinting = false;
wDoubleTapTimer = Float.MAX_VALUE;
```

---

## Task 2 — Sprint FOV boost in Game.java

**Files:** Modify `src/main/java/com/mineclone/game/Game.java`

- [ ] Find the FOV line (currently around line 1290):

```java
float targetFov = player.eyeInWater ? fovDegrees * 0.85f : fovDegrees;
```

Replace with:

```java
float targetFov = player.eyeInWater  ? fovDegrees * 0.85f
                : player.isSprinting ? fovDegrees + 10f
                : fovDegrees;
```

- [ ] Compile to verify Tasks 1–2:

```powershell
$libs = Join-Path $PWD 'libs'; $out = Join-Path $PWD 'out'
$jars = (Get-ChildItem $libs -Filter *.jar | % { $_.FullName }) -join ';'
$sources = Get-ChildItem -Path 'src\main\java' -Recurse -Filter *.java | % { $_.FullName }
$srcList = Join-Path $out 'sources.txt'
[IO.File]::WriteAllLines($srcList, [string[]]$sources, [System.Text.UTF8Encoding]::new($false))
javac -encoding UTF-8 -d $out -cp $jars "@$srcList" 2>&1
```

Expected: no output (clean compile).

- [ ] Commit:

```
git add src/main/java/com/mineclone/game/Player.java src/main/java/com/mineclone/game/Game.java
git commit -m "feat(player): sprint via Ctrl+W and double-tap W with FOV boost"
```

---

## Task 3 — BlockType hardness field

**Files:** Modify `src/main/java/com/mineclone/world/BlockType.java`

- [ ] Add `public final float hardness;` field after `public final int emittedLight;`.

- [ ] Add `hardness` as the last parameter in the constructor:

```java
BlockType(boolean solid, boolean transparent, boolean cutout,
        int side, int top, int bottom,
        float pr, float pg, float pb, int emittedLight, float hardness) {
    // ... existing assignments ...
    this.hardness = hardness;
}
```

- [ ] Update every enum value to add the hardness as the last argument (replace the entire enum block):

```java
AIR         (false, true,  false, -1, -1, -1, 0f,    0f,    0f,    0,  0f),
GRASS       (true,  false, false,  1,  0,  2, 0.40f, 0.55f, 0.28f, 0,  0.6f),
DIRT        (true,  false, false,  2,  2,  2, 0.47f, 0.33f, 0.22f, 0,  0.5f),
STONE       (true,  false, false,  3,  3,  3, 0.47f, 0.47f, 0.47f, 0,  7.5f),
SAND        (true,  false, false,  4,  4,  4, 0.86f, 0.78f, 0.55f, 0,  0.5f),
WOOD        (true,  false, false,  5,  6,  6, 0.37f, 0.26f, 0.15f, 0,  2.0f),
LEAVES      (true,  false, true,   7,  7,  7, 0.20f, 0.47f, 0.16f, 0,  0.2f),
WATER       (false, true,  false,  8,  8,  8, 0.16f, 0.35f, 0.78f, 0,  0f),
BEDROCK     (true,  false, false,  9,  9,  9, 0.20f, 0.20f, 0.20f, 0,  Float.MAX_VALUE),
COBBLE      (true,  false, false, 10, 10, 10, 0.45f, 0.45f, 0.45f, 0,  7.5f),
PLANKS      (true,  false, false, 11, 11, 11, 0.60f, 0.45f, 0.27f, 0,  1.5f),
TORCH       (false, true,  false, 12, 12, 12, 1.0f,  0.85f, 0.40f, 15, 0.05f),
GLASS       (true,  false, true,  14, 14, 14, 0.70f, 0.90f, 0.90f, 0,  0.3f),
DOOR_CLOSED (true,  false, true,  15, 15, 15, 0.60f, 0.45f, 0.27f, 0,  1.5f),
DOOR_OPEN   (false, true,  false, 15, 15, 15, 0.60f, 0.45f, 0.27f, 0,  1.5f),
STAIRS      (true,  false, true,  11, 11, 11, 0.60f, 0.45f, 0.27f, 0,  1.5f),
WATER_FLOW  (false, true,  false,  8,  8,  8, 0.16f, 0.35f, 0.78f, 0,  0f);
```

- [ ] Compile to verify:

```powershell
javac -encoding UTF-8 -d $out -cp $jars "@$srcList" 2>&1
```

Expected: no output.

- [ ] Commit:

```
git add src/main/java/com/mineclone/world/BlockType.java
git commit -m "feat(block): add hardness field to BlockType for mining progress"
```

---

## Task 4 — Generate crack PNG sprites

**Files:** Create then delete `CrackSpriteGen.java` (project root); generates `assets/textures/blocks/crack_0.png` … `crack_9.png`

- [ ] Create `CrackSpriteGen.java` in the project root:

```java
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;

public class CrackSpriteGen {
    static final int TILE = 32;

    public static void main(String[] args) throws Exception {
        File dir = new File("assets/textures/blocks");
        dir.mkdirs();
        for (int stage = 0; stage < 10; stage++) {
            BufferedImage img = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            // Transparent background
            g.setColor(new Color(0, 0, 0, 0));
            g.fillRect(0, 0, TILE, TILE);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setStroke(new BasicStroke(1.0f));
            // Each stage accumulates all previous cracks plus one new line
            for (int s = 0; s <= stage; s++) {
                Random rng = new Random(s * 137L + 42L);
                int x0 = 2 + rng.nextInt(TILE - 4);
                int y0 = 2 + rng.nextInt(TILE - 4);
                int x1 = 2 + rng.nextInt(TILE - 4);
                int y1 = 2 + rng.nextInt(TILE - 4);
                // Dark line, alpha ~140/255 ≈ 0.55
                g.setColor(new Color(30, 20, 10, 140));
                g.drawLine(x0, y0, x1, y1);
                // Short branch off the midpoint
                int mx = (x0 + x1) / 2, my = (y0 + y1) / 2;
                int bx = mx + rng.nextInt(9) - 4;
                int by = my + rng.nextInt(9) - 4;
                g.setColor(new Color(30, 20, 10, 100));
                g.drawLine(mx, my, bx, by);
            }
            g.dispose();
            File out = new File(dir, "crack_" + stage + ".png");
            ImageIO.write(img, "png", out);
            System.out.println("Generated " + out.getPath());
        }
    }
}
```

- [ ] Compile and run the generator (no LWJGL needed — pure Java stdlib):

```powershell
javac -encoding UTF-8 CrackSpriteGen.java
java CrackSpriteGen
```

Expected output:
```
Generated assets\textures\blocks\crack_0.png
...
Generated assets\textures\blocks\crack_9.png
```

- [ ] Delete the generator source and class:

```powershell
Remove-Item CrackSpriteGen.java, CrackSpriteGen.class
```

- [ ] Commit the 10 generated PNG files:

```
git add assets/textures/blocks/crack_0.png assets/textures/blocks/crack_1.png assets/textures/blocks/crack_2.png assets/textures/blocks/crack_3.png assets/textures/blocks/crack_4.png assets/textures/blocks/crack_5.png assets/textures/blocks/crack_6.png assets/textures/blocks/crack_7.png assets/textures/blocks/crack_8.png assets/textures/blocks/crack_9.png
git commit -m "asset: add procedural crack overlay sprites (stages 0-9)"
```

---

## Task 5 — Register crack tiles in TextureAtlas

**Files:** Modify `src/main/java/com/mineclone/render/TextureAtlas.java`

- [ ] At the end of `TILE_NAMES` array (after `"heart_half"` at index 36), add:

```java
// Crack overlay stages 0-9 (mining progress)
"crack_0", "crack_1", "crack_2", "crack_3", "crack_4",
"crack_5", "crack_6", "crack_7", "crack_8", "crack_9",
```

- [ ] Add a public constant after `WATER_FLOW_FRAMES`:

```java
/** First tile index of the crack overlay animation strip (10 stages). */
public static final int CRACK_TILE_0 = 37;
```

- [ ] Compile to verify:

```powershell
javac -encoding UTF-8 -d $out -cp $jars "@$srcList" 2>&1
```

Expected: no output.

---

## Task 6 — CRACK shader in Shaders.java

**Files:** Modify `src/main/java/com/mineclone/render/Shaders.java`

- [ ] Add two shader string constants at the end of the class (before the closing `}`):

```java
public static final String CRACK_VERTEX = """
    #version 330 core
    layout (location = 0) in vec3 aPos;
    layout (location = 1) in vec2 aUv;
    uniform mat4 uProjection;
    uniform mat4 uView;
    out vec2 vUv;
    void main() {
        gl_Position = uProjection * uView * vec4(aPos, 1.0);
        vUv = aUv;
    }
    """;

public static final String CRACK_FRAGMENT = """
    #version 330 core
    in vec2 vUv;
    uniform sampler2D uAtlas;
    out vec4 FragColor;
    void main() {
        vec4 col = texture(uAtlas, vUv);
        if (col.a < 0.01) discard;
        FragColor = col;
    }
    """;
```

- [ ] Compile to verify:

```powershell
javac -encoding UTF-8 -d $out -cp $jars "@$srcList" 2>&1
```

Expected: no output.

---

## Task 7 — BlockBreakOverlay class

**Files:** Create `src/main/java/com/mineclone/render/BlockBreakOverlay.java`

- [ ] Create the file:

```java
package com.mineclone.render;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

public class BlockBreakOverlay {
    // 6 faces × 2 triangles × 3 verts = 36 verts; 5 floats each (x,y,z,u,v)
    private static final int VERTEX_COUNT = 36;
    private static final int STRIDE = 5 * Float.BYTES; // 20 bytes

    private final int vao, vbo;
    private final Shader shader;

    public BlockBreakOverlay() {
        shader = new Shader(Shaders.CRACK_VERTEX, Shaders.CRACK_FRAGMENT);
        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) VERTEX_COUNT * STRIDE, GL_DYNAMIC_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, STRIDE, 0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, STRIDE, 3 * Float.BYTES);
        glEnableVertexAttribArray(1);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(0);
    }

    /** Render crack overlay for block at (bx,by,bz), stage 0..9. */
    public void render(Matrix4f proj, Matrix4f view, int bx, int by, int bz,
                       int stage, TextureAtlas atlas) {
        float[] uv = TextureAtlas.uv(TextureAtlas.CRACK_TILE_0 + stage);
        float u0 = uv[0], v0 = uv[1], u1 = uv[2], v1 = uv[3];
        float x = bx, y = by, z = bz;

        float[] verts = {
            // Top (+Y)
            x,   y+1, z,   u0,v0,   x+1, y+1, z,   u1,v0,   x+1, y+1, z+1, u1,v1,
            x,   y+1, z,   u0,v0,   x+1, y+1, z+1, u1,v1,   x,   y+1, z+1, u0,v1,
            // Bottom (-Y)
            x,   y, z+1, u0,v0,   x+1, y, z+1, u1,v0,   x+1, y, z,   u1,v1,
            x,   y, z+1, u0,v0,   x+1, y, z,   u1,v1,   x,   y, z,   u0,v1,
            // North (-Z)
            x+1, y,   z, u0,v0,   x,   y,   z, u1,v0,   x,   y+1, z, u1,v1,
            x+1, y,   z, u0,v0,   x,   y+1, z, u1,v1,   x+1, y+1, z, u0,v1,
            // South (+Z)
            x,   y,   z+1, u0,v0,   x+1, y,   z+1, u1,v0,   x+1, y+1, z+1, u1,v1,
            x,   y,   z+1, u0,v0,   x+1, y+1, z+1, u1,v1,   x,   y+1, z+1, u0,v1,
            // East (+X)
            x+1, y,   z,   u0,v0,   x+1, y,   z+1, u1,v0,   x+1, y+1, z+1, u1,v1,
            x+1, y,   z,   u0,v0,   x+1, y+1, z+1, u1,v1,   x+1, y+1, z,   u0,v1,
            // West (-X)
            x,   y,   z+1, u1,v0,   x,   y,   z,   u0,v0,   x,   y+1, z,   u0,v1,
            x,   y,   z+1, u1,v0,   x,   y+1, z,   u0,v1,   x,   y+1, z+1, u1,v1,
        };

        FloatBuffer fb = MemoryUtil.memAllocFloat(verts.length);
        try {
            fb.put(verts).flip();
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferSubData(GL_ARRAY_BUFFER, 0, fb);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
        } finally {
            MemoryUtil.memFree(fb);
        }

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(-1f, -1f);

        shader.bind();
        shader.setMat4("uProjection", proj);
        shader.setMat4("uView", view);
        shader.setInt("uAtlas", 0);
        atlas.bind(0);
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, VERTEX_COUNT);
        glBindVertexArray(0);
        shader.unbind();

        glPolygonOffset(0f, 0f);
        glDisable(GL_POLYGON_OFFSET_FILL);
        glDisable(GL_BLEND);
    }

    public void destroy() {
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        shader.destroy();
    }
}
```

- [ ] Compile to verify:

```powershell
javac -encoding UTF-8 -d $out -cp $jars "@$srcList" 2>&1
```

Expected: no output.

---

## Task 8 — Mining progress state and logic in Game.java

**Files:** Modify `src/main/java/com/mineclone/game/Game.java`

- [ ] Add break-state fields near the other interaction fields (around `private Raycaster.Hit lastHit`):

```java
private static final int NO_BREAK = Integer.MIN_VALUE;
private int breakX = NO_BREAK, breakY = NO_BREAK, breakZ = NO_BREAK;
private float breakProgress = 0f;
private float breakDigTimer = 0f;
private BlockBreakOverlay breakOverlay;
```

- [ ] In the `Game` constructor (or wherever other renderers are initialised, search for `new BlockOutline()`), add:

```java
breakOverlay = new BlockBreakOverlay();
```

- [ ] In `cleanup()`, alongside `outline.destroy()`, add:

```java
breakOverlay.destroy();
```

- [ ] Change `handleInteraction()` signature to `handleInteraction(float dt)` and update its single call site in `updatePlaying`:

```java
// call site (was: handleInteraction();)
handleInteraction(dt);
```

- [ ] Replace the entire `handleInteraction` method body with the new mining-progress version:

```java
private void handleInteraction(float dt) {
    Vector3f origin = new Vector3f(player.camera.position);
    Vector3f dir = player.camera.forward();
    lastHit = Raycaster.cast(world, origin, dir, 6f);

    if (lastHit == null) {
        resetBreakState();
        return;
    }

    // Debug stick: middle click cycles block metadata
    if (input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_MIDDLE)) {
        byte m = world.getBlockMeta(lastHit.x, lastHit.y, lastHit.z);
        world.setBlock(lastHit.x, lastHit.y, lastHit.z,
                world.getBlock(lastHit.x, lastHit.y, lastHit.z), (byte) ((m + 1) & 0x0F));
    }

    // --- Left mouse: hold-to-break ---
    if (input.mouseDown(GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
        BlockType target = world.getBlock(lastHit.x, lastHit.y, lastHit.z);
        if (target.hardness > 0f && target.hardness < Float.MAX_VALUE) {
            if (breakX == lastHit.x && breakY == lastHit.y && breakZ == lastHit.z) {
                // Accumulate progress on the same block
                breakProgress += dt / target.hardness;
                breakDigTimer -= dt;
                if (breakDigTimer <= 0f) {
                    startHandSwing();
                    sound.playOneOfAt(sounds.dig(target),
                            blockSoundPosition(lastHit.x, lastHit.y, lastHit.z),
                            0.8f, 0.9f + 0.2f * (float) Math.random());
                    breakDigTimer = 0.4f;
                }
                if (breakProgress >= 1f) {
                    executeBlockBreak(lastHit.x, lastHit.y, lastHit.z, target);
                    resetBreakState();
                }
            } else {
                // New target block
                breakX = lastHit.x; breakY = lastHit.y; breakZ = lastHit.z;
                breakProgress = 0f;
                breakDigTimer = 0f;
            }
        } else {
            resetBreakState();
        }
    } else {
        resetBreakState();
    }

    // --- Right mouse: place / interact (unchanged) ---
    if (input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT)) {
        startHandSwing();
        BlockType target = world.getBlock(lastHit.x, lastHit.y, lastHit.z);
        if (target == BlockType.DOOR_CLOSED || target == BlockType.DOOR_OPEN) {
            byte m = world.getBlockMeta(lastHit.x, lastHit.y, lastHit.z);
            BlockType next = (target == BlockType.DOOR_CLOSED) ? BlockType.DOOR_OPEN : BlockType.DOOR_CLOSED;
            world.setBlock(lastHit.x, lastHit.y, lastHit.z, next, m);
            int otherY = ((m & 0x4) != 0) ? lastHit.y - 1 : lastHit.y + 1;
            BlockType other = world.getBlock(lastHit.x, otherY, lastHit.z);
            if (other == BlockType.DOOR_CLOSED || other == BlockType.DOOR_OPEN) {
                byte om = world.getBlockMeta(lastHit.x, otherY, lastHit.z);
                world.setBlock(lastHit.x, otherY, lastHit.z, next, om);
            }
            sound.playOneOfAt(sounds.doorToggle(),
                    blockSoundPosition(lastHit.x, lastHit.y, lastHit.z),
                    0.8f, 0.95f + 0.1f * (float) Math.random());
        } else {
            BlockType held = currentBlock();
            if (held != BlockType.AIR) {
                int px = lastHit.x + lastHit.nx;
                int py = lastHit.y + lastHit.ny;
                int pz = lastHit.z + lastHit.nz;
                if (!playerOccupies(px, py, pz)) {
                    byte meta = computePlaceMeta(held, px, py, pz);
                    world.setBlock(px, py, pz, held, meta);
                    sound.playOneOfAt(sounds.dig(held),
                            blockSoundPosition(px, py, pz),
                            0.8f, 0.9f + 0.2f * (float) Math.random());
                    if (held == BlockType.DOOR_CLOSED) {
                        world.setBlock(px, py + 1, pz, BlockType.DOOR_CLOSED, (byte) (meta | 0x4));
                    }
                }
            }
        }
    }
}
```

- [ ] Add the two helper methods right after `handleInteraction`:

```java
private void resetBreakState() {
    breakX = NO_BREAK; breakY = NO_BREAK; breakZ = NO_BREAK;
    breakProgress = 0f;
    breakDigTimer = 0f;
}

private void executeBlockBreak(int x, int y, int z, BlockType target) {
    startHandSwing();
    byte targetMeta = world.getBlockMeta(x, y, z);
    sound.playOneOfAt(sounds.dig(target), blockSoundPosition(x, y, z),
            0.8f, 0.9f + 0.2f * (float) Math.random());
    world.setBlock(x, y, z, BlockType.AIR);
    float pSky = world.getSkyLight(x, y, z) / (float) com.mineclone.world.Chunk.MAX_LIGHT;
    float pBlk = world.getBlockLightWorld(x, y, z) / (float) com.mineclone.world.Chunk.MAX_LIGHT;
    particles.emitBlockBreak(x, y, z, target.particleColor, target.sideTile, pSky, pBlk);
    if (target == BlockType.DOOR_CLOSED || target == BlockType.DOOR_OPEN) {
        int otherY = ((targetMeta & 0x4) != 0) ? y - 1 : y + 1;
        BlockType other = world.getBlock(x, otherY, z);
        if (other == BlockType.DOOR_CLOSED || other == BlockType.DOOR_OPEN)
            world.setBlock(x, otherY, z, BlockType.AIR);
    }
}
```

- [ ] Also reset break state on world unload: in `unloadWorld()`, after clearing chunk meshes, add:

```java
resetBreakState();
```

- [ ] Compile to verify:

```powershell
javac -encoding UTF-8 -d $out -cp $jars "@$srcList" 2>&1
```

Expected: no output (or fix any method-not-found errors from the right-click block — the exact existing code must be preserved for those branches, check against original if compilation fails).

---

## Task 9 — Render crack overlay in Game.render()

**Files:** Modify `src/main/java/com/mineclone/game/Game.java`

- [ ] In `render()`, find the block-outline render call (search for `outline.render` or `BlockOutline`). After the outline block, add the crack overlay:

```java
// Crack overlay: show break progress on the target block
if (breakX != NO_BREAK && breakProgress > 0f) {
    int stage = Math.min(9, (int) (breakProgress * 10f));
    breakOverlay.render(proj, view, breakX, breakY, breakZ, stage, atlas);
}
```

- [ ] Compile final time:

```powershell
javac -encoding UTF-8 -d $out -cp $jars "@$srcList" 2>&1
```

Expected: no output.

- [ ] Commit all remaining changes:

```
git add src/main/java/com/mineclone/game/Game.java src/main/java/com/mineclone/world/BlockType.java src/main/java/com/mineclone/render/Shaders.java src/main/java/com/mineclone/render/TextureAtlas.java src/main/java/com/mineclone/render/BlockBreakOverlay.java .gitignore
git commit -m "feat: hold-to-break mining with crack overlay (10 stages) and hardness per block"
```

---

## Self-Review Checklist

- [x] Sprint constants + state fields defined before use — ✅
- [x] Double-tap W uses `Float.MAX_VALUE` sentinel — avoids false trigger on game start — ✅
- [x] `checkSprintActivation` called only when `controlsEnabled` — menus/dead state won't trigger sprint — ✅
- [x] Wall-collision cancel covers both dx and dz branches — ✅
- [x] FOV priority: eyeInWater overrides sprint (correct — can't see far underwater anyway) — ✅
- [x] Crack tiles at indices 37–46, slot 36 = `heart_half` (last existing), no collision — ✅
- [x] `CRACK_TILE_0 = 37` constant matches first crack tile position — ✅
- [x] `handleInteraction(float dt)` signature matches call site updated to `handleInteraction(dt)` — ✅
- [x] `resetBreakState()` called on null lastHit, LMB release, unbreakable block, world unload — ✅
- [x] `executeBlockBreak` preserves all original door logic — ✅
- [x] `breakDigTimer = 0f` on new block triggers sound on first frame of breaking — ✅
- [x] `stage = min(9, (int)(progress * 10))` maps 0..1 → 0..9 correctly — ✅
- [x] Right-click block (door, place) code preserved verbatim — ✅
