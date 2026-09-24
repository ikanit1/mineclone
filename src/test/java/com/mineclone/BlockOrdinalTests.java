package com.mineclone;

import com.mineclone.world.BlockType;
import java.nio.file.Files;
import java.nio.file.Path;

/** Frozen 1.0.1 values, captured before centralizing properties (BLK-01). */
public final class BlockOrdinalTests {
    public static void runAll(TestMain.Runner runner) {
        runner.run("saved block IDs retain the complete 1.0.1 prefix", BlockOrdinalTests::ordinals);
        runner.run("centralized block properties preserve 1.0.1 behavior", BlockOrdinalTests::properties);
    }

    private static void ordinals() throws Exception {
        for (String line : Files.readAllLines(Path.of("src/test/resources/fixtures/block-ordinals.txt"))) {
            String[] parts = line.split(" ");
            int id = Integer.parseInt(parts[0]);
            if (id >= BlockType.values().length || !BlockType.values()[id].name().equals(parts[1]))
                throw new AssertionError("Saved block ID changed: " + line);
            if (BlockType.byId((byte) id) != BlockType.valueOf(parts[1]))
                throw new AssertionError("Reader changed saved block ID: " + line);
        }
    }

    private static void properties() throws Exception {
        for (String line : Files.readAllLines(Path.of("src/test/resources/fixtures/block-properties-v1.txt"))) {
            BlockType b = BlockType.valueOf(line.substring(0, line.indexOf(' ')));
            String actual = b.name() + " " + b.isCross() + " " + b.isLayered() + " " + b.hasGravity()
                    + " " + b.isSoil() + " " + b.isFlammable() + " " + b.grip() + " "
                    + b.walkSpeedMultiplier() + " " + b.preferredTool() + " " + b.requiredToolLevel();
            if (!line.equals(actual)) throw new AssertionError("Changed properties: expected " + line + "; got " + actual);
        }
    }
}
