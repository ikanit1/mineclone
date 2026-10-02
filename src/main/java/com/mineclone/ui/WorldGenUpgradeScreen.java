package com.mineclone.ui;

import com.mineclone.save.LevelLoad;
import com.mineclone.save.SaveManager;
import com.mineclone.world.gen.WorldGenSettings;
import com.mineclone.world.gen.WorldGenVersion;
import java.util.concurrent.CompletableFuture;

/** Explicit generation choice before opening an old world, including Continue and LAN hosting. */
public final class WorldGenUpgradeScreen implements Screen {
    private final SaveManager save;
    private final MenuAction opening;
    private CompletableFuture<WorldGenSettings> upgrading;
    private String error = "";

    private WorldGenUpgradeScreen(SaveManager save, MenuAction opening) {
        this.save = save;
        this.opening = opening.withWorldgenChecked();
    }

    /** Common entry gate: bad/missing worlds retain the normal opening policy. */
    public static MenuAction offer(SaveManager save, MenuAction opening) {
        if (opening.worldgenChecked || !(opening.is(MenuAction.Kind.PLAY_WORLD) || opening.is(MenuAction.Kind.NET_HOST)))
            return opening;
        if (save.readLevel(opening.worldId) instanceof LevelLoad.Loaded loaded) {
            var settings = WorldGenSettings.decode(loaded.data().extraSections.get(WorldGenSettings.SAVE_SECTION));
            if (settings.version().id() < WorldGenVersion.LATEST.id())
                return MenuAction.push(new WorldGenUpgradeScreen(save, opening));
        }
        return opening;
    }

    public MenuAction keepCurrent() { return upgrading == null ? opening : MenuAction.NONE; }

    public void requestUpgrade() {
        if (upgrading == null) {
            error = "";
            upgrading = save.upgradeWorldGen(opening.worldId);
        }
    }

    /** Poll without blocking the render/input thread. */
    public MenuAction poll() {
        if (upgrading != null && upgrading.isDone()) {
            try { upgrading.join(); return opening; }
            catch (java.util.concurrent.CompletionException failure) {
                error = "Обновление не удалось. Генерация мира осталась прежней.";
                upgrading = null;
                System.err.println("worldgen upgrade failed: " + failure.getCause());
            }
        }
        return MenuAction.NONE;
    }

    @Override public MenuAction draw(MenuTheme t) {
        MenuAction completed = poll();
        if (completed != MenuAction.NONE) return completed;
        float width = Math.min(720f, t.width() - 48f), height = 340f;
        float x = (t.width() - width) / 2f, y = (t.height() - height) / 2f;
        t.flush(); t.dim(.45f); t.panel(x, y, width, height);
        t.textCentered("Обновить генерацию мира?", t.width() / 2f, y + 46f, MenuTheme.HEADER, 1f);
        t.smallCentered("В новых землях появится медная руда.", t.width() / 2f, y + 86f, MenuTheme.TEXT, 1f);
        t.smallCentered("Записанные в журнале чанки сохранят прежнюю генерацию.", t.width() / 2f, y + 114f, MenuTheme.TEXT_DIM, 1f);
        t.smallCentered("В старых мирах защищаем также землю вокруг сохранённых", t.width() / 2f, y + 140f, MenuTheme.TEXT_DIM, 1f);
        t.smallCentered("чанков, точек появления и позиций игроков.", t.width() / 2f, y + 162f, MenuTheme.TEXT_DIM, 1f);
        t.smallCentered("Перед обновлением будет создана резервная копия.", t.width() / 2f, y + 194f, MenuTheme.TEXT_FAINT, 1f);
        if (upgrading != null)
            t.smallCentered("Создание копии и обновление…", t.width() / 2f, y + 222f, MenuTheme.TEXT, 1f);
        if (!error.isEmpty())
            t.smallCentered(MenuTheme.ellipsize(t.small(), error, width - 40f), t.width() / 2f, y + 222f, MenuTheme.DANGER, 1f);
        float gap = 12f, bw = (width - 48f - gap) / 2f, by = y + 248f;
        if (t.button("worldgen.upgrade", x + 24f, by, bw, 44f, "Обновить", MenuTheme.Style.PRIMARY, upgrading == null))
            requestUpgrade();
        if (t.button("worldgen.keep", x + 24f + bw + gap, by, bw, 44f, "Без обновления", MenuTheme.Style.NORMAL, upgrading == null))
            return keepCurrent();
        if (t.button("worldgen.back", x + 24f, y + 300f, width - 48f, 28f, "Назад", MenuTheme.Style.QUIET, upgrading == null))
            return MenuAction.back();
        return MenuAction.NONE;
    }

    @Override public MenuAction escape() { return upgrading == null ? MenuAction.back() : MenuAction.NONE; }
}
