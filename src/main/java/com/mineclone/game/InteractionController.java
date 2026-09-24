package com.mineclone.game;

import com.mineclone.core.Input;
import com.mineclone.core.KeyBindings;
import com.mineclone.item.BlockState;
import com.mineclone.item.Components;
import com.mineclone.item.FurnaceState;
import com.mineclone.item.Item;
import com.mineclone.item.Items;
import com.mineclone.item.loot.LootContext;
import com.mineclone.item.loot.LootRegistry;
import com.mineclone.net.Multiplayer;
import com.mineclone.world.BlockType;
import com.mineclone.world.Chunk;
import com.mineclone.world.Furnace;
import com.mineclone.world.GameMode;
import com.mineclone.world.Inventory;
import com.mineclone.world.ItemStack;
import com.mineclone.world.World;
import com.mineclone.world.behavior.Behaviors;
import com.mineclone.world.behavior.BlockBehavior;
import com.mineclone.world.behavior.PlaceContext;
import com.mineclone.world.behavior.UseContext;
import com.mineclone.world.behavior.UseResult;
import java.util.random.RandomGenerator;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

/**
 * What a click does to the block under the crosshair (BLK-03): breaking,
 * placing, using, picking. The rules of each block live in its
 * {@link BlockBehavior}; this class only turns input into calls on them, and
 * asks the game ({@link Host}) for everything a player sees and hears.
 *
 * <p>A guest runs the same code on its mirror of the world. A use it can
 * predict (a door) it applies at once, without echoing the edit, and asks the
 * host to do it for real with {@code C_USE_BLOCK}; the host's
 * {@code S_BLOCK_SET} settles it either way.
 */
final class InteractionController {
    /** How far the hand reaches, in blocks. */
    static final float REACH = 6f;

    /** Everything the controller needs from the game that owns it. */
    interface Host {
        World world();
        Player player();
        GameMode gameMode();
        Inventory inventory();
        int selectedSlot();
        /** The pipette chose this slot. */
        void pickedSlot(int slot);
        boolean instantBreak();
        RandomGenerator itemRandom();

        /**
         * The mob part of a click: attack what the crosshair holds when it is
         * nearer than {@code hit}.
         *
         * @return whether a mob is under the crosshair
         */
        boolean attack(Vector3f origin, Vector3f dir, float dt, Raycaster.Hit hit);
        /** A left click at nothing: the tool whooshes. */
        void swingAtAir();
        /** Eat what is held; true when the right click went into eating. */
        boolean tryEat();
        void swing();
        boolean occupiedByPlayer(int x, int y, int z);
        void wearHeldTool();
        void drop(ItemStack stack, float x, float y, float z);

        void digSound(int x, int y, int z, BlockType block);
        /** A block was broken: sound, debris, noise, collapsing structures. */
        void broken(int x, int y, int z, BlockType block);
        /** A block was placed: sound, particles, noise, the structure it joins. */
        void placed(int x, int y, int z, BlockType block);
        /** A use changed the world (a door swung). */
        void used(int x, int y, int z, BlockType block);
        void openMenu(UseResult.Menu menu, int x, int y, int z);
        void sleep(int x, int y, int z);
    }

    private static final int NO_BREAK = Integer.MIN_VALUE;

    private final Host host;
    private final Input input;
    private final Multiplayer net;
    private final InteractionRepeat creativeBreakRepeat = new InteractionRepeat();
    private final InteractionRepeat placeRepeat = new InteractionRepeat();

    private Raycaster.Hit hit;
    /** The eye and the look of this frame's ray. */
    private final Vector3f origin = new Vector3f(), dir = new Vector3f();
    private int breakX = NO_BREAK, breakY = NO_BREAK, breakZ = NO_BREAK;
    private float breakProgress;
    private float breakDigTimer;

    InteractionController(Host host, Input input, Multiplayer net) {
        this.host = host;
        this.input = input;
        this.net = net;
    }

    /** The block under the crosshair this frame, or null. */
    Raycaster.Hit hit() {
        return hit;
    }

    /** The block being dug and how far along, for the crack overlay; x is {@code MIN_VALUE} when none. */
    int breakX() { return breakX; }
    int breakY() { return breakY; }
    int breakZ() { return breakZ; }
    float breakProgress() { return breakProgress; }

