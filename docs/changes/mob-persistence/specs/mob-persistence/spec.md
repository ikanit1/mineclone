## ADDED Requirements

### Requirement: Persistent mobs

A mob whose type's temper is `PASSIVE`, `SKITTISH` or `NEUTRAL` SHALL be persistent. A
`HOSTILE` mob SHALL NOT be persistent.

#### Scenario: MP-1 animals are written, hostiles are not
- **WHEN** a chunk in which a cow, a rabbit, a wolf and a zombie stand is written
- **THEN** its `entities` section holds the cow, the rabbit and the wolf, and not the zombie

### Requirement: Written with the chunk they stand in

A persistent mob SHALL be written with the chunk that contains its position at the
moment of the write, with its type, id, position, yaw and health. A chunk that held
mobs at its last write SHALL be written again even if its blocks did not change.

#### Scenario: MP-2 round trip
- **WHEN** a chunk with a sheep is written and read back, and the chunk becomes live
- **THEN** a sheep with the same id, position, yaw and health is in the world

#### Scenario: MP-3 a mob that walked away
- **WHEN** a cow written with chunk A has since walked into chunk B, and both chunks are written
- **THEN** A's write no longer holds the cow and B's write does

### Requirement: Unload removes, load restores

When a chunk is evicted, the mobs standing in it SHALL leave the world after the chunk
is written. Persistent mobs SHALL return when the chunk loads again. A quick unload
and reload SHALL NOT duplicate a mob.

#### Scenario: MP-4 evict and reload
- **WHEN** a chunk with a herd of four is evicted and loaded again at once
- **THEN** the herd is in the world exactly once, four mobs with their old ids

### Requirement: Despawn spares persistent mobs

Despawning by distance SHALL NOT remove a persistent mob.

#### Scenario: MP-5 far from everyone
- **WHEN** a cow and a zombie are 100 blocks from every participant and despawn runs
- **THEN** the zombie is removed and the cow stays

### Requirement: Bounded, preserving section

The `entities` section SHALL hold at most 256 records of at most 1024 bytes each. A
non-finite number, an id or health that is not positive, or a record shorter than its
fields SHALL make the read fail as corrupt. A record of a type this build does not know
SHALL be kept with its chunk, never enter the world, and be written back unchanged;
bytes a newer build appended to a record SHALL be kept.

#### Scenario: MP-6 a mob from a newer build
- **WHEN** a chunk holds a record of an unknown type and is read, left alone and written again
- **THEN** no mob of that record enters the world and the written section holds the record byte for byte

#### Scenario: MP-7 corrupt records
- **WHEN** a section holds 257 records, or a record with a NaN position, a zero id or a zero health
- **THEN** reading the chunk fails as corrupt

#### Scenario: MP-8 chunks from before this change
- **WHEN** a chunk written before this change (no `entities` section) is read
- **THEN** it reads without error and brings no mobs

### Requirement: A dedicated server keeps its animals

A dedicated server SHALL write its animals with its chunks when it saves or stops, and
bring them back on the next start.

#### Scenario: MP-9 server restart
- **WHEN** a dedicated server with a herd in a loaded chunk saves, and a new session reads that chunk back
- **THEN** the herd is there with the same ids
