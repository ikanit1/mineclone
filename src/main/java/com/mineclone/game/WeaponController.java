package com.mineclone.game;

import com.mineclone.core.KeyBindings;
import com.mineclone.item.Bow;
import com.mineclone.world.GameMode;
import com.mineclone.world.ItemStack;
import com.mineclone.world.entity.ItemEntity;
import com.mineclone.world.entity.Projectile;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

/**
 * The bow and the charged throw, moved out of {@code Game} whole (SIM-08):
 * how long the string is held, how far a throw is charged, and what leaves
 * the hand. Both draw the same ballistic arc; {@code Game} renders it from
 * {@link #bowDraw()} and {@link #throwCharge()}.
 */
final class WeaponController {
    /** Скорость броска по Q и подброс вверх, блоки/с. */
    static final float THROW_SPEED = 5.5f, THROW_LIFT = 1.6f;
    static final float THROW_CHARGE_TIME = 1.15f;

    private final Game g;
    private float throwCharge;
    private boolean chargingThrow;
    private boolean throwWholeStack;
    /** Сколько секунд удерживается натяжение лука; −1 — лук не натянут. */
    private float bowHeld = -1f;

    WeaponController(Game game) {
        this.g = game;
    }

    /** How far a held throw is charged, 0..1; zero when none is. */
    float throwCharge() {
        return chargingThrow ? throwCharge : 0f;
    }

    /**
     * Бросок предмета из руки по Q: один предмет, с Ctrl — вся стопка.
     *
     * Брошенный летит туда, куда смотрит игрок, и долго не даётся в руки —
     * иначе магнит возвращал бы его обратно, не дав упасть.
     */
    void throwHeldItem(boolean wholeStack) {
        throwHeldItem(wholeStack, 0.45f);
    }

    private void throwHeldItem(boolean wholeStack, float charge) {
        if (g.net.inventoryBusy()) return;
        ItemStack held = g.inventory.get(g.selectedSlot);
        if (held == null)
            return;
        ItemStack thrown;
        if (wholeStack || held.count <= 1) {
            thrown = held;
            g.inventory.set(g.selectedSlot, null);
        } else {
            thrown = held.copy();
            thrown.count = 1;
            held.count--;
        }
        throwStack(thrown, charge);
        g.startHandSwing();
    }

    /** Натянут ли лук прямо сейчас и насколько: это же видит и рука, и прицел. */
    float bowDraw() {
        return bowHeld < 0f ? 0f : Bow.draw(bowHeld);
    }

    void update(float dt) {
        updateChargedThrow(dt);
        updateBow(dt);
    }

