# Living population verification

## Integrated GitHub build, Windows/JDK17, 2 October 2026

Includes `origin/codex/roadmap-1.1-generation` at `c6c1774` (the 30-task batch),
its GEN-04/08 and GEN-02 predecessors, collision recovery and Living World trails.
The separate dirty generation worktree and original saves were left untouched.

| Check | Result and boundary |
|---|---|
| `run-tests.ps1` | **849 passed, 0 failed, 0 skipped**, 29.551 s |
| `tools/TestRunnerCli.ps1` | PASS: filters/list/environment precedence and assertion exit 1 |
| `run.ps1 -CompileOnly` | PASS |
| `PopulationGameSmoke.java` | PASS, 118 real Game frames, commands, finite herds, one death/loot, ten days, menu/save/reopen, byte-identical ecology/rules and same body ID, no duplicate bodies, F3+G |
| `PlayerCollisionSmoke.java` | PASS: held keys at 20/30/60/144 FPS; enclosed player/mob recovery without roof escape |
| `run-net-test.ps1 -Farm -LivingWorld` | Both host and guest PASS; authority refusal, mob/equipment/forms replication, farm maturation/harvest/tree |
| `run-server-test.ps1 -Farm -LivingWorld` | Server and both clients PASS, console ecology on/advance/save/stop; 82 chunks on disk |
| `gradlew.bat serverZip` | BUILD SUCCESSFUL; extracted archive launcher reaches TCP, saves and stops with exit 0 |

Native captures were inspected: [day 0](evidence/day-0.png) and
[day 10 after reopening](evidence/day-10-reopened.png). These are functional
checks, not the full A–C stories or a performance acceptance run. Runtime smoke
logs contain frame/update spikes; LW-12 and existing rare-hitch work remain open.
Photon and operation on a clean machine without a JDK were not tested.

Independent integration review found and resolved distant pending-body accounting,
save-before-death accounting, IDs allocated after level checkpoints, incompatible
animal layouts, and collision recovery around tall fences. Existing babies,
breeding clocks, full-width IDs and unknown tails now survive the merged lifecycle.

Agent Validator's earlier standalone population snapshot passed tests and runner-cli.
For the integrated build its retained metrics closure and retry limit stop invocation
before checks. The two configured underlying checks passed directly above;
the wrapper is not claimed to have passed. Claude review remains disabled by
repository configuration; independent source review was performed in this session.

## Actual older-reader compatibility

`PopulationSaveCompatibility.java write -> rewrite -> verify` used the real
pre-ecology build `6f35d01eab9e` from `E:/mineclone/out`, which has no EcologyCodec.
The fresh tool-owned fixtures live under `out-test/population-merged-compatibility-v3`.

- A separate specimen with supported clock version 1 and unchanged player bytes
  was rewritten by that build. Known/unknown species, one reservation, rule bytes,
  and opaque region/lair/scar/section tails remained byte-identical.
- A normal current clock-version-2 level was refused by the older build, which
  could not overwrite its original bytes even through a direct save attempt.
- The canonical chunk was returned as TooNew by the old reader (8 requires 8,
  old reader supports 7), survived a bypass-save attempt unchanged, and reopened
  under the current build with the same baby ID, age and opaque tail.

Ecology SHA256: `c90e669a95536c934daf32ff9292f55d5ecc81b4afe1a477510257152c091a85`.
This proves optional payload preservation and safe refusal, not complete-world
playability in an old build that lacks clock version 2 and chunk reader 8.

## Earlier standalone baseline

2026-10-02, Windows/JDK17, before integration with the newly published
`codex/roadmap-1.1-generation` series:

- Full TestMain: 763 passed, 0 failed. Agent Validator tests and runner-cli passed.
  Its historical log cleanup warning does not change the passing gate result.
  Claude review is disabled by repository configuration; independent agent review ran.
- Native Game: PASS,125 frames; actual F3+G, owner commands, finite herds,
  player death and loot, ten-day catch-up, save/menu/reopen, exact rule/population
  bytes and restored body identity. Captures inspected.
- LAN with Living World: both sides passed, owner enable survives room opening,
  guest command refused, all ordinary mob species replicated.
- Dedicated server with Living World: both clients passed, console enables and
  advances10days, clean save/stop,21chunks on disk.
- Genuine legacy build6f35d01 (no EcologyCodec) rewrote a new ecology fixture;
  the current build verified byte-identical ecology/rules including unknown species,
  reservation and opaque records. Ecology SHA256:
  c90e669a95536c934daf32ff9292f55d5ecc81b4afe1a477510257152c091a85.

The integrated results above supersede this baseline for current implementation.
The full A–C stories, Photon and LW-12 performance budget remain open.
