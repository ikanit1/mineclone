package com.mineclone;

import com.mineclone.sim.EntityStore;
import com.mineclone.sim.Participants;
import com.mineclone.sim.WorldClock;
import com.mineclone.sim.WorldEvents;
import com.mineclone.sim.WorldSession;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.Furnace;
import com.mineclone.world.ItemStack;
import com.mineclone.world.NeighbourUpdates;
import com.mineclone.world.World;
import com.mineclone.world.behavior.Behaviors;
import com.mineclone.world.behavior.BlockBehavior;
import com.mineclone.world.behavior.PlaceContext;
import com.mineclone.world.behavior.UseContext;
import com.mineclone.world.behavior.UseResult;
import com.mineclone.world.entity.MobSpawner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.joml.Vector3f;

/**
 * BLK-03: what blocks do. Containers spill whatever removes them, doors are
 * placed and removed whole, torches and snow fall without their support, and
 * all of it runs through {@code World} itself — not through whichever caller
 * remembered to do it.
 */
final class BehaviorTests {
    static void runAll(TestMain.Runner r) {
        r.run("a chest spills whatever removes it; a guest's mirror spills nothing", BehaviorTests::chest);
        r.run("a furnace spills its three slots; a meta change spills nothing", BehaviorTests::furnace);
        r.run("an explosion spills the chests it takes", BehaviorTests::explosion);
        r.run("a door is placed, opened and removed whole, and drops once", BehaviorTests::door);
        r.run("a torch falls with its floor or its wall, as an item", BehaviorTests::torch);
        r.run("snow goes with the ground under it", BehaviorTests::snow);
        r.run("support is checked only where the world is simulated", BehaviorTests::mirror);
        r.run("a collapse is worked a budget at a time", BehaviorTests::budget);
        r.run("the world's tick works the queue, and only where it simulates", BehaviorTests::tick);
        r.run("placement: facings, mounts, ceilings and carried meta", BehaviorTests::placement);
        r.run("the interactive blocks are the ones the game treated so", BehaviorTests::interactive);
        r.run("Game hands clicks to the interaction controller", BehaviorTests::controller);
    }

    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    /** A drop sink that remembers what fell. */
    private static final class Drops implements com.mineclone.world.behavior.DropSink {
        final List<ItemStack> stacks = new ArrayList<>();
        @Override public void drop(ItemStack stack, float x, float y, float z) { stacks.add(stack.copy()); }
        int count(String id) {
            int n = 0;
            for (ItemStack s : stacks)
                if (s.item.id.toString().equals("mineclone:" + id)) n += s.count;
            return n;
        }
        int total() { return stacks.stream().mapToInt(s -> s.count).sum(); }
    }

    /** Stone to y = 40 over one chunk, air above. */
    private static World flat(long seed) {
        World w = new World(seed);
        Chunk c = w.getChunk(0, 0);
        for (int x = 0; x < Chunk.SIZE_X; x++)
            for (int z = 0; z < Chunk.SIZE_Z; z++)
                for (int y = 0; y < Chunk.SIZE_Y; y++)
                    c.set(x, y, z, y <= 40 ? BlockType.STONE : BlockType.AIR);
        for (int cx = -1; cx <= 2; cx++)
            for (int cz = -1; cz <= 1; cz++)
                w.getChunk(cx, cz);
        return w;
    }

    private static World simulated(Drops drops) {
        World w = flat(7L);
        w.simulate(drops);
        return w;
    }

    private static void settle(World w) {
        for (int i = 0; i < 16 && w.pendingNeighbourUpdates() > 0; i++)
            w.processNeighbourUpdates(NeighbourUpdates.BUDGET);
    }

