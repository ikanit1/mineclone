package com.mineclone.render;

import com.mineclone.world.BlockType;
import com.mineclone.world.World;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Обломки разбитого блока: маленькие кубики с физикой.
 *
 * Плоские квадратики-частицы, развёрнутые к камере, в блочном мире выдают себя
 * с первого взгляда — у куска камня нет «лица», которое всегда смотрит на
 * игрока. Здесь каждый обломок — настоящий куб с кусочком текстуры блока:
 * он кувыркается, падает, отскакивает от земли и замирает, прежде чем
 * растаять. Лёд бьётся на плоские осколки, которые прыгают выше и дольше.
 *
 * Только логика, без GL — поэтому поведение обломков проверяется тестом.
 * Рисует {@link DebrisRenderer}.
 */
public final class Debris {

    /** Предел живых обломков: сверх него старые уходят первыми. */
    public static final int MAX = 220;
    public static final float GRAVITY = 24f;
    /** Сколько живёт обломок, секунды. */
    public static final float LIFE_MIN = 1.1f, LIFE_MAX = 1.9f;

    /** Один обломок. Поля открыты рендереру. */
    public static final class Piece {
        public float x, y, z;
        float vx, vy, vz;
        /** Углы кувырка и скорость вращения, радианы. */
        public float rotX, rotY, rotZ;
        float spinX, spinY, spinZ;
        /** Ребро кубика и его сплющенность по высоте (у осколков льда меньше единицы). */
        public float size, flatten = 1f;
        public float life, maxLife;
        /** Тайл блока и сдвиг кусочка текстуры внутри тайла (0..1). */
        public int topTile, sideTile;
        public float texU, texV;
        public float skyLight, blockLight;
        public boolean glassy;
        float bounce;
        public boolean resting;

        /** Масштаб 0..1 на исчезновение: последняя четверть жизни — усыхание. */
        public float scale() {
            float k = life / (maxLife * 0.25f);
            return Math.max(0f, Math.min(1f, k));
        }
    }

    private final List<Piece> pieces = new ArrayList<>();
    private final Random rnd;

    public Debris(long seed) {
        this.rnd = new Random(seed);
    }

    /**
     * Разлетающиеся обломки блока.
     *
     * @param sky,blk свет в точке блока — обломки освещены так же, как он
     */
    public void spawn(int bx, int by, int bz, BlockType block, float sky, float blk) {
        boolean ice = block == BlockType.ICE || block == BlockType.GLASS;
        int count = ice ? 16 : 10 + rnd.nextInt(5);
        spawnPieces(bx, by, bz, block, sky, blk, count, 1f, ice);
    }

    /** Сильный удар дробит блок на более мелкие и быстрые микровоксели. */
    public void spawnShatter(int bx, int by, int bz, BlockType block, float sky, float blk,
                             float impulse) {
        boolean ice = block == BlockType.ICE || block == BlockType.THIN_ICE || block == BlockType.GLASS;
        int count = Math.min(38, 20 + (int) (Math.max(0f, impulse) * 4f));
        spawnPieces(bx, by, bz, block, sky, blk, count, 0.62f, ice);
    }

    private void spawnPieces(int bx, int by, int bz, BlockType block, float sky, float blk,
                             int count, float sizeMul, boolean ice) {
        for (int i = 0; i < count; i++) {
            if (pieces.size() >= MAX)
                pieces.remove(0);
            Piece p = new Piece();
            p.x = bx + 0.2f + rnd.nextFloat() * 0.6f;
            p.y = by + 0.2f + rnd.nextFloat() * 0.6f;
            p.z = bz + 0.2f + rnd.nextFloat() * 0.6f;
            // Разлёт от центра блока: кусок с краю летит в свою сторону.
            float ox = p.x - (bx + 0.5f), oz = p.z - (bz + 0.5f);
            float kick = sizeMul < 1f ? 7.5f : 5f;
            p.vx = ox * kick + (rnd.nextFloat() - 0.5f) * 1.5f;
            p.vz = oz * kick + (rnd.nextFloat() - 0.5f) * 1.5f;
            p.vy = 2.5f + rnd.nextFloat() * (ice ? 4.5f : 3.5f) + (sizeMul < 1f ? 1.5f : 0f);
            p.size = (ice ? 0.07f + rnd.nextFloat() * 0.09f : 0.08f + rnd.nextFloat() * 0.08f)
                    * sizeMul;
            p.flatten = ice ? 0.25f + rnd.nextFloat() * 0.2f : 0.8f + rnd.nextFloat() * 0.2f;
            p.spinX = (rnd.nextFloat() - 0.5f) * 14f;
            p.spinY = (rnd.nextFloat() - 0.5f) * 14f;
            p.spinZ = (rnd.nextFloat() - 0.5f) * 14f;
            p.rotX = rnd.nextFloat() * 6.28f;
            p.rotY = rnd.nextFloat() * 6.28f;
            p.life = p.maxLife = LIFE_MIN + rnd.nextFloat() * (LIFE_MAX - LIFE_MIN);
            p.topTile = block.topTile;
            p.sideTile = block.sideTile;
            p.texU = rnd.nextFloat();
            p.texV = rnd.nextFloat();
            p.skyLight = sky;
            p.blockLight = blk;
            p.glassy = ice;
            p.bounce = ice ? 0.42f : 0.28f;
            pieces.add(p);
        }
    }

    public void update(World world, float dt) {
        Iterator<Piece> it = pieces.iterator();
        while (it.hasNext()) {
            Piece p = it.next();
            p.life -= dt;
            if (p.life <= 0f) {
                it.remove();
                continue;
            }
            if (p.resting)
                continue;
            p.vy -= GRAVITY * dt;
            float half = p.size * p.flatten * 0.5f;

            // Горизонталь: в стену — отскок назад с потерей скорости.
            float nx = p.x + p.vx * dt;
            if (solid(world, nx, p.y, p.z)) {
                p.vx = -p.vx * 0.3f;
            } else {
                p.x = nx;
            }
            float nz = p.z + p.vz * dt;
            if (solid(world, p.x, p.y, nz)) {
                p.vz = -p.vz * 0.3f;
            } else {
                p.z = nz;
            }

            // Вертикаль: пол ловится по нижней грани кубика.
            float ny = p.y + p.vy * dt;
            if (p.vy < 0f && solid(world, p.x, ny - half, p.z)) {
                p.y = (float) Math.floor(ny - half) + 1f + half;
                if (-p.vy < 1.2f) {
                    // Слишком медленно для отскока — ложится и замирает.
                    p.vy = 0f;
                    p.resting = true;
                } else {
                    p.vy = -p.vy * p.bounce;
                    p.vx *= 0.55f;
                    p.vz *= 0.55f;
                    p.spinX *= 0.5f;
                    p.spinY *= 0.5f;
                    p.spinZ *= 0.5f;
                }
            } else if (p.vy > 0f && solid(world, p.x, ny + half, p.z)) {
                p.vy = 0f;
            } else {
                p.y = ny;
            }
            p.rotX += p.spinX * dt;
            p.rotY += p.spinY * dt;
            p.rotZ += p.spinZ * dt;
            float drag = (float) Math.pow(0.35, dt);
            p.vx *= drag;
            p.vz *= drag;
        }
    }

    private static boolean solid(World world, float x, float y, float z) {
        return world.getBlock((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)).solid;
    }

    public List<Piece> pieces() {
        return pieces;
    }

    public void clear() {
        pieces.clear();
    }
}
