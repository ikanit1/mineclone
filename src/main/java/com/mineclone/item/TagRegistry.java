package com.mineclone.item;

import com.mineclone.data.DataPack;
import com.mineclone.data.JsonException;
import com.mineclone.data.JsonObject;
import com.mineclone.data.ResourceId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Теги предметов: {@code assets/data/<ns>/tags/items/<путь>.json}.
 *
 * <p>Тег перечисляет предметы и другие теги ({@code "#mineclone:wooden_tools"}).
 * Ссылка, а не копия: перечислять три деревянных инструмента в каждом теге —
 * значит забыть об одном из них, когда появится четвёртый.
 *
 * <p>Часть тегов не перечисляется, а вычисляется: «свет», «прочность»,
 * «топливо», «еда», «горючее» — это вопрос к полям предмета, и список,
 * составленный руками, разошёлся бы с ними на первом же новом блоке. Файл для
 * такого тега всё равно нужен — он даёт имя и псевдонимы для поиска.
 */
public final class TagRegistry {

    /** Вычисляемые теги: состав считает код, файл даёт только имя. */
    private static final Map<String, Predicate<Item>> COMPUTED = Map.of(
            "light", i -> i.block != null && i.block.emittedLight > 0,
            "durable", i -> i.durability > 0,
            "fuel", i -> i.fuelSeconds > 0f,
            "food", i -> i.food != null,
            "flammable", i -> i.block != null && i.block.isFlammable());

    private final Map<ResourceId, Tag> tags;
    private final Map<ResourceId, Set<Item>> members;

    private TagRegistry(Map<ResourceId, Tag> tags, Map<ResourceId, Set<Item>> members) {
        this.tags = tags;
        this.members = members;
    }

    public static TagRegistry empty() {
        return new TagRegistry(Map.of(), Map.of());
    }

    // ------------------------------------------------------------- загрузка

    /** Сырое определение из файла: до разрешения ссылок. */
    private record Def(ResourceId id, String name, List<String> aliases,
            List<String> values, boolean computed, JsonObject json) {}

    static TagRegistry load(DataPack pack, Collection<Item> items, Map<ResourceId, Item> byId) {
        Map<ResourceId, Def> defs = new LinkedHashMap<>();
        for (DataPack.Entry e : pack.files("tags/items")) {
            JsonObject o = e.json();
            o.allowOnly("name", "aliases", "values");
            String path = e.relPath().substring(0, e.relPath().length() - ".json".length());
            ResourceId id;
            try {
                id = new ResourceId(e.namespace(), path);
            } catch (IllegalArgumentException bad) {
                throw new JsonException(o.source(), path, bad.getMessage());
            }
            if (defs.containsKey(id))
                throw new JsonException(o.source(), path, "duplicate tag " + id);
            boolean computed = ResourceId.DEFAULT_NAMESPACE.equals(id.namespace())
                    && COMPUTED.containsKey(id.path());
            if (computed && o.has("values"))
                throw o.error("values", "tag " + id + " is computed and cannot list values");
            defs.put(id, new Def(id, o.string("name", path),
                    o.strings("aliases", List.of()),
                    computed ? List.of() : o.strings("values", List.of()),
                    computed, o));
        }

        Map<ResourceId, Set<Item>> members = new LinkedHashMap<>();
        for (ResourceId id : defs.keySet())
            resolve(id, defs, byId, items, members, new LinkedHashSet<>());

        Map<ResourceId, Tag> tags = new LinkedHashMap<>();
        for (Def d : defs.values())
            tags.put(d.id(), new Tag(d.id(), d.name(), d.aliases()));

        // Обратный индекс: предмет обязан уметь ответить на вопрос о теге сам,
        // иначе каждая подсказка обходила бы все теги подряд.
        Map<Item, Set<ResourceId>> owned = new HashMap<>();
        for (Map.Entry<ResourceId, Set<Item>> e : members.entrySet())
            for (Item i : e.getValue())
                owned.computeIfAbsent(i, k -> new LinkedHashSet<>()).add(e.getKey());
        for (Item i : items)
            i.setTags(Set.copyOf(owned.getOrDefault(i, Set.of())));

        return new TagRegistry(tags, members);
    }

    private static Set<Item> resolve(ResourceId id, Map<ResourceId, Def> defs,
            Map<ResourceId, Item> byId, Collection<Item> items,
            Map<ResourceId, Set<Item>> done, Set<ResourceId> open) {
        Set<Item> ready = done.get(id);
        if (ready != null)
            return ready;
        Def d = defs.get(id);
        if (!open.add(id))
            throw new JsonException(d.json().source(), id.path(),
                    "tag cycle: " + String.join(" -> ", open.stream().map(Object::toString).toList())
                            + " -> " + id);

        Set<Item> out = new LinkedHashSet<>();
        if (d.computed()) {
            Predicate<Item> rule = COMPUTED.get(id.path());
            for (Item i : items)
                if (rule.test(i))
                    out.add(i);
        } else {
            List<String> values = d.values();
            for (int n = 0; n < values.size(); n++) {
                String v = values.get(n);
                String where = "values[" + n + "]";
                if (v.startsWith("#")) {
                    ResourceId ref = parse(d, where, v.substring(1));
                    if (!defs.containsKey(ref))
                        throw d.json().error(where, "unknown tag " + ref);
                    out.addAll(resolve(ref, defs, byId, items, done, open));
                } else {
                    Item item = byId.get(parse(d, where, v));
                    if (item == null)
                        throw d.json().error(where, "unknown item " + v);
                    out.add(item);
                }
            }
        }
        open.remove(id);
        done.put(id, Set.copyOf(out));
        return done.get(id);
    }

    private static ResourceId parse(Def d, String where, String text) {
        try {
            return ResourceId.parse(text, d.id().namespace());
        } catch (IllegalArgumentException bad) {
            throw d.json().error(where, bad.getMessage());
        }
    }

    // ---------------------------------------------------------------- доступ

    public boolean has(ResourceId id) {
        return tags.containsKey(id);
    }

    public Tag get(ResourceId id) {
        return tags.get(id);
    }

    public List<Tag> all() {
        return List.copyOf(tags.values());
    }

    public Set<Item> members(ResourceId id) {
        return members.getOrDefault(id, Set.of());
    }

    /**
     * Поиск по началу пути id, имени или псевдонима. Регистр не важен, «ё»
     * читается как «е»: игрок набирает «брев», а тег называется «брёвна».
     */
    public List<Tag> find(String query) {
        String q = norm(query);
        if (q.isEmpty())
            return all();
        List<Tag> out = new ArrayList<>();
        for (Tag t : tags.values()) {
            if (norm(t.id().path()).startsWith(q) || norm(t.name()).startsWith(q)) {
                out.add(t);
                continue;
            }
            for (String a : t.aliases())
                if (norm(a).startsWith(q)) {
                    out.add(t);
                    break;
                }
        }
        return out;
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    /** Есть ли такой вычисляемый тег — для проверок и подсказок. */
    public static boolean isComputed(ResourceId id) {
        return ResourceId.DEFAULT_NAMESPACE.equals(id.namespace()) && COMPUTED.containsKey(id.path());
    }

    /** Все объявленные в коде вычисляемые теги. */
    public static Set<String> computedNames() {
        return new HashSet<>(COMPUTED.keySet());
    }
}
