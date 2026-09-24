# Save preservation before extensibility

Date: 2026-09-24. Status: accepted and implemented in M0 (SAVE-01 through SAVE-06).

Opening a missing world is different from failing to read an existing one.
Typed reads distinguish absence, damage and an unsupported minimum reader.
Neither a graphical fallback nor a queued write may turn the latter two into a
new world over the original. Corrupt chunks are renamed to evidence files only
after a backup succeeds; future chunks remain protected in place.

Level v11 and chunk v7 use bounded named byte sections with an explicit minimum
reader version. Additive future files can be read when their minimum permits it;
unknown sections travel with the level or chunk and are written back unchanged.
This avoids inventing a format-version bump for every later gameplay feature.
Legacy readers reject the new outer version before interpreting the new layout.

One writer executor orders original-byte session backups, immutable level
snapshots, chunk writes and guest saves. Read barriers provide read-after-write
behavior without letting executor tasks wait for themselves. The render loop
shows a progress screen during a session backup. Restore publishes a new world
directory and never replaces the selected source.

The original format readers remain available and are checked against historical
fixtures. See [save architecture](../../docs/architecture/saves.md) and
[fixture provenance](../../src/test/resources/fixtures/saves/README.md).

Tradeoffs: backup cost is visible before entering large worlds; filesystems still
determine crash durability. A readonly regenerated chunk is a temporary view of
unreadable future data, not a repaired version of that data. Region files and
fsync durability are separate work.
