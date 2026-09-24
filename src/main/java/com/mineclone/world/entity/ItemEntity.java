package com.mineclone.world.entity;

import com.mineclone.world.DroppedItem;
import com.mineclone.world.ItemStack;
import com.mineclone.world.World;
import org.joml.Vector3f;

import java.util.List;
import java.util.Random;

/**
 * Предмет на земле: выпал из блока, из моба, из разбитого сундука или брошен.
 *
 * Лежит не мёртвой иконкой, а живой вещью: подпрыгивает, падая, покачивается
 * над землёй и медленно вращается. Когда игрок подходит ближе
 * {@link #MAGNET_RANGE} — и в инвентаре есть место, — предмет срывается с
 * места и летит к нему, ускоряясь. Одинаковые стопки рядом сливаются: сотня
 * булыжников из разбитой стены — это пара стопок, а не сотня сущностей.
 *
 * Без GL: логика проверяется тестами, рисует {@code ItemRenderer}.
 */
public final class ItemEntity {

    /** Сторона коробки предмета для физики, блоки. */
    public static final float SIZE = 0.25f;
    /** С какого расстояния предмет тянется к игроку. */
    public static final float MAGNET_RANGE = 3.2f;
    /** Ближе этого — подобран. */
    public static final float PICKUP_RANGE = 0.85f;
    /** Ускорение полёта к игроку и его предел. */
    private static final float MAGNET_ACCEL = 32f, MAGNET_MAX_SPEED = 10f;
    /** Пауза до подбора у выпавшего из блока и у брошенного рукой. */
    public static final float DROP_DELAY = 0.45f, THROW_DELAY = 1.6f;
    /** Через сколько секунд лежащий предмет исчезает. */
    public static final float DESPAWN_TIME = 300f;
    /** Ближе этого одинаковые стопки сливаются. */
    public static final float MERGE_RANGE = 0.8f;
    private static final float MAX_FALL = -20f;

    public final ItemStack stack;
    public final Vector3f position = new Vector3f();
    public final Vector3f velocity = new Vector3f();
    public float age;
    public float pickupDelay;
    public boolean onGround;
    /** Сейчас летит к игроку. */
    public boolean magnetized;
    /** Water state is exposed to the renderer for wave bob/tilt. */
    public boolean floating;
    /** Фаза покачивания и вращения — у каждого своя, иначе россыпь качается строем. */
    public final float phase;

    public ItemEntity(ItemStack stack, float x, float y, float z, float pickupDelay, float phase) {
        this.stack = stack;
        this.position.set(x, y, z);
        this.pickupDelay = pickupDelay;
        this.phase = phase;
    }

    /** Выпавший предмет с небольшим разлётом вверх и в сторону. */
    public static ItemEntity popped(ItemStack stack, float x, float y, float z, Random rnd) {
        ItemEntity e = new ItemEntity(stack, x, y, z, DROP_DELAY, rnd.nextFloat() * 6.28f);
        e.velocity.set((rnd.nextFloat() - 0.5f) * 2.4f, 3.2f + rnd.nextFloat() * 1.6f,
                (rnd.nextFloat() - 0.5f) * 2.4f);
        return e;
    }

    public static ItemEntity restored(DroppedItem d, Random rnd) {
        ItemEntity e = new ItemEntity(d.stack, d.x, d.y, d.z, 0f, rnd.nextFloat() * 6.28f);
        e.age = d.age;
        return e;
    }

    public DroppedItem toDropped() {
        return new DroppedItem(stack, position.x, position.y, position.z, age);
    }