    private static void chest() {
        // Each way a chest leaves the world: a pickaxe (air), a guest's edit
        // or /fill (another block) — the same hook spills it.
        for (BlockType replacement : new BlockType[] { BlockType.AIR, BlockType.STONE, BlockType.WATER }) {
            Drops drops = new Drops();
            World w = simulated(drops);
            w.setBlock(5, 41, 5, BlockType.CHEST);
            ItemStack[] slots = w.createChest(5, 41, 5);
            slots[0] = ItemStack.of("diamond", 16);
            slots[26] = ItemStack.of("coal", 3);
            w.setBlock(5, 41, 5, replacement);
            check(drops.count("diamond") == 16 && drops.count("coal") == 3,
                    "replacing a chest with " + replacement + " spilled " + drops.stacks);
            check(w.getChest(5, 41, 5) == null, "the chest's slots outlived it");
            // An open chest window holds this very array: what spilled must not
            // still be there for it to hand out a second time.
            check(java.util.Arrays.stream(slots).allMatch(java.util.Objects::isNull),
                    "a window's view of the chest still holds what was spilled");
        }
        // A guest's world is a mirror: the host spills, the mirror must not.
        World mirror = flat(7L);
        mirror.setBlock(5, 41, 5, BlockType.CHEST);
        mirror.createChest(5, 41, 5)[0] = ItemStack.of("diamond", 16);
        mirror.setBlock(5, 41, 5, BlockType.AIR);
        check(mirror.drops() == com.mineclone.world.behavior.DropSink.NONE, "a mirror has no drop sink");
    }

    private static void furnace() {
        Drops drops = new Drops();
        World w = simulated(drops);
        w.setBlock(5, 41, 5, BlockType.FURNACE);
        Furnace f = w.createFurnace(5, 41, 5);
        f.input = ItemStack.of("iron_ore", 3);
        f.fuel = ItemStack.of("coal", 4);
        f.output = ItemStack.of("iron_ingot", 5);
        w.setBlock(5, 41, 5, BlockType.FURNACE, (byte) 2);
        check(drops.stacks.isEmpty() && f.input.count == 3, "a meta change spilled the furnace");
        w.setBlock(5, 41, 5, BlockType.COBBLE);
        check(drops.count("iron_ore") == 3 && drops.count("coal") == 4 && drops.count("iron_ingot") == 5,
                "furnace slots: " + drops.stacks);
        check(f.input == null && f.fuel == null && f.output == null, "an open furnace window still holds the spill");
    }

    private static void explosion() {
        World w = flat(9L);
        EntityStore entities = new EntityStore();
        WorldSession session = new WorldSession(w, new WorldClock(0.5), new MobSpawner(1L), entities,
                new Participants(), () -> new Vector3f(8f, 42f, 8f), WorldEvents.NONE);
        check(w.drops() != com.mineclone.world.behavior.DropSink.NONE, "a session gives its world a drop sink");
        w.setBlock(8, 41, 8, BlockType.CHEST);
        w.createChest(8, 41, 8)[3] = ItemStack.of("diamond", 7);
        session.explode(8.5f, 41.5f, 8.5f, 3f, null);
        check(w.getBlock(8, 41, 8) == BlockType.AIR, "the blast took the chest");
        int diamonds = entities.items.stream()
                .filter(e -> e.stack.item.id.toString().equals("mineclone:diamond"))
                .mapToInt(e -> e.stack.count).sum();
        check(diamonds == 7, "a creeper's crater keeps the chest's diamonds: " + diamonds);
    }

