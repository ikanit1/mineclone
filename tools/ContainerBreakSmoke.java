package com.mineclone;

import com.mineclone.core.Input;
import com.mineclone.core.Window;
import com.mineclone.game.Game;
import com.mineclone.net.*;
import com.mineclone.ui.WorldSettings;
import com.mineclone.world.*;
import com.mineclone.world.entity.ItemEntity;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.List;

/** Real Game host/guest adapters with OpenGL resources, isolated saves and loopback. */
public class ContainerBreakSmoke {
    static Object get(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }
    static Object call(Object object, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = object.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(object, args);
    }
    static void pump(Multiplayer host, Multiplayer guest) {
        for (int i = 0; i < 12; i++) { host.update(.1f); guest.update(.1f); }
    }
    static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }
    public static void main(String[] args) throws Exception {
        System.setProperty("mineclone.savesDir", Path.of("out-test/container-break-smoke/saves").toAbsolutePath().toString());
        Window window = new Window("Container destruction regression", 960, 540, false);
        window.init();
        Game game = null;
        Multiplayer host = null, guestNet = null;
        try {
            game = new Game(window, false);
            ((Input) get(game, "input")).setGrabAllowed(false);
            call(game, "createWorld", new Class<?>[] {WorldSettings.class}, new WorldSettings("Container regression", 42, GameMode.SURVIVAL));
            World world = (World) get(game, "world");
            host = (Multiplayer) get(game, "net");
            var guest = new NetworkTests.TestContext(null, "guest");
            guest.onWorldStarted = w -> w.getChunk(0, 0);
            guestNet = new Multiplayer(guest);
            LoopbackTransport.reset();
            host.start(new LoopbackTransport(host), "container-smoke", true, "host");
            guestNet.start(new LoopbackTransport(guestNet), "container-smoke", false, "guest");
            pump(host, guestNet);
            world.setBlock(8, 100, 8, BlockType.CHEST);
            world.createChest(8, 100, 8)[0] = ItemStack.of("diamond", 16);
            pump(host, guestNet);
            guestNet.onWorldBlockChanged(8, 100, 8, BlockType.CHEST, BlockType.AIR, (byte) 0);
            pump(host, guestNet);
            @SuppressWarnings("unchecked") List<ItemEntity> items = (List<ItemEntity>) get(game, "items");
            check(items.stream().mapToInt(e -> e.stack.count).sum() == 16, "Game host lost chest contents");
            check(guest.items.stream().mapToInt(e -> e.stack.count).sum() == 16, "S_ITEMS did not reach guest");
            world.setBlock(9, 100, 8, BlockType.FURNACE);
            Furnace furnace = world.createFurnace(9, 100, 8);
            furnace.input = ItemStack.of("iron_ore", 3);
            furnace.fuel = ItemStack.of("coal", 4);
            furnace.output = ItemStack.of("iron_ingot", 5);
            pump(host, guestNet);
            guestNet.onWorldBlockChanged(9, 100, 8, BlockType.FURNACE, BlockType.STONE, (byte) 0);
            pump(host, guestNet);
            check(items.stream().mapToInt(e -> e.stack.count).sum() == 28, "Game host lost furnace contents");
            check(guest.items.stream().mapToInt(e -> e.stack.count).sum() == 28, "furnace drops did not replicate");
            System.out.println("CONTAINER_BREAK_GAME PASS: chest 16, furnace 12, host and S_ITEMS agree");
        } finally {
            if (guestNet != null) guestNet.stop(null);
            if (host != null) host.stop(null);
            if (game != null) call(game, "cleanup", new Class<?>[0]);
            LoopbackTransport.reset();
            window.destroy();
        }
    }
}
