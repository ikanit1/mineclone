# Living population verification

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

These are baseline results; the merged generation/equipment/farming build needs
its own checks. The full A–C stories, Photon and LW-12 performance budget remain open.