    private static void door() {
        Drops drops = new Drops();
        World w = simulated(drops);
        BlockBehavior door = Behaviors.of(BlockType.DOOR_CLOSED);
        PlaceContext facingNorth = new PlaceContext(0, 1, 0, 0f, -1f, PlaceContext.NO_META);
        check(door.canPlaceAt(w, 5, 41, 5, facingNorth), "a door stands on stone");
        byte meta = door.placementMeta(facingNorth);
        w.setBlock(5, 41, 5, BlockType.DOOR_CLOSED, meta);
        door.onPlaced(w, 5, 41, 5, meta);
        check(w.getBlock(5, 42, 5) == BlockType.DOOR_CLOSED && w.getBlockMeta(5, 42, 5) == (meta | 4),
                "placing the lower half placed the upper");
        settle(w);
        check(w.getBlock(5, 41, 5) == BlockType.DOOR_CLOSED && drops.stacks.isEmpty(), "a whole door stays");

        // Using either half swings both.
        UseResult used = door.use(w, 5, 42, 5, w.getBlockMeta(5, 42, 5), new UseContext(0));
        check(used.kind() == UseResult.Kind.CONSUMED, "a door's use is consumed");
        check(w.getBlock(5, 41, 5) == BlockType.DOOR_OPEN && w.getBlock(5, 42, 5) == BlockType.DOOR_OPEN,
                "the door opened whole");
        door.use(w, 5, 41, 5, w.getBlockMeta(5, 41, 5), new UseContext(0));
        check(w.getBlock(5, 42, 5) == BlockType.DOOR_CLOSED, "and closed whole");

        // Broken by hand (the controller drops the broken half's loot): the other half follows, silently.
        w.setBlock(5, 42, 5, BlockType.AIR);
        settle(w);
        check(w.getBlock(5, 41, 5) == BlockType.AIR, "the lower half outlived the upper");
        check(drops.stacks.isEmpty(), "the half that followed dropped " + drops.stacks);

        // The floor goes: the door falls once, as one door.
        w.setBlock(7, 41, 7, BlockType.DOOR_CLOSED, (byte) 0);
        door.onPlaced(w, 7, 41, 7, (byte) 0);
        w.setBlock(7, 40, 7, BlockType.AIR);
        settle(w);
        check(w.getBlock(7, 41, 7) == BlockType.AIR && w.getBlock(7, 42, 7) == BlockType.AIR, "the door fell");
        check(drops.total() == 1, "a fallen door drops one door, not two: " + drops.stacks);

        check(!door.canPlaceAt(w, 7, 41, 7, facingNorth), "no door on air");
        w.setBlock(9, 42, 9, BlockType.STONE);
        check(!door.canPlaceAt(w, 9, 41, 9, facingNorth), "no door under a ceiling one block high");
    }

    private static void torch() {
        Drops drops = new Drops();
        World w = simulated(drops);
        BlockBehavior torch = Behaviors.of(BlockType.TORCH);
        // On the floor: falls with it.
        w.setBlock(5, 41, 5, BlockType.TORCH, torch.placementMeta(new PlaceContext(0, 1, 0, 0, 1, PlaceContext.NO_META)));
        w.setBlock(5, 40, 5, BlockType.AIR);
        settle(w);
        check(w.getBlock(5, 41, 5) == BlockType.AIR, "a torch hung on air");
        check(drops.count("torch") == 1, "the torch fell as an item: " + drops.stacks);

        // On a wall at -X (a click on the wall's +X face): the floor may go, the wall may not.
        w.setBlock(7, 41, 5, BlockType.STONE);
        w.setBlock(8, 41, 5, BlockType.TORCH, torch.placementMeta(new PlaceContext(1, 0, 0, 1, 0, PlaceContext.NO_META)));
        w.setBlock(8, 40, 5, BlockType.AIR);
        settle(w);
        check(w.getBlock(8, 41, 5) == BlockType.TORCH, "a wall torch fell with the floor under it");
        w.setBlock(9, 41, 5, BlockType.STONE);
        w.setBlock(9, 41, 5, BlockType.AIR);
        settle(w);
        check(w.getBlock(8, 41, 5) == BlockType.TORCH, "a change on the other side dropped the torch");
        w.setBlock(7, 41, 5, BlockType.AIR);
        settle(w);
        check(w.getBlock(8, 41, 5) == BlockType.AIR && drops.count("torch") == 2, "the wall went, the torch stayed");

        // Every mount holds on its own wall and nothing else.
        int[][] faces = { { 0, 1, 0 }, { 1, 0, 0 }, { -1, 0, 0 }, { 0, 0, 1 }, { 0, 0, -1 } };
        for (int[] f : faces) {
            World t = simulated(new Drops());
            int x = 5, y = 41, z = 5;
            t.setBlock(x - f[0], y - f[1], z - f[2], BlockType.STONE);
            PlaceContext ctx = new PlaceContext(f[0], f[1], f[2], 0, 1, PlaceContext.NO_META);
            check(torch.canPlaceAt(t, x, y, z, ctx), "mount by face " + java.util.Arrays.toString(f));
            byte mount = torch.placementMeta(ctx);
            t.setBlock(x, y, z, BlockType.TORCH, mount);
            check(torch.canSurvive(t, x, y, z, mount), "survives on its own support " + mount);
            t.setBlock(x - f[0], y - f[1], z - f[2], BlockType.AIR);
            check(!torch.canSurvive(t, x, y, z, mount), "survives without its support " + mount);
        }
        check(!torch.canPlaceAt(w, 5, 45, 5, new PlaceContext(0, -1, 0, 0, 1, PlaceContext.NO_META)),
                "a torch does not hang from a ceiling");
    }

