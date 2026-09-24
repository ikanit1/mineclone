# Loot tables

Updated: 2026-09-24. Paths in backticks are relative to the repository root.

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

**Randomness.** Block and mob drops roll on the session's item RNG. A constant
drop draws no value from it, so porting a fixed drop into data does not shift
anything downstream. Chest loot passes no session RNG: its generator is seeded
from the world seed, position and table id, so a chest is a pure function of
where it stands and every build and every guest sees the same contents.

**Mob kills** carry `killedByParticipant` — true when a player's hit (a guest's
through `C_MOB_HIT`, an arrow) was fatal — and no tool: the host's held item
says nothing about who killed. A mob eaten by a wolf still drops nothing.

## Verification

`LootTableTests` compares every block and mob with the frozen M0 policy in
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
recipe and smelting result must be reachable. The known gaps are listed in the
test with the task that closes them — copper tools until copper ore exists
(GEN-08), and the bedroll, whose bedding is leaves that have dropped nothing
since the recipe was written — and a gap that closes fails the test until the
list is updated. A
sampled generation over three seeds checks that the natural-block roots include
everything the generator actually places.
