package com.mineclone.world;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Eight 16-cubed sections with local palettes and 0/1/2/4/8-bit indices.
 *
 * <p><b>Чтение не берёт монитор.</b> Раньше {@code get} был
 * {@code synchronized}, и это оказалось самой дорогой строчкой в игре:
 * начиная с JDK 15 смещённые блокировки выключены, поэтому каждый вход в
 * монитор — настоящий CAS. Один меш чанка читает блоки сотни тысяч раз
 * (32 768 ячеек, шесть соседей на каждую, плюс выборки AO), заливка света —
 * ещё 32 768. Замер до правки: 6,5 мс на чанк.
 *
 * <p>Вместо монитора читатель снимает один {@code volatile}-снимок
 * {@link Data}: палитра, слова и разрядность всегда согласованы между собой,
 * потому что при росте публикуется новый объект целиком. Сами слова пишутся
 * и читаются через {@link VarHandle} в режиме opaque — это гарантирует
 * атомарность 64-битного слова без единого барьера на x86 и aarch64.
 *
 * <p>Читатель может увидеть значение на одну запись старше — ровно то же
 * допущение, что уже записано у {@code Chunk.blockLight}: устаревший кадр
 * меша сам себя чинит на следующей перестройке.
 */
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
        for (int s = 0; s < sections.length; s++) sections[s].copyInto(out, s * 4096);
        return out;
    }
    public void restore(byte[] data) {
        if (data.length != length) throw new IllegalArgumentException("Invalid block count");
        // Секции не пересоздаются, а перезаполняются: ссылка на секцию лежит в
        // обычном массиве, и подменить её на ходу значило бы опубликовать объект
        // мимо volatile. Внутри секции публикация есть — через поле data.
        for (int s = 0; s < sections.length; s++) sections[s].restore(data, s * 4096);
    }
    public int payloadBytes() {
        int total = 0;
        for (Section s : sections) total += s.bytes();
        return total;
    }

    /** Согласованный снимок секции: палитра, слова и разрядность одной пачкой. */
    private static final class Data {
        final byte[] palette;
        final long[] words;
        final int bits;

        Data(byte[] palette, long[] words, int bits) {
            this.palette = palette;
            this.words = words;
            this.bits = bits;
        }
    }

    private static final class Section {
        private static final VarHandle WORD =
                MethodHandles.arrayElementVarHandle(long[].class);

        private volatile Data data = new Data(new byte[1], new long[0], 0);
        /** Длина занятой части палитры. Только под монитором. */
        private int size = 1;

        synchronized int bytes() {
            Data d = data;
            return d.palette.length + d.words.length * 8;
        }

        byte get(int i) {
            Data d = data;
            return d.palette[index(d, i)];
        }

        void copyInto(byte[] out, int offset) {
            Data d = data;
            for (int i = 0; i < 4096; i++) out[offset + i] = d.palette[index(d, i)];
        }

        private static int index(Data d, int i) {
            if (d.bits == 0) return 0;
            long word = (long) WORD.getOpaque(d.words, i * d.bits >>> 6);
            return (int) (word >>> (i * d.bits & 63)) & ((1 << d.bits) - 1);
        }

        /** Перезаполняет секцию из куска массива, публикуя результат одной записью. */
        synchronized void restore(byte[] src, int offset) {
            data = new Data(new byte[1], new long[0], 0);
            size = 1;
            for (int i = 0; i < 4096; i++) set(i, src[offset + i]);
        }

        synchronized void set(int i, byte value) {
            Data d = data;
            if (d.palette[index(d, i)] == value) return;
            int p = 0;
            while (p < size && d.palette[p] != value) p++;
            if (p == size) {
                byte[] palette = d.palette;
                if (size == palette.length)
                    palette = java.util.Arrays.copyOf(palette, Math.min(256, Math.max(2, size * 2)));
                palette[size] = value;
                long[] words = d.words;
                int bits = d.bits;
                if (size + 1 > (1 << bits)) {
                    int nextBits = bits == 0 ? 1 : bits * 2;
                    long[] next = new long[4096 * nextBits / 64];
                    for (int j = 0; j < 4096; j++)
                        next[j * nextBits >>> 6] |= (long) index(d, j) << (j * nextBits & 63);
                    words = next;
                    bits = nextBits;
                }
                // Новый объект публикуется целиком: читатель либо видит старую
                // тройку, либо новую, но никогда половину одной и половину другой.
                d = new Data(palette, words, bits);
                size++;
                data = d;
            }
            if (d.bits == 0) return;          // одна запись в палитре — писать нечего
            int at = i * d.bits >>> 6, shift = i * d.bits & 63;
            long mask = ((1L << d.bits) - 1) << shift;
            long word = (long) WORD.getOpaque(d.words, at);
            WORD.setOpaque(d.words, at, (word & ~mask) | ((long) p << shift));
        }
    }
}
