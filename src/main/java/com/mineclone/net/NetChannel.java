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
 */
public final class NetChannel {

    /** Отправить копилку, не дожидаясь конца тика, если она доросла до этого. */
    public static final int SOFT_LIMIT = 24_000;

    /** Всем, кроме себя. */
    public static final int ALL = 0;

    private final NetTransport transport;
    /** Надёжные копилки по получателю: у каждого свой список чанковых дельт. */
    private final Map<Integer, PacketBuf> reliable = new HashMap<>();
    private final Map<Integer, PacketBuf> unreliable = new HashMap<>();
    private long sentMessages;
    private long sentBytes;

    public NetChannel(NetTransport transport) {
        this.transport = transport;
    }

    /**
     * Начать пакет: код уже записан, тело пишет вызывающий.
     *
     * <p>Возвращается общая копилка, а не свой буфер на пакет: пакеты внутри
     * тика пишутся строго по очереди, и лишний массив на каждый был бы
     * мусором на ровном месте.
     */
    public PacketBuf packet(int code, boolean isReliable, int target) {
        Map<Integer, PacketBuf> bucket = isReliable ? reliable : unreliable;
        PacketBuf buf = bucket.computeIfAbsent(target, t -> new PacketBuf(512));
        if (buf.size() >= SOFT_LIMIT)
            flushOne(bucket, target, isReliable);
        buf = bucket.computeIfAbsent(target, t -> new PacketBuf(512));
        buf.u8(code);
        return buf;
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

    private void flushBucket(Map<Integer, PacketBuf> bucket, boolean isReliable) {
        if (bucket.isEmpty())
            return;
        List<Integer> targets = new ArrayList<>(bucket.keySet());
        for (Integer t : targets)
            flushOne(bucket, t, isReliable);
    }

    private void flushOne(Map<Integer, PacketBuf> bucket, int target, boolean isReliable) {
        PacketBuf buf = bucket.remove(target);
        if (buf == null || buf.size() == 0)
            return;
        byte[] payload = buf.toBytes();
        sentMessages++;
        sentBytes += payload.length;
        transport.send(payload, isReliable, target);
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
