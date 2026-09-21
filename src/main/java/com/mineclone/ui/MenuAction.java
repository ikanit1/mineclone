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
        /** Открыть мир {@link #worldId} для игры по сети — мы хозяин. */
        NET_HOST,
        /** Войти в чужую комнату по настройкам {@link #net}. */
        NET_JOIN,
        RESUME,
        SAVE,
        MAIN_MENU,
        QUIT,
        RESPAWN
    }

    public static final MenuAction NONE = new MenuAction(Kind.NONE, null, null, null, null);

    public final Kind kind;
    public final Screen screen;
    public final String worldId;
    public final WorldSettings world;
    /** Настройки сети для {@link Kind#NET_HOST} и {@link Kind#NET_JOIN}. */
    public final com.mineclone.net.NetSettings net;

    private MenuAction(Kind kind, Screen screen, String worldId, WorldSettings world,
            com.mineclone.net.NetSettings net) {
        this.kind = kind;
        this.screen = screen;
        this.worldId = worldId;
        this.world = world;
        this.net = net;
    }

    public static MenuAction of(Kind kind) {
        return kind == Kind.NONE ? NONE : new MenuAction(kind, null, null, null, null);
    }

    public static MenuAction push(Screen screen) {
        return new MenuAction(Kind.PUSH, screen, null, null, null);
    }

    public static MenuAction back() {
        return of(Kind.BACK);
    }

    public static MenuAction play(String worldId) {
        return new MenuAction(Kind.PLAY_WORLD, null, worldId, null, null);
    }

    public static MenuAction create(WorldSettings world) {
        return new MenuAction(Kind.CREATE_WORLD, null, null, world, null);
    }

    /** Открыть свой мир по сети. */
    public static MenuAction netHost(String worldId, com.mineclone.net.NetSettings net) {
        return new MenuAction(Kind.NET_HOST, null, worldId, null, net);
    }

    /** Войти в чужой мир. */
    public static MenuAction netJoin(com.mineclone.net.NetSettings net) {
        return new MenuAction(Kind.NET_JOIN, null, null, null, net);
    }

    public boolean is(Kind k) {
        return kind == k;
    }

    @Override
    public String toString() {
        return "MenuAction[" + kind + (worldId != null ? " " + worldId : "") + "]";
    }
}
