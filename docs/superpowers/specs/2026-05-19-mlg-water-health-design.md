# MLG Water Bucket & Health System Design

**Date:** 2026-05-19  
**Status:** Approved

## Overview

Full Minecraft-faithful fall damage + health system + MLG water bucket physics:
1. Health system (HP, hearts HUD, regen)
2. Fall distance tracking + fall damage
3. Fall damage negation when touching water (MLG)
4. MC-accurate fluid drag on water entry
5. Death screen + respawn

**Architecture:** Approach A — all health/fall logic in `Player.java`. `Game.java` only reads `player.isDead()` and handles state transition. New `DEAD` state in `Game`.

---

## Section 1: Player.java — Health & Fall Damage

### New Fields

```java
public float health = 20f;
public static final float MAX_HEALTH = 20f;
public float fallDistance = 0f;
private boolean wasOnGround = false;
private float regenTimer = 0f;
```

### Fall Distance Tracking (in `update()`, after movement is applied)

```java
// Accumulate fall distance while airborne and falling
if (!onGround && !inWater && !flying && velocity.y < 0)
    fallDistance += -velocity.y * dt;

// MLG: touching water resets fall damage
if (inWater)
    fallDistance = 0f;

// Landing: apply damage if needed
if (onGround && !wasOnGround) {
    if (!inWater) {  // guard: if landed INTO water in same frame, no damage
        float dmg = Math.max(0f, fallDistance - 3f);
        if (dmg > 0f) takeDamage(dmg);
    }
    fallDistance = 0f;
}
wasOnGround = onGround;
```

**Order matters:** `inWater` reset runs before the `onGround` check — guaranteed by sequential evaluation.

**Note on dt lag spikes:** `velocity.y * dt` accumulation is correct for variable-framerate physics. Extreme lag spikes (dt > 0.05s) are already capped by `Game.run()` with `Math.min(0.05, dt)`.

### HP Regeneration (~0.5 HP per 4 seconds, like MC peaceful)

```java
regenTimer += dt;
if (regenTimer >= 4f && health < MAX_HEALTH) {
    health = Math.min(MAX_HEALTH, health + 0.5f);
    regenTimer = 0f;
}
```

### New Methods

```java
public void takeDamage(float amount) {
    health = Math.max(0f, health - amount);
}

public void respawn() {
    health = MAX_HEALTH;
    fallDistance = 0f;
    regenTimer = 0f;
    position.set(8, 90, 8);
    velocity.set(0, 0, 0);
    onGround = false;
}

public boolean isDead() {
    return health <= 0f;
}
```

### Damage Formula

Same as vanilla Minecraft: `damage = max(0, fallDistance - 3)` HP.

| Fall height | Damage | Effect |
|-------------|--------|--------|
| ≤ 3 blocks  | 0 HP   | Safe |
| 4 blocks    | 1 HP   | ½ heart |
| 23 blocks   | 20 HP  | Death |

---

## Section 2: Fluid Drag (Player.java water physics)

**Remove** the `justEnteredWater` one-shot absorption hack:
```java
// DELETE this:
if (justEnteredWater && velocity.y < 0f)
    velocity.y = Math.max(velocity.y * 0.4f, -4f);
```

**Replace** vertical water physics with MC-faithful continuous formula:

```java
// MC per tick: vy = vy * 0.8 - 0.02  (at 20 Hz / 50ms tick)
// Continuous equivalent:
float waterVDrag = (float) Math.pow(0.8, dt / 0.05f);
velocity.y = velocity.y * waterVDrag - 0.4f * dt;
if (!sinking) velocity.y += 1.0f * dt;  // buoyancy (SPACE not held)
if (spaceDown) velocity.y = Math.min(velocity.y + 12f * dt, SWIM_UP_MAX);
```

Terminal velocity in water: ≈ −2 m/s (vs −0.1 blocks/tick in MC — same feel).

Horizontal physics (`hDrag` toward `wish * SWIM_SPEED`) is **unchanged** — already better than MC.

---

## Section 3: HUD Hearts (Hud.java)

Add method `drawHearts(int w, int h, float health)`.

Draw 10 heart positions starting at bottom-left of screen (same row as hotbar, above or left of it).

- Position: `x = 4 + i * 11`, `y = screenH - hotbarHeight - 14`
- Heart size: 9×9 px quads via `UiRenderer`
- Full heart (i < floor(health/2)): `rgba(220, 0, 0, 1.0)`
- Half heart (i == floor(health/2) && health%2 != 0): `rgba(220, 0, 0, 0.55)`
- Empty heart (i >= ceil(health/2)): `rgba(60, 0, 0, 1.0)`

Call in `Game.render()` inside `State.PLAYING` HUD section:
```java
hud.drawHearts(w, h, player.health);
```

---

## Section 4: Death Screen & Respawn (Game.java + Hud.java)

### New State

Add `DEAD` to `enum State` in `Game.java`.

### Death Detection

In `updatePlaying()`, at the end:
```java
if (player.isDead()) {
    state = State.DEAD;
}
```

### Death Screen Rendering

In `Game.render()`, add `case DEAD`. The death state shares the same world-render code as `PLAYING` — extract the world render block into a helper `renderWorld(proj, view, ...)` or simply fall through to PLAYING render then draw the overlay. Simplest: duplicate the HUD-only part after `renderWorld()` call. The death overlay is drawn after world render in the same frame.

`Hud.drawDeathScreen()`:
- Semi-transparent dark-red overlay: `rgba(80, 0, 0, 0.6)` full-screen quad
- Text "Вы умерли" centered, white, large
- Button "Возродиться" centered below text

### Respawn Button Handler

In `Game` where `drawDeathScreen` result is checked:
```java
if (hud.drawDeathScreen(w, h, mx, my, clicked) == Hud.MenuAction.RESPAWN) {
    player.respawn();
    state = State.PLAYING;
}
```

Add `RESPAWN` to `Hud.MenuAction` enum.

---

## Files Changed

| File | Change |
|------|--------|
| `src/main/java/com/mineclone/game/Player.java` | Add health, fallDistance, regen, takeDamage, respawn, isDead; fix fluid drag |
| `src/main/java/com/mineclone/game/Game.java` | Add `DEAD` state, death detection in `updatePlaying`, death render case, respawn handler |
| `src/main/java/com/mineclone/game/Hud.java` | Add `drawHearts()`, `drawDeathScreen()`, `RESPAWN` to MenuAction |
