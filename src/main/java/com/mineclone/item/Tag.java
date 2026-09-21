package com.mineclone.item;

import com.mineclone.data.ResourceId;

import java.util.List;

/**
 * Тег: именованная группа предметов.
 *
 * <p>Имя и псевдонимы нужны поиску: в книге рецептов и в подсказках игрок
 * ищет «дерево», а не {@code mineclone:wood}, и то же слово по-английски.
 */
public record Tag(ResourceId id, String name, List<String> aliases) {

    public Tag {
        aliases = List.copyOf(aliases);
    }
}
