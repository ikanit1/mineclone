package com.mineclone.net;

/** Application payload counters; transport framing and kernel traffic are intentionally excluded. */
public final class NetStats {
    private long sentBytes, receivedBytes, sentPackets, receivedPackets;
    public synchronized void sent(int bytes, int recipients) { sentBytes += (long) bytes * recipients; sentPackets += recipients; }
    public synchronized void received(int bytes) { receivedBytes += bytes; receivedPackets++; }
    public synchronized Snapshot snapshot() { return new Snapshot(sentBytes, receivedBytes, sentPackets, receivedPackets); }
    public record Snapshot(long sentBytes, long receivedBytes, long sentPackets, long receivedPackets) {}
}