    boolean breaking() {
        return breakX != NO_BREAK && breakProgress > 0f;
    }

    void resetBreak() {
        breakX = NO_BREAK;
        breakY = NO_BREAK;
        breakZ = NO_BREAK;
        breakProgress = 0f;
        breakDigTimer = 0f;
    }

    /** A new mode or world: nothing half-dug and no held button carried over. */
    void reset() {
        resetBreak();
        creativeBreakRepeat.reset();
        placeRepeat.reset();
    }

    void update(float dt) {
        if (net.inventoryBusy())
            return;
        World world = host.world();
        Player player = host.player();
        origin.set(player.camera.position);
        dir.set(player.camera.forward());
        hit = Raycaster.cast(world, origin, dir, REACH);
        boolean creative = host.gameMode() == GameMode.CREATIVE;
        boolean breakNow = creativeBreakRepeat.update(dt,
                input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_LEFT),
                input.mouseDown(GLFW.GLFW_MOUSE_BUTTON_LEFT), creative);
        boolean placeNow = placeRepeat.update(dt,
                input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT),
                input.mouseDown(GLFW.GLFW_MOUSE_BUTTON_RIGHT), true);

        // The mob goes first: while it is on the line of sight and nearer than
        // the block, digging does not happen at all — not only on the frame of
        // the click. Otherwise holding the button on a mob would dig through
        // to the block behind it.
        boolean mobAimed = host.attack(origin, dir, dt, hit);

        if (input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT) && host.tryEat())
            return;

        if (hit == null) {
            if (!mobAimed && input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_LEFT))
                host.swingAtAir();
            resetBreak();
            return;
        }

        // The middle button picks; F3 with it is the debug meta cycle.
        if (input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_MIDDLE) && !input.keyDown(GLFW.GLFW_KEY_F3))
            pickBlock(world, hit.x, hit.y, hit.z, input.keyDown(GLFW.GLFW_KEY_LEFT_CONTROL)
                    || input.keyDown(GLFW.GLFW_KEY_RIGHT_CONTROL));

        dig(world, dt, mobAimed, creative, breakNow);

        if (placeNow)
            rightClick(world, player);
    }

    // ------------------------------------------------------------------ digging

    private void dig(World world, float dt, boolean mobAimed, boolean creative, boolean breakNow) {
        GameMode mode = host.gameMode();
        if (mobAimed) {
            resetBreak();
        } else if (creative || host.instantBreak()) {
            if (breakNow) {
                BlockType target = world.getBlock(hit.x, hit.y, hit.z);
                if (mode.canBreak(target))
                    breakBlock(world, hit.x, hit.y, hit.z, target);
            }
            resetBreak();
        } else if (input.mouseDown(GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
            BlockType target = world.getBlock(hit.x, hit.y, hit.z);
            if (!mode.canBreak(target)) {
                resetBreak();
            } else if (breakX == hit.x && breakY == hit.y && breakZ == hit.z) {
                breakProgress += dt * miningSpeed(target) / target.hardness;
                breakDigTimer -= dt;
                if (breakDigTimer <= 0f) {
                    host.swing();
                    host.digSound(hit.x, hit.y, hit.z, target);
                    breakDigTimer = 0.4f;
                }
                if (breakProgress >= 1f) {
                    breakBlock(world, hit.x, hit.y, hit.z, target);
                    resetBreak();
                }
            } else {
                breakX = hit.x;
                breakY = hit.y;
                breakZ = hit.z;
                breakProgress = 0f;
                breakDigTimer = 0f;
            }
        } else {
            resetBreak();
        }
    }

    /**
     * Breaks a block by hand. What it holds spills in {@code World.setBlock}
     * through the block's behaviour, whoever breaks it; the other half of a
     * door follows through the neighbour updates.
     */
    void breakBlock(World world, int x, int y, int z, BlockType target) {
        GameMode mode = host.gameMode();
        boolean harvest = LootRegistry.canHarvest(target, heldTool());
        host.swing();
        net.noteBlockAction(target, true, x, y, z);
        // Broken ice comes back as water: a frozen lake would otherwise become
        // a pit, and there was always water under the ice.
        world.setBlock(x, y, z, target == BlockType.ICE && mode == GameMode.SURVIVAL
                ? BlockType.WATER : BlockType.AIR);
        if (mode == GameMode.SURVIVAL) {
            if (harvest) {
                var context = new LootContext(world.seed, x, y, z, heldTool(), true, mode, host.itemRandom());
                for (var drop : Items.get().loot().blockDrops(target, context))
                    host.drop(drop, x + 0.5f, y + 0.3f, z + 0.5f);
            }
            host.wearHeldTool();
        }
        host.broken(x, y, z, target);
    }

    /**
     * How many times faster than bare hands this block is dug. A tool that does
     * not suit it does not help at all: a pickaxe digs earth like a fist.
     */
    private float miningSpeed(BlockType target) {
        ItemStack tool = heldTool();
        if (tool == null || !tool.tool().suits(target))
            return 1f;
        return tool.tool().speed();
    }

    private ItemStack heldTool() {
        ItemStack s = host.inventory().get(host.selectedSlot());
        return s != null && s.tool() != null ? s : null;
    }

    // ------------------------------------------------------------ right click

    private void rightClick(World world, Player player) {
        // Shift lets builders place against interactive blocks.
        boolean interact = !input.down(KeyBindings.Action.DESCEND);
        host.swing();
        BlockType target = world.getBlock(hit.x, hit.y, hit.z);
        BlockBehavior behavior = Behaviors.of(target);
        if (interact && behavior.interactive()) {
            // A held button repeats placement, never a use: a door would flap.
            if (input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT))
                use(world, target, behavior);
            return;
        }
        place(world, player, target);
    }

    private void use(World world, BlockType target, BlockBehavior behavior) {
        int x = hit.x, y = hit.y, z = hit.z;
        byte meta = world.getBlockMeta(x, y, z);
        UseContext who = new UseContext(net.localActor());
        UseResult result;
        if (net.isClient()) {
            // The guest applies what it can foresee without sending it as an
            // edit, and asks the host to do the use itself.
            UseResult[] out = new UseResult[1];
            net.predicted(() -> out[0] = behavior.use(world, x, y, z, meta, who));
            result = out[0];
            if (result.kind() == UseResult.Kind.CONSUMED) {
                float t = Math.max(0f, hit.distance);
                net.requestUse(x, y, z, hit.nx, hit.ny, hit.nz, origin.x + dir.x * t - x,
                        origin.y + dir.y * t - y, origin.z + dir.z * t - z);
            }
        } else {
            result = behavior.use(world, x, y, z, meta, who);
        }
        switch (result.kind()) {
            case OPEN_MENU -> host.openMenu(result.menu(), x, y, z);
            case SLEEP -> host.sleep(x, y, z);
            case CONSUMED -> host.used(x, y, z, target);
            case PASS -> { }
        }
    }

    private void place(World world, Player player, BlockType target) {
        int px = hit.x + hit.nx, py = hit.y + hit.ny, pz = hit.z + hit.nz;
        // A film of snow gives way to the new block instead of carrying it.
        if (target == BlockType.SNOW_LAYER && (world.getBlockMeta(hit.x, hit.y, hit.z) & 0x7) == 0) {
            px = hit.x;
            py = hit.y;
            pz = hit.z;
        }
        if (py < 0 || py >= Chunk.SIZE_Y
                || world.getChunkIfExists(Math.floorDiv(px, Chunk.SIZE_X), Math.floorDiv(pz, Chunk.SIZE_Z)) == null)
            return;
        if (!replaceable(world.getBlock(px, py, pz)) || host.occupiedByPlayer(px, py, pz))
            return;
        Inventory inventory = host.inventory();
        int slot = host.selectedSlot();
        ItemStack held = inventory.get(slot);
        BlockType placing = held == null || held.block() == null ? BlockType.AIR : held.block();
        if (placing == BlockType.AIR)
            return;
        GameMode mode = host.gameMode();
        if (mode == GameMode.SURVIVAL && !inventory.hasItem(slot))
            return;
        BlockState carried = held.get(Components.BLOCK_STATE);
        Vector3f look = player.camera.forward();
        PlaceContext ctx = new PlaceContext(hit.nx, hit.ny, hit.nz, look.x, look.z,
                carried == null ? PlaceContext.NO_META : carried.meta() & 0xFF);
        BlockBehavior behavior = Behaviors.of(placing);
        if (!behavior.canPlaceAt(world, px, py, pz, ctx))
            return;
        if (twoTall(placing) && host.occupiedByPlayer(px, py + 1, pz))
            return;
        byte meta = behavior.placementMeta(ctx);
        world.setBlock(px, py, pz, placing, meta);
        net.noteBlockAction(placing, false, px, py, pz);
        behavior.onPlaced(world, px, py, pz, meta);
        restoreBlockState(world, carried, px, py, pz);
        host.placed(px, py, pz, placing);
        if (mode == GameMode.SURVIVAL)
            inventory.removeOne(slot);
    }

    /** What a placed block may take the place of. */
    static boolean replaceable(BlockType existing) {
        return existing == BlockType.AIR || existing == BlockType.WATER || existing == BlockType.WATER_FLOW
                || existing == BlockType.LAVA || existing == BlockType.SNOW_LAYER || existing == BlockType.FIRE;
    }

    /** Blocks that take the cell above as well. */
    private static boolean twoTall(BlockType type) {
        return type == BlockType.DOOR_CLOSED || type == BlockType.DOOR_OPEN;
    }

    // ------------------------------------------------------------------ pipette

    /**
     * The pipette: puts the aimed block in the hand. With Ctrl in creative the
     * item carries the block's contents too — meta, a chest's slots, a
     * furnace's state; otherwise "copy a chest" would copy only its shell.
     */
    private void pickBlock(World world, int x, int y, int z, boolean withState) {
        BlockType target = world.getBlock(x, y, z);
        Item item = Items.get().forBlock(target);
        if (item == null)
            return;
        boolean creative = host.gameMode() == GameMode.CREATIVE;
        ItemStack give = null;
        if (creative) {
            give = new ItemStack(item, item.maxStack);
            if (withState)
                give.set(Components.BLOCK_STATE, captureState(world, x, y, z));
        }
        int slot = PickBlock.pick(host.inventory(), host.selectedSlot(), item, creative, give);
        if (slot != host.selectedSlot() || creative)
            host.pickedSlot(slot);
    }

    /** A snapshot of a block: its meta and what lies in it. */
    private static BlockState captureState(World world, int x, int y, int z) {
        byte meta = world.getBlockMeta(x, y, z);
        ItemStack[] chest = world.getChest(x, y, z);
        Furnace f = world.getFurnace(x, y, z);
        FurnaceState furnace = f == null ? null
                : new FurnaceState(f.input, f.fuel, f.output, f.burnLeft, f.burnMax, f.cook);
        return new BlockState(meta, chest == null ? null : java.util.Arrays.asList(chest), furnace);
    }

    /**
     * Puts back what the pipette carried away with the block — strictly after
     * {@code setBlock}, which is what creates the empty chest.
     */
    private static void restoreBlockState(World world, BlockState st, int x, int y, int z) {
        if (st == null)
            return;
        if (st.hasChest()) {
            ItemStack[] slots = world.createChest(x, y, z);
            if (slots != null) {
                java.util.List<ItemStack> src = st.chestCopy();
                for (int i = 0; i < slots.length; i++)
                    slots[i] = i < src.size() ? src.get(i) : null;
                world.markChestDirty(x, z);
            }
        }
        if (st.hasFurnace()) {
            Furnace f = world.createFurnace(x, y, z);
            if (f != null) {
                FurnaceState fs = st.furnace();
                f.input = fs.inputCopy();
                f.fuel = fs.fuelCopy();
                f.output = fs.outputCopy();
                f.burnLeft = fs.burnLeft();
                f.burnMax = fs.burnMax();
                f.cook = fs.cook();
                world.markChestDirty(x, z);
            }
        }
    }

    /** The debug stick under F3: the aimed block's meta, one step on. */
    void cycleMeta() {
        World world = host.world();
        if (world == null || hit == null)
            return;
        byte m = world.getBlockMeta(hit.x, hit.y, hit.z);
        world.setBlock(hit.x, hit.y, hit.z, world.getBlock(hit.x, hit.y, hit.z), (byte) ((m + 1) & 0x0F));
    }
}
