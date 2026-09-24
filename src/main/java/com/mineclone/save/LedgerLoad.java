package com.mineclone.save;

import com.mineclone.world.gen.ChunkLedger;

/** Typed chunk-ledger read (GEN-02); reading never renames or replaces the file. */
public sealed interface LedgerLoad {
    record Absent() implements LedgerLoad {}
    record Loaded(ChunkLedger ledger) implements LedgerLoad {}
    record Unreadable(String reason) implements LedgerLoad {}
}
