package com.mineclone.ui;

/**
 * Что экран просит сделать после кадра.
 *
 * <p>Переходы между экранами ({@link Kind#PUSH}, {@link Kind#BACK}) разбирает
 * сам {@link ScreenStack}; всё остальное уходит в {@code Game} — одно
 * действие на кадр.
 */
public final class MenuAction {

    public enum Kind {
        NONE,
        /** Открыть экран поверх текущего. */
        PUSH,
        /** Шаг назад; с корня стека решает игра. */
        BACK,
        /** Играть в мир {@link #worldId}. */
        PLAY_WORLD,
        /** Создать мир по {@link #world} и войти в него. */
        CREATE_WORLD,
        /** На экране настроек что-то сдвинули — применить сразу. */
        SETTINGS_CHANGED,
        /** Экран настроек закрыт — записать options.dat. */
        SETTINGS_CLOSED,
        RESUME,
        SAVE,
        MAIN_MENU,
        QUIT,
        RESPAWN
    }

    public static final MenuAction NONE = new MenuAction(Kind.NONE, null, null, null);

    public final Kind kind;
    public final Screen screen;
    public final String worldId;
    public final WorldSettings world;

    private MenuAction(Kind kind, Screen screen, String worldId, WorldSettings world) {
        this.kind = kind;
        this.screen = screen;
        this.worldId = worldId;
        this.world = world;
    }

    public static MenuAction of(Kind kind) {
        return kind == Kind.NONE ? NONE : new MenuAction(kind, null, null, null);
    }

    public static MenuAction push(Screen screen) {
        return new MenuAction(Kind.PUSH, screen, null, null);
    }

    public static MenuAction back() {
        return of(Kind.BACK);
    }

    public static MenuAction play(String worldId) {
        return new MenuAction(Kind.PLAY_WORLD, null, worldId, null);
    }

    public static MenuAction create(WorldSettings world) {
        return new MenuAction(Kind.CREATE_WORLD, null, null, world);
    }

    public boolean is(Kind k) {
        return kind == k;
    }

    @Override
    public String toString() {
        return "MenuAction[" + kind + (worldId != null ? " " + worldId : "") + "]";
    }
}
