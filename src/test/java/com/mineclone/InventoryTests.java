package com.mineclone;

import com.mineclone.data.Json;
import com.mineclone.data.JsonException;
import com.mineclone.data.JsonObject;
import com.mineclone.data.DataPack;
import com.mineclone.data.ResourceId;
import com.mineclone.item.BlockState;
import com.mineclone.item.Categories;
import com.mineclone.item.ComponentType;
import com.mineclone.item.Components;
import com.mineclone.item.FurnaceState;
import com.mineclone.item.Item;
import com.mineclone.item.ItemComponents;
import com.mineclone.item.ItemRegistry;
import com.mineclone.item.Items;
import com.mineclone.item.Tag;
import com.mineclone.item.TagRegistry;
import com.mineclone.item.ToolClass;
import com.mineclone.save.ChunkSnapshot;
import com.mineclone.save.LevelData;
import com.mineclone.save.Options;
import com.mineclone.save.SaveFormat;
import com.mineclone.save.SaveManager;
import com.mineclone.world.BlockType;
import com.mineclone.world.Inventory;
import com.mineclone.world.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * Проверки меню выживания и креатива: данные предметов, компоненты, сейвы,
 * логика окон. Отдельным классом, как {@link FeatureTests}: раннер и счётчики
 * общие, {@code TestMain} зовёт {@link #runAll} перед итогом.
 */
final class InventoryTests {
    private InventoryTests() {}

    interface Check { void run() throws Exception; }
    interface Runner { void run(String name, Check check); }

    static void runAll(Runner r) {
        r.run("json parses nested objects, arrays and escapes", InventoryTests::testJsonParses);
        r.run("json allows line comments and trailing commas", InventoryTests::testJsonLenient);
        r.run("json errors carry file, line and column", InventoryTests::testJsonErrors);
        r.run("json object accessors report the key path", InventoryTests::testJsonAccessors);
        r.run("json allowOnly rejects a typo", InventoryTests::testJsonAllowOnly);
        r.run("resource ids parse with and without a namespace", InventoryTests::testResourceIdParse);
        r.run("resource ids reject upper case and spaces", InventoryTests::testResourceIdRejects);
        r.run("data pack lists files by kind in a stable order", InventoryTests::testDataPackListing);
        r.run("every placeable block has exactly one item", InventoryTests::testBlockItems);
        r.run("technical blocks have no item", InventoryTests::testTechnicalBlocks);
        r.run("tools keep their old level, speed and durability", InventoryTests::testToolNumbers);
        r.run("food keeps its old nutrition", InventoryTests::testFoodNumbers);
        r.run("computed tags find light sources and durable items", InventoryTests::testComputedTags);
        r.run("tag search matches russian aliases and treats yo as ye", InventoryTests::testTagSearch);
        r.run("tag includes resolve and a cycle is an error", InventoryTests::testTagIncludes);
        r.run("registry rejects an unknown property with its path", InventoryTests::testRegistryStrict);
        r.run("missing items are cached placeholders", InventoryTests::testMissingItems);
        r.run("components compare regardless of insertion order", InventoryTests::testComponentOrder);
        r.run("component codec round-trips every known type", InventoryTests::testComponentCodecs);
        r.run("unknown components survive a round trip byte for byte", InventoryTests::testUnknownComponents);
        r.run("with null removes a component", InventoryTests::testComponentRemoval);
        r.run("stacks merge only with equal components", InventoryTests::testStackMerging);
        r.run("unbreakable tools never wear", InventoryTests::testUnbreakable);
        r.run("custom name overrides the item name", InventoryTests::testCustomName);
        r.run("inventory add keeps components apart", InventoryTests::testInventoryComponents);
        r.run("content hash changes when a count changes", InventoryTests::testContentHash);
        r.run("level v9 bytes load into registry items", InventoryTests::testLevelV9Migration);
        r.run("chunk v5 chests, furnaces and dropped items migrate", InventoryTests::testChunkV5Migration);
        r.run("level v10 round-trips components, missing items and pending stacks",
                InventoryTests::testLevelV10RoundTrip);
        r.run("unknown level sections survive a save", InventoryTests::testUnknownSections);
        r.run("options v5 load with default inventory preferences", InventoryTests::testOptionsV5);
        r.run("options v6 round-trip", InventoryTests::testOptionsV6);
        r.run("batch transform scales around its pivot", InventoryTests::testBatchTransform);
    }

    private static void testBatchTransform() {
        // Опора остаётся на месте — в этом весь смысл «подрастания» окна.
        float[] pivot = com.mineclone.render.UiRenderer.transformPoint(
                100f, 50f, 0.5f, 100f, 50f, 0f, 0f);
        assertEq("pivot x stays", 100f, pivot[0]);
        assertEq("pivot y stays", 50f, pivot[1]);

        float[] corner = com.mineclone.render.UiRenderer.transformPoint(
                200f, 150f, 0.5f, 100f, 50f, 0f, 0f);
        assertEq("corner halves its distance in x", 150f, corner[0]);
        assertEq("and in y", 100f, corner[1]);

        // Сдвиг прибавляется после масштаба, а не масштабируется сам.
        float[] moved = com.mineclone.render.UiRenderer.transformPoint(
                200f, 150f, 0.5f, 100f, 50f, 10f, -4f);
        assertEq("shift is added after scaling", 160f, moved[0]);
        assertEq("and in y too", 96f, moved[1]);

        float[] same = com.mineclone.render.UiRenderer.transformPoint(
                7f, 9f, 1f, 0f, 0f, 0f, 0f);
        assertEq("unit transform changes nothing in x", 7f, same[0]);
        assertEq("nor in y", 9f, same[1]);
    }

    // -------------------------------------------------------------- сейвы

    /** Прежний размеченный слот: пусто / блок / инструмент / еда. */
    private static void writeLegacyStack(java.io.DataOutputStream o, int kind, int id, int value)
            throws java.io.IOException {
        o.writeByte(kind);
        if (kind == 0)
            return;
        o.writeByte(id);
        o.writeShort(value);
    }

    private static java.io.File freshRoot() throws java.io.IOException {
        java.io.File dir = java.io.File.createTempFile("mineclone-inv-", "");
        if (!dir.delete() || !dir.mkdirs())
            throw new IllegalStateException("could not create temp dir " + dir);
        dir.deleteOnExit();
        return dir;
    }

    private static java.io.DataOutputStream gzip(java.io.File f) throws java.io.IOException {
        f.getParentFile().mkdirs();
        return new java.io.DataOutputStream(new java.util.zip.GZIPOutputStream(
                new java.io.BufferedOutputStream(new java.io.FileOutputStream(f))));
    }

    private static void testLevelV9Migration() throws Exception {
        java.io.File root = freshRoot();
        java.io.File saves = new java.io.File(root, "saves");
        // Писатель v9 скопирован сюда целиком: он должен пережить любую
        // будущую правку живого кода, иначе проверка миграции проверяет
        // migration против самой себя.
        try (java.io.DataOutputStream o = gzip(new java.io.File(saves, "old/level.dat"))) {
            o.writeInt(SaveFormat.MAGIC);
            o.writeInt(9);
            o.writeUTF("Старый мир");
            o.writeLong(4242L);
            o.writeLong(777L);
            o.writeFloat(15f);
            o.writeFloat(11f);
            o.writeDouble(1.5); o.writeDouble(65.0); o.writeDouble(-2.5);
            o.writeDouble(8.5); o.writeDouble(80.0); o.writeDouble(8.5);
            o.writeFloat(0.25f); o.writeFloat(-0.1f);
            o.writeFloat(1.75f);
            o.writeInt(3);
            o.writeInt(com.mineclone.world.GameMode.SURVIVAL.ordinal());
            o.writeInt(36);
            for (int i = 0; i < 36; i++) {
                if (i == 0)
                    writeLegacyStack(o, 1, BlockType.COBBLE.ordinal(), 17);   // блок
                else if (i == 1)
                    writeLegacyStack(o, 2, 2, 77);                            // iron_pickaxe, износ 77
                else if (i == 2)
                    writeLegacyStack(o, 3, 1, 5);                             // porkchop x5
                else
                    writeLegacyStack(o, 0, 0, 0);
            }
        }

        SaveManager sm = new SaveManager(saves);
        LevelData d = sm.loadLevel("old");
        assertTrue("v9 level loaded", d != null);
        assertEq("name", "Старый мир", d.name);
        assertEq("seed", 4242L, d.seed);
        assertEq("hunger", 11f, d.hunger);
        assertEq("block slot", Items.get().forBlock(BlockType.COBBLE), d.inventory[0].item);
        assertEq("block count", 17, d.inventory[0].count);
        assertEq("tool slot", Items.get().require("iron_pickaxe"), d.inventory[1].item);
        assertEq("tool wear became a component", 77, d.inventory[1].damage());
        assertEq("food slot", Items.get().require("porkchop"), d.inventory[2].item);
        assertEq("food count", 5, d.inventory[2].count);
        assertTrue("the rest is empty", d.inventory[3] == null);
        assertEq("nothing was pending", 0, d.pending.length);

        // Пересохранение поднимает файл до v10, ничего не теряя.
        sm.saveLevel("old", d);
        LevelData again = sm.loadLevel("old");
        assertEq("tool survived the upgrade", 77, again.inventory[1].damage());
        assertEq("food survived the upgrade", 5, again.inventory[2].count);
    }

    private static void testChunkV5Migration() throws Exception {
        java.io.File root = freshRoot();
        java.io.File saves = new java.io.File(root, "saves");
        byte[] blocks = new byte[SaveFormat.CHUNK_VOLUME];
        byte[] meta = new byte[SaveFormat.CHUNK_VOLUME];
        blocks[0] = (byte) BlockType.CHEST.ordinal();
        try (java.io.DataOutputStream o = gzip(new java.io.File(saves, "old/chunks/c.0.0.dat"))) {
            o.writeInt(SaveFormat.MAGIC);
            o.writeInt(5);
            com.mineclone.save.RunLengthCodec.write(o, blocks);
            com.mineclone.save.RunLengthCodec.write(o, meta);
            o.writeInt(1);                              // сундуки
            o.writeInt(0);
            o.writeByte(3);
            writeLegacyStack(o, 1, BlockType.PLANKS.ordinal(), 12);
            writeLegacyStack(o, 0, 0, 0);
            writeLegacyStack(o, 2, 5, 9);               // stone_axe, износ 9
            o.writeInt(1);                              // печи
            o.writeInt(7);
            writeLegacyStack(o, 3, 0, 4);               // beef x4
            writeLegacyStack(o, 1, BlockType.COAL_ORE.ordinal(), 2);
            writeLegacyStack(o, 3, 4, 1);               // cooked_beef x1
            o.writeFloat(3.5f); o.writeFloat(8f); o.writeFloat(1.25f);
            o.writeInt(1);                              // предметы на земле
            writeLegacyStack(o, 1, BlockType.STONE.ordinal(), 30);
            o.writeFloat(1f); o.writeFloat(64f); o.writeFloat(2f); o.writeFloat(5f);
        }

        SaveManager sm = new SaveManager(saves);
        ChunkSnapshot c = sm.loadChunk("old", 0, 0);
        assertTrue("v5 chunk loaded", c != null);
        assertEq("chest kept", 1, c.chests.size());
        ItemStack[] chest = c.chests.get(0);
        assertEq("chest planks", Items.get().require("planks"), chest[0].item);
        assertEq("chest planks count", 12, chest[0].count);
        assertTrue("chest gap", chest[1] == null);
        assertEq("chest axe", Items.get().require("stone_axe"), chest[2].item);
        assertEq("chest axe wear", 9, chest[2].damage());

        com.mineclone.world.Furnace f = c.furnaces.get(7);
        assertTrue("furnace kept", f != null);
        assertEq("furnace input", Items.get().require("beef"), f.input.item);
        assertEq("furnace fuel", Items.get().require("coal_ore"), f.fuel.item);
        assertEq("furnace output", Items.get().require("cooked_beef"), f.output.item);
        assertEq("furnace burn", 3.5f, f.burnLeft);

        assertEq("one item on the ground", 1, c.items.size());
        assertEq("its stack", 30, c.items.get(0).stack.count);
        assertEq("its age", 5f, c.items.get(0).age);

        // Обратная запись — уже шестой версией, и она читается так же.
        sm.saveChunkAsync("old", c);
        sm.flushAndAwait();
        ChunkSnapshot back = sm.loadChunk("old", 0, 0);
        assertEq("axe wear survived the upgrade", 9, back.chests.get(0)[2].damage());
        assertEq("furnace still cooking", 1.25f, back.furnaces.get(7).cook);
    }

    private static void testLevelV10RoundTrip() throws Exception {
        java.io.File saves = new java.io.File(freshRoot(), "saves");
        SaveManager sm = new SaveManager(saves);

        ItemStack[] inv = LevelData.emptyInventory();
        inv[0] = ItemStack.of("iron_pickaxe").set(Components.DAMAGE, 42)
                .set(Components.CUSTOM_NAME, "Кайло");
        inv[1] = ItemStack.of("cobblestone", 40)
                .set(Components.LORE, List.of("из первой шахты"));
        // Предмет, которого нет в реестре: пишем его чужой стопкой, чтобы
        // проверить, что сейв с ним открывается, а не обнуляется.
        Item ghost = Items.get().missing(ResourceId.of("mymod:ruby"));
        inv[2] = new ItemStack(ghost, 3);
        ItemStack[] pending = { ItemStack.of("planks", 7) };

        LevelData d = new LevelData("Мир", 5L, 1, 2, 3, 4, 5, 6, 0.5f, 0.25f, 1f, 2,
                inv, com.mineclone.world.GameMode.SURVIVAL, 11L, 9f, 8f, pending, null);
        sm.saveLevel("w", d);
        LevelData back = sm.loadLevel("w");
        assertTrue("loaded", back != null);
        assertEq("tool item", Items.get().require("iron_pickaxe"), back.inventory[0].item);
        assertEq("tool wear", 42, back.inventory[0].damage());
        assertEq("tool name", "Кайло", back.inventory[0].displayName());
        assertEq("lore", List.of("из первой шахты"), back.inventory[1].get(Components.LORE));
        assertEq("missing item kept its id", "mymod:ruby", back.inventory[2].item.id.toString());
        assertTrue("and is marked missing", back.inventory[2].item.missing);
        assertEq("missing count", 3, back.inventory[2].count);
        assertEq("pending came back", 1, back.pending.length);
        assertEq("pending count", 7, back.pending[0].count);
        assertEq("hunger", 8f, back.hunger);
    }

    private static void testUnknownSections() throws Exception {
        java.io.File saves = new java.io.File(freshRoot(), "saves");
        SaveManager sm = new SaveManager(saves);
        byte[] alien = { 9, 8, 7, 6, 5 };
        java.util.LinkedHashMap<String, byte[]> extra = new java.util.LinkedHashMap<>();
        extra.put("futuremod:armour", alien);

        LevelData d = new LevelData("Мир", 1L, 0, 64, 0, 0, 64, 0, 0f, 0f, 0f, 0,
                LevelData.emptyInventory(), com.mineclone.world.GameMode.CREATIVE, 0L, 20f, 20f,
                null, extra);
        sm.saveLevel("w", d);
        LevelData back = sm.loadLevel("w");
        assertTrue("section survived", back.extraSections.containsKey("futuremod:armour"));
        assertTrue("byte for byte",
                java.util.Arrays.equals(alien, back.extraSections.get("futuremod:armour")));

        // И переживает ещё одну запись: сборка, которая секцию не понимает,
        // не имеет права стереть её вторым сохранением.
        sm.saveLevel("w", back);
        LevelData twice = sm.loadLevel("w");
        assertTrue("and a second save",
                java.util.Arrays.equals(alien, twice.extraSections.get("futuremod:armour")));
    }

    private static void testOptionsV5() throws Exception {
        java.io.File root = freshRoot();
        java.io.File saves = new java.io.File(root, "saves");
        saves.mkdirs();
        try (java.io.DataOutputStream o = gzip(new java.io.File(root, "options.dat"))) {
            o.writeInt(SaveFormat.MAGIC);
            o.writeInt(5);
            o.writeInt(8); o.writeInt(90); o.writeFloat(0.5f); o.writeFloat(0.6f);
            o.writeInt(120); o.writeBoolean(false); o.writeBoolean(true); o.writeBoolean(false);
            o.writeFloat(1.5f); o.writeBoolean(true); o.writeFloat(0.3f); o.writeFloat(0.4f);
            o.writeInt(2); o.writeInt(2);
            o.writeInt(0);   // пустая раскладка — значения по умолчанию
        }
        Options opts = new SaveManager(saves).loadOptions();
        assertEq("render radius", 8, opts.renderRadius);
        assertEq("shader quality", 2, opts.shaderQuality);
        assertTrue("advanced tooltips default off", !opts.advancedTooltips);
        assertTrue("recipe book default closed", !opts.recipeBookOpen);
        assertTrue("craftable filter default off", !opts.recipeBookCraftable);
        assertEq("category defaults to all", "all", opts.recipeBookCategory);
        assertEq("sort mode defaults to zero", 0, opts.sortMode);
    }

    private static void testOptionsV6() throws Exception {
        java.io.File saves = new java.io.File(freshRoot(), "saves");
        saves.mkdirs();
        SaveManager sm = new SaveManager(saves);
        Options in = new Options(7, 80, 1f, 1f, 60, true, false, true, 1f, false, 1f, 1f, 1, 2,
                null, true, true, false, "building", 3);
        sm.saveOptions(in);
        Options out = sm.loadOptions();
        assertTrue("advanced tooltips", out.advancedTooltips);
        assertTrue("recipe book open", out.recipeBookOpen);
        assertTrue("craftable filter off", !out.recipeBookCraftable);
        assertEq("category", "building", out.recipeBookCategory);
        assertEq("sort mode", 3, out.sortMode);
        assertEq("older fields intact", 7, out.renderRadius);
        assertEq("and the shader quality", 2, out.shaderQuality);
    }

    // ---------------------------------------------------------------- стопки

    private static void testStackMerging() {
        ItemStack a = ItemStack.of("cobblestone", 10);
        ItemStack b = ItemStack.of("cobblestone", 10);
        assertTrue("same item stacks", a.stacksWith(b));
        b.set(Components.CUSTOM_NAME, "Особый булыжник");
        assertTrue("a named stack keeps to itself", !a.stacksWith(b));
        assertTrue("and the other way round", !b.stacksWith(a));
        b.set(Components.CUSTOM_NAME, null);
        assertTrue("removing it lets them merge again", a.stacksWith(b));
        assertTrue("another item never stacks", !a.stacksWith(ItemStack.of("stone", 1)));
        // Изнашиваемое не стопкуется вовсе: иначе два кайла с разным износом
        // слились бы в одно и одна из прочностей пропала бы.
        ItemStack pick = ItemStack.of("iron_pickaxe");
        assertTrue("tools never stack", !pick.stacksWith(ItemStack.of("iron_pickaxe")));
        assertEq("tools do not stack at all", 1, pick.maxStack());
    }

    private static void testUnbreakable() {
        ItemStack pick = ItemStack.of("wooden_pickaxe");
        pick.set(Components.UNBREAKABLE, true);
        for (int i = 0; i < 500; i++)
            assertTrue("never breaks", !pick.wear());
        assertEq("and never takes damage", 0, pick.damage());
        assertEq("so it stays whole", 1f, pick.condition());

        ItemStack plain = ItemStack.of("wooden_pickaxe");
        int uses = 0;
        while (!plain.wear() && uses < 10000)
            uses++;
        assertEq("a plain one lasts its durability", plain.item.durability - 1, uses);
        // Блок износом не интересуется даже с компонентом прочности.
        assertTrue("blocks never wear", !ItemStack.of("stone", 1).wear());
    }

    private static void testCustomName() {
        ItemStack s = ItemStack.of("stone", 1);
        assertEq("plain name comes from the registry", "Камень", s.displayName());
        s.set(Components.CUSTOM_NAME, "Первый камень");
        assertEq("custom name wins", "Первый камень", s.displayName());
        assertEq("the item itself is untouched", "Камень", s.item.name);
        s.set(Components.CUSTOM_NAME, null);
        assertEq("and it comes back", "Камень", s.displayName());
    }

    private static void testInventoryComponents() {
        Inventory inv = new Inventory();
        ItemStack plain = ItemStack.of("cobblestone", 30);
        ItemStack named = ItemStack.of("cobblestone", 30).set(Components.CUSTOM_NAME, "Метка");
        assertEq("plain fits", 0, inv.add(plain));
        assertEq("named fits too", 0, inv.add(named));

        int plainSlots = 0, namedSlots = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.get(i);
            if (s == null)
                continue;
            if ("Метка".equals(s.get(Components.CUSTOM_NAME)))
                namedSlots++;
            else
                plainSlots++;
        }
        assertEq("the named one did not merge in", 1, namedSlots);
        assertEq("nor the plain one", 1, plainSlots);

        // Добавление кладёт копию: стопка на руках у вызывающего не должна
        // оказаться той же самой, что легла в слот.
        assertTrue("a copy went in, not the stack itself", inv.get(0) != plain);
        assertEq("and the copy carries the count", 30, inv.get(0).count);
    }

    private static void testContentHash() {
        Inventory inv = new Inventory();
        inv.set(0, ItemStack.of("stone", 4));
        int before = inv.contentHash();
        inv.get(0).count = 5;
        assertTrue("count shows up in the hash", before != inv.contentHash());
        inv.get(0).count = 4;
        assertEq("and back", before, inv.contentHash());
        inv.get(0).set(Components.CUSTOM_NAME, "Метка");
        assertTrue("components show up too", before != inv.contentHash());
        inv.set(0, ItemStack.of("dirt", 4));
        assertTrue("so does the item", before != inv.contentHash());
    }

    // ----------------------------------------------------------- компоненты

    private static void testComponentOrder() {
        ItemComponents a = ItemComponents.EMPTY
                .with(Components.DAMAGE, 7)
                .with(Components.CUSTOM_NAME, "Кайло")
                .with(Components.UNBREAKABLE, true);
        ItemComponents b = ItemComponents.EMPTY
                .with(Components.UNBREAKABLE, true)
                .with(Components.DAMAGE, 7)
                .with(Components.CUSTOM_NAME, "Кайло");
        assertEq("equal regardless of order", a, b);
        assertEq("hash matches", a.hashCode(), b.hashCode());
        assertEq("size", 3, a.size());
        assertTrue("empty is empty", ItemComponents.EMPTY.isEmpty());
        assertTrue("with does not touch the original", ItemComponents.EMPTY.isEmpty());
        assertTrue("a damage of another value differs",
                !a.equals(a.with(Components.DAMAGE, 8)));
    }

    private static void testComponentCodecs() throws Exception {
        // Образец на каждый зарегистрированный тип. Новый тип без образца
        // валит тест — иначе он уехал бы в сейв непроверенным.
        Map<String, Object> samples = new java.util.LinkedHashMap<>();
        samples.put("damage", 123);
        samples.put("unbreakable", Boolean.TRUE);
        samples.put("custom_name", "Кайло гнома");
        samples.put("lore", List.of("строка раз", "строка два"));
        samples.put("block_state", new BlockState((byte) 5,
                java.util.Arrays.asList(new ItemStack(BlockType.STONE, 5), null,
                        new ItemStack(BlockType.PLANKS, 12)),
                new FurnaceState(new ItemStack(BlockType.SAND, 3), new ItemStack(BlockType.COBBLE, 1),
                        new ItemStack(BlockType.GLASS, 2), 4.5f, 8f, 1.25f)));

        ItemComponents all = ItemComponents.EMPTY;
        for (ComponentType<?> t : Components.all()) {
            Object v = samples.get(t.id);
            assertTrue("sample for " + t.id, v != null);
            all = withRaw(all, t, v);
        }
        assertEq("every known type has a sample", Components.all().size(), samples.size());

        ItemComponents back = roundTrip(all);
        assertEq("round trip is equal", all, back);
        assertEq("damage", 123, back.get(Components.DAMAGE));
        assertEq("name", "Кайло гнома", back.get(Components.CUSTOM_NAME));
        assertEq("lore", List.of("строка раз", "строка два"), back.get(Components.LORE));
        BlockState st = back.get(Components.BLOCK_STATE);
        assertEq("meta", (byte) 5, st.meta());
        assertEq("chest size", 3, st.chest().size());
        assertTrue("empty chest slot stays empty", st.chest().get(1) == null);
        assertEq("chest stack count", 12, st.chest().get(2).count);
        assertEq("furnace burn", 4.5f, st.furnace().burnLeft());

        // Слишком длинное описание режется, а не ломает запись.
        ItemComponents lore = ItemComponents.EMPTY.with(Components.LORE,
                List.of("1", "2", "3", "4", "5", "6"));
        assertEq("lore is capped", 4, lore.get(Components.LORE).size());
    }

    @SuppressWarnings("unchecked")
    private static <T> ItemComponents withRaw(ItemComponents c, ComponentType<T> t, Object v) {
        return c.with(t, (T) v);
    }

    private static ItemComponents roundTrip(ItemComponents c) throws java.io.IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(buf)) {
            c.write(out);
        }
        try (java.io.DataInputStream in =
                new java.io.DataInputStream(new java.io.ByteArrayInputStream(buf.toByteArray()))) {
            return ItemComponents.read(in);
        }
    }

    private static void testUnknownComponents() throws Exception {
        byte[] payload = { 1, 2, 3, (byte) 200, 0, 0, 77 };
        ItemComponents c = ItemComponents.EMPTY
                .with(Components.DAMAGE, 4)
                .withRaw("mymod:enchantments", payload);
        ItemComponents back = roundTrip(c);
        assertEq("known part survived", 4, back.get(Components.DAMAGE));
        assertTrue("unknown id kept", back.ids().contains("mymod:enchantments"));
        assertTrue("bytes kept", java.util.Arrays.equals(payload, back.raw("mymod:enchantments")));
        assertEq("equal after a round trip", c, back);
        // Пересохранение чужого компонента не имеет права его портить.
        assertTrue("stable across two round trips",
                java.util.Arrays.equals(payload, roundTrip(back).raw("mymod:enchantments")));
    }

    private static void testComponentRemoval() {
        ItemComponents c = ItemComponents.EMPTY
                .with(Components.DAMAGE, 3)
                .with(Components.UNBREAKABLE, true);
        assertEq("null removes", 1, c.with(Components.DAMAGE, null).size());
        assertTrue("value is gone", c.with(Components.DAMAGE, null).get(Components.DAMAGE) == null);
        assertEq("without removes", 1, c.without(Components.UNBREAKABLE).size());
        assertTrue("removing what is not there changes nothing",
                c.without(Components.CUSTOM_NAME) == c);
        assertTrue("emptied is EMPTY",
                c.without(Components.DAMAGE).without(Components.UNBREAKABLE) == ItemComponents.EMPTY);
    }

    // -------------------------------------------------------------- реестр

    /** Блоки без предмета: существуют внутри мира, но в руки не берутся. */
    private static final java.util.Set<BlockType> TECHNICAL =
            java.util.Set.of(BlockType.AIR, BlockType.WATER_FLOW, BlockType.DOOR_OPEN);

    private static void testBlockItems() {
        ItemRegistry reg = Items.get();
        Categories cats = reg.categories();
        for (BlockType b : BlockType.VALUES) {
            if (TECHNICAL.contains(b))
                continue;
            Item item = reg.forBlock(b);
            assertTrue("item for " + b, item != null);
            assertEq("block round trip " + b, b, item.block);
            assertTrue("name of " + b, !item.name.isBlank());
            assertTrue("category of " + b + " is known", cats.has(item.category));
        }
        int blockItems = 0;
        for (Item i : reg.all())
            if (i.block != null)
                blockItems++;
        assertEq("one item per placeable block", BlockType.VALUES.length - TECHNICAL.size(), blockItems);
    }

    private static void testTechnicalBlocks() {
        ItemRegistry reg = Items.get();
        for (BlockType b : TECHNICAL)
            assertTrue("no item for " + b, reg.forBlock(b) == null);
    }

    private static void testToolNumbers() {
        // Таблица прежних значений ToolType. Числа стоят прямо здесь, а не
        // берутся из кода: перенос в данные не имел права тронуть баланс, и
        // сверять данные с ними же самими смысла нет.
        Object[][] table = {
                { "wooden_pickaxe",  ToolClass.PICKAXE, 1, 2.2f,  60 },
                { "stone_pickaxe",   ToolClass.PICKAXE, 2, 4.0f, 132 },
                { "iron_pickaxe",    ToolClass.PICKAXE, 3, 6.5f, 251 },
                { "diamond_pickaxe", ToolClass.PICKAXE, 4, 9.0f, 900 },
                { "wooden_axe",      ToolClass.AXE,     1, 2.2f,  60 },
                { "stone_axe",       ToolClass.AXE,     2, 4.0f, 132 },
                { "iron_axe",        ToolClass.AXE,     3, 6.5f, 251 },
                { "diamond_axe",     ToolClass.AXE,     4, 9.0f, 900 },
                { "wooden_shovel",   ToolClass.SHOVEL,  1, 2.2f,  60 },
                { "stone_shovel",    ToolClass.SHOVEL,  2, 4.0f, 132 },
                { "iron_shovel",     ToolClass.SHOVEL,  3, 6.5f, 251 },
                { "diamond_shovel",  ToolClass.SHOVEL,  4, 9.0f, 900 },
        };
        ItemRegistry reg = Items.get();
        for (Object[] row : table) {
            Item t = reg.require((String) row[0]);
            assertTrue("tool spec " + row[0], t.tool != null);
            assertEq("class " + row[0], row[1], t.tool.toolClass());
            assertEq("level " + row[0], row[2], t.tool.level());
            assertEq("speed " + row[0], row[3], t.tool.speed());
            assertEq("durability " + row[0], row[4], t.durability);
            assertEq("tools do not stack " + row[0], 1, t.maxStack);
            assertTrue("no fuel from a tool " + row[0], t.fuelSeconds == 0f);
        }
        assertTrue("pickaxe suits stone", reg.require("iron_pickaxe").tool.suits(BlockType.STONE));
        assertTrue("pickaxe does not suit dirt", !reg.require("iron_pickaxe").tool.suits(BlockType.DIRT));
    }

    private static void testFoodNumbers() {
        Object[][] table = {
                { "beef", 3 }, { "porkchop", 3 }, { "chicken", 2 }, { "mutton", 2 },
                { "cooked_beef", 7 }, { "cooked_porkchop", 7 },
                { "cooked_chicken", 5 }, { "cooked_mutton", 5 },
        };
        ItemRegistry reg = Items.get();
        for (Object[] row : table) {
            Item f = reg.require((String) row[0]);
            assertTrue("food spec " + row[0], f.food != null);
            assertEq("nutrition " + row[0], row[1], f.food.nutrition());
        }
    }

    private static void testComputedTags() {
        ItemRegistry reg = Items.get();
        assertTrue("torch is a light source", reg.require("torch").hasTag(ResourceId.of("light")));
        assertTrue("stone is not a light source", !reg.require("stone").hasTag(ResourceId.of("light")));
        assertTrue("pickaxe is durable", reg.require("iron_pickaxe").hasTag(ResourceId.of("durable")));
        assertTrue("coal ore is fuel", reg.require("coal_ore").hasTag(ResourceId.of("fuel")));
        assertTrue("stone is not fuel", !reg.require("stone").hasTag(ResourceId.of("fuel")));
        assertTrue("beef is food", reg.require("beef").hasTag(ResourceId.of("food")));
        assertTrue("planks burn", reg.require("planks").hasTag(ResourceId.of("flammable")));
        // Вычисляемый тег обязан попадать и в состав: иначе его не перечислить.
        assertTrue("computed tag is listed",
                !reg.tags().members(ResourceId.of("light")).isEmpty());
    }

    private static void testTagSearch() {
        TagRegistry tags = Items.get().tags();
        assertTrue("by id prefix", named(tags.find("wooden_to"), "mineclone:wooden_tools"));
        assertTrue("by russian name", named(tags.find("дерев"), "mineclone:wood"));
        assertTrue("by alias", named(tags.find("древес"), "mineclone:wood"));
        assertTrue("case is ignored", named(tags.find("ДЕРЕВ"), "mineclone:wood"));
        assertTrue("yo reads as ye", named(tags.find("брев"), "mineclone:logs"));
        assertTrue("ye reads as yo", named(tags.find("брёв"), "mineclone:logs"));
        assertTrue("nothing matches", tags.find("zzz").isEmpty());
    }

    private static boolean named(List<Tag> found, String id) {
        for (Tag t : found)
            if (t.id().toString().equals(id))
                return true;
        return false;
    }

    private static void testTagIncludes() throws Exception {
        ItemRegistry reg = Items.get();
        java.util.Set<Item> wood = reg.tags().members(ResourceId.of("wood"));
        assertTrue("include pulled the wooden pickaxe in", wood.contains(reg.require("wooden_pickaxe")));
        assertTrue("direct value", wood.contains(reg.require("planks")));
        assertTrue("stone stayed out", !wood.contains(reg.require("stone")));
        assertTrue("wooden pickaxe knows the tag", reg.require("wooden_pickaxe").hasTag(ResourceId.of("wood")));

        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("tagcycle");
        write(root.resolve("mineclone/categories.json"), CATEGORIES_MISC);
        write(root.resolve("mineclone/items/a.json"), STONE_ITEM);
        write(root.resolve("mineclone/tags/items/a.json"), "{\"name\": \"a\", \"values\": [\"#mineclone:b\"]}");
        write(root.resolve("mineclone/tags/items/b.json"), "{\"name\": \"b\", \"values\": [\"#mineclone:a\"]}");
        try {
            ItemRegistry.load(new DataPack(root));
            throw new AssertionError("a tag cycle loaded");
        } catch (JsonException expected) {
            assertTrue("cycle is named: " + expected.getMessage(),
                    expected.getMessage().contains("cycle"));
        }
    }

    private static final String CATEGORIES_MISC =
            "{\"categories\": [{\"id\": \"misc\", \"name\": \"m\"}]}";
    private static final String STONE_ITEM =
            "{\"stone\": {\"name\": \"s\", \"category\": \"misc\", \"block\": \"stone\"}}";

    private static void testRegistryStrict() throws Exception {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("strict");
        write(root.resolve("mineclone/categories.json"), CATEGORIES_MISC);
        write(root.resolve("mineclone/items/a.json"),
                "{\"stone\": {\"name\": \"s\", \"category\": \"misc\", \"block\": \"stone\", \"masss\": 1}}");
        try {
            ItemRegistry.load(new DataPack(root));
            throw new AssertionError("a typo loaded");
        } catch (JsonException e) {
            assertTrue("file named: " + e.getMessage(), e.getMessage().contains("mineclone/items/a.json"));
            assertTrue("key path named: " + e.getMessage(), e.getMessage().contains("stone.masss"));
        }

        java.nio.file.Path bad = java.nio.file.Files.createTempDirectory("strict2");
        write(bad.resolve("mineclone/categories.json"), CATEGORIES_MISC);
        write(bad.resolve("mineclone/items/a.json"),
                "{\"stone\": {\"name\": \"s\", \"category\": \"nope\", \"block\": \"stone\"}}");
        try {
            ItemRegistry.load(new DataPack(bad));
            throw new AssertionError("an unknown category loaded");
        } catch (JsonException e) {
            assertTrue("category named: " + e.getMessage(), e.getMessage().contains("nope"));
        }
    }

    private static void testMissingItems() {
        ItemRegistry reg = Items.get();
        assertTrue("absent id is null", reg.get("mineclone:no_such_item") == null);
        Item a = reg.missing(ResourceId.of("no_such_item"));
        Item b = reg.missing(ResourceId.of("no_such_item"));
        assertTrue("placeholder is cached", a == b);
        assertTrue("placeholder is marked", a.missing);
        assertTrue("placeholder is hidden from creative", a.hidden);
        assertEq("placeholder stacks like a block", 64, a.maxStack);
        assertTrue("placeholder has no block", a.block == null);
        assertEq("placeholder keeps its id", "mineclone:no_such_item", a.id.toString());
    }

    // ------------------------------------------------------------------ JSON

    @SuppressWarnings("unchecked")
    private static void testJsonParses() {
        Object root = Json.parse("{\"a\": [1, 2.5, -3e2, true, false, null], "
                + "\"b\": {\"c\": \"q\\\"\\\\\\/\\n\\u0416\"}}", "t.json");
        Map<String, Object> m = (Map<String, Object>) root;
        List<Object> a = (List<Object>) m.get("a");
        assertEq("int as double", 1.0, a.get(0));
        assertEq("fraction", 2.5, a.get(1));
        assertEq("exponent", -300.0, a.get(2));
        assertEq("true", Boolean.TRUE, a.get(3));
        assertEq("false", Boolean.FALSE, a.get(4));
        assertTrue("null kept", a.get(5) == null && a.size() == 6);
        Map<String, Object> b = (Map<String, Object>) m.get("b");
        assertEq("escapes", "q\"\\/\nЖ", b.get("c"));
        assertEq("key order kept", List.of("a", "b"), List.copyOf(m.keySet()));
    }

    @SuppressWarnings("unchecked")
    private static void testJsonLenient() {
        String text = """
                // шапка файла
                {
                  "items": [1, 2, 3,], // хвост массива
                  "name": "a // не комментарий", // а это комментарий
                }
                """;
        Map<String, Object> m = (Map<String, Object>) Json.parse(text, "c.json");
        assertEq("trailing comma in array", 3, ((List<Object>) m.get("items")).size());
        assertEq("slashes inside a string stay", "a // не комментарий", m.get("name"));
    }

    private static void testJsonErrors() {
        String text = "{\n  \"a\": 1,\n  \"b\": tru\n}";
        try {
            Json.parse(text, "items/bad.json");
            throw new AssertionError("expected a parse error");
        } catch (JsonException e) {
            assertEq("source", "items/bad.json", e.source);
            assertEq("line", 3, e.line);
            assertEq("column", 8, e.column);
            assertTrue("message names the place: " + e.getMessage(),
                    e.getMessage().startsWith("items/bad.json:3:8:"));
        }
        try {
            Json.parse("{\"a\": 1} 2", "x.json");
            throw new AssertionError("expected trailing garbage to fail");
        } catch (JsonException e) {
            assertEq("garbage line", 1, e.line);
        }
    }

    private static void testJsonAccessors() {
        JsonObject o = Json.parseObject("{\"tool\": {\"level\": 3, \"speed\": 6.5, \"name\": \"x\"},"
                + " \"tags\": [\"a\", \"b\"]}", "items/tools.json", "iron_pickaxe");
        JsonObject tool = o.object("tool");
        assertEq("int", 3, tool.integer("level"));
        assertEq("float", 6.5f, tool.number("speed"));
        assertEq("default int", 7, tool.integer("missing", 7));
        assertEq("strings", List.of("a", "b"), o.strings("tags"));
        assertTrue("null object for absent key", o.objectOrNull("armor") == null);
        try {
            tool.integer("speed");
            throw new AssertionError("6.5 is not an integer");
        } catch (JsonException e) {
            assertTrue("path in message: " + e.getMessage(),
                    e.getMessage().contains("items/tools.json") && e.getMessage().contains("iron_pickaxe.tool.speed"));
        }
        try {
            tool.string("name2");
            throw new AssertionError("missing required key");
        } catch (JsonException e) {
            assertTrue("missing key named: " + e.getMessage(), e.getMessage().contains("iron_pickaxe.tool.name2"));
        }
    }

    private static void testJsonAllowOnly() {
        JsonObject o = Json.parseObject("{\"name\": \"a\", \"categroy\": \"tools\"}", "items/t.json", "pick");
        try {
            o.allowOnly("name", "category");
            throw new AssertionError("typo must be rejected");
        } catch (JsonException e) {
            assertTrue("typo named: " + e.getMessage(), e.getMessage().contains("pick.categroy"));
        }
        o.allowOnly("name", "categroy");
    }

    // ----------------------------------------------------------- ids and pack

    private static void testResourceIdParse() {
        ResourceId a = ResourceId.parse("stone", "mineclone");
        assertEq("default namespace", new ResourceId("mineclone", "stone"), a);
        assertEq("printed form", "mineclone:stone", a.toString());
        ResourceId b = ResourceId.parse("mymod:blocks/ruby_ore", "mineclone");
        assertEq("namespace", "mymod", b.namespace());
        assertEq("path", "blocks/ruby_ore", b.path());
    }

    private static void testResourceIdRejects() {
        for (String bad : new String[] { "Stone", "my mod:x", "mineclone:", ":stone", "a:b:c", "" }) {
            try {
                ResourceId.parse(bad, "mineclone");
                throw new AssertionError("accepted " + bad);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }

    private static void testDataPackListing() throws Exception {
        java.nio.file.Path root = java.nio.file.Files.createTempDirectory("pack");
        write(root.resolve("mineclone/items/tools.json"), "{\"items\": {}}");
        write(root.resolve("mineclone/items/blocks.json"), "{\"items\": {}}");
        write(root.resolve("mineclone/items/sub/extra.json"), "{\"items\": {}}");
        write(root.resolve("aaa/items/one.json"), "{\"items\": {}}");
        write(root.resolve("mineclone/tags/items/logs.json"), "{\"values\": []}");
        write(root.resolve("mineclone/categories.json"), "{\"categories\": []}");
        DataPack pack = new DataPack(root);
        List<String> seen = new java.util.ArrayList<>();
        for (DataPack.Entry e : pack.files("items"))
            seen.add(e.namespace() + ":" + e.relPath());
        assertEq("sorted by namespace then path",
                List.of("aaa:one.json", "mineclone:blocks.json", "mineclone:sub/extra.json", "mineclone:tools.json"),
                seen);
        assertEq("nested kind", 1, pack.files("tags/items").size());
        assertTrue("root file", pack.root("mineclone", "categories.json") != null);
        assertTrue("absent root file", pack.root("mineclone", "nope.json") == null);
        assertEq("source names the file", "mineclone/items/tools.json",
                pack.files("items").get(3).json().source());
    }

    private static void write(java.nio.file.Path p, String text) throws java.io.IOException {
        java.nio.file.Files.createDirectories(p.getParent());
        java.nio.file.Files.writeString(p, text);
    }

    // --------------------------------------------------------------- helpers

    static void assertTrue(String what, boolean cond) {
        if (!cond) throw new AssertionError("expected true: " + what);
    }

    static void assertEq(String what, Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual))
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
    }
}
