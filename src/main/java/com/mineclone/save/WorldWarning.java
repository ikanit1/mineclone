package com.mineclone.save;

import java.nio.file.Path;

/** Delivered from generation workers to the main-thread toast/log consumer. */
public record WorldWarning(String worldId, int cx, int cz, Kind kind, String reason, Path evidence) {
    public enum Kind { QUARANTINED, TOO_NEW, QUARANTINE_FAILED }

    public String message() {
        String chunk = "Чанк " + cx + "," + cz;
        return switch (kind) {
            case QUARANTINED -> chunk + " повреждён: копия сохранена";
            case TOO_NEW -> chunk + " создан новой версией: сохранение отключено";
            case QUARANTINE_FAILED -> chunk + " повреждён: карантин не удался, сохранение отключено";
        };
    }
}
