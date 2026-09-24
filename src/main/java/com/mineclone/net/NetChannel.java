package com.mineclone.net;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Пакеты копятся, сообщения уходят.
 *
 * <p>Кадр сети собирает все пакеты, накопленные за тик, в одно сообщение на
 * получателя. Считать надо именно сообщения: у комнаты Photon лимит
 * измеряется ими, а не байтами, и копание, при котором в секунду улетает
 * тридцать правок блока, без склейки съело бы полосу впустую.
 *
 * <p>Надёжные и ненадёжные пакеты живут в разных сообщениях: положение игрока
 * устарело через тик, и переотправлять его после потери — только добавлять
 * задержку. Правка блока, наоборот, обязана дойти, иначе у соседа в стене
 * останется дыра.
 *
 * <p>Ручного переполнения нет: копилка сама уходит в сеть, дойдя до
 * {@link #SOFT_LIMIT}. Это важно на входе участника, когда хозяин отдаёт
 * дельты десятков чанков сразу.
 *
 * <p>Каждый пакет засчитывается в {@link NetStats} по своему коду (NET-01) в
 * момент отправки сообщения: только тогда известно, скольким он достанется.
 */
public final class NetChannel {

    /** Отправить копилку, не дожидаясь конца тика, если она доросла до этого. */
    public static final int SOFT_LIMIT = 24_000;

    /** Всем, кроме себя. */
    public static final int ALL = 0;

    private final NetTransport transport;
    private final NetStats stats;
    /** Надёжные копилки по получателю: у каждого свой список чанковых дельт. */
    private final Map<Integer, Pending> reliable = new HashMap<>();
    private final Map<Integer, Pending> unreliable = new HashMap<>();
    private long sentMessages;
    private long sentBytes;

    public NetChannel(NetTransport transport) {
        this(transport, null);
    }

    /** @param stats куда считать трафик; null — не считать */
    public NetChannel(NetTransport transport, NetStats stats) {
        this.transport = transport;
        this.stats = stats;
    }

    /** Копилка одного получателя и где в ней начинается каждый пакет. */
    private static final class Pending {
        final PacketBuf buf = new PacketBuf(512);
        int[] codes = new int[16];
        int[] starts = new int[16];
        int count;

        void open(int code) {
            if (count == codes.length) {
                codes = java.util.Arrays.copyOf(codes, count * 2);
                starts = java.util.Arrays.copyOf(starts, count * 2);
            }
            codes[count] = code;
            starts[count] = buf.size();
            count++;
            buf.u8(code);
        }

        /** Пакет {@code i} тянется до начала следующего или до конца копилки. */
        int size(int i) {
            return (i + 1 < count ? starts[i + 1] : buf.size()) - starts[i];
        }
    }

    /**
     * Начать пакет: код уже записан, тело пишет вызывающий.
     *
     * <p>Возвращается общая копилка, а не свой буфер на пакет: пакеты внутри
     * тика пишутся строго по очереди, и лишний массив на каждый был бы
     * мусором на ровном месте.
     */
    public PacketBuf packet(int code, boolean isReliable, int target) {
        Map<Integer, Pending> bucket = isReliable ? reliable : unreliable;
        Pending pending = bucket.computeIfAbsent(target, t -> new Pending());
        if (pending.buf.size() >= SOFT_LIMIT) {
            flushOne(bucket, target, isReliable);
            pending = bucket.computeIfAbsent(target, t -> new Pending());
        }
        pending.open(code);
        return pending.buf;
    }

    /** Пакет без тела. */
    public void send(int code, boolean isReliable, int target) {
        packet(code, isReliable, target);
    }

    /** Отправить всё накопленное. Зовётся раз в тик сети. */
    public void flush() {
        flushBucket(reliable, true);
        flushBucket(unreliable, false);
    }

    private void flushBucket(Map<Integer, Pending> bucket, boolean isReliable) {
        if (bucket.isEmpty())
            return;
        List<Integer> targets = new ArrayList<>(bucket.keySet());
        for (Integer t : targets)
            flushOne(bucket, t, isReliable);
    }

    private void flushOne(Map<Integer, Pending> bucket, int target, boolean isReliable) {
        Pending pending = bucket.remove(target);
        if (pending == null || pending.buf.size() == 0)
            return;
        byte[] payload = pending.buf.toBytes();
        sentMessages++;
        sentBytes += payload.length;
        if (stats != null)
            count(pending, payload.length, target);
        transport.send(payload, isReliable, target);
    }

    private void count(Pending pending, int length, int target) {
        List<Integer> others = target == ALL ? transport.actors() : null;
        int recipients = others == null ? 1 : others.size();
        if (recipients == 0)
            return;                       // никому: такое сообщение транспорт просто не отправит
        for (int i = 0; i < pending.count; i++)
            stats.packet(NetStats.OUT, pending.codes[i], pending.size(i), recipients);
        stats.message(NetStats.OUT, length, transport.wireBytes(length), recipients);
        if (others == null)
            stats.peer(target, NetStats.OUT, length);
        else
            for (int actor : others)
                stats.peer(actor, NetStats.OUT, length);
    }

    /** Выбросить накопленное не отправляя: например, комнату уже закрыли. */
    public void discard() {
        reliable.clear();
        unreliable.clear();
    }

    public long messagesSent() {
        return sentMessages;
    }

    public long bytesSent() {
        return sentBytes;
    }
}
