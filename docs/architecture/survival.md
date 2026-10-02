# Survival state

Updated: 2026-10-02. The running player owns vitals and status effects; guests checkpoint them to the host through PlayerRecord. World rules and hostile events belong to the host.

## Balance decision

SURV-02 replaces passive hunger drain and the old regeneration threshold of 14. Idle players spend no exhaustion. Sprinting costs 0.1 per meter, normal jumps 0.05, sprint jumps 0.2, attacks and accepted damage 0.1, block mining 0.005, regeneration 6 per HP. Every four exhaustion spend one saturation before one hunger. Regeneration requires hunger 18: one HP per 0.5 seconds with saturation, otherwise per four seconds. This is an intentional balance change, not physics drift.

Air is 300 simulation ticks (15 seconds), refills at 100 ticks/second and drowning deals two HP/second after it runs out. Environmental sources bypass or use armor according to DamageType.

The effects section preserves unknown effect names and damaged payloads when loading an older/future checkpoint. Known effects stack by maximum duration and amplifier; cosmetic feedback reads those same effects.

Food nutrition, saturation, duration and chances are authored in assets/data/mineclone/items/food.json. Eating takes 1.6 seconds held input and cancels on release, slot changes or damage.

## Food balance and survival rules (2 October 2026)

| Item | Hunger | Saturation | Completed-food effects |
|---|---:|---:|---|
| `beef` | 3 | 1.8 | — |
| `porkchop` | 3 | 1.8 | — |
| `chicken` | 2 | 1.2 | hunger 30s (30%) |
| `mutton` | 2 | 1.2 | — |
| `cooked_beef` | 7 | 11.2 | well_fed 60s (100%) |
| `cooked_porkchop` | 7 | 11.2 | well_fed 60s (100%) |
| `cooked_chicken` | 5 | 6 | well_fed 30s (100%) |
| `cooked_mutton` | 5 | 6 | well_fed 30s (100%) |
| `bread` | 5 | 6 | well_fed 30s (100%) |
| `carrot` | 3 | 3.6 | — |
| `potato` | 1 | 0.6 | — |
| `baked_potato` | 5 | 6 | well_fed 30s (100%) |
| `apple` | 4 | 2.4 | — |
| `rotten_flesh` | 4 | 0.8 | hunger 30s (80%) |

A deterministic 21-minute activity scenario (1,701 sprint meters, 252 normal
jumps, 630 mined blocks, 126 attacks, four two-HP falls and recovery) fits the
2–3 cooked-beef portions target. Idle time costs no food. Environmental hazards
share EntityHazards: fire 1 HP/s plus four residual seconds, lava 4 HP/s, cactus
1/0.5s, opaque head suffocation 1/0.5s, void 4 HP/s below -16, lightning 5 HP
inside two blocks. Water extinguishes; creative and fire resistance obey their
respective damage rules. Mob air and poison use the same typed damage split.

Rules are NORMAL/retention/commands enabled for legacy saves. Peaceful removes
hostiles and restores 1 HP/2s; easy/normal/hard damage factors are 0.5/1/1.5,
starvation floors 10/1/0 and elite chance factors 0.5/1/1.5. The ten-minute
peaceful-night scenario remains empty. Mob-specific hard-door attacks belong to
MOB-07 and are still planned.

Death with retention off transfers all inventory/equipment/pending items once
through the host; they live ten minutes. Retention on keeps equipment intact.
Personal respawn requires a present, unobstructed bed outside hazardous liquids;
otherwise the player uses world spawn and receives an explanation. Unknown
stored effect identifiers remain opaque. Host effects pause while disconnected;
known effects resume from remaining time on rejoin and cannot be revived by a
stale checkpoint. Test coverage: SurvivalRulesTests and InventoryNetworkTests.
