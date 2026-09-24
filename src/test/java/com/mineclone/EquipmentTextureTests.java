package com.mineclone;

import com.mineclone.item.Items;
import com.mineclone.render.HeldToolTemplate;
import com.mineclone.render.TextureAtlas;
import javax.imageio.ImageIO;
import java.nio.file.Path;

/** Asset contracts that previously allowed a square shovel and mismatched held icons. */
final class EquipmentTextureTests {
    static void runAll(NetworkTests.Runner r) {
        r.run("equipment icons have native resolution, transparent margins and distinct silhouettes",
                EquipmentTextureTests::icons);
        r.run("held tools sample only their own inventory tile, including every opaque pixel",
                EquipmentTextureTests::heldPixels);
        r.run("all ores retain the same stone base and distinct mineral clusters",
                EquipmentTextureTests::ores);
    }

    private static void icons() throws Exception {
        var masks = new java.util.EnumMap<com.mineclone.item.ToolClass, String>(com.mineclone.item.ToolClass.class);
        int count = 0;
        for (var item : Items.get().all()) {
            if (item.tool == null) continue;
            count++;
            String name = TextureAtlas.TILE_NAMES[item.iconTile];
            var image = ImageIO.read(Path.of(TextureAtlas.BLOCKS_DIR, name + ".png").toFile());
            check(image.getWidth() == 32 && image.getHeight() == 32, item.id + " must be native 32px");
            var mask = new StringBuilder();
            int opaque = 0;
            for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) {
                int alpha = image.getRGB(x, y) >>> 24;
                check(alpha == 0 || alpha == 255, item.id + " has an antialiased fringe");
                if (x == 0 || y == 0 || x == 31 || y == 31)
                    check(alpha == 0, item.id + " needs an empty inventory margin");
                mask.append(alpha == 0 ? '.' : '#');
                if (alpha == 255) opaque++;
            }
            check(opaque > 60 && opaque < 500, item.id + " must be a tool, not a swatch or empty icon");
            String previous = masks.putIfAbsent(item.tool.toolClass(), mask.toString());
            check(previous == null || previous.equals(mask.toString()), item.id + " changes its class silhouette");
        }
        check(count == 24, "cover all six materials and four tool classes");
        check(new java.util.HashSet<>(masks.values()).size() == 4, "axe, shovel, pickaxe and sword must differ");
        check(TextureAtlas.TILE_NAMES[Items.get().require("copper_shovel").iconTile].equals("copper_shovel"),
                "copper shovel must not use the solid material swatch");
        for (String name : new String[]{"copper_ingot", "iron_ingot", "gold_ingot", "stick"}) {
            var icon = TextureAtlas.sprite(Items.get().require(name).iconTile);
            check((icon.getRGB(0, 0) >>> 24) == 0, name + " must have a transparent background");
        }
    }

    private static void heldPixels() {
        for (var item : Items.get().all()) {
            if (item.tool == null) continue;
            var mesh = HeldToolTemplate.mesh(item);
            var sprite = TextureAtlas.sprite(item.iconTile);
            boolean[] sampled = new boolean[32 * 32];
            for (int i = 0; i < mesh.uvs().length; i += 2) {
                int x = (int) (mesh.uvs()[i] * TextureAtlas.ATLAS_SIZE);
                int y = (int) (mesh.uvs()[i + 1] * TextureAtlas.ATLAS_SIZE);
                int tile = x / 32 + y / 32 * TextureAtlas.TILES_PER_ROW;
                check(tile == item.iconTile, item.id + " samples another item's material");
                check((sprite.getRGB(x % 32, y % 32) >>> 24) == 255, item.id + " samples transparency");
                sampled[y % 32 * 32 + x % 32] = true;
            }
            for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++)
                check(sampled[y * 32 + x] == ((sprite.getRGB(x, y) >>> 24) == 255),
                        item.id + " held silhouette omits an icon pixel");
        }
    }

    private static void ores() throws Exception {
        var stone = ImageIO.read(Path.of(TextureAtlas.BLOCKS_DIR, "stone.png").toFile());
        for (String name : new String[]{"coal_ore", "iron_ore", "gold_ore", "diamond_ore"}) {
            var ore = ImageIO.read(Path.of(TextureAtlas.BLOCKS_DIR, name + ".png").toFile());
            check(ore.getWidth() == 32 && ore.getHeight() == 32, name + " resolution");
            int changed = 0;
            for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) {
                check((ore.getRGB(x, y) >>> 24) == 255, name + " must be opaque");
                if (ore.getRGB(x, y) != stone.getRGB(x, y)) changed++;
            }
            check(changed > 40 && changed < 350, name + " must be mineral clusters in the shared stone");
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
