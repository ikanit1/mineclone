import com.mineclone.item.Components;
import com.mineclone.net.NetProto;
import com.mineclone.net.PlayerData;
import com.mineclone.save.LevelData;
import com.mineclone.save.SaveFormat;
import com.mineclone.save.SaveManager;
import com.mineclone.world.GameMode;
import com.mineclone.world.ItemStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/**
 * Writes the {@code beta-guest} save fixture with the compiled v1.0.1-alpha tag:
 * a level v10 and the guest checkpoint format that build introduced
 * ({@code players/<uuid>.dat} version 1, the protocol-v7 checkpoint bytes).
 * Run with that tag's classes on the classpath and its worktree as the cwd;
 * it refuses any other writer and an occupied output directory.
 * See src/test/resources/fixtures/saves/README.md for the exact command.
 */
public class MakeGuestFixture {
    private static final String TAG = "v1.0.1-alpha";
    private static final String COMMIT = "967011088d641cdb83e1c895537c515ff3f52cb6";
    private static final String WORLD = "beta-guest";
    static final String GUEST = "3f2a7c1e-5b6d-4e8f-9a0b-1c2d3e4f5a6b";
    private static final long LAST_PLAYED = 1_727_000_000_000L;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Output saves directory required");
        if (SaveFormat.LEVEL_VERSION != 10 || SaveFormat.CHUNK_VERSION != 6 || NetProto.VERSION != 7)
            throw new IllegalStateException("Compile/run this generator against " + TAG + ", never the current writer");
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        if (Files.exists(root)) try (var files = Files.list(root)) {
            if (files.findAny().isPresent()) throw new IllegalStateException("Refusing to replace fixtures: " + root);
        }
        Files.createDirectories(root);
        SaveManager save = new SaveManager(root.toFile());

        ItemStack[] hostInventory = LevelData.emptyInventory();
        hostInventory[0] = ItemStack.of("log", 12);
        LevelData level = new LevelData(WORLD, 91001, 2.5, 76.0, -4.5, 8.5, 81.0, 8.5,
                0.75f, -0.125f, 21.5f, 2, hostInventory, GameMode.SURVIVAL, LAST_PLAYED,
                18f, 16.5f, new ItemStack[0],
                Map.of("mineclone:survival_progress", new byte[] { 2, 3 }, "fixture:opaque", new byte[] { 5, 1 }));
        save.saveLevel(WORLD, level);

        ItemStack[] inventory = new ItemStack[36];
        inventory[0] = ItemStack.of("diamond", 5);
        inventory[3] = ItemStack.of("iron_pickaxe", 1);
        inventory[3].set(Components.DAMAGE, 21);
        inventory[3].set(Components.CUSTOM_NAME, "Guest pick");
        inventory[35] = ItemStack.of("cobblestone", 40);
        ItemStack[] pending = { ItemStack.of("stick", 3), ItemStack.of("coal", 7) };
        PlayerData guest = new PlayerData(inventory, pending, -12.5f, 70.25f, 30.75f,
                1.5f, 0.25f, 13.5f, 7.25f, 5, new byte[] { 2, 6 });
        save.saveGuest(WORLD, GUEST, guest);
        save.flushAndAwait();
        writeManifest(root.resolve(WORLD), level, guest);
        System.out.println("GUEST_FIXTURE_WRITTEN tag=" + TAG + " commit=" + COMMIT + " root=" + root);
    }

    private static Map<String, Object> stack(ItemStack stack) {
        Map<String, Object> value = new TreeMap<>();
        value.put("id", stack.item.id.toString());
        value.put("count", stack.count);
        value.put("damage", stack.damage());
        String name = stack.get(Components.CUSTOM_NAME);
        if (name != null) value.put("customName", name);
        return value;
    }

    private static List<Object> stacks(ItemStack[] slots) {
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < slots.length; i++) if (slots[i] != null) {
            Map<String, Object> value = new TreeMap<>(stack(slots[i]));
            value.put("slot", i);
            out.add(value);
        }
        return out;
    }

    private static void writeManifest(Path dir, LevelData level, PlayerData guest) throws Exception {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("writerTag", TAG); manifest.put("writerCommit", COMMIT); manifest.put("kind", "tagged-writer");
        manifest.put("levelVersion", 10); manifest.put("chunkVersion", 6); manifest.put("guestVersion", 1);
        manifest.put("name", level.name); manifest.put("seed", level.seed);
        manifest.put("health", level.health); manifest.put("hunger", level.hunger);
        manifest.put("mode", level.gameMode.name()); manifest.put("lastPlayed", level.lastPlayed);
        manifest.put("position", List.of(level.px, level.py, level.pz));
        manifest.put("spawn", List.of(level.spawnX, level.spawnY, level.spawnZ));
        manifest.put("yaw", level.yaw); manifest.put("pitch", level.pitch);
        manifest.put("gameTime", level.timeOfDay); manifest.put("selectedSlot", level.selectedSlot);
        manifest.put("inventory", stacks(level.inventory)); manifest.put("pending", stacks(level.pending));
        Map<String, Object> extra = new TreeMap<>();
        level.extraSections.forEach((key, bytes) -> extra.put(key, HexFormat.of().formatHex(bytes)));
        manifest.put("extraSections", extra);
        manifest.put("chunks", List.of());
        Map<String, Object> player = new LinkedHashMap<>();
        player.put("id", GUEST);
        player.put("position", List.of(guest.x, guest.y, guest.z));
        player.put("yaw", guest.yaw); player.put("pitch", guest.pitch);
        player.put("health", guest.health); player.put("hunger", guest.hunger);
        player.put("selectedSlot", guest.selected);
        player.put("progress", HexFormat.of().formatHex(guest.progress));
        player.put("inventory", stacks(guest.inventory)); player.put("pending", stacks(guest.pending));
        manifest.put("guest", player);
        Map<String, Object> hashes = new TreeMap<>();
        try (var files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList())
                hashes.put(dir.relativize(file).toString().replace('\\', '/'), digest(Files.readAllBytes(file)));
        }
        manifest.put("files", hashes);
        Files.writeString(dir.resolve("fixture.json"), json(manifest, 0) + "\n", StandardCharsets.UTF_8);
    }

    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String json(Object value, int indent) {
        if (value instanceof String s) return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
        if (value instanceof Map<?, ?> map) {
            List<String> entries = new ArrayList<>();
            for (var entry : map.entrySet()) entries.add(" ".repeat(indent + 2)
                    + json(entry.getKey().toString(), 0) + ": " + json(entry.getValue(), indent + 2));
            return "{\n" + String.join(",\n", entries) + "\n" + " ".repeat(indent) + "}";
        }
        if (value instanceof List<?> list) {
            List<String> entries = new ArrayList<>();
            for (Object entry : list) entries.add(json(entry, indent));
            return "[" + String.join(", ", entries) + "]";
        }
        return value.toString();
    }
}
