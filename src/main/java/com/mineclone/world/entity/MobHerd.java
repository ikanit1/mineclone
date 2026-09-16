package com.mineclone.world.entity;

import java.util.List;

/**
 * Стайность мирных мобов: кто с кем пасётся.
 *
 * Раз в тик считает для каждого мирного моба центр своих сородичей поблизости
 * и отдаёт его мобу. Дальше решение принимает сам моб — здесь только
 * арифметика соседства.
 *
 * Отдельный класс, а не метод в {@code Game}: перебор пар — это правило игры
 * («корова тянется к коровам, а не к зомби»), и его надо уметь прогнать в
 * тесте без окна и без цикла кадров.
 */
public final class MobHerd {

    /** Дальше этого сородич уже не считается соседом по стаду. */
    public static final float RANGE = 14f;

    private MobHerd() {}
    private static final ThreadLocal<MobSpatialGrid> GRIDS = ThreadLocal.withInitial(MobSpatialGrid::new);

    /**
     * Обновляет у каждого мирного моба центр его стада.
     *
     * Мёртвые не считаются: стадо не должно кучковаться вокруг трупа, пока
     * тот доигрывает падение.
     */
    public static void update(List<Mob> mobs) {
        MobSpatialGrid grid = GRIDS.get();
        grid.rebuild(mobs);
        float r2 = RANGE * RANGE;
        for (Mob m : mobs) {
            if (m.dead || m.type.hostile) {
                m.setHerd(0f, 0f, 0);
                continue;
            }
            float sx = 0f, sz = 0f;
            int n = 0;
            for (Mob o : grid.nearby(m, RANGE)) {
                if (o == m || o.dead || o.type != m.type)
                    continue;
                float dx = o.position.x - m.position.x;
                float dz = o.position.z - m.position.z;
                if (dx * dx + dz * dz > r2)
                    continue;
                sx += o.position.x;
                sz += o.position.z;
                n++;
            }
            if (n == 0)
                m.setHerd(0f, 0f, 0);
            else
                m.setHerd(sx / n, sz / n, n);
        }
    }
}
