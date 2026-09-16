package com.mineclone.data;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * Целое переменной длины: по семь бит на байт, старший бит — «есть продолжение».
 *
 * <p>Компонентов у стопки почти всегда ноль, изредка один; счётчик в четыре
 * байта на каждую из тысяч стопок сейва — чистая потеря. Отрицательные числа
 * занимают все пять байт, поэтому сюда пишутся только длины и счётчики.
 */
public final class VarInt {

    private VarInt() {}

    public static void write(DataOutput out, int value) throws IOException {
        int v = value;
        while ((v & ~0x7F) != 0) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v);
    }

    public static int read(DataInput in) throws IOException {
        int result = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.readUnsignedByte();
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0)
                return result;
        }
        throw new IOException("varint is too long");
    }

    /** Сколько байт займёт запись — для расчёта размеров без буфера. */
    public static int size(int value) {
        int n = 1;
        int v = value;
        while ((v & ~0x7F) != 0) {
            n++;
            v >>>= 7;
        }
        return n;
    }
}