    private static void snow() {
        Drops drops = new Drops();
        World w = simulated(drops);
        w.setBlock(5, 41, 5, BlockType.SNOW_LAYER, (byte) 2);
        w.setBlock(6, 41, 5, BlockType.STONE);
        settle(w);
        check(w.getBlock(5, 41, 5) == BlockType.SNOW_LAYER, "snow on stone went");
        w.setBlock(5, 40, 5, BlockType.AIR);
        settle(w);
        check(w.getBlock(5, 41, 5) == BlockType.AIR, "snow hung over a dug-out cell");
    }

    private static void mirror() {
        World w = flat(7L);
        w.setBlock(5, 41, 5, BlockType.TORCH, (byte) 0);
        w.setBlock(5, 40, 5, BlockType.AIR);
        check(w.processNeighbourUpdates(NeighbourUpdates.BUDGET) == 0 && w.pendingNeighbourUpdates() == 0,
                "a world nobody simulates queues nothing");
        check(w.getBlock(5, 41, 5) == BlockType.TORCH, "the host decides what falls, not a mirror");
    }

    private static void budget() {
        Drops drops = new Drops();
        World w = simulated(drops);
        // Two chunks of torches: more than one tick's budget.
        int torches = 0;
        for (int x = 0; x < 2 * Chunk.SIZE_X; x++)
            for (int z = 0; z < Chunk.SIZE_Z; z++) {
                w.setBlock(x, 40, z, BlockType.STONE);
                w.setBlock(x, 41, z, BlockType.TORCH, (byte) 0);
                torches++;
            }
        w.processNeighbourUpdates(Integer.MAX_VALUE);
        check(drops.stacks.isEmpty(), "a supported torch fell");
        for (int x = 0; x < 2 * Chunk.SIZE_X; x++)
            for (int z = 0; z < Chunk.SIZE_Z; z++)
                w.setBlock(x, 40, z, BlockType.AIR);
        int queued = w.pendingNeighbourUpdates();
        check(queued >= torches, "every torch waits for a check: " + queued);
        int checked = w.processNeighbourUpdates(NeighbourUpdates.BUDGET);
        check(checked == NeighbourUpdates.BUDGET, "one tick checks its budget: " + checked);
        check(w.pendingNeighbourUpdates() > 0, "the rest waits for the next tick");
        int ticks = 1;
        while (w.pendingNeighbourUpdates() > 0 && ticks < 100) {
            w.processNeighbourUpdates(NeighbourUpdates.BUDGET);
            ticks++;
        }
        check(drops.count("torch") == torches, "every torch fell once: " + drops.count("torch"));
        check(ticks > 1 && ticks <= (queued + NeighbourUpdates.BUDGET - 1) / NeighbourUpdates.BUDGET + 1,
                "worked in " + ticks + " ticks");
    }

    /** Game and server tick through {@code WorldSimulation}: that is where the queue is worked. */
    private static void tick() {
        for (boolean simulate : new boolean[] { true, false }) {
            Drops drops = new Drops();
            World w = simulated(drops);
            w.setBlock(5, 41, 5, BlockType.TORCH, (byte) 0);
            w.setBlock(5, 40, 5, BlockType.AIR);
            new com.mineclone.world.WorldSimulation(w.seed).update(w, 0.05f, List.of(), 0f, simulate);
            check((w.getBlock(5, 41, 5) == BlockType.AIR) == simulate,
                    (simulate ? "the host's tick left" : "a guest's tick dropped") + " the torch");
        }
    }

