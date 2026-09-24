package com.mineclone.ui;

/** Read failures stay in the menu, with no world construction or disk writes. */
public final class WorldLoadErrorScreen implements Screen {
    private final boolean tooNew;
    private final String title;

    public WorldLoadErrorScreen(boolean tooNew) {
        this.tooNew = tooNew;
        this.title = tooNew ? "Нужна более новая версия игры" : "Не удалось прочитать мир";
    }
    public WorldLoadErrorScreen(String title) { this.title = title; this.tooNew = false; }

    @Override public MenuAction draw(MenuTheme theme) {
        float width = Math.min(620f, theme.width() - 40f);
        float x = (theme.width() - width) * .5f;
        float y = (theme.height() - 220f) * .5f;
        theme.dim(.55f);
        theme.panel(x, y, width, 220f);
        theme.textCentered(title,
                theme.width() * .5f, y + 48f, MenuTheme.HEADER, 1f);
        theme.smallCentered("Сохранение не изменено.", theme.width() * .5f,
                y + 88f, MenuTheme.TEXT, 1f);
        theme.smallCentered(tooNew ? "Откройте мир в совместимой версии игры."
                        : "Можно восстановить мир из резервной копии.",
                theme.width() * .5f, y + 116f, MenuTheme.TEXT_DIM, 1f);
        return theme.button("world.read.error.back", x + 24f, y + 150f, width - 48f, 46f,
                "Назад", MenuTheme.Style.NORMAL, true) ? MenuAction.back() : MenuAction.NONE;
    }
}
