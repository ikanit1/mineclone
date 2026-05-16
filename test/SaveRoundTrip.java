package com.mineclone.save;

import java.io.File;
import java.util.Random;

public class SaveRoundTrip {
    static int checks = 0;
    static void check(boolean cond, String what) {
        checks++;
        if (!cond) { System.err.println("FAIL: " + what); System.exit(1); }
    }

    public static void main(String[] args) {
        File tmp = new File("saves_test_tmp");
        SaveManager sm = new SaveManager(tmp);
        String id = "rt";

        check(!sm.hasSave(id), "no save before write");

        LevelData lvl = new LevelData(123456789L, 1.5, 90.25, -3.75,
                0.7f, -0.2f, 1.234f, 4);
        sm.saveLevel(id, lvl);
        check(sm.hasSave(id), "hasSave after saveLevel");

        LevelData back = sm.loadLevel(id);
        check(back != null, "loadLevel non-null");
        check(back.seed == 123456789L, "seed");
        check(back.px == 1.5 && back.py == 90.25 && back.pz == -3.75, "position");
        check(back.yaw == 0.7f && back.pitch == -0.2f, "orientation");
        check(back.timeOfDay == 1.234f, "timeOfDay");
        check(back.selectedSlot == 4, "selectedSlot");

        byte[] blocks = new byte[SaveFormat.CHUNK_VOLUME];
        byte[] meta = new byte[SaveFormat.CHUNK_VOLUME];
        new Random(42).nextBytes(blocks);
        new Random(43).nextBytes(meta);
        sm.saveChunkBlocking(id, new ChunkSnapshot(-5, 12, blocks, meta));

        ChunkSnapshot cs = sm.loadChunk(id, -5, 12);
        check(cs != null, "loadChunk non-null");
        check(cs.cx == -5 && cs.cz == 12, "chunk coords");
        check(java.util.Arrays.equals(cs.blocks, blocks), "blocks round-trip");
        check(java.util.Arrays.equals(cs.meta, meta), "meta round-trip");

        check(sm.loadChunk(id, 99, 99) == null, "absent chunk -> null");

        sm.deleteWorld(id);
        check(!sm.hasSave(id), "deleteWorld removed save");
        new File(tmp, "").delete();
        tmp.delete();

        System.out.println("SaveRoundTrip OK (" + checks + " checks)");
    }
}
