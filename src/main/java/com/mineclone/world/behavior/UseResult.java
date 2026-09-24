package com.mineclone.world.behavior;

import java.util.Objects;

/**
 * What a right click on a block did (AC-06). {@link Kind#PASS} lets the click
 * go on to placing the held block against it.
 */
public record UseResult(Kind kind, Menu menu) {
    public enum Kind { PASS, CONSUMED, OPEN_MENU, SLEEP }

    /** The windows a block opens. */
    public enum Menu { CHEST, FURNACE, CRAFTING }

    public static final UseResult PASS = new UseResult(Kind.PASS, null);
    public static final UseResult CONSUMED = new UseResult(Kind.CONSUMED, null);
    public static final UseResult SLEEP = new UseResult(Kind.SLEEP, null);

    public UseResult {
        Objects.requireNonNull(kind, "kind");
        if ((kind == Kind.OPEN_MENU) != (menu != null))
            throw new IllegalArgumentException("a menu goes with OPEN_MENU and only with it");
    }

    public static UseResult open(Menu menu) {
        return new UseResult(Kind.OPEN_MENU, Objects.requireNonNull(menu, "menu"));
    }
}
