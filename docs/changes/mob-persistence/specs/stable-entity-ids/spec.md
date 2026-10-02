## ADDED Requirements

### Requirement: Allocation

Every mob in a simulated world SHALL carry an id greater than zero, unique among the
world's mobs, assigned when it first enters the world and never changed afterwards.
Ids SHALL stay within the positive `int` range.

#### Scenario: ID-1 new mobs
- **WHEN** two mobs enter the world one after another
- **THEN** each gets a positive id, the second larger than the first and than every id handed out before in this world

### Requirement: The counter is saved with the level

The next id SHALL be written in the level section `entity_ids` and restored when the
world opens. A world without the section SHALL start at 1. Adopting a saved mob whose
id is at or above the counter SHALL move the counter past that id.

#### Scenario: ID-2 save and reopen
- **WHEN** a world where ids up to 40 were handed out is saved and reopened, and a mob spawns
- **THEN** that mob's id is 41 or more

#### Scenario: ID-3 the counter heals from its mobs
- **WHEN** a world without the section adopts a saved mob with id 900
- **THEN** the next new mob gets an id above 900

### Requirement: A mob written twice enters once

Adopting a saved mob whose id is already live in the world SHALL NOT add a second mob.

#### Scenario: ID-4 the same mob in two chunks
- **WHEN** two chunks both hold the record of the mob with id 7 (a crash between their writes) and both become live
- **THEN** exactly one mob with id 7 is in the world

### Requirement: The network uses the id

`S_MOBS` SHALL carry each mob's id. The wire format and `NetProto.VERSION` SHALL NOT
change. A guest's hit SHALL name the mob by that id.

#### Scenario: ID-5 unload and reload on the host
- **WHEN** the host unloads and reloads the chunk of a cow a guest can see
- **THEN** the snapshots the guest receives before and after carry the same id for that cow

#### Scenario: ID-6 a guest's hit
- **WHEN** a guest's hit names id N
- **THEN** the host damages the mob whose id is N and no other
