package com.mineclone.net;

import java.nio.charset.StandardCharsets;

/**
 * Компактный двоичный кадр: чтение и запись одним объектом.
 *
 * <p>Своё, а не {@code DataOutputStream}: у сети другая цена ошибки, чем у
 * файла. Счётчик мобов или список дельт чанка пишется {@link #varInt(int)} —
 * маленькое число занимает один байт, и на десяти пакетах в секунду это уже
 * заметная разница. Координаты блока едут не тремя {@code int}, а одним
 * {@link #blockPos}: мир ограничен по высоте, и 128 значений {@code y} не
 * стоят четырёх байт.
 *
 * <p>Чтение намеренно не бросает исключение на исчерпании буфера, а отдаёт
 * нули и поднимает {@link #truncated()}: пакет приходит из сети, то есть от
 * кого угодно, и обрыв на середине не должен валить кадр игры. Решение
 * «пакет битый — выбросить» принимает разбор, а не арифметика смещений.
 */
public final class PacketBuf {

    private byte[] data;
    private int pos;
    private int limit;
    private boolean truncated;

    /** Пустой буфер под запись. */
    public PacketBuf() {
        this(256);
    }

    public PacketBuf(int capacity) {
        this.data = new byte[Math.max(16, capacity)];
        this.limit = 0;
    }

    /** Буфер поверх готовых байтов — под чтение. */
    public static PacketBuf reading(byte[] bytes, int offset, int length) {
        PacketBuf b = new PacketBuf(1);
        b.data = bytes;
        b.pos = offset;
        b.limit = offset + length;
        return b;
    }

    public static PacketBuf reading(byte[] bytes) {
        return reading(bytes, 0, bytes.length);
    }

    // ------------------------------------------------------------- состояние

    public int size() {
        return limit;
    }

    public int remaining() {
        return Math.max(0, limit - pos);
    }

    public boolean hasMore() {
        return pos < limit;
    }

    /** Чтение ушло за конец буфера — всё, что вернули после этого, недостоверно. */
    public boolean truncated() {
        return truncated;
    }

    public byte[] toBytes() {
        byte[] out = new byte[limit];
        System.arraycopy(data, 0, out, 0, limit);
        return out;
    }

    /** Сбросить под новую запись, не выбрасывая уже выделенный массив. */
    public void clear() {
        pos = 0;
        limit = 0;
        truncated = false;
    }

    private void ensure(int more) {
        if (limit + more <= data.length)
            return;
        int cap = Math.max(data.length * 2, limit + more);
        byte[] bigger = new byte[cap];
        System.arraycopy(data, 0, bigger, 0, limit);
        data = bigger;
    }

    // ---------------------------------------------------------------- запись

    public PacketBuf u8(int v) {
        ensure(1);
        data[limit++] = (byte) v;
        return this;
    }

    public PacketBuf bool(boolean v) {
        return u8(v ? 1 : 0);
    }

    public PacketBuf i16(int v) {
        ensure(2);
        data[limit++] = (byte) (v >> 8);
        data[limit++] = (byte) v;
        return this;
    }

    public PacketBuf i32(int v) {
        ensure(4);
        data[limit++] = (byte) (v >> 24);
        data[limit++] = (byte) (v >> 16);
        data[limit++] = (byte) (v >> 8);
        data[limit++] = (byte) v;
        return this;
    }

    public PacketBuf i64(long v) {
        i32((int) (v >>> 32));
        i32((int) v);
        return this;
    }

    public PacketBuf f32(float v) {
        return i32(Float.floatToIntBits(v));
    }

    /** Беззнаковое число переменной длины: 7 бит на байт, старший — признак продолжения. */
    public PacketBuf varInt(int v) {
        int x = v;
        while ((x & ~0x7F) != 0) {
            u8((x & 0x7F) | 0x80);
            x >>>= 7;
        }
        return u8(x);
    }

    public PacketBuf str(String s) {
        byte[] utf = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        varInt(utf.length);
        ensure(utf.length);
        System.arraycopy(utf, 0, data, limit, utf.length);
        limit += utf.length;
        return this;
    }

    public PacketBuf bytes(byte[] src) {
        varInt(src.length);
        ensure(src.length);
        System.arraycopy(src, 0, data, limit, src.length);
        limit += src.length;
        return this;
    }

    /**
     * Координаты блока одним словом: по 20 бит на x и z, семь на y.
     *
     * <p>Высота мира — 128 блоков, то есть ровно семь бит; двадцать бит на
     * горизонталь это ±524287 блоков, дальше игра не уходит. Три честных
     * {@code int} на каждую правку блока были бы просто расходом полосы, а
     * правок в секунду при копании десятки.
     */
    public PacketBuf blockPos(int x, int y, int z) {
        long packed = ((long) (x & 0xFFFFF) << 27) | ((long) (z & 0xFFFFF) << 7) | (y & 0x7F);
        return i64(packed);
    }

    // ----------------------------------------------------------------- чтение

    private int next() {
        if (pos >= limit) {
            truncated = true;
            return 0;
        }
        return data[pos++] & 0xFF;
    }

    public int readU8() {
        return next();
    }

    public boolean readBool() {
        return next() != 0;
    }

    public short readI16() {
        return (short) ((next() << 8) | next());
    }

    public int readI32() {
        return (next() << 24) | (next() << 16) | (next() << 8) | next();
    }

    public long readI64() {
        return ((long) readI32() << 32) | (readI32() & 0xFFFFFFFFL);
    }

    public float readF32() {
        return Float.intBitsToFloat(readI32());
    }

    public int readVarInt() {
        int result = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = next();
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0)
                break;
        }
        return result;
    }

    public String readStr() {
        int n = readVarInt();
        if (n < 0 || n > remaining()) {
            truncated = true;
            pos = limit;
            return "";
        }
        String s = new String(data, pos, n, StandardCharsets.UTF_8);
        pos += n;
        return s;
    }

    public byte[] readBytes() {
        int n = readVarInt();
        if (n < 0 || n > remaining()) {
            truncated = true;
            pos = limit;
            return new byte[0];
        }
        byte[] out = new byte[n];
        System.arraycopy(data, pos, out, 0, n);
        pos += n;
        return out;
    }

    /** Три координаты блока из {@link #blockPos}; знак x и z восстанавливается. */
    public int[] readBlockPos() {
        long packed = readI64();
        int x = (int) ((packed >>> 27) & 0xFFFFF);
        int z = (int) ((packed >>> 7) & 0xFFFFF);
        int y = (int) (packed & 0x7F);
        return new int[] { sign20(x), y, sign20(z) };
    }

    private static int sign20(int v) {
        return (v & 0x80000) != 0 ? v | ~0xFFFFF : v;
    }
}
