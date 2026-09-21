package com.mineclone.item;

import com.mineclone.data.ResourceId;
import com.mineclone.world.BlockType;

import java.util.Set;

/**
 * Предмет: одна строка из {@code assets/data/<ns>/items/*.json}.
 *
 * <p>Экземпляр единственный на id и живёт столько же, сколько реестр, поэтому
 * стопка держит на него ссылку, а сравнение идёт по тождеству. Изменяемого
 * состояния здесь нет вовсе: износ, имя и прочее, что различает две одинаковые
 * кирки, живёт в компонентах стопки.
 *
 * <p>Блок, инструмент, оружие и еда — необязательные части, а не подклассы:
 * жареная свинина в теории может быть и топливом, и едой, а иерархия классов
 * такой предмет описать не даёт.
 */
public final class Item {

    public final ResourceId id;
    public final String name;
    /** Категория креатива; всегда есть в {@link Categories}. */
    public final String category;
    /** Масса для лежащего предмета: от неё зависит, как он подпрыгивает. */
    public final float mass;
    public final int maxStack;
    /** Ноль — предмет не изнашивается. */
    public final int durability;
    /** Не показывать в креативе: заглушки и служебные предметы. */
    public final boolean hidden;
    /** Секунды горения в печи; ноль — не топливо. */
    public final float fuelSeconds;

    /** Блок, который предмет ставит, или {@code null}. */
    public final BlockType block;
    public final ToolSpec tool;
    public final AttackSpec attack;
    public final FoodSpec food;

    /**
     * Тайл плоской иконки. У блока, который рисуется кубиком, — {@code -1}:
     * форму такому предмету даёт не тайл, а сам блок.
     */
    public final int iconTile;

    /** Заглушка неизвестного id: предмет из сейва, которого больше нет. */
    public final boolean missing;

    private Set<ResourceId> tags = Set.of();

    Item(ResourceId id, String name, String category, float mass, int maxStack,
            int durability, boolean hidden, float fuelSeconds,
            BlockType block, ToolSpec tool, AttackSpec attack, FoodSpec food,
            int iconTile, boolean missing) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.mass = mass;
        this.maxStack = maxStack;
        this.durability = durability;
        this.hidden = hidden;
        this.fuelSeconds = fuelSeconds;
        this.block = block;
        this.tool = tool;
        this.attack = attack;
        this.food = food;
        this.iconTile = iconTile;
        this.missing = missing;
    }

    /** Заполняется один раз при загрузке, когда теги уже разрешены. */
    void setTags(Set<ResourceId> resolved) {
        this.tags = resolved;
    }

    public Set<ResourceId> tags() {
        return tags;
    }

    public boolean hasTag(ResourceId tag) {
        return tags.contains(tag);
    }

    public boolean isBlock() {
        return block != null;
    }

    public boolean hasDurability() {
        return durability > 0;
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
