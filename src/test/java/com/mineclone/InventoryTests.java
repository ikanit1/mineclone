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
import com.mineclone.ui.container.ArrayStorage;
import com.mineclone.ui.container.ContainerMenu;
import com.mineclone.ui.container.DragSplit;
import com.mineclone.ui.container.SlotGroup;
import com.mineclone.ui.container.SlotRef;
import com.mineclone.ui.container.SlotRole;
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
        r.run("box faces of a cube match the old iso cube at 45 degrees",
                InventoryTests::testCubeFaces);
        r.run("stairs icon has more faces than a cube", InventoryTests::testStairsFaces);
        r.run("box faces write into the caller buffer without allocating",
                InventoryTests::testFacesBuffer);
        r.run("left and right clicks keep the old inventory rules", InventoryTests::testMenuClicks);
        r.run("take-only slots pull into a matching cursor", InventoryTests::testTakeOnlySlots);
        r.run("drag split shares evenly and keeps the remainder on the cursor",
                InventoryTests::testDragSplitEven);
        r.run("right drag places one per slot", InventoryTests::testDragSplitRight);
        r.run("drag stops adding slots past the cursor count", InventoryTests::testDragSlotLimit);
        r.run("a one-slot drag is a plain click", InventoryTests::testOneSlotDrag);
        r.run("shift-click fills partial stacks before empty slots", InventoryTests::testShiftOrder);
        r.run("shift-click follows the window routes", InventoryTests::testShiftRoutes);
        r.run("double click collects partial stacks first", InventoryTests::testDoubleClick);
        r.run("number key swaps with the hotbar and respects slot filters",
                InventoryTests::testNumberKey);
        r.run("q drops one and ctrl-q drops the stack", InventoryTests::testDropKeys);
        r.run("creative source clicks add one, middle click gives a full stack",
                InventoryTests::testCreativeSource);
        r.run("a stack dropped on the creative source or trash disappears",
                InventoryTests::testCreativeAndTrashSwallow);
        r.run("closing a window returns the cursor to the player", InventoryTests::testCloseReturns);
        r.run("a critically damped spring does not overshoot", InventoryTests::testSpringCritical);
        r.run("an underdamped spring overshoots a little and settles",
                InventoryTests::testSpringBouncy);
        r.run("spring stays stable at a 50 ms frame", InventoryTests::testSpringLongFrame);
        r.run("tooltip prefers right-below and flips at the right and bottom edges",
                InventoryTests::testTooltipCorners);
        r.run("tooltip is clamped when no corner fits", InventoryTests::testTooltipClamp);
        r.run("advanced tooltip shows id, durability numbers and tags",
                InventoryTests::testAdvancedTooltip);
        r.run("flights hide their target until they land", InventoryTests::testFlights);
    }

    // ------------------------------------------------ пружины и подсказки

    private static void testSpringCritical() {
        com.mineclone.ui.container.Spring s = new com.mineclone.ui.container.Spring(10f, 1f);
        float peak = 0f;
        for (int i = 0; i < 400; i++) {
            s.update(1f, 1f / 120f);
            peak = Math.max(peak, s.value);
        }
        assertTrue("never goes past the target: " + peak, peak <= 1.0005f);
        assertTrue("and gets there", s.settled(1f, 1e-3f));
    }

    private static void testSpringBouncy() {
        com.mineclone.ui.container.Spring s = new com.mineclone.ui.container.Spring(10f, 0.45f);
        float peak = 0f;
        for (int i = 0; i < 400; i++) {
            s.update(1f, 1f / 120f);
            peak = Math.max(peak, s.value);
        }
        assertTrue("overshoots: " + peak, peak > 1.02f);
        assertTrue("but not wildly: " + peak, peak < 1.4f);
        assertTrue("and settles anyway", s.settled(1f, 1e-3f));
    }

    private static void testSpringLongFrame() {
        // Просадка кадра не имеет права раскачать интерфейс: явный шаг в 50 мс
        // на такой частоте расходится, поэтому шаг дробится.
        com.mineclone.ui.container.Spring s = new com.mineclone.ui.container.Spring(18f, 0.6f);
        for (int i = 0; i < 60; i++) {
            s.update(1f, 0.05f);
            assertTrue("stays finite", Float.isFinite(s.value) && Float.isFinite(s.velocity));
            assertTrue("stays in a sane range: " + s.value, Math.abs(s.value) < 3f);
        }
        assertTrue("and still arrives", Math.abs(s.value - 1f) < 0.05f);
    }

    private static void testTooltipCorners() {
        float w = 1280, h = 720, gap = 8, margin = 4;
        var right = com.mineclone.ui.container.TooltipLayout.place(
                100, 100, 40, 40, 200, 80, w, h, gap, margin);
        assertEq("the usual corner", com.mineclone.ui.container.TooltipLayout.Corner.RIGHT_BELOW,
                right.corner());
        assertEq("to the right of the slot", 148f, right.x());
        assertEq("and below it", 148f, right.y());

        var atRight = com.mineclone.ui.container.TooltipLayout.place(
                1200, 100, 40, 40, 200, 80, w, h, gap, margin);
        assertEq("flips left at the right edge",
                com.mineclone.ui.container.TooltipLayout.Corner.LEFT_BELOW, atRight.corner());
        assertEq("and stands left of the slot", 992f, atRight.x());

        var atBottom = com.mineclone.ui.container.TooltipLayout.place(
                100, 680, 40, 40, 200, 80, w, h, gap, margin);
        assertEq("flips up at the bottom edge",
                com.mineclone.ui.container.TooltipLayout.Corner.RIGHT_ABOVE, atBottom.corner());
        assertEq("and stands above the slot", 592f, atBottom.y());

        var corner = com.mineclone.ui.container.TooltipLayout.place(
                1200, 680, 40, 40, 200, 80, w, h, gap, margin);
        assertEq("the far corner takes the last option",
                com.mineclone.ui.container.TooltipLayout.Corner.LEFT_ABOVE, corner.corner());
    }

    private static void testTooltipClamp() {
        // Подсказка шире экрана: показать урезанную лучше, чем никакой.
        var p = com.mineclone.ui.container.TooltipLayout.place(
                50, 50, 20, 20, 400, 400, 300, 200, 8, 4);
        assertTrue("x stays on screen", p.x() >= 4f);
        assertTrue("y stays on screen", p.y() >= 4f);
    }

    private static void testAdvancedTooltip() {
        ItemStack pick = ItemStack.of("iron_pickaxe").set(Components.DAMAGE, 51);
        List<com.mineclone.ui.container.Tooltip.Line> plain =
                com.mineclone.ui.container.Tooltip.lines(pick, false);
        assertEq("plain tooltip is just the name", 1, plain.size());
        assertEq("the name", "Железная кирка", plain.get(0).text());

        List<com.mineclone.ui.container.Tooltip.Line> adv =
                com.mineclone.ui.container.Tooltip.lines(pick, true);
        assertTrue("id is shown", hasLine(adv, "mineclone:iron_pickaxe"));
        assertTrue("durability numbers are shown", hasLine(adv, "Прочность: 200 / 251"));
        assertTrue("tags are shown", anyLineContains(adv, "#pickaxes"));

        ItemStack named = ItemStack.of("stone", 3)
                .set(Components.CUSTOM_NAME, "Первый камень")
                .set(Components.LORE, List.of("из первой шахты"))
                .set(Components.UNBREAKABLE, true);
        List<com.mineclone.ui.container.Tooltip.Line> lines =
                com.mineclone.ui.container.Tooltip.lines(named, false);
        assertEq("custom name first", "Первый камень", lines.get(0).text());
        assertTrue("and it is amber", lines.get(0).rgb()[2] < 0.6f);
        assertTrue("lore follows", hasLine(lines, "из первой шахты"));
        assertTrue("unbreakable is called out", hasLine(lines, "Неразрушимый"));
    }

    private static boolean hasLine(List<com.mineclone.ui.container.Tooltip.Line> lines, String s) {
        for (var l : lines)
            if (l.text().equals(s))
                return true;
        return false;
    }

    private static boolean anyLineContains(List<com.mineclone.ui.container.Tooltip.Line> lines,
            String s) {
        for (var l : lines)
            if (l.text().contains(s))
                return true;
        return false;
    }

    private static void testFlights() {
        ContainerMenu m = twoGroups();
        SlotRef target = at(m, "hotbar", 0);
        com.mineclone.ui.container.ItemFlights flights =
                new com.mineclone.ui.container.ItemFlights();
        assertTrue("nothing hidden at rest", !flights.hides(target));

        flights.launch(ItemStack.of("stone", 4), 10f, 10f, 100f, 200f, target);
        assertTrue("the target hides while the icon is in the air", flights.hides(target));
        assertTrue("another slot is unaffected", !flights.hides(at(m, "hotbar", 1)));

        float[] seen = new float[2];
        flights.update(com.mineclone.ui.container.ItemFlights.TIME * 0.5f);
        flights.forEach((icon, x, y, scale) -> {
            seen[0] = x;
            seen[1] = y;
        });
        assertTrue("halfway it is between the ends", seen[0] > 10f && seen[0] < 100f);
        assertTrue("in both axes", seen[1] > 10f && seen[1] < 200f);

        flights.update(com.mineclone.ui.container.ItemFlights.TIME);
        assertTrue("it lands", flights.isEmpty());
        assertTrue("and the slot shows its icon again", !flights.hides(target));
    }

    // ----------------------------------------------------------- окна

    private static SlotGroup boxGroup(String id, SlotRole role, int size) {
        return new SlotGroup(id, role, new ArrayStorage(new ItemStack[size]), 9);
    }

    /** Окно из двух групп: «хранилище» и хотбар — как у сундука. */
    private static ContainerMenu twoGroups() {
        ContainerMenu m = new ContainerMenu(List.of(
                boxGroup("box", SlotRole.CONTAINER, 9),
                boxGroup("hotbar", SlotRole.HOTBAR, 9)));
        m.route(SlotRole.CONTAINER, SlotRole.HOTBAR);
        m.route(SlotRole.HOTBAR, SlotRole.CONTAINER);
        return m;
    }

    private static SlotRef at(ContainerMenu m, String group, int index) {
        return new SlotRef(m.group(group), index);
    }

    private static void testMenuClicks() {
        ContainerMenu m = twoGroups();
        SlotRef a = at(m, "box", 0), b = at(m, "box", 1);
        a.set(ItemStack.of("cobblestone", 20));

        m.leftClick(a);
        assertEq("left click picks up the whole slot", 20, m.cursor().count);
        assertTrue("and empties it", a.get() == null);

        m.rightClick(b);
        assertEq("right click drops one", 1, b.get().count);
        assertEq("and keeps the rest", 19, m.cursor().count);

        m.leftClick(b);
        assertEq("left click merges the rest", 20, b.get().count);
        assertTrue("cursor is empty", m.cursor() == null);

        m.rightClick(b);
        assertEq("right click on a full slot takes half", 10, m.cursor().count);
        assertEq("and leaves half", 10, b.get().count);

        // Разные предметы меняются местами.
        a.set(ItemStack.of("planks", 3));
        m.leftClick(a);
        assertEq("swap puts the cursor into the slot", 10, a.get().count);
        assertEq("and takes what was there", 3, m.cursor().count);
        assertEq("of the right item", "mineclone:planks", m.cursor().item.id.toString());

        // Нестопкуемое не делится правой кнопкой.
        m.setCursor(null);
        SlotRef tool = at(m, "hotbar", 0);
        tool.set(ItemStack.of("iron_pickaxe"));
        m.rightClick(tool);
        assertEq("a tool comes whole", 1, m.cursor().count);
        assertTrue("and the slot is empty", tool.get() == null);
    }

    private static void testTakeOnlySlots() {
        ContainerMenu m = new ContainerMenu(List.of(
                boxGroup("out", SlotRole.FURNACE_OUTPUT, 1),
                boxGroup("hotbar", SlotRole.HOTBAR, 9)));
        SlotRef out = at(m, "out", 0);
        out.set(ItemStack.of("cooked_beef", 5));

        m.setCursor(ItemStack.of("planks", 2));
        m.leftClick(out);
        assertEq("a foreign cursor cannot be put down", 5, out.get().count);
        assertEq("and keeps what it held", 2, m.cursor().count);

        m.setCursor(ItemStack.of("cooked_beef", 3));
        m.leftClick(out);
        assertEq("a matching cursor pulls the slot in", 8, m.cursor().count);
        assertTrue("and the slot empties", out.get() == null);

        // Пустой рукой забирается как обычно.
        out.set(ItemStack.of("cooked_beef", 4));
        m.setCursor(null);
        m.leftClick(out);
        assertEq("an empty hand takes it all", 4, m.cursor().count);
    }

    private static void testDragSplitEven() {
        int[] add = DragSplit.distribute(8, false, new int[3], new int[] { 64, 64, 64 });
        assertEq("even share", 2, add[0]);
        assertEq("even share", 2, add[1]);
        assertEq("even share", 2, add[2]);
        // Остаток не размазывается: игрок ждёт 2/2/2 и двойку в руке.
        assertEq("remainder stays on the cursor", 2, 8 - (add[0] + add[1] + add[2]));

        int[] capped = DragSplit.distribute(9, false, new int[] { 0, 62, 0 },
                new int[] { 64, 64, 64 });
        assertEq("a nearly full slot takes what fits", 2, capped[1]);
        assertEq("the others take their share", 3, capped[0]);
    }

    private static void testDragSplitRight() {
        int[] add = DragSplit.distribute(5, true, new int[4], new int[] { 64, 64, 64, 64 });
        for (int i = 0; i < 4; i++)
            assertEq("one per slot", 1, add[i]);
        assertEq("one stays on the cursor", 1, 5 - 4);

        ContainerMenu m = twoGroups();
        m.setCursor(ItemStack.of("cobblestone", 5));
        m.beginDrag(true);
        for (int i = 0; i < 3; i++)
            m.dragOver(at(m, "box", i));
        m.endDrag();
        for (int i = 0; i < 3; i++)
            assertEq("slot " + i + " got one", 1, at(m, "box", i).get().count);
        assertEq("the rest stayed in hand", 2, m.cursor().count);
    }

    private static void testDragSlotLimit() {
        ContainerMenu m = twoGroups();
        m.setCursor(ItemStack.of("cobblestone", 2));
        m.beginDrag(false);
        for (int i = 0; i < 5; i++)
            m.dragOver(at(m, "box", i));
        assertEq("only as many slots as there are items", 2, m.dragPreview().size());
        m.endDrag();
        assertEq("first slot", 1, at(m, "box", 0).get().count);
        assertEq("second slot", 1, at(m, "box", 1).get().count);
        assertTrue("third slot untouched", at(m, "box", 2).get() == null);
        assertTrue("cursor is spent", m.cursor() == null);
    }

    private static void testOneSlotDrag() {
        ContainerMenu m = twoGroups();
        m.setCursor(ItemStack.of("cobblestone", 7));
        m.beginDrag(false);
        m.dragOver(at(m, "box", 0));
        m.endDrag();
        // Нажал и отпустил на одном слоте — это обычный клик, а не «раздать
        // поровну на один слот».
        assertEq("the whole stack went down", 7, at(m, "box", 0).get().count);
        assertTrue("and the cursor is empty", m.cursor() == null);
    }

    private static void testShiftOrder() {
        ContainerMenu m = twoGroups();
        at(m, "hotbar", 0).set(ItemStack.of("cobblestone", 60));
        at(m, "hotbar", 4).set(ItemStack.of("cobblestone", 62));
        SlotRef from = at(m, "box", 0);
        from.set(ItemStack.of("cobblestone", 10));

        m.shiftClick(from);
        // Неполные стопки вперёд: иначе окно оставляет россыпь огрызков.
        assertEq("first partial filled up", 64, at(m, "hotbar", 0).get().count);
        assertEq("second partial filled up", 64, at(m, "hotbar", 4).get().count);
        assertEq("the rest went to an empty slot", 4, at(m, "hotbar", 1).get().count);
        assertTrue("source is empty", from.get() == null);
    }

    private static void testShiftRoutes() {
        ContainerMenu m = new ContainerMenu(List.of(
                boxGroup("box", SlotRole.CONTAINER, 9),
                boxGroup("main", SlotRole.MAIN, 9),
                boxGroup("hotbar", SlotRole.HOTBAR, 9)));
        // Из сундука — сначала в основную часть, и только потом в хотбар.
        m.route(SlotRole.CONTAINER, SlotRole.MAIN, SlotRole.HOTBAR);
        m.route(SlotRole.HOTBAR, SlotRole.CONTAINER);

        SlotRef from = at(m, "box", 0);
        from.set(ItemStack.of("stone", 5));
        m.shiftClick(from);
        assertTrue("it went to main", at(m, "main", 0).get() != null);
        assertTrue("and not to the hotbar", at(m, "hotbar", 0).get() == null);

        // Обратно — только в сундук: маршрут хотбара не знает про main.
        m.shiftClick(at(m, "main", 0));
        assertTrue("back into the container", at(m, "box", 0).get() == null);

        SlotRef hot = at(m, "hotbar", 0);
        hot.set(ItemStack.of("planks", 2));
        m.shiftClick(hot);
        assertTrue("hotbar goes to the container", at(m, "box", 0).get() != null);
    }

    private static void testDoubleClick() {
        ContainerMenu m = twoGroups();
        at(m, "box", 0).set(ItemStack.of("cobblestone", 64));
        at(m, "box", 1).set(ItemStack.of("cobblestone", 5));
        at(m, "box", 2).set(ItemStack.of("cobblestone", 7));
        m.setCursor(ItemStack.of("cobblestone", 1));

        m.doubleClick(at(m, "box", 1));
        assertEq("the cursor filled up", 64, m.cursor().count);
        // Сначала неполные: полная стопка тронута последней и лишь настолько,
        // насколько не хватило.
        assertTrue("partial stacks went first",
                at(m, "box", 1).get() == null && at(m, "box", 2).get() == null);
        assertEq("the full stack gave the remainder", 13, at(m, "box", 0).get().count);
    }

    private static void testNumberKey() {
        ContainerMenu m = twoGroups();
        SlotRef box = at(m, "box", 0);
        box.set(ItemStack.of("stone", 12));
        at(m, "hotbar", 2).set(ItemStack.of("planks", 3));

        m.numberKey(box, 2);
        assertEq("slot took what the hotbar had", "mineclone:planks", box.get().item.id.toString());
        assertEq("hotbar took what the slot had", "mineclone:stone",
                at(m, "hotbar", 2).get().item.id.toString());

        // Слот с фильтром не принимает чужое, и обмен просто не происходит.
        ContainerMenu fuelMenu = new ContainerMenu(List.of(
                new SlotGroup("fuel", SlotRole.FURNACE_FUEL,
                        new ArrayStorage(new ItemStack[1],
                                (i, s) -> s.item.fuelSeconds > 0f, null), 1),
                boxGroup("hotbar", SlotRole.HOTBAR, 9)));
        SlotRef fuel = at(fuelMenu, "fuel", 0);
        at(fuelMenu, "hotbar", 0).set(ItemStack.of("stone", 4));
        fuelMenu.numberKey(fuel, 0);
        assertTrue("stone is not fuel and stays put", fuel.get() == null);
        assertTrue("and the hotbar keeps it", at(fuelMenu, "hotbar", 0).get() != null);

        at(fuelMenu, "hotbar", 1).set(ItemStack.of("planks", 4));
        fuelMenu.numberKey(fuel, 1);
        assertTrue("planks burn and go in", fuel.get() != null);
    }

    private static void testDropKeys() {
        ContainerMenu m = twoGroups();
        SlotRef s = at(m, "box", 0);
        s.set(ItemStack.of("cobblestone", 9));

        m.drop(s, false);
        assertEq("one left the slot", 8, s.get().count);
        assertEq("and one is falling", 1, m.dropped().size());
        assertEq("exactly one item", 1, m.dropped().get(0).count);

        m.dropped().clear();
        m.drop(s, true);
        assertTrue("the slot is empty", s.get() == null);
        assertEq("eight are falling", 8, m.dropped().get(0).count);

        m.dropped().clear();
        m.setCursor(ItemStack.of("planks", 4));
        m.dropCursor(false);
        assertEq("one left the cursor", 3, m.cursor().count);
        m.dropCursor(true);
        assertTrue("and the rest went with ctrl", m.cursor() == null);
        assertEq("two throws", 2, m.dropped().size());
    }

    private static void testCreativeSource() {
        ItemStack[] source = { ItemStack.of("stone", 1), ItemStack.of("iron_pickaxe") };
        ContainerMenu m = new ContainerMenu(List.of(
                new SlotGroup("source", SlotRole.CREATIVE_SOURCE, new ArrayStorage(source), 9),
                boxGroup("hotbar", SlotRole.HOTBAR, 9)));
        m.route(SlotRole.CREATIVE_SOURCE, SlotRole.HOTBAR);
        SlotRef stone = at(m, "source", 0);

        m.leftClick(stone);
        assertEq("first click gives one", 1, m.cursor().count);
        m.leftClick(stone);
        assertEq("the next click adds one", 2, m.cursor().count);
        m.rightClick(stone);
        assertEq("right click adds one too", 3, m.cursor().count);
        assertEq("the source is untouched", 1, stone.get().count);

        m.cloneFull(stone);
        assertEq("middle click gives a full stack", 64, m.cursor().count);
        m.cloneFull(at(m, "source", 1));
        assertEq("a tool is still just one", 1, m.cursor().count);

        m.setCursor(null);
        m.shiftClick(stone);
        assertEq("shift sends a full stack to the hotbar", 64, at(m, "hotbar", 0).get().count);
    }

    private static void testCreativeAndTrashSwallow() {
        ItemStack[] source = { ItemStack.of("stone", 1) };
        ContainerMenu m = new ContainerMenu(List.of(
                new SlotGroup("source", SlotRole.CREATIVE_SOURCE, new ArrayStorage(source), 9),
                boxGroup("trash", SlotRole.TRASH, 1),
                boxGroup("hotbar", SlotRole.HOTBAR, 9)));

        m.setCursor(ItemStack.of("planks", 40));
        m.leftClick(at(m, "source", 0));
        assertEq("the source swallowed it and gave its own", "mineclone:stone",
                m.cursor().item.id.toString());
        assertEq("one of it", 1, m.cursor().count);
        assertEq("nothing fell out", 0, m.dropped().size());

        m.setCursor(ItemStack.of("planks", 40));
        m.leftClick(at(m, "trash", 0));
        assertTrue("the bin swallowed it", m.cursor() == null);
        assertEq("and nothing fell out", 0, m.dropped().size());
    }

    private static void testCloseReturns() {
        ContainerMenu m = twoGroups();
        m.setCursor(ItemStack.of("cobblestone", 17));
        List<ItemStack> back = m.closeAll();
        assertEq("one stack came back", 1, back.size());
        assertEq("with everything in it", 17, back.get(0).count);
        assertTrue("and the cursor is empty", m.cursor() == null);
        assertEq("closing an empty window returns nothing", 0, m.closeAll().size());
    }

    // --------------------------------------------------------------- иконки

    private static final int FACE = com.mineclone.render.ItemIcons.FLOATS_PER_FACE;

    private static void testCubeFaces() {
        float[] out = new float[com.mineclone.render.ItemIcons.MAX_FACES * FACE];
        int n = com.mineclone.render.ItemIcons.boxFaces(0f, 0f, 0f, 1f, 1f, 1f, 45f, out, 0);
        // Ровно прежняя иконка: две боковые грани и крышка.
        assertEq("a cube at 45 degrees shows three faces", 3, n);
        assertEq("right side is a side", 1f, out[9]);
        assertEq("left side is a side", 1f, out[FACE + 9]);
        assertEq("the top comes last", 0f, out[2 * FACE + 9]);
        // Яркость — из того же профиля, что печёт мешер.
        near("right side keeps 0.62", 0.62f, out[8]);
        near("left side keeps 0.80", 0.80f, out[FACE + 8]);
        near("the top stays full", 1.00f, out[2 * FACE + 8]);

        // Крышка — ромб: два угла на горизонтали, два по вертикали.
        float half = (float) (0.5 * Math.sqrt(2.0));
        int t = 2 * FACE;
        near("left corner", -half, out[t]);
        near("far corner", 0f, out[t + 2]);
        near("right corner", half, out[t + 4]);
        near("near corner", 0f, out[t + 6]);
    }

    private static void testStairsFaces() {
        float[] cube = new float[com.mineclone.render.ItemIcons.MAX_FACES * FACE];
        float[] stairs = new float[com.mineclone.render.ItemIcons.MAX_FACES * FACE];
        int cubeFaces = com.mineclone.render.ItemIcons.shapeFaces(BlockType.STONE, 45f, cube);
        int stairFaces = com.mineclone.render.ItemIcons.shapeFaces(BlockType.STAIRS, 45f, stairs);
        assertEq("a cube is three faces", 3, cubeFaces);
        assertTrue("stairs need more than a cube", stairFaces > cubeFaces);
        assertTrue("and fit in the buffer",
                stairFaces <= com.mineclone.render.ItemIcons.MAX_FACES);

        // Слой ниже куба: его крышка стоит на экране ниже крышки куба.
        float[] snow = new float[com.mineclone.render.ItemIcons.MAX_FACES * FACE];
        int snowFaces = com.mineclone.render.ItemIcons.shapeFaces(BlockType.SNOW_LAYER, 45f, snow);
        float snowTop = snow[(snowFaces - 1) * FACE + 1];
        float cubeTop = cube[(cubeFaces - 1) * FACE + 1];
        assertTrue("a snow layer sits lower than a full cube", snowTop > cubeTop);
        assertTrue("a bedroll sits between them",
                bedrollTop() > cubeTop && bedrollTop() < snowTop);
    }

    private static float bedrollTop() {
        float[] out = new float[com.mineclone.render.ItemIcons.MAX_FACES * FACE];
        int n = com.mineclone.render.ItemIcons.shapeFaces(BlockType.BEDROLL, 45f, out);
        return out[(n - 1) * FACE + 1];
    }

    private static void testFacesBuffer() {
        float[] out = new float[com.mineclone.render.ItemIcons.MAX_FACES * FACE];
        java.util.Arrays.fill(out, 7f);
        int first = com.mineclone.render.ItemIcons.boxFaces(0f, 0f, 0f, 1f, 1f, 1f, 30f, out, 0);
        int second = com.mineclone.render.ItemIcons.boxFaces(0f, 0f, 0f, 1f, 1f, 1f, 30f,
                out, first);
        assertEq("the same box gives the same face count", first, second);
        for (int i = 0; i < first * FACE; i++)
            assertEq("face " + i + " is written identically", out[i], out[first * FACE + i]);
        // За своими гранями функция ничего не трогает — там остался маркер.
        assertEq("nothing beyond the written faces", 7f, out[(first + second) * FACE]);
    }

    private static void near(String what, float expected, float actual) {
        if (Math.abs(expected - actual) > 1e-4f)
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
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
