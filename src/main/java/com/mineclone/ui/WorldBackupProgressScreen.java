package com.mineclone.ui;

/** The render loop remains responsive while the save writer snapshots the world. */
public final class WorldBackupProgressScreen implements Screen {
    @Override public MenuAction draw(MenuTheme theme) {
        float width = Math.min(580f, theme.width() - 40f);
        float x = (theme.width() - width) * .5f, y = (theme.height() - 170f) * .5f;
        theme.dim(.5f);
        theme.panel(x, y, width, 170f);
        theme.textCentered("Резервная копия мира…", theme.width() * .5f, y + 50f, MenuTheme.HEADER, 1f);
        theme.smallCentered("Мир откроется после завершения копирования.", theme.width() * .5f,
                y + 85f, MenuTheme.TEXT_DIM, 1f);
        theme.spinner(theme.width() * .5f, y + 125f, 10f, MenuTheme.ACCENT);
        return MenuAction.NONE;
    }
    @Override public MenuAction escape() { return MenuAction.NONE; }
}
