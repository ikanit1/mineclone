import com.mineclone.save.*;
import com.mineclone.server.DedicatedServer;
import com.mineclone.server.ServerConfig;
import com.mineclone.sim.WorldClock;
import com.mineclone.sim.ecology.*;
import com.mineclone.world.*;
import com.mineclone.world.behavior.Behaviors;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;

/** Disposable server fixture and read-only proof of the ordinary network lair outcome on disk. */
public final class LairSaveAcceptance {
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !(args[0].equals("prepare") || args[0].equals("verify")))
            throw new IllegalArgumentException("Usage: LairSaveAcceptance.java prepare <test config> | verify <test config> <initial IDs>");
        Path file = Path.of(args[1]).toAbsolutePath().normalize(), testRoot = Path.of("out-test").toAbsolutePath().normalize();
        check(file.startsWith(testRoot), "Lair fixture config must be below this checkout's out-test");
        ServerConfig config = ServerConfig.load(file.toFile()); Path saves = Path.of(config.savesDir).toAbsolutePath().normalize();
        check(saves.startsWith(file.getParent()) && config.worldId.equals("test"), "Refusing a non-test world or save path");
        if (args[0].equals("prepare")) { check(args.length == 2, "Unexpected prepare arguments"); prepare(config, saves); }
        else { check(args.length == 3, "Verification needs the actual four IDs recorded by the network guest"); verify(config, saves, args[2]); }
    }
    private static void prepare(ServerConfig config, Path saves) throws Exception {
        check(!Files.exists(saves.resolve(config.worldId)), "Lair fixture needs a new temporary world");
        DedicatedServer server = new DedicatedServer(config);
        try {
            var open = DedicatedServer.class.getDeclaredMethod("openWorld"); open.setAccessible(true);
            check((boolean) open.invoke(server), "Actual server world opening refused fixture");
            World world = (World) get(server, "world"); var spawn = server.spawn(); ChunkLoader loader = (ChunkLoader) get(server, "loader");
            int x = Math.floorDiv((int) Math.floor(spawn.x), 256) * 256 + 70;
            int z = Math.floorDiv((int) Math.floor(spawn.z), 256) * 256 + 70;
            int floor = Math.min(116, (int) Math.floor(spawn.y) + 20);
            for (int centre : new int[]{x, x + 80}) {
                for (int cx = Math.floorDiv(centre - 7, 16); cx <= Math.floorDiv(centre + 7, 16); cx++)
                    for (int cz = Math.floorDiv(z - 7, 16); cz <= Math.floorDiv(z + 7, 16); cz++) loader.loadNow(cx, cz);
                for (int y = floor; y <= floor + 5; y++) for (int dz = -7; dz <= 7; dz++) for (int dx = -7; dx <= 7; dx++)
                    world.setBlock(centre + dx, y, z + dz, y == floor || y == floor + 5 || Math.abs(dx) == 7 || Math.abs(dz) == 7 ? BlockType.STONE : BlockType.AIR);
                for (int cx = Math.floorDiv(centre - 7, 16); cx <= Math.floorDiv(centre + 7, 16); cx++)
                    for (int cz = Math.floorDiv(z - 7, 16); cz <= Math.floorDiv(z + 7, 16); cz++) world.getChunkIfExists(cx, cz).computeSkyLight();
                Chunk c = world.getChunkIfExists(Math.floorDiv(centre, 16), Math.floorDiv(z, 16));
                int cell = Chunk.idx(Math.floorMod(centre, 16), floor + 1, Math.floorMod(z, 16));
                check(LairSpots.eligible(world, c, cell), "Fixture chamber is not a true dark supported spider cavity");
                c.setExtraSection(LairSpots.SECTION, new LairSpots(new int[]{cell}).encode());
            }
            world.setBlock(x, floor + 1, z, BlockType.SPIDER_NEST);
            Behaviors.of(BlockType.SPIDER_NEST).onPlaced(world, x, floor + 1, z, (byte) 0);
            server.ecology().regionAt(x, z); server.ecology().setEnabled(true);
            // Ordinary host observation registers the placed heart. There are no participants or emitted bodies yet.
            server.ecology().tickLairs(.5f, false);
            check(server.ecology().lairs().lairs(EcologyRegion.key(Math.floorDiv(x, 256), Math.floorDiv(z, 256))).stream()
                    .anyMatch(l -> l.x() == x && l.y() == floor + 1 && l.z() == z && l.alive()), "Placed heart was not observed by the actual runtime");
            server.saveWorld(); ((SaveManager) get(server, "save")).flushAndAwait();
            Files.writeString(saves.getParent().resolve("lair-fixture.txt"), x + " " + (floor + 1) + " " + z + "\n");
            System.out.println("LAIR_SAVE_PREPARE PASS nestId=" + BlockType.SPIDER_NEST.ordinal() + " twoDarkRooms=true finiteBodies=0");
        } finally {
            ChunkLoader loader = (ChunkLoader) get(server, "loader"); if (loader != null) loader.shutdown(); close((SaveManager) get(server, "save"));
        }
    }
    private static void verify(ServerConfig config, Path saves, String recordedIds) throws Exception {
        long[] original = Arrays.stream(recordedIds.split(",")).mapToLong(Long::parseLong).sorted().toArray();
        check(original.length == 4 && original[0] > 0 && Arrays.stream(original).distinct().count() == 4, "Invalid network-emitted original IDs");
        String[] pose = Files.readString(saves.getParent().resolve("lair-fixture.txt")).trim().split("\\s+");
        int x = Integer.parseInt(pose[0]), y = Integer.parseInt(pose[1]), z = Integer.parseInt(pose[2]);
        SaveManager save = new SaveManager(saves.toFile());
        try {
            LevelData level = save.loadLevel(config.worldId); check(level != null, "Server did not save level");
            WorldClock clock = WorldClock.fromSaved(level.timeOfDay, level.extraSections.get(WorldClock.SAVE_SECTION));
            check(Ecology.day(clock.gameTime()) >= 11, "Actual next-night and ten-day commands were not persisted");
            EcologyCodec.Snapshot ecology = EcologyCodec.decode(level.extraSections.get(Ecology.SAVE_SECTION));
            Lair old = null, replacement = null;
            for (EcologyRegion region : ecology.regions()) if (region.x() == Math.floorDiv(x, 256) && region.z() == Math.floorDiv(z, 256))
                for (byte[] bytes : region.lairs()) {
                    Lair lair = Lair.decode(bytes); if (lair == null) continue;
                    if (lair.x() == x && lair.y() == y && lair.z() == z) old = lair;
                    else if (lair.alive()) replacement = lair;
                }
            check(old != null && !old.alive() && old.destroyedDay() >= 0, "Original heart was not saved destroyed");
            check(old.population() == 5, "One ordinary death did not reduce original population 6 to5: " + old.population());
            var surviving = new TreeSet<Long>(); for (int n = 1; n < original.length; n++) surviving.add(original[n]);
            check(old.bodyIds().equals(surviving), "Saved old wanderer IDs changed or dead ID was retained");
            check(replacement != null && replacement.alive(), "No actual live replacement was saved");
            long dx = (long) replacement.x() - x, dz = (long) replacement.z() - z;
            check(dx * dx + dz * dz >= 48 * 48, "Replacement returned to the old territory");
            check(block(save, config.worldId, x, y, z) == BlockType.AIR.ordinal(), "Destroyed original chunk heart reappeared");
            check(block(save, config.worldId, replacement.x(), replacement.y(), replacement.z()) == BlockType.SPIDER_NEST.ordinal(), "Replacement chunk has no actual heart");
            var savedMobs = new HashMap<Long, SavedMob>();
            try (var files = Files.list(saves.resolve(config.worldId).resolve("chunks"))) {
                for (Path file : files.toList()) {
                    String name = file.getFileName().toString(); if (!name.matches("c\\.-?\\d+\\.-?\\d+\\.dat")) continue;
                    String[] coords = name.split("\\."); ChunkLoad loaded = save.readChunk(config.worldId, Integer.parseInt(coords[1]), Integer.parseInt(coords[2]));
                    check(loaded instanceof ChunkLoad.Loaded, "Saved fixture chunk cannot reopen: " + loaded);
                    for (SavedMob mob : ((ChunkLoad.Loaded) loaded).snapshot().mobs) check(savedMobs.putIfAbsent(mob.id(), mob) == null, "Duplicate saved body ID " + mob.id());
                }
            }
            check(!savedMobs.containsKey(original[0]), "Ordinarily killed spider is still saved as a living body");
            var all = new TreeSet<>(old.bodyIds());
            for (long id : replacement.bodyIds()) check(all.add(id) && Arrays.binarySearch(original, id) < 0, "Replacement reused an old or duplicated ID");
            for (long id : all) {
                SavedMob mob = savedMobs.get(id);
                check(mob != null && mob.type().equals("SPIDER") && mob.persistent() && mob.health() > 0, "Reserved spider " + id + " has no intact living saved record");
                check(clock.entityCounter() >= id, "Saved clock allocator is behind a represented body");
            }
            System.out.println("LAIR_SAVE_VERIFY PASS oldHeart=AIR oldPopulation=5 oldBodies=" + old.bodyIds() + " newHeart="
                    + replacement.x() + "," + replacement.y() + "," + replacement.z() + " newBodies=" + replacement.bodyIds() + " uniqueSavedIDs=true clockDay=" + Ecology.day(clock.gameTime()));
        } finally { close(save); }
    }
    private static int block(SaveManager save, String id, int x, int y, int z) {
        ChunkLoad loaded = save.readChunk(id, Math.floorDiv(x, 16), Math.floorDiv(z, 16));
        check(loaded instanceof ChunkLoad.Loaded, "Heart chunk cannot reopen: " + loaded);
        return Byte.toUnsignedInt(((ChunkLoad.Loaded) loaded).snapshot().blocks[Chunk.idx(Math.floorMod(x, 16), y, Math.floorMod(z, 16))]);
    }
    private static Object get(Object object, String name) throws Exception { var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
    private static void close(SaveManager save) throws Exception { if (save != null) { save.flushAndAwait(); ((ExecutorService) get(save, "chunkWriter")).shutdown(); } }
    private static void check(boolean good, String why) { if (!good) throw new IllegalStateException(why); }
}
