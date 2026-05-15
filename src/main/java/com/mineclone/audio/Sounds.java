package com.mineclone.audio;

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

    public enum Material { GRASS, STONE, SAND, WOOD, GRAVEL, NONE }

    private static final String ROOT = "assets/sounds";

    private final Map<Material, List<String>> stepFiles = new EnumMap<>(Material.class);
    private final Map<Material, List<String>> digFiles  = new EnumMap<>(Material.class);

    public Sounds() {
        for (Material m : Material.values()) {
            if (m == Material.NONE) continue;
            stepFiles.put(m, listMatching(ROOT + "/step", m.name().toLowerCase(Locale.ROOT)));
            digFiles.put(m,  listMatching(ROOT + "/dig",  m.name().toLowerCase(Locale.ROOT)));
        }
    }

    private static List<String> listMatching(String dir, String prefix) {
        File d = new File(dir);
        File[] kids = d.listFiles();
        if (kids == null) return Collections.emptyList();
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
        if (b == null) return Material.NONE;
        return switch (b) {
            case GRASS, DIRT, LEAVES -> Material.GRASS;
            case STONE, COBBLE, BEDROCK -> Material.STONE;
            case SAND -> Material.SAND;
            case WOOD, PLANKS -> Material.WOOD;
            default -> Material.NONE;
        };
    }

    public List<String> step(Material m) { return stepFiles.getOrDefault(m, Collections.emptyList()); }
    public List<String> dig(Material m)  { return digFiles .getOrDefault(m, Collections.emptyList()); }

    /** Place sound: reuse the dig samples (matches Minecraft behaviour). */
    public List<String> place(BlockType b) { return dig(materialOf(b)); }
    public List<String> dig(BlockType b)   { return dig(materialOf(b)); }
    public List<String> step(BlockType b)  { return step(materialOf(b)); }

    /** Path to the generic break sound (when a block is destroyed). */
    public String breakRandom() {
        File f = new File(ROOT + "/random/break.ogg");
        return f.exists() ? f.getAbsolutePath() : null;
    }

    /** Classic UI button click (random/click.ogg). */
    public List<String> uiClick() {
        File f = new File(ROOT + "/random/click.ogg");
        return f.exists() ? List.of(f.getAbsolutePath()) : Collections.emptyList();
    }

    public List<String> emptyList() { return Arrays.asList(); }
}
