package com.mineclone.item;

import com.mineclone.data.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Категории креатива: список, а не перечисление.
 *
 * <p>Порядок в файле и есть порядок вкладок и сортировки — отдельного поля
 * «вес» нет: два способа задать очередь неизбежно разойдутся.
 */
public final class Categories {

    private final Map<String, String> names = new LinkedHashMap<>();
    private final List<String> ids = new ArrayList<>();

    private Categories() {}

    /** Пустой набор: нужен реестру, собранному в тесте без файла категорий. */
    public static Categories empty() {
        return new Categories();
    }

    /**
     * Собирает категории из {@code categories.json} нескольких пространств
     * имён. Повтор id — ошибка: мод, переопределяющий чужую категорию,
     * молча менял бы игре порядок вкладок.
     */
    public static Categories load(List<JsonObject> roots) {
        Categories c = new Categories();
        for (JsonObject root : roots) {
            root.allowOnly("categories");
            int n = root.array("categories").size();
            for (int i = 0; i < n; i++) {
                JsonObject e = root.element("categories", i);
                e.allowOnly("id", "name");
                String id = e.string("id");
                if (c.names.containsKey(id))
                    throw e.error("id", "duplicate category " + id);
                c.names.put(id, e.string("name"));
                c.ids.add(id);
            }
        }
        return c;
    }

    public boolean has(String id) {
        return names.containsKey(id);
    }

    /** Имена по порядку объявления. */
    public List<String> ids() {
        return List.copyOf(ids);
    }

    public String name(String id) {
        return names.get(id);
    }

    /** Порядковый номер для сортировки; {@code -1} — категории нет. */
    public int index(String id) {
        return ids.indexOf(id);
    }

    public int size() {
        return ids.size();
    }
}
