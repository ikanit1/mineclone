package com.mineclone.world.entity;

import com.mineclone.world.BlockType;
import com.mineclone.world.World;
import org.joml.Vector3f;

/**
 * Упрощённая физика сущностей: гравитация + осевой AABB-свип по solid-блокам.
 *
 * Отличия от {@link com.mineclone.game.Player}: нет авто-шага по лестницам, нет
 * MLG-механик, нет плавания — в воде сущность просто всплывает и барахтается.
 * Упёрлась в стену → {@code hitWall}, дальше решает ИИ (обычно прыжком).
 *
 * Без состояния: вся память живёт в переданных pos/vel.
 */
public final class EntityPhysics {
    public static final float GRAVITY = -28f;        // как у игрока
    public static final float JUMP_VELOCITY = 8.0f;  // хватает на 1 блок
    /** Подъёмная сила в воде, м/с². */
    public static final float BUOYANCY = 4.0f;
    private static final float EPS = 1e-4f;

    /** Результат одного шага: контакты, которые нужны ИИ. */
    public record Contact(boolean onGround, boolean hitWall, boolean inWater) {}

    private EntityPhysics() {}

    /**
     * Продвигает сущность на dt. Горизонтальную составляющую vel задаёт ИИ
     * перед вызовом; вертикальную считает этот метод.
     *
     * @param maxFallSpeed терминальная скорость падения (отрицательная)
     */
    public static Contact step(World world, Vector3f pos, Vector3f vel,
                               float width, float height, float dt, float maxFallSpeed) {
        boolean inWater = touchingWater(world, pos, width, height);
        if (inWater) {
            // Ослабленная гравитация + подъём: сущность выныривает и качается
            // у поверхности вместо того, чтобы утонуть.
            vel.y += GRAVITY * 0.25f * dt;
            if (vel.y < 0.6f)
                vel.y += BUOYANCY * dt;
            vel.y = Math.max(-1.2f, Math.min(vel.y, 1.6f));
        } else {
            vel.y += GRAVITY * dt;
            if (vel.y < maxFallSpeed)
                vel.y = maxFallSpeed;
        }

        boolean hitWall = false;
        boolean onGround = false;

        pos.x += vel.x * dt;
        if (resolve(world, pos, width, height, 0, vel.x)) {
            vel.x = 0f;
            hitWall = true;
        }
        pos.z += vel.z * dt;
        if (resolve(world, pos, width, height, 2, vel.z)) {
            vel.z = 0f;
            hitWall = true;
        }
        pos.y += vel.y * dt;
        if (resolve(world, pos, width, height, 1, vel.y)) {
            if (vel.y < 0f)
                onGround = true;
            vel.y = 0f;
        }
        if (!onGround)
            onGround = groundBelow(world, pos, width);

        return new Contact(onGround, hitWall, inWater);
    }

