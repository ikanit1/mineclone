package com.mineclone.item;

import com.mineclone.core.AppPaths;
import com.mineclone.data.DataPack;

/**
 * Единственный реестр предметов игры.
 *
 * <p>Статическая точка входа, а не поле {@code Game}: ссылку на предмет держит
 * стопка, а стопки живут в сейвах, в мире и в тестах — протаскивать реестр
 * через каждый из этих слоёв пришлось бы ради одного и того же объекта.
 *
 * <p>Загрузка ленивая: тесту, который реестр не трогает, незачем читать
 * каталог данных с диска.
 */
public final class Items {

    private static ItemRegistry registry;

    private Items() {}

    public static synchronized ItemRegistry get() {
        if (registry == null)
            registry = ItemRegistry.load(new DataPack(AppPaths.file("assets/data").toPath()));
        return registry;
    }

    /** Подменить реестр: тест на своём наборе данных. */
    public static synchronized void set(ItemRegistry r) {
        registry = r;
    }
}
