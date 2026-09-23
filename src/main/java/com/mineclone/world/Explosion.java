package com.mineclone.world;

import com.mineclone.world.entity.EntityPhysics;

import java.util.ArrayList;
import java.util.List;

/**
 * Взрыв: что сносит и кого задевает.
 *
 * Чистые функции над миром — без GL, звука и частиц. Поэтому «взрыв не
 * пробивает стену» и «обсидиан устоит» проверяются числами, а не подрывом
 * крипера у себя во дворе.
 *
 * <p>Блок сносится, только если до него есть <b>прямая видимость</b> из
 * центра. Без этого взрыв в соседней комнате выносил бы стену насквозь, а
 * укрытие переставало быть укрытием — то есть терялась единственная защита
 * от крипера, которая у игрока есть.
 */
public final class Explosion {

    /** Радиус взрыва крипера в блоках. */
    public static final float RADIUS = 3.4f;
    /** Урон в эпицентре. */
    public static final float MAX_DAMAGE = 14f;
    /**
     * Прочнее этого блок устоит.
     *
     * Обсидиан и коренная порода переживают взрыв — иначе укрыться нельзя
     * нигде, а подрыв у пола вскрывает мир до самого низа.
     */
    public static final float TOUGH = 20f;

    private Explosion() {}

    /**
     * Координаты блоков, которые снесёт взрыв.
     *
     * @param radius радиус в блоках; за ним ничего не ломается
     */
    public static List<int[]> destroyed(World world, float x, float y, float z, float radius) {
        List<int[]> out = new ArrayList<>();
        if (world == null)
            return out;
        int r = (int) Math.ceil(radius);
        int cx = (int) Math.floor(x), cy = (int) Math.floor(y), cz = (int) Math.floor(z);
        for (int bx = cx - r; bx <= cx + r; bx++)
            for (int by = cy - r; by <= cy + r; by++)
                for (int bz = cz - r; bz <= cz + r; bz++) {
                    if (by < 1 || by >= Chunk.SIZE_Y)
                        continue;
                    float dx = bx + 0.5f - x, dy = by + 0.5f - y, dz = bz + 0.5f - z;
                    float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (dist > radius)
                        continue;
                    BlockType b = world.getBlock(bx, by, bz);
                    if (b == BlockType.AIR || !survivesNothing(b))
                        continue;
                    // Луч ведётся к грани, обращённой к центру, а не к
                    // середине блока: иначе первый же блок стены закрывает
                    // сам себя и не ломается ничего.
                    float tx = bx + 0.5f - Math.signum(dx) * 0.45f;
                    float ty = by + 0.5f - Math.signum(dy) * 0.45f;
                    float tz = bz + 0.5f - Math.signum(dz) * 0.45f;
                    if (!EntityPhysics.lineOfSight(world, x, y, z, tx, ty, tz))
                        continue;
                    out.add(new int[] { bx, by, bz });
                }
        return out;
    }

    /** Ломается ли блок взрывом вообще. */
    public static boolean survivesNothing(BlockType b) {
        return b.hardness >= 0f && b.hardness < TOUGH;
    }

    /**
     * Урон существу на таком расстоянии.
     *
     * Спадает линейно до нуля на краю: в эпицентре смертельно, у границы —
     * царапина, и отбежать всегда имеет смысл.
     */
    public static float damageAt(float distance, float radius, float maxDamage) {
        if (distance >= radius)
            return 0f;
        return maxDamage * (1f - distance / radius);
    }

    /** Урон по умолчанию — от крипера. */
    public static float damageAt(float distance) {
        return damageAt(distance, RADIUS, MAX_DAMAGE);
    }
}
