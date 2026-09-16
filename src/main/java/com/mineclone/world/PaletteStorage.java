package com.mineclone.world;

/** Eight 16-cubed sections with local palettes and 0/1/2/4/8-bit indices. */
public final class PaletteStorage {
    public final int length;
    private final Section[] sections;

    public PaletteStorage(int length) {
        if (length % 4096 != 0) throw new IllegalArgumentException("Section alignment");
        this.length = length;
        sections = new Section[length / 4096];
        for (int i = 0; i < sections.length; i++) sections[i] = new Section();
    }

    public byte get(int i) { return sections[i >>> 12].get(i & 4095); }
    public void set(int i, byte value) { sections[i >>> 12].set(i & 4095, value); }
    public byte[] copy() {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) out[i] = get(i);
        return out;
    }
    public void restore(byte[] data) {
        if (data.length != length) throw new IllegalArgumentException("Invalid block count");
        for (int s = 0; s < sections.length; s++) {
            sections[s] = new Section();
            for (int i = 0; i < 4096; i++) sections[s].set(i, data[s * 4096 + i]);
        }
    }
    public int payloadBytes() {
        int total = 0;
        for (Section s : sections) total += s.bytes();
        return total;
    }
    private static final class Section {
        private byte[] palette = new byte[1];
        private long[] words = new long[0];
        private int size = 1, bits;
        synchronized int bytes() { return palette.length + words.length * 8; }
        synchronized byte get(int i) { return palette[index(i)]; }
        private int index(int i) {
            return bits == 0 ? 0 : (int)(words[i * bits >>> 6] >>> (i * bits & 63)) & ((1 << bits) - 1);
        }
        synchronized void set(int i, byte value) {
            if (palette[index(i)] == value) return;
            int p = 0;
            while (p < size && palette[p] != value) p++;
            if (p == size) {
                if (size == palette.length) palette = java.util.Arrays.copyOf(palette, Math.min(256, size * 2));
                palette[size++] = value;
                if (size > (1 << bits)) {
                    int nextBits = bits == 0 ? 1 : bits * 2;
                    long[] next = new long[4096 * nextBits / 64];
                    for (int j = 0; j < 4096; j++) next[j * nextBits >>> 6] |= (long)index(j) << (j * nextBits & 63);
                    words = next;
                    bits = nextBits;
                }
            }
            int word = i * bits >>> 6, shift = i * bits & 63;
            long mask = ((1L << bits) - 1) << shift;
            words[word] = (words[word] & ~mask) | ((long)p << shift);
        }
    }
}
