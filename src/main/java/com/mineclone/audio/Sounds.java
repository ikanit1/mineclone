package com.mineclone.audio;

import com.mineclone.core.AppPaths;
import com.mineclone.world.BlockType;
import com.mineclone.world.entity.MobType;

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
        GRASS, STONE, SAND, WOOD, GRAVEL, GLASS, SNOW, CLOTH, NONE,
        /** Лёд: звонкий камень выше тоном. */
        ICE,
        /** Раскисшая земля под дождём — хлюпает. */
        MUD,
        /** Трава под дождём. */
        WET_GRASS,
        /** Сугроб: нога проваливается с глухим хрустом. */
        DEEP_SNOW
    }

    /** Толщина снежного покрова, с которой шаг проваливается. */
    public static final int DEEP_SNOW_LEVEL = 3;

    /**
     * Чем звучит шаг — не только по блоку под ногой, но и по тому, что
     * лежит на нём и какая погода. Сухая земля хрустит, как гравий (так в MC),
     * мокрая хлюпает; тонкий снег скрипит, сугроб проваливается.
     *
     * @param under     блок, на котором стоим
     * @param feet      блок в клетке ног (снежный покров не твёрдый и лежит там)
     * @param snowLevel толщина покрова 0..7, если он есть
     * @param wet       идёт дождь и над головой открытое небо
     */
    public static Material stepMaterial(BlockType under, BlockType feet, int snowLevel, boolean wet) {
        if (feet == BlockType.SNOW_LAYER)
            return snowLevel >= DEEP_SNOW_LEVEL ? Material.DEEP_SNOW : Material.SNOW;
        if (under == null)
            return Material.NONE;
        return switch (under) {
            case GRASS -> wet ? Material.WET_GRASS : Material.GRASS;
            case DIRT -> wet ? Material.MUD : Material.GRAVEL;
            case ICE -> Material.ICE;
            default -> materialOfStatic(under);
        };
    }

    /** Высота тона шага по материалу: лёд звенит, грязь и сугроб глуше. */
    public static float stepPitch(Material m) {
        return switch (m) {
            case ICE -> 1.35f;
            case MUD -> 0.78f;
            case DEEP_SNOW -> 0.92f;
            case WET_GRASS -> 0.9f;
            default -> 1f;
        };
    }

    /** Громкость шага по материалу. */
    public static float stepVolume(Material m) {
        return switch (m) {
            case DEEP_SNOW -> 0.5f;
            case ICE -> 0.28f;
            case MUD -> 0.42f;
            default -> 0.35f;
        };
    }

    private static final String ROOT = "assets/sounds";

    private final Map<Material, List<String>> stepFiles = new EnumMap<>(Material.class);
    private final Map<Material, List<String>> digFiles = new EnumMap<>(Material.class);

    /** Stone samples reused for glass step/place (classic MC soundTypeGlass). */
    private List<String> stoneStep = Collections.emptyList();
    private List<String> stoneDig = Collections.emptyList();

    public Sounds() {
        for (Material m : Material.values()) {
            if (m == Material.NONE || m == Material.GLASS || m == Material.ICE
                    || m == Material.MUD || m == Material.DEEP_SNOW)
                continue;
            stepFiles.put(m, listMatching(ROOT + "/step", m.name().toLowerCase(Locale.ROOT)));
            digFiles.put(m, listMatching(ROOT + "/dig", m.name().toLowerCase(Locale.ROOT)));
        }
        // Материалы без своих файлов собираются из соседних: лёд шагает
        // камнем (выше тоном) и бьётся стеклом, грязь хлюпает мокрой травой,
        // сугроб — отдельной библиотекой рыхлого снега.
        stepFiles.put(Material.ICE, stepFiles.getOrDefault(Material.STONE, Collections.emptyList()));
        digFiles.put(Material.ICE, listMatching(ROOT + "/random", "glass"));
        stepFiles.put(Material.MUD, stepFiles.getOrDefault(Material.WET_GRASS, Collections.emptyList()));
        digFiles.put(Material.MUD, digFiles.getOrDefault(Material.WET_GRASS, Collections.emptyList()));
        List<String> deep = listMatching(ROOT + "/block/powder_snow", "step");
        stepFiles.put(Material.DEEP_SNOW, deep.isEmpty()
                ? stepFiles.getOrDefault(Material.SNOW, Collections.emptyList()) : deep);
        digFiles.put(Material.DEEP_SNOW, digFiles.getOrDefault(Material.SNOW, Collections.emptyList()));
        // Glass behaves like stone for stepping and placing; only the
        // destruction is the shatter (random/glass1-3). Walking on glass or
        // placing a pane must NOT shatter — that was the bug.
        stoneStep = stepFiles.getOrDefault(Material.STONE, Collections.emptyList());
        stoneDig = digFiles.getOrDefault(Material.STONE, Collections.emptyList());
        stepFiles.put(Material.GLASS, stoneStep);
        digFiles.put(Material.GLASS, listMatching(ROOT + "/random", "glass"));
    }

    /**
     * Всё, что игра может заиграть в обычной игре — для прогрева декодера.
     *
     * <p>Список, а не «все 2728 файлов из assets/sounds»: там лежат целые
     * библиотеки, которых эта игра не касается, и разжимать их в память
     * незачем. Длинных фонов здесь тоже нет намеренно — они дороги по памяти,
     * а рывка от них больше не будет: декодер всё равно работает в фоне.
     */
    public List<String> warmupPaths() {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (List<String> l : stepFiles.values()) out.addAll(l);
        for (List<String> l : digFiles.values()) out.addAll(l);
        for (String dir : new String[] {
                ROOT + "/random", ROOT + "/damage", ROOT + "/liquid", ROOT + "/ui/loom",
                ROOT + "/entity/player/attack", ROOT + "/ambient/weather",
                ROOT + "/block/azalea_leaves", ROOT + "/block/powder_snow" })
            out.addAll(listMatching(dir, ""));
        for (MobType t : MobType.values())
            out.addAll(listMatching(ROOT + "/mob/" + t.soundDir, ""));
        return new ArrayList<>(out);
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
        return materialOfStatic(b);
    }

    private static Material materialOfStatic(BlockType b) {
        if (b == null)
            return Material.NONE;
        return switch (b) {
            case GRASS, DIRT, LEAVES, PODZOL, DRY_GRASS -> Material.GRASS;
            case STONE, COBBLE, BEDROCK, COAL_ORE, IRON_ORE, GOLD_ORE, DIAMOND_ORE,
                    TERRACOTTA, LIMESTONE, BASALT, MOSSY_COBBLE, OBSIDIAN, CHAIN -> Material.STONE;
            case SAND, RED_SAND, ASH -> Material.SAND;
            case PEAT, MUD -> Material.MUD;
            case GRAVEL -> Material.GRAVEL;
            case WOOD, PLANKS, TORCH, FIRE, CRAFTING_TABLE -> Material.WOOD;
            case GLASS -> Material.GLASS;
            case ICE, THIN_ICE -> Material.ICE;
            case STAIRS, DOOR_CLOSED, DOOR_OPEN, CHEST -> Material.WOOD;
            case FURNACE -> Material.STONE;
            case SNOWY_GRASS, SNOW_LAYER -> Material.SNOW;
            case CACTUS, BEDROLL -> Material.CLOTH;
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
        Material m = materialOf(b);
        if (m == Material.GLASS || m == Material.ICE)
            return stoneDig;
        return dig(m);
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

    /** Тихий щелчок наведения в меню (ui/loom/select_pattern). */
    public List<String> uiHover() {
        return listMatching(ROOT + "/ui/loom", "select_pattern");
    }

    public List<String> emptyList() {
        return Arrays.asList();
    }

    public List<String> waterSplash() {
        return listMatching(ROOT + "/liquid", "splash");
    }

    public List<String> lavaAmbient() {
        return List.of(AppPaths.file(ROOT + "/liquid/lava.ogg").getAbsolutePath());
    }

    public List<String> lavaPop() {
        return List.of(AppPaths.file(ROOT + "/liquid/lavapop.ogg").getAbsolutePath());
    }

    public List<String> waterSwim() {
        return listMatching(ROOT + "/liquid", "swim");
    }

    public List<String> waterFlow() {
        File f = AppPaths.file(ROOT + "/liquid/water.ogg");
        return f.exists() ? List.of(f.getAbsolutePath()) : Collections.emptyList();
    }

    public List<String> fallBig() {
        File f = AppPaths.file(ROOT + "/damage/fallbig.ogg");
        return f.exists() ? List.of(f.getAbsolutePath()) : Collections.emptyList();
    }

    public List<String> fallSmall() {
        File f = AppPaths.file(ROOT + "/damage/fallsmall.ogg");
        return f.exists() ? List.of(f.getAbsolutePath()) : Collections.emptyList();
    }

    /**
     * Final block-break sound — distinct from the periodic dig sound.
     * Uses block-specific break files where available, falls back to dig().
     */
    public List<String> breakBlock(BlockType b) {
        if (b == BlockType.LEAVES) {
            List<String> s = listMatching(ROOT + "/block/azalea_leaves", "break");
            if (!s.isEmpty()) return s;
        }
        return dig(b); // glass already returns glass-shatter via dig(); everything else uses material dig
    }

    public List<String> hurt() {
        return listMatching(ROOT + "/damage", "hit");
    }

    /**
     * Звук удара игрока: {@code crit} — в падении, {@code strong} — обычный
     * полновесный, {@code sweep} — взмах инструментом по воздуху.
     */
    public List<String> playerAttack(String kind) {
        return listMatching(ROOT + "/entity/player/attack", kind);
    }

    /** Предмет подобран с земли. */
    public List<String> pickup() {
        File f = AppPaths.file(ROOT + "/random/pop.ogg");
        return f.exists() ? List.of(f.getAbsolutePath()) : Collections.emptyList();
    }

    public List<String> playerDeath() {
        File f = AppPaths.file(ROOT + "/random/classic_hurt.ogg");
        return f.exists() ? List.of(f.getAbsolutePath()) : Collections.emptyList();
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

    // ---- Мобы ----
    //
    // Файлы лежат в MC-нейминге: assets/sounds/mob/<dir>/{say,hurt,death,step}*.ogg.
    // Покрытие неполное (у овцы нет hurt/death, у коровы нет death, у свиньи нет
    // hurt), поэтому hurt → say, death → hurt → say. Иначе часть мобов молчит.

    /** Периодический «холостой» голос моба (MC-шный say*.ogg, у зверей — idle/panting). */
    public List<String> mobSay(MobType t) {
        return listMatching(ROOT + "/mob/" + t.soundDir, t.sayPrefix);
    }

    /**
     * Голос разозлённого моба: рычание волка. У кого отдельного нет —
     * обычный голос.
     */
    public List<String> mobAngry(MobType t) {
        List<String> s = listMatching(ROOT + "/mob/" + t.soundDir, "growl");
        return s.isEmpty() ? mobSay(t) : s;
    }

    /** Ночной вой волка; у прочих — пусто. */
    public List<String> mobHowl(MobType t) {
        return listMatching(ROOT + "/mob/" + t.soundDir, "howl");
    }

    /** Взмах крыльев при взлёте птицы. */
    public List<String> mobFly(MobType t) {
        return listMatching(ROOT + "/mob/" + t.soundDir, "fly");
    }

    /** Звук боли; при отсутствии файлов — голос. */
    public List<String> mobHurt(MobType t) {
        List<String> s = listMatching(ROOT + "/mob/" + t.soundDir, "hurt");
        return s.isEmpty() ? mobSay(t) : s;
    }

    // ---- фоновая атмосфера -------------------------------------------------

    /** Далёкие звуки в тёмных пещерах. */
    public List<String> ambientCave() {
        return listMatching(ROOT + "/ambient/cave", "cave");
    }

    /** Шум дождя. */
    public List<String> ambientRain() {
        return listMatching(ROOT + "/ambient/weather", "rain");
    }

    /** Раскаты грома. */
    public List<String> ambientThunder() {
        return listMatching(ROOT + "/ambient/weather", "thunder");
    }

    /**
     * Порыв ветра. Отдельных погодных сэмплов ветра в библиотеке нет;
     * протяжный вой из долины душ без своего контекста звучит ровно как
     * метель над пустой равниной.
     */
    public List<String> ambientWind() {
        return listMatching(ROOT + "/ambient/nether/soulsand_valley", "wind");
    }

    /** Гул под водой. */
    public List<String> ambientUnderwater() {
        return listMatching(ROOT + "/ambient/underwater", "underwater");
    }

    /** Пузыри, киты и прочая мелочь под водой. */
    public List<String> ambientUnderwaterExtra() {
        return listMatching(ROOT + "/ambient/underwater/additions", "");
    }

    /** Всплеск при погружении. */
    public List<String> waterEnter() {
        return listMatching(ROOT + "/ambient/underwater", "enter");
    }

    /** Всплеск при выныривании. */
    public List<String> waterExit() {
        return listMatching(ROOT + "/ambient/underwater", "exit");
    }

    /** Шаги моба (step*.ogg) — тихие, играются по пройденному пути. */
    public List<String> mobStep(MobType t) {
        return listMatching(ROOT + "/mob/" + t.soundDir, t.stepPrefix);
    }

    /** Звук смерти; при отсутствии файлов — боль, затем голос. */
    public List<String> mobDeath(MobType t) {
        List<String> s = listMatching(ROOT + "/mob/" + t.soundDir, "death");
        return s.isEmpty() ? mobHurt(t) : s;
    }
}