    /**
     * Лук: правая кнопка тянет, отпускание стреляет.
     *
     * Натяжение сбрасывается и при смене слота, и при открытии окна — иначе
     * лук «помнит» натяжение, которого игрок уже не держит.
     */
    private void updateBow(float dt) {
        boolean holding = !g.photoMode && g.state == Game.State.PLAYING
                && g.heldItem() != null
                && Bow.ITEM.equals(g.heldItem().id.path());
        if (!holding) {
            bowHeld = -1f;
            return;
        }
        if (g.input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT) && hasAmmo())
            bowHeld = 0f;
        else if (bowHeld >= 0f && g.input.mouseDown(GLFW.GLFW_MOUSE_BUTTON_RIGHT))
            bowHeld += dt;
        else if (bowHeld >= 0f) {
            releaseBow(Bow.draw(bowHeld));
            bowHeld = -1f;
        }
    }

    /** Есть ли чем стрелять. В творческом режиме стрелы не кончаются. */
    private boolean hasAmmo() {
        if (g.gameMode == GameMode.CREATIVE)
            return true;
        for (int i = 0; i < g.inventory.size(); i++) {
            var s = g.inventory.get(i);
            if (s != null && Bow.AMMO.equals(s.item.id.path()))
                return true;
        }
        return false;
    }

    /** Снимает одну стрелу; false — стрелять нечем. */
    private boolean takeAmmo() {
        if (g.gameMode == GameMode.CREATIVE)
            return true;
        for (int i = 0; i < g.inventory.size(); i++) {
            var s = g.inventory.get(i);
            if (s == null || !Bow.AMMO.equals(s.item.id.path()))
                continue;
            if (--s.count <= 0)
                g.inventory.set(i, null);
            return true;
        }
        return false;
    }

    private void releaseBow(float draw) {
        if (!Bow.canRelease(draw) || !takeAmmo())
            return;
        Player player = g.player;
        Vector3f eye = new Vector3f(player.camera.position);
        Vector3f fwd = player.camera.forward();
        float speed = Bow.speed(draw);
        var shot = new Projectile(Bow.AMMO, g.playerTarget, true, Bow.damage(draw));
        shot.position.set(eye).fma(0.6f, fwd).add(0f, -0.12f, 0f);
        // Скорость игрока складывается с выстрелом: стрела, пущенная на бегу,
        // летит дальше — как и брошенный предмет.
        shot.velocity.set(fwd).mul(speed).add(player.velocity.x * 0.4f, 0f,
                player.velocity.z * 0.4f);
        shot.heading.set(fwd);
        g.addProjectile(shot);
        // У участника выстрел считает хозяин: местный снаряд нужен только
        // ради отклика и будет заменён ближайшим снимком.
        g.net.requestShot(shot);
        wearBow();
        g.sound.playOneOf(g.sounds.playerAttack("sweep"), 0.35f, 1.35f + 0.12f * (float) Math.random());
        g.startHandSwing();
    }

    /** Лук тупится о тетиву: у него есть прочность, но нет инструментальной части. */
    private void wearBow() {
        var held = g.inventory.get(g.selectedSlot);
        if (held != null && held.item.durability > 0 && held.wear())
            g.inventory.set(g.selectedSlot, null);
    }

    private void updateChargedThrow(float dt) {
        if (g.photoMode) {
            chargingThrow = false;
            throwCharge = 0f;
            return;
        }
        if (g.input.pressed(KeyBindings.Action.DROP)) {
            chargingThrow = g.inventory.get(g.selectedSlot) != null;
            throwCharge = 0f;
            throwWholeStack = g.input.keyDown(GLFW.GLFW_KEY_LEFT_CONTROL)
                    || g.input.keyDown(GLFW.GLFW_KEY_RIGHT_CONTROL);
        }
        if (chargingThrow && g.input.down(KeyBindings.Action.DROP))
            throwCharge = Math.min(1f, throwCharge + dt / THROW_CHARGE_TIME);
        if (chargingThrow && g.input.released(KeyBindings.Action.DROP)) {
            throwHeldItem(throwWholeStack, throwCharge);
            chargingThrow = false;
            throwCharge = 0f;
        }
    }

    /**
     * Бросает стопку перед игроком — туда, куда он смотрит. Так же уходит на
     * землю то, что осталось на курсоре при закрытии окна и не влезло
     * обратно: молча уничтожать предмет нельзя.
     */
    void throwStack(ItemStack thrown) {
        throwStack(thrown, 0.45f);
    }

    private void throwStack(ItemStack thrown, float charge) {
        if (thrown == null || thrown.count <= 0 || g.world == null)
            return;
        Player player = g.player;
        Vector3f eye = player.camera.position;
        Vector3f fwd = player.camera.forward();
        float sx = eye.x + fwd.x * 0.45f, sy = eye.y - 0.3f + fwd.y * 0.45f, sz = eye.z + fwd.z * 0.45f;
        // Лицом в стену точка вылета оказалась бы в блоке — тогда из глаз.
        if (g.world.getBlock((int) Math.floor(sx), (int) Math.floor(sy), (int) Math.floor(sz)).solid) {
            sx = eye.x;
            sy = eye.y - 0.3f;
            sz = eye.z;
        }
        ItemEntity e = new ItemEntity(thrown,
                sx, sy, sz, ItemEntity.THROW_DELAY, g.itemRandom.nextFloat() * 6.28f);
        float strength = Math.max(0f, Math.min(1f, charge));
        float speed = THROW_SPEED * (0.45f + 1.15f * strength);
        e.velocity.set(fwd.x * speed + player.velocity.x,
                fwd.y * speed + THROW_LIFT * (0.65f + strength * 0.35f),
                fwd.z * speed + player.velocity.z);
        if (!g.net.requestDrop(e)) g.addItemEntity(e);
        g.sound.playOneOf(g.sounds.pickup(), 0.3f, 0.75f + 0.15f * g.itemRandom.nextFloat());
    }
}
