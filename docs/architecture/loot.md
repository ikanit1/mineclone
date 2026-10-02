# Loot tables

Updated: 2026-10-01. Paths in backticks are relative to the repository root.

What blocks, mobs and chests drop lives in `assets/data/<namespace>/loot_tables/`,
under `blocks/`, `entities/` or `chests/` (BLK-06). `ItemRegistry.load` builds the
immutable `LootRegistry` after items, tags and recipes; a table referring to an
unknown item, tag, block, mob or tool class fails loading with a `JsonException`
naming the file and key. In the `mineclone` namespace a `blocks/<block>.json` or
`entities/<mob>.json` file is bound to that `BlockType` or `MobType` by name.

```json
{
  "conditions": [ { "not_creative": true } ],
  "pools": [
    { "rolls": [2, 5],
      "conditions": [ { "killed_by_participant": true } ],
      "entries": [
        { "item": "coal", "weight": 10, "count": [2, 8] },
        { "item": "#mineclone:planks", "weight": 5 },
        { "item": "iron_pickaxe", "weight": 1, "functions": [ { "set_damage": [0.2, 0.8] } ] }
      ] }
  ]
}
```

- **Rolls, counts**: a number or `[min, max]`, inclusive. A table can produce at
  most 4096 item units; larger counts split into stacks of the item's limit.
- **Weights** pick one eligible entry per roll. A tag entry picks a member.
- **Conditions** (table, pool or entry): `tool_class`, `tool_level_min`,
  `killed_by_participant`, `not_creative`, `random_chance`.
- **Functions**: `set_damage` — a fraction of durability, a number or a range;
  only for items that wear.
- `"pools": []` is an explicit empty table (water, fire, a zombie).

A block without a table drops its own item, which is what most blocks do; a
table exists only where the drop differs. Whether a block drops **at all** stays
a block property: `BlockProps.requiredToolLevel` and `preferredTool` gate the
harvest (`LootRegistry.canHarvest`) before any table is rolled, and creative
mode drops nothing. The table only decides what comes out.

Natural scar saplings store their host lifecycle flag in world meta bit 2.
SAPLING loot contexts use species bits 0..1, so natural and player seedlings
yield the same ordinary species item through hand breaking, support collapse
and explosions. Player placement also masks carried BLOCK_STATE to those two
species bits: creative picking or an older carried item cannot turn a player
plant into a seedling that pauses when Living World is disabled. Saved item
components keep their established opaque-preservation policy.

**Randomness.** Block and mob drops roll on the session's item RNG. A constant
drop draws no value from it, so porting a fixed drop into data does not shift
anything downstream. Chest loot passes no session RNG: its generator is seeded
from the world seed, position and table id, so a chest is a pure function of
where it stands and every build and every guest sees the same contents.

`LootRegistry.fillChest` fills an empty generated chest, merges compatible
stacks, and scatters them using a separate deterministic stream. It stages the
whole result before touching the destination: overflow refuses the table rather
than losing items or leaving a partial chest. The four legacy structure chest
tables now contain weighted materials, food and worn tools; V1 structures still
do not place chests. The new structure writer consumes these tables when a
structure type explicitly requests a chest (GEN-04, natural types in GEN-05).

**Deliberate departure from M0.** Leaves drop a leaves block one time in
four. They used to drop nothing, while the bedroll's bedding has been leaves
since its recipe was written (`31afedb`) — so the bed and its respawn point could
not be made in survival. The reachability check found it. One tree now gives
the three leaves the recipe needs; FARM-03 adds saplings from decaying leaves.

**Mob kills** carry `killedByParticipant` — true when a player's hit (a guest's
through `C_MOB_HIT`, an arrow) was fatal — and no tool: the host's held item
says nothing about who killed. A mob eaten by a wolf still drops nothing.

## Verification

`LootTableTests` compares every block (but the leaves above) and mob with the frozen M0 policy in
`src/test/resources/loot/legacy-*.tsv`. Those rows were produced by the M0 build
itself (`0b7a12a`): its private `Game.blockDrop` and `MobType.drop/dropCount`
were invoked over every block and mob type. The suite also checks the harvest
gate (stone → cobblestone, coal ore → coal, iron ore needs level 2), that constant
drops draw no randomness, chest determinism, a 10 000-roll weight distribution,
every condition and `set_damage`, stack splitting, 16 malformed fixtures in
`src/test/resources/datapacks/bad/loot_tables`, and that no drop table is left
in `BlockType`, `MobType` or `Game`.

`ReachabilityTests` runs `LootReachability`, a least fixed point over natural
blocks, mobs, chest tables, recipes (3×3 only once a workbench is reachable) and
smelting (once a furnace and a fuel are). Creative-only building blocks have no
survival source by design, so the checked property is dead content: every
recipe and smelting result must be reachable. There are no known unreachable
recipe or smelting results after GEN-08 added copper ore. A new gap fails the
test rather than joining an allowlist. A
sampled generation over three seeds checks that the natural-block roots include
everything the generator actually places.

## Metadata, adults and explosions (2 October 2026)

`LootContext` adds block metadata and `isAdult`, with compatibility constructors.
Crop stage and leaf species conditions select authored drops. Mob tables require
adult status; cow leather and rotten flesh are reachable, and the bow accepts
`#mineclone:bowstring` (cobweb/string). `MobType.drop` is removed. Explosions roll
block loot at 30%; container removal spills contents independently of that roll.
TCP scenarios verify all stacks arrive at the guest even when an explosion removes
the chest, and griefing-disabled blasts leave its contents intact.

The spider-nest block table drops three string through the ordinary host loot
path; breaking its heart changes the colony lifecycle independently of loot.
Creative/explosion rules remain the existing LootContext policy. The full-cube
block, loot registration and repeat-removal conservation are covered by
NestBlockTests; the runtime never grants a separate duplicate nest reward.
