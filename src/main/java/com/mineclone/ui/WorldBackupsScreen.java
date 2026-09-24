package com.mineclone.ui;

import com.mineclone.save.SaveManager;
import com.mineclone.save.WorldBackups;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Restores into a new world and opens that copy; the selected source stays untouched. */
public final class WorldBackupsScreen implements Screen {
    private final SaveManager save;
    private final String worldId;
    private final ScrollState scroll = new ScrollState();
    private List<WorldBackups.Backup> backups = List.of();
    private int selected = -1;
    private CompletableFuture<String> restoring;
    private String error = "";

    public WorldBackupsScreen(SaveManager save, String worldId) {
        this.save = save;
        this.worldId = worldId;
        try {
            backups = save.listBackups(worldId);
            if (!backups.isEmpty()) selected = 0;
        } catch (java.io.IOException e) {
            error = "Не удалось прочитать список резервных копий.";
        }
    }

    @Override public MenuAction draw(MenuTheme t) {
        if (restoring != null && restoring.isDone()) {
            try { return MenuAction.play(restoring.join()); }
            catch (java.util.concurrent.CompletionException e) {
                error = "Не удалось восстановить копию. Исходный мир не изменён.";
                restoring = null;
            }
        }
        float width = Math.min(760f, t.width() - 48f), height = Math.min(570f, t.height() - 48f);
        float x = (t.width() - width) / 2f, y = (t.height() - height) / 2f;
        t.dim(.45f);
        t.panel(x, y, width, height);
        float inside = x + 24f, available = width - 48f;
        float top = t.header("Резервные копии", inside, y + 14f, available) + 14f;
        t.smallText("Восстановление создаст отдельный мир и откроет его.", inside, top + 12f, MenuTheme.TEXT_DIM, 1f);
        float listY = top + 30f, listHeight = Math.max(60f, y + height - 118f - listY);
        float rowHeight = 58f, contentHeight = backups.size() * (rowHeight + 6f);
        t.beginClip(inside, listY, available, listHeight);
        boolean enabled = t.inputEnabled();
        t.setInputEnabled(enabled && restoring == null && t.input().mouseY >= listY
                && t.input().mouseY <= listY + listHeight);
        if (backups.isEmpty()) t.smallText("Резервных копий пока нет.", inside + 12f, listY + 36f, MenuTheme.TEXT_DIM, 1f);
        for (int i = 0; i < backups.size(); i++) {
            WorldBackups.Backup backup = backups.get(i);
            float rowY = listY + i * (rowHeight + 6f) - scroll.offset();
            if (rowY + rowHeight < listY || rowY > listY + listHeight) continue;
            if (t.row("backup.row." + i, inside, rowY, available - 16f, rowHeight, selected == i)) selected = i;
            t.text(backup.label(), inside + 12f, rowY + 25f, MenuTheme.TEXT, 1f);
            String reason = backup.migration() ? "Перед обновлением формата"
                    : backup.reason().equals("session") ? "Перед входом в мир" : "Ручная копия";
            t.smallText(reason + "  ·  " + MenuText.fileSize(backup.sizeBytes()), inside + 12f, rowY + 46f,
                    MenuTheme.TEXT_DIM, 1f);
        }
        t.setInputEnabled(enabled);
        t.endClip();
        t.scrollArea("backups.scroll", inside, listY, available, listHeight, scroll, contentHeight);
        float footer = y + height - 76f;
        if (!error.isEmpty()) t.smallText(MenuTheme.ellipsize(t.small(), error, available), inside, footer - 16f,
                MenuTheme.DANGER, 1f);
        if (restoring != null) t.smallText("Восстановление резервной копии…", inside, footer - 16f, MenuTheme.TEXT_DIM, 1f);
        float backWidth = 120f;
        if (t.button("backups.restore", inside, footer, available - backWidth - 12f, 48f,
                "Восстановить как копию", MenuTheme.Style.PRIMARY, selected >= 0 && restoring == null)) {
            error = "";
            restoring = save.restoreBackup(worldId, backups.get(selected));
        }
        if (t.button("backups.back", inside + available - backWidth, footer, backWidth, 48f, "Назад",
                MenuTheme.Style.NORMAL, restoring == null)) return MenuAction.back();
        return MenuAction.NONE;
    }

    @Override public MenuAction escape() { return restoring == null ? MenuAction.back() : MenuAction.NONE; }
}
