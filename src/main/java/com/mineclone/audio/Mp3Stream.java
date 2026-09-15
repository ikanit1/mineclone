package com.mineclone.audio;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.JavaLayerException;
import javazoom.jl.decoder.SampleBuffer;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * MP3 как поток PCM, который читается кусками любой длины.
 *
 * JLayer отдаёт по кадру — 1152 отсчёта на канал, — а OpenAL удобнее
 * кормить ровными буферами по четверти секунды. Хвост кадра, не влезший в
 * буфер, ждёт следующего чтения: иначе на стыке буферов терялись бы отсчёты,
 * и трек щёлкал бы четыре раза в секунду.
 */
public final class Mp3Stream implements Closeable {

    private final InputStream in;
    private final Bitstream bitstream;
    private final Decoder decoder = new Decoder();
    private short[] frame = new short[0];
    private int frameLen, framePos;
    private int channels, rate;
    private boolean eof;

    public Mp3Stream(File file) throws IOException {
        in = new BufferedInputStream(new FileInputStream(file), 1 << 16);
        bitstream = new Bitstream(in);
        // Первый кадр сразу: формат нужен до первого чтения.
        if (!decodeNext()) {
            close();
            throw new IOException("no MPEG audio frames in " + file.getName());
        }
    }

    /** 1 или 2. */
    public int channels() {
        return channels;
    }

    /** Частота дискретизации, Гц. */
    public int sampleRate() {
        return rate;
    }

    /**
     * Читает до {@code len} отсчётов подряд, каналы перемежаются.
     *
     * @return сколько прочитано; меньше {@code len} — только в конце трека;
     *         −1 — трек кончился и читать больше нечего
     */
    public int read(short[] dst, int off, int len) throws IOException {
        int done = 0;
        while (done < len) {
            if (framePos >= frameLen && !decodeNext())
                break;
            int n = Math.min(len - done, frameLen - framePos);
            System.arraycopy(frame, framePos, dst, off + done, n);
            framePos += n;
            done += n;
        }
        return done == 0 && len > 0 ? -1 : done;
    }

    private boolean decodeNext() throws IOException {
        if (eof)
            return false;
        try {
            Header h = bitstream.readFrame();
            if (h == null) {
                eof = true;
                return false;
            }
            SampleBuffer out = (SampleBuffer) decoder.decodeFrame(h, bitstream);
            bitstream.closeFrame();
            channels = out.getChannelCount();
            rate = out.getSampleFrequency();
            frameLen = out.getBufferLength();
            if (frame.length < frameLen)
                frame = new short[frameLen];
            System.arraycopy(out.getBuffer(), 0, frame, 0, frameLen);
            framePos = 0;
            return true;
        } catch (JavaLayerException e) {
            throw new IOException("mp3 decode failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            bitstream.close();
        } catch (BitstreamException e) {
            // Поток всё равно закрывается ниже.
        }
        in.close();
    }
}
