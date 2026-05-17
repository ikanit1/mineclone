package com.mineclone.audio;

import com.mineclone.core.AppPaths;
import com.mineclone.world.BlockType;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Maps BlockType -> material -> list of file paths under assets/sounds. */
public final class Sounds {

    public enum Material {
        GRASS, STONE, SAND, WOOD, GRAVEL, GLASS, NONE
    }

    private static final String ROOT = "assets/sounds";

    private final Map<Material, List<String>> stepFiles = new EnumMap<>(Material.class);
    private final Map<Material, List<String>> digFiles = new EnumMap<>(Material.class);

    /** Stone samples reused for glass step/place (classic MC soundTypeGlass). */
    private List<String> stoneStep = Collections.emptyList();
    private List<String> stoneDig = Collections.emptyList();

    public Sounds() {
        for (Material m : Material.values()) {
            if (m == Material.NONE || m == Material.GLASS)
                continue;
            stepFiles.put(m, listMatching(ROOT + "/step", m.name().toLowerCase(Locale.ROOT)));
            digFiles.put(m, listMatching(ROOT + "/dig", m.name().toLowerCase(Locale.ROOT)));
        }
        // Glass behaves like stone for stepping and placing; only the
        // destruction is the shatter (random/glass1-3). Walking on glass or
        // placing a pane must NOT shatter — that was the bug.
        stoneStep = stepFiles.getOrDefault(Material.STONE, Collections.emptyList());
        stoneDig = digFiles.getOrDefault(Material.STONE, Collections.emptyList());
        stepFiles.put(Material.GLASS, stoneStep);
        digFiles.put(Material.GLASS, listMatching(ROOT + "/random", "glass"));
    }

    static List<String> listMatching(String dir, String prefix) {
        File d = AppPaths.file(dir);
        File[] kids = d.listFiles();
        if (kids == null)
            return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (File f : kids) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.startsWith(prefix) && n.endsWith(".ogg")) {
                out.add(f.getAbsolutePath());
            }
        }
        // sort for deterministic ordering, not that it matters for random pick
        out.sort(null);
        return out;
    }

    public Material materialOf(BlockType b) {
        if (b == null)
            return Material.NONE;
        return switch (b) {
            case GRASS, DIRT, LEAVES -> Material.GRASS;
            case STONE, COBBLE, BEDROCK -> Material.STONE;
            case SAND -> Material.SAND;
            case WOOD, PLANKS, TORCH -> Material.WOOD;
            case GLASS -> Material.GLASS;
            case STAIRS, DOOR_CLOSED, DOOR_OPEN -> Material.WOOD;
            default -> Material.NONE;
        };
    }

    public List<String> step(Material m) {
        return stepFiles.getOrDefault(m, Collections.emptyList());
    }

    public List<String> dig(Material m) {
        return digFiles.getOrDefault(m, Collections.emptyList());
    }

    /**
     * Place sound: reuse the dig samples (matches Minecraft behaviour), except
     * glass — placing a pane is a soft stone-like clink, never the shatter
     * that {@link #dig(BlockType)} returns for destroying it.
     */
    public List<String> place(BlockType b) {
        if (materialOf(b) == Material.GLASS)
            return stoneDig;
        return dig(materialOf(b));
    }

    public List<String> dig(BlockType b) {
        return dig(materialOf(b));
    }

    public List<String> step(BlockType b) {
        return step(materialOf(b));
    }

    /** Path to the generic break sound (when a block is destroyed). */
    public String breakRandom() {
        File f = AppPaths.file(ROOT + "/random/break.ogg");
        return f.exists() ? f.getAbsolutePath() : null;
    }

    /** Classic UI button click (random/click.ogg). */
    public List<String> uiClick() {
        File f = AppPaths.file(ROOT + "/random/click.ogg");
        return f.exists() ? List.of(f.getAbsolutePath()) : Collections.emptyList();
    }

    public List<String> emptyList() {
        return Arrays.asList();
    }

    public List<String> waterSplash() {
        return listMatching(ROOT + "/liquid", "splash");
    }

    public List<String> waterSwim() {
        return listMatching(ROOT + "/liquid", "swim");
    }

    public List<String> doorToggle() {
        List<String> out = new ArrayList<>();
        File open = AppPaths.file(ROOT + "/random/door_open.ogg");
        File close = AppPaths.file(ROOT + "/random/door_close.ogg");
        if (open.exists())
            out.add(open.getAbsolutePath());
        if (close.exists())
            out.add(close.getAbsolutePath());
        return out;
    }
}
