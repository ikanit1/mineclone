package com.mineclone.ui.container;

import com.mineclone.data.ResourceId;
import com.mineclone.item.Components;
import com.mineclone.item.ItemComponents;
import com.mineclone.world.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Что написано в подсказке о предмете — без единого GL-вызова.
 *
 * <p>Содержимое отдельно от отрисовки, потому что спорное здесь именно оно:
 * сколько строк показывать, что прятать за F3+H, в каком порядке идут теги.
 * Проверять это надо списком строк, а не глазами по скриншоту.
 */
public final class Tooltip {

    /** Строка подсказки и её цвет. */
    public record Line(String text, float[] rgb) {}

    private static final float[] NAME = { 1f, 1f, 1f };
    /** Своё имя — янтарным: сразу видно, что предмет переименован. */
    private static final float[] CUSTOM = { 1f, 0.82f, 0.36f };
    private static final float[] LORE = { 0.70f, 0.74f, 0.82f };
    private static final float[] SPECIAL = { 0.62f, 0.82f, 1f };
    private static final float[] FAINT = { 0.48f, 0.52f, 0.60f };

    /** Сколько тегов помещается, прежде чем список сворачивается в многоточие. */
    public static final int MAX_TAGS = 6;

    private Tooltip() {}

    public static List<Line> lines(ItemStack s, boolean advanced) {
        List<Line> out = new ArrayList<>();
        if (s == null)
            return out;
        ItemComponents c = s.components();
        boolean renamed = c.get(Components.CUSTOM_NAME) != null;
        out.add(new Line(s.displayName(), renamed ? CUSTOM : NAME));

        List<String> lore = c.get(Components.LORE);
        if (lore != null)
            for (String l : lore)
                out.add(new Line(l, LORE));

        if (Boolean.TRUE.equals(c.get(Components.UNBREAKABLE)))
            out.add(new Line("Неразрушимый", SPECIAL));
        if (c.get(Components.BLOCK_STATE) != null)
            out.add(new Line("+ данные блока", SPECIAL));

        if (!advanced)
            return out;

        // Расширенные строки — для отладки и для тех, кто их просил: обычному
        // игроку id предмета и число прочности не говорят ничего.
        if (s.hasDurability())
            out.add(new Line("Прочность: " + (s.item.durability - s.damage())
                    + " / " + s.item.durability, FAINT));
        out.add(new Line(s.item.id.toString(), FAINT));

        List<ResourceId> tags = new ArrayList<>(s.item.tags());
        tags.sort(null);
        if (!tags.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            int shown = Math.min(MAX_TAGS, tags.size());
            for (int i = 0; i < shown; i++) {
                if (i > 0)
                    sb.append(", ");
                sb.append('#').append(tags.get(i).path());
            }
            if (tags.size() > shown)
                sb.append(", …");
            out.add(new Line(sb.toString(), FAINT));
        }

        int extra = countExtra(c);
        if (extra > 0)
            out.add(new Line("+" + extra + " компонентов", FAINT));
        return out;
    }

    /** Компоненты, которых не видно в строках выше. */
    private static int countExtra(ItemComponents c) {
        int n = c.size();
        if (c.get(Components.CUSTOM_NAME) != null)
            n--;
        if (c.get(Components.LORE) != null)
            n--;
        if (c.get(Components.UNBREAKABLE) != null)
            n--;
        if (c.get(Components.BLOCK_STATE) != null)
            n--;
        if (c.get(Components.DAMAGE) != null)
            n--;
        return Math.max(0, n);
    }
}
