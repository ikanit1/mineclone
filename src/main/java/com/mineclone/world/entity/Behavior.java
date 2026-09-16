package com.mineclone.world.entity;

import java.util.function.BiFunction;
import java.util.function.BiPredicate;

/**
 * Дерево поведения моба: один узел, который за тик возвращает свой исход.
 *
 * Зачем оно вместо цепочки {@code if}: у зомби набралось пять поводов
 * действовать (гореть и искать тень, гнаться, бить, идти на шум, гулять), и
 * порядок между ними — это правило игры, а не деталь реализации. В цепочке
 * {@code if} порядок размазан по телу метода вместе с ранними {@code return};
 * в дереве он записан одним выражением сверху вниз, и новую ветку можно
 * вставить, не перечитывая соседние.
 *
 * Дерево без состояния: вся память живёт в самом мобе, узлы — чистые правила,
 * одни и те же на всех мобов вида. Поэтому и строится оно один раз статически.
 */
@FunctionalInterface
public interface Behavior {

    enum Status {
        /** Ветка отработала и закончила. */
        SUCCESS,
        /** Ветка не подошла — селектор пробует следующую. */
        FAILURE,
        /** Ветка работает и хочет продолжения в следующем тике. */
        RUNNING
    }

    Status tick(Mob mob, MobContext ctx);

    /** Первая ветка, которая не провалилась. Это «или» по порядку важности. */
    static Behavior selector(Behavior... children) {
        return (mob, ctx) -> {
            for (Behavior c : children) {
                Status s = c.tick(mob, ctx);
                if (s != Status.FAILURE)
                    return s;
            }
            return Status.FAILURE;
        };
    }

    /** Все ветки подряд, пока каждая удаётся. Это «и». */
    static Behavior sequence(Behavior... children) {
        return (mob, ctx) -> {
            for (Behavior c : children) {
                Status s = c.tick(mob, ctx);
                if (s != Status.SUCCESS)
                    return s;
            }
            return Status.SUCCESS;
        };
    }

    /** Условие: SUCCESS, если предикат верен, иначе FAILURE. */
    static Behavior check(BiPredicate<Mob, MobContext> test) {
        return (mob, ctx) -> test.test(mob, ctx) ? Status.SUCCESS : Status.FAILURE;
    }

    /** Действие, само решающее свой исход. */
    static Behavior act(BiFunction<Mob, MobContext, Status> action) {
        return action::apply;
    }

    /** Действие, которое всегда удаётся. */
    static Behavior run(java.util.function.BiConsumer<Mob, MobContext> action) {
        return (mob, ctx) -> {
            action.accept(mob, ctx);
            return Status.SUCCESS;
        };
    }
}