    /**
     * Шаг предмета.
     *
     * @param target   куда тянет магнит — середина тела игрока; null, когда
     *                 тянуть некому (у выделенного сервера своего игрока нет)
     * @param canTake  в инвентаре есть место под эту стопку: без места
     *                 предмет не срывается к игроку и не мельтешит у ног
     */
    public void update(World world, Vector3f target, boolean canTake, float dt) {
        age += dt;
        if (pickupDelay > 0f)
            pickupDelay -= dt;
        float dx = 0f, dy = 0f, dz = 0f, dist = Float.POSITIVE_INFINITY;
        if (target != null) {
            dx = target.x - position.x;
            dy = target.y - position.y;
            dz = target.z - position.z;
            dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        magnetized = canTake && pickupDelay <= 0f && dist < MAGNET_RANGE;
        if (magnetized && dist > 1e-3f) {
            // Тяга растёт к игроку: издалека предмет трогается с места, у ног
            // уже летит. Гравитацию отчасти гасим — иначе он волочится по земле.
            float pull = MAGNET_ACCEL * (1.2f - dist / MAGNET_RANGE);
            velocity.x += dx / dist * pull * dt;
            velocity.y += (dy / dist * pull + 20f) * dt;
            velocity.z += dz / dist * pull * dt;
            float speed = velocity.length();
            if (speed > MAGNET_MAX_SPEED)
                velocity.mul(MAGNET_MAX_SPEED / speed);
        }
        EntityPhysics.Contact c = EntityPhysics.step(world, position, velocity, SIZE, SIZE, dt, MAX_FALL);
        onGround = c.onGround();
        if (c.inWater()) {
            float density = density(stack);
            floating = density < 1f;
            if (floating) {
                velocity.y += (1.05f - density) * 8f * dt;
                velocity.y = Math.min(1.05f, velocity.y);
                float drag = (float)Math.pow(0.18, dt);
                velocity.x *= drag;
                velocity.z *= drag;
            } else {
                velocity.y -= Math.min(8f, (density - 1f) * 5f) * dt;
                velocity.y = Math.max(-2.8f, velocity.y);
            }
        } else {
            floating = false;
        }
        if (onGround && !magnetized) {
            // Трение: брошенный предмет скользит и останавливается, а не катится вечно.
            float friction = (float) Math.pow(0.002, dt);
            velocity.x *= friction;
            velocity.z *= friction;
        }
    }

    /** Может ли игрок подобрать предмет прямо сейчас. */
    public boolean readyForPickup(Vector3f target) {
        return pickupDelay <= 0f && position.distanceSquared(target) < PICKUP_RANGE * PICKUP_RANGE;
    }

    public boolean expired() {
        return age >= DESPAWN_TIME || stack.count <= 0;
    }

    /** Подъём над землёй для отрисовки: предмет висит и покачивается. */
    public float bob() {
        return 0.12f + (float) Math.sin(age * 2.6f + phase) * 0.05f;
    }

    /** Угол медленного вращения, радианы. */
    public float spin() {
        return age * 1.7f + phase;
    }

    /** Relative density: below one floats, above one sinks. */
    public static float density(ItemStack s) {
        if (s.food() != null) return 0.82f;
        if (s.hasDurability()) return 2.4f;
        if (s.block() == null) return 1f;
        return switch (s.block()) {
            case WOOD, PLANKS, LEAVES, ROPE, CHEST, JOURNAL -> 0.58f;
            case TORCH, WEB -> 0.72f;
            case STONE, COBBLE, MOSSY_COBBLE, BEDROCK, COAL_ORE, IRON_ORE,
                    GOLD_ORE, DIAMOND_ORE, OBSIDIAN, CHAIN -> 2.7f;
            default -> 1.15f;
        };
    }

    /** Wave tilt used by ItemRenderer while the item rides the surface. */
    public float waveTilt() {
        return floating ? (float)Math.sin(age * 2.1f + phase) * 0.18f : 0f;
    }

    /**
     * Сливает соседние одинаковые стопки. Второй предмет отдаёт первому
     * сколько влезет; опустевший исчезает на ближайшей проверке {@link #expired}.
     * Инструменты не сливаются никогда — у каждого свой износ.
     */
    public static void mergeNearby(List<ItemEntity> items) {
        for (int i = 0; i < items.size(); i++) {
            ItemEntity a = items.get(i);
            if (a.stack.count <= 0 || a.stack.isFull() || a.magnetized)
                continue;
            for (int j = i + 1; j < items.size(); j++) {
                ItemEntity b = items.get(j);
                if (b.stack.count <= 0 || b.magnetized || !a.stack.stacksWith(b.stack))
                    continue;
                if (a.position.distanceSquared(b.position) > MERGE_RANGE * MERGE_RANGE)
                    continue;
                int left = a.stack.addUpTo(b.stack.count);
                b.stack.count = left;
                // Слитая стопка берёт возраст младшей: иначе свежий дроп
                // исчезал бы по таймеру старого.
                a.age = Math.min(a.age, b.age);
                if (a.stack.isFull())
                    break;
            }
        }
    }
}
