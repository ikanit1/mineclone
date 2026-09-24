package com.mineclone.item.recipe;

import com.mineclone.item.Item;
import com.mineclone.data.ResourceId;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** A resolved exact item or tag, retaining a stable representative for recipe previews. */
public final class Ingredient {
    private final String key;
    private final List<Item> alternatives;
    private final Set<Item> members;

    private Ingredient(String key, Collection<Item> items) {
        this.key = key;
        alternatives = items.stream().sorted(Comparator.comparing(item -> item.id.toString())).toList();
        if (alternatives.isEmpty()) throw new IllegalArgumentException("Empty ingredient " + key);
        members = Set.copyOf(alternatives);
    }
    public static Ingredient item(Item item) { return new Ingredient(item.id.toString(), List.of(item)); }
    public static Ingredient tag(ResourceId id, Collection<Item> items) { return new Ingredient("#" + id, items); }
    public String key() { return key; }
    public Item example() { return alternatives.get(0); }
    public List<Item> alternatives() { return alternatives; }
    public boolean matches(Item item) { return item != null && members.contains(item); }
}
