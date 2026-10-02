# Behaviour and preservation requirements

1. With Living World off, existing rabbit AI, spawning, hearing and recorded
   simulation parity remain unchanged. Guests cannot author raids or noise rules.
2. A hungry rabbit may target only loaded writable wheat, carrot or potato plants
   at stage 4–7 within 16 blocks. Searches inspect at most 192 sparse cells and
   attempt at most two bounded paths per staggered 1.5-second search.
   The first selects the nearest eligible inspected crop; the second, if needed,
   selects a distinct eligible crop from rotating scan order. The cursor advances
   even when the full table was scanned, so a large inaccessible nearby field
   cannot permanently exclude a farther reachable crop through cache expiry.
3. A rabbit must physically approach the crop using an actual navigable route.
   Closed 1.5-block fences and gates block it; a real gap or open gate permits
   passage. Eating cannot pass through a wall or collision panel. Route failure
   must not become direct steering through a barrier.
4. Player/wolf fear, existing flight and held-food temptation take priority.
   Turning the rule off or distracting a sleeping rabbit retires its crop route.
   Living World wildlife threat, prey and pack distances include height, so a
   hostile twenty blocks below the field cannot trigger surface fear within the
   usual alert radius. Wolf bite range also includes height: four-block vertical
   separation is outside its 1.4-block range, while a nearby same-level bite works.
   The disabled sensing and bite metrics retain their original horizontal values.
5. Eating retains the plant type, resets stage to 0 and schedules normal regrowth
   without removal or harvest drops. Satiety lasts 45–90 seconds. Appetite and
   route state are transient; plant stage and scheduled growth persist normally.
6. Field food uses all observed crop stages: one food point per four crops, capped
   at 64 per ecology region. Per-chunk observations saturate at 256. Unloading
   terrain preserves its last estimate; observed removal withdraws its contribution.
   Chunk snapshots observe before eviction. No observation generates terrain.
7. `ecology_fields` is optional version 1, bounded to 4096 regions, 256 chunk cells
   per region and counts 1–256, with an opaque tail at most 4096 bytes. The maximal
   supported payload remains below 3.2 MiB. Duplicate keys/cells and invalid
   lengths/counts fail the decode.
8. A newer field schema disables Living World and preserves original field,
   ecology and rule bytes. Damaged data rebuilds only after durable evidence is
   preserved; failure disables the feature and keeps the original payloads.
9. Rain masks hearing only at an exposed rainy source. Roofs, arid biomes, snow
   and unloaded source columns retain the existing radius. The full rain/thunder
   radius multiplier is 0.25, so radius 16 becomes 4. Game uses its actual weather;
   the dedicated host uses the shared deterministic seed/clock front.
10. Owners can inspect the field estimate and rabbit food bonus through existing
    `/ecology info` and F3+G diagnostics. No new block ordinals, control bindings,
    network packets or level/chunk header versions are introduced by this change.

The LW-06 acceptance scenario is four of five wolves removed, seven ordinary
regional days, raids on reachable crops and intact closed fields, followed by a
real gate opening. LW-09 acceptance compares a real noise source heard in clear
weather and masked in thunder. Both acceptances are complete with the frozen
height-aware build; [validation.md](validation.md) records headless, native,
matching LAN, NORMAL dedicated-server and packaged-launch evidence.
LW-11's complete A–C stories and the LW-12 budget
remain separate acceptance work.
