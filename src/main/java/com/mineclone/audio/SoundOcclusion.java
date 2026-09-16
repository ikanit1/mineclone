package com.mineclone.audio;

import com.mineclone.world.BlockType;
import com.mineclone.world.World;
import org.joml.Vector3f;

/**
 * Насколько геометрия глушит звук по пути от источника к уху.
 *
 * Полноценной окклюзии с фильтрацией верхних частот здесь нет: она требует
 * расширения OpenAL EFX, которое есть не на каждом драйвере, а проверить
 * результат на слух в автоматическом прогоне всё равно нельзя. Зато падение
 * громкости по числу стен — честная, дешёвая и полностью проверяемая часть
 * эффекта, и именно она несёт геймплей: зомби за стеной слышно тише, чем
 * зомби в коридоре, и по громкости можно понять, открыт ли путь.
 *
 * Чистая математика, без OpenAL — поэтому покрывается обычными тестами.
 */
public final class SoundOcclusion {

    /** Во сколько раз глушит один сплошной блок на пути. */
    public static final float PER_BLOCK = 0.55f;
    /**
     * Дальше считать бессмысленно: шесть стен это уже -97 дБ, и разница
     * между шестью и двадцатью неслышима, а цикл дорожает.
     */
    public static final int MAX_BLOCKS = 6;
    /** Шаг трассировки в блоках. Меньше половины блока, чтобы не перескакивать стены. */
    private static final float STEP = 0.35f;

    private SoundOcclusion() {}

    /**
     * Множитель громкости 0..1.
     *
     * @param listener позиция уха
     * @param source   позиция источника
     */
    public static float factor(World world, Vector3f listener, Vector3f source) {
        if (world == null)
            return 1f;
        return gainFor(solidBetween(world, listener, source));
    }

    /** Множитель громкости для известного числа стен. */
    public static float gainFor(int blocks) {
        int n = Math.min(Math.max(blocks, 0), MAX_BLOCKS);
        return (float) Math.pow(PER_BLOCK, n);
    }

    /** Сколько стен глушат верх полностью. */
    public static final int MUFFLE_BLOCKS = 3;

    /**
     * Насколько звук глухой 0..1: за стеной пропадают высокие частоты раньше,
     * чем громкость. Одна стена — уже «из-за стены», три — глухой гул.
     * Отдаётся фильтру нижних частот в {@link SoundEngine}, если драйвер
     * умеет EFX; без него остаётся одна громкость.
     */
    public static float muffle(int blocks) {
        return Math.max(0f, Math.min(1f, blocks / (float) MUFFLE_BLOCKS));
    }

    /**
     * Сколько сплошных блоков пересекает отрезок.
     *
     * Считаются именно разные клетки: без дедупликации один блок, пройденный
     * по диагонали, засчитывался бы трижды и глушил звук как три стены.
     * Клетки самого источника и самого уха не считаются — источник внутри
     * блока (вода, листва) не должен глушить сам себя.
     */
    public static int solidBetween(World world, Vector3f a, Vector3f b) {
        float dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 1e-4f)
            return 0;
        int steps = (int) Math.ceil(dist / STEP);
        int startX = (int) Math.floor(a.x), startY = (int) Math.floor(a.y), startZ = (int) Math.floor(a.z);
        int endX = (int) Math.floor(b.x), endY = (int) Math.floor(b.y), endZ = (int) Math.floor(b.z);
        int lastX = Integer.MIN_VALUE, lastY = 0, lastZ = 0;
        int count = 0;
        for (int i = 1; i < steps; i++) {
            float t = i / (float) steps;
            int x = (int) Math.floor(a.x + dx * t);
            int y = (int) Math.floor(a.y + dy * t);
            int z = (int) Math.floor(a.z + dz * t);
            if (x == lastX && y == lastY && z == lastZ)
                continue;
            lastX = x; lastY = y; lastZ = z;
            if ((x == startX && y == startY && z == startZ) || (x == endX && y == endY && z == endZ))
                continue;
            BlockType block = world.getBlock(x, y, z);
            if (block.solid && !block.transparent && !block.cutout) {
                if (++count >= MAX_BLOCKS)
                    return MAX_BLOCKS;
            }
        }
        return count;
    }
}