    /**
     * Выталкивает AABB из solid-блоков по одной оси (0=X, 1=Y, 2=Z).
     * Одного разрешения на ось достаточно при малом dt — так же устроен Player.
     *
     * @param dir знак движения по оси
     * @return true, если было пересечение
     */
    private static boolean resolve(World world, Vector3f pos, float width, float height,
                                   int axis, float dir) {
        if (dir == 0f)
            return false;
        float hw = width / 2f;
        int x0 = (int) Math.floor(pos.x - hw), x1 = (int) Math.floor(pos.x + hw);
        int y0 = (int) Math.floor(pos.y),      y1 = (int) Math.floor(pos.y + height);
        int z0 = (int) Math.floor(pos.z - hw), z1 = (int) Math.floor(pos.z + hw);
        boolean hit = false;
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++) {
                    if (!world.getBlock(x, y, z).solid)
                        continue;
                    hit = true;
                    switch (axis) {
                        case 0 -> pos.x = dir > 0 ? x - hw - EPS : x + 1 + hw + EPS;
                        case 1 -> pos.y = dir > 0 ? y - height - EPS : y + 1 + EPS;
                        default -> pos.z = dir > 0 ? z - hw - EPS : z + 1 + hw + EPS;
                    }
                }
        return hit;
    }

    private static boolean groundBelow(World world, Vector3f pos, float width) {
        float hw = width / 2f;
        int y = (int) Math.floor(pos.y - 1e-3f);
        int xa = (int) Math.floor(pos.x - hw + 1e-3f), xb = (int) Math.floor(pos.x + hw - 1e-3f);
        int za = (int) Math.floor(pos.z - hw + 1e-3f), zb = (int) Math.floor(pos.z + hw - 1e-3f);
        for (int x = xa; x <= xb; x++)
            for (int z = za; z <= zb; z++)
                if (world.getBlock(x, y, z).solid)
                    return true;
        return false;
    }

    private static boolean touchingWater(World world, Vector3f pos, float width, float height) {
        float hw = width / 2f;
        int x0 = (int) Math.floor(pos.x - hw), x1 = (int) Math.floor(pos.x + hw - 0.01f);
        int y0 = (int) Math.floor(pos.y + 0.1f), y1 = (int) Math.floor(pos.y + height * 0.75f);
        int z0 = (int) Math.floor(pos.z - hw), z1 = (int) Math.floor(pos.z + hw - 0.01f);
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++) {
                    BlockType b = world.getBlock(x, y, z);
                    if (b == BlockType.WATER || b == BlockType.WATER_FLOW)
                        return true;
                }
        return false;
    }

    /**
     * Есть ли прямая видимость между двумя точками — сэмплирование отрезка с
     * шагом чуть меньше блока. Не DDA: нужен ответ «да/нет», а не список ячеек,
     * и такой шаг не пропускает стену толщиной в блок.
     */
    public static boolean lineOfSight(World world, float x0, float y0, float z0,
                                      float x1, float y1, float z1) {
        float dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 1e-3f)
            return true;
        int steps = (int) Math.ceil(dist / 0.25f);
        for (int i = 1; i < steps; i++) {
            float t = i / (float) steps;
            int bx = (int) Math.floor(x0 + dx * t);
            int by = (int) Math.floor(y0 + dy * t);
            int bz = (int) Math.floor(z0 + dz * t);
            if (world.getBlock(bx, by, bz).solid)
                return false;
        }
        return true;
    }

    /**
     * Мягко расталкивает два AABB по горизонтали, если они пересекаются: каждый
     * сдвигается на свою долю перекрытия. Доля 0 означает «не двигать» — так
     * игрок остаётся хозяином своей физики, а отходит только моб.
     *
     * Полное перекрытие за один тик не устраняется специально: доли по 0.25
     * разводят тела за несколько кадров без дёрганья.
     *
     * @return true, если было перекрытие
     */
    public static boolean separate(Vector3f a, float widthA, float heightA, float shareA,
                                   Vector3f b, float widthB, float heightB, float shareB) {
        // По вертикали не пересекаются — один стоит на другом, не расталкиваем.
        if (a.y + heightA <= b.y || b.y + heightB <= a.y)
            return false;
        float dx = b.x - a.x, dz = b.z - a.z;
        float minDist = (widthA + widthB) * 0.5f;
        float d2 = dx * dx + dz * dz;
        if (d2 >= minDist * minDist)
            return false;
        float d = (float) Math.sqrt(d2);
        if (d < 1e-4f) {   // центры совпали — разводим по произвольной оси
            dx = 1f;
            dz = 0f;
            d = 1f;
        }
        float overlap = minDist - d;
        float nx = dx / d, nz = dz / d;
        a.x -= nx * overlap * shareA;
        a.z -= nz * overlap * shareA;
        b.x += nx * overlap * shareB;
        b.z += nz * overlap * shareB;
        return true;
    }

    /**
     * Параметрическое пересечение луча с AABB (метод слэбов).
     * Используется и для выбора моба под прицелом, и для дистанции до блока.
     *
     * @return дистанция вдоль нормализованного dir до входа в бокс, либо -1
     */
    public static float rayAabbDistance(float ox, float oy, float oz,
                                        float dx, float dy, float dz,
                                        float minX, float minY, float minZ,
                                        float maxX, float maxY, float maxZ) {
        float tmin = 0f;
        float tmax = Float.MAX_VALUE;
        for (int axis = 0; axis < 3; axis++) {
            float o = axis == 0 ? ox : axis == 1 ? oy : oz;
            float d = axis == 0 ? dx : axis == 1 ? dy : dz;
            float lo = axis == 0 ? minX : axis == 1 ? minY : minZ;
            float hi = axis == 0 ? maxX : axis == 1 ? maxY : maxZ;
            if (Math.abs(d) < 1e-8f) {
                if (o < lo || o > hi)
                    return -1f;  // луч параллелен слэбу и вне него
                continue;
            }
            float t1 = (lo - o) / d;
            float t2 = (hi - o) / d;
            if (t1 > t2) {
                float tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
            if (tmin > tmax)
                return -1f;
        }
        return tmin;
    }
}
