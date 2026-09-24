package com.mineclone.save;

/** Shared, side-effect-free opening policy for the game and dedicated server. */
public final class WorldOpenPolicy {
    private WorldOpenPolicy() {}

    public record Decision(boolean allowed, boolean createNew, LevelData data, String reason) {
        public int exitCode() { return allowed ? 0 : 2; }
    }

    public static Decision decide(LevelLoad result) {
        if (result instanceof LevelLoad.Absent)
            return new Decision(true, true, null, "");
        if (result instanceof LevelLoad.Loaded loaded)
            return new Decision(true, false, loaded.data(), "");
        if (result instanceof LevelLoad.TooNew newer)
            return new Decision(false, false, null, "level version " + newer.version()
                    + " is newer than supported version " + newer.supportedVersion());
        if (result instanceof LevelLoad.Unreadable unreadable)
            return new Decision(false, false, null, unreadable.reason());
        throw new IllegalArgumentException("Missing level read result");
    }

    public static String refusalMessage(String id, Decision decision) {
        return "world '" + id + "' cannot be read (" + decision.reason()
                + "); refusing to overwrite. Restore a backup from saves/" + id
                + "/backups or pick another world.";
    }
}