    /** The facings the game computed in its own methods before BLK-03, transcribed. */
    private static void placement() {
        float[][] looks = { { 0.9f, 0.1f }, { -0.9f, 0.2f }, { 0.1f, 0.9f }, { -0.2f, -0.9f }, { 0.5f, 0.5f } };
        for (float[] l : looks) {
            PlaceContext ctx = new PlaceContext(0, 1, 0, l[0], l[1], PlaceContext.NO_META);
            float ax = Math.abs(l[0]), az = Math.abs(l[1]);
            byte oldDoor = (byte) (ax > az ? (l[0] > 0 ? 1 : 3) : (l[1] > 0 ? 0 : 2));
            byte oldStairs = (byte) (ax > az ? (l[0] > 0 ? 1 : 3) : (l[1] > 0 ? 2 : 0));
            check(Behaviors.of(BlockType.DOOR_CLOSED).placementMeta(ctx) == oldDoor, "door facing " + l[0] + "," + l[1]);
            check(Behaviors.of(BlockType.STAIRS).placementMeta(ctx) == oldStairs, "stairs facing " + l[0] + "," + l[1]);
        }
        // What a picked block carries wins over the facing — except for a door,
        // whose upper half is in the meta, and a torch, whose mount is.
        PlaceContext carried = new PlaceContext(1, 0, 0, 0.9f, 0f, 2);
        check(Behaviors.of(BlockType.STAIRS).placementMeta(carried) == 2, "stairs take the carried meta");
        check(Behaviors.of(BlockType.BEDROLL).placementMeta(carried) == 2, "a bedroll takes the carried meta");
        check(Behaviors.of(BlockType.STONE).placementMeta(carried) == 2, "a plain block takes the carried meta");
        check(Behaviors.of(BlockType.DOOR_CLOSED).placementMeta(carried) == 1, "a door keeps its own facing");
        check(Behaviors.of(BlockType.TORCH).placementMeta(carried) == 1, "a torch keeps its mount");
        check(Behaviors.of(BlockType.BEDROLL).placementMeta(new PlaceContext(0, 1, 0, 0, 1, PlaceContext.NO_META)) == 3,
                "a bedroll is half a block high");
        // The old torch rule, transcribed: floor 0, walls 1..4, a click from below is refused.
        int[][] faces = { { 0, 1, 0, 0 }, { 1, 0, 0, 1 }, { -1, 0, 0, 2 }, { 0, 0, 1, 3 }, { 0, 0, -1, 4 }, { 0, 0, 0, 0 } };
        for (int[] f : faces)
            check(Behaviors.torchMount(f[0], f[1], f[2]) == f[3], "torch mount " + java.util.Arrays.toString(f));
    }

    private static void interactive() {
        EnumSet<BlockType> interactive = EnumSet.noneOf(BlockType.class);
        for (BlockType t : BlockType.VALUES) {
            check(Behaviors.of(t) != null, t + " has no behaviour");
            if (Behaviors.of(t).interactive())
                interactive.add(t);
        }
        check(interactive.equals(EnumSet.of(BlockType.CHEST, BlockType.FURNACE, BlockType.CRAFTING_TABLE,
                BlockType.BEDROLL, BlockType.DOOR_CLOSED, BlockType.DOOR_OPEN)), "interactive: " + interactive);
        World w = flat(3L);
        UseContext who = new UseContext(0);
        check(Behaviors.of(BlockType.CHEST).use(w, 1, 41, 1, (byte) 0, who).menu() == UseResult.Menu.CHEST, "chest");
        check(Behaviors.of(BlockType.FURNACE).use(w, 1, 41, 1, (byte) 0, who).menu() == UseResult.Menu.FURNACE, "furnace");
        check(Behaviors.of(BlockType.CRAFTING_TABLE).use(w, 1, 41, 1, (byte) 0, who).menu() == UseResult.Menu.CRAFTING,
                "crafting table");
        check(Behaviors.of(BlockType.BEDROLL).use(w, 1, 41, 1, (byte) 3, who) == UseResult.SLEEP, "bedroll");
        check(Behaviors.of(BlockType.STONE).use(w, 1, 40, 1, (byte) 0, who) == UseResult.PASS, "stone");
    }

    /** Acceptance of BLK-03: the click left Game, and its new home stays small. */
    private static void controller() throws Exception {
        String game = Files.readString(Path.of("src/main/java/com/mineclone/game/Game.java"));
        check(!game.contains("handleInteraction"), "Game still handles clicks itself");
        check(!game.contains("spillChest") && !game.contains("spillFurnace"), "Game still spills containers itself");
        String server = Files.readString(Path.of("src/main/java/com/mineclone/server/DedicatedServer.java"));
        check(!server.contains("getChest(") && !server.contains("getFurnace("),
                "the server still spills a guest's container itself");
        long lines = Files.readAllLines(Path.of("src/main/java/com/mineclone/game/InteractionController.java")).size();
        check(lines < 600, "InteractionController has " + lines + " lines");
    }
}
