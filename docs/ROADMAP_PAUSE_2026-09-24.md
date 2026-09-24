# Roadmap paused at user request — 2026-09-24

> **Status: resumed.** The resume points below were completed on
> `claude/jolly-franklin-wiwdbh` (SIM-02, SAVE-07, BLK-06); see
> [ROADMAP_PROGRESS.md](ROADMAP_PROGRESS.md). This file records the pause as it was.

The user requested that development and long-running checks stop and that all
current work be committed and pushed. No automatic continuation is scheduled.

## Branches and verified baseline

- `develop-1.1`: M0 implementation at `0b7a12a3e132df990d99d90fd83ef5a90f4a1081`,
  with 528 passing headless checks, native save/UI checks, LAN, dedicated-server
  and packaged-server checks. Remote Windows CI passed.
- [PR #4](https://github.com/ikanit1/mineclone/pull/4) remains a draft. The full
  performance baseline was interrupted; M0 is not accepted as complete.
- `codex/m1-core-architecture`: this unverified work-in-progress snapshot, based
  on the same M0 commit. It has not been compiled or tested and is not a release.

The benchmark used a private frozen M0 snapshot while M1 was authored in another
worktree. Heavy local compilation and tests were deliberately deferred so that
they would not distort the measurement. They were not run before this pause.

## SAVE-07: authored, not validated

`PlayerRecord` and its builder/section codec, a marked nested player format in
level v11, separate world spawn, guest v2 with v1 reading, queued guest persistence,
and adapters preserving the existing wire v7 layout are present. Game capture
and restore and dedicated-server owner persistence use the shared record.
Rename, duplication and backup restoration preserve it.

Still needed: dedicated regression tests and registration, migration/corruption
coverage, compatibility review, network/native lifecycle checks and documentation.
Old tests may still assume a top-level inventory section or mutable legacy fields.
NET-02 and wire v8 are not implemented. New marked level-v11 payloads require
the new reader; compatibility must be validated before using real worlds.

## BLK-06: authored, not validated

Loot context/table/registry/reachability code and JSON tables are present for
19 block tables, 11 mob tables and four empty chest tables. Game drops use the
registry; former block and mob drop methods are removed. Existing core/feature
tests were adjusted, and source/expected-output fixtures preserve the old policy.

Still needed: golden verification against the frozen M0 implementation, focused
loot and reachability tests, malformed-data fixtures, test registration, full
compile/search/integration checks and documentation. Chest generation is not
implemented. Some item sources remain unavailable until GEN-08. Tool conditions
for mobs currently read the player's tool when processing death rather than a
snapshot at the fatal hit; current tables do not use that condition.

## SIM-02: tests-first draft only

`ParticipantTests.java` is a design/test draft and is not registered. The participant
interfaces, adapters and membership lifecycle it references are not implemented.
Consequently the current test tree is not expected to compile. The damage assertion
must use `NetworkTests.TestContext.hurtTaken`; loopback constructor compatibility
also needs checking before the tests can be executed.

GEN-01 implementation has not started. No milestone after M0 is complete.

## Resume points

Review the uncompiled changes before integration, implement the missing SIM-02
contracts or move its draft out of the compiled test tree, add the missing SAVE-07
and BLK-06 tests, then compile and run the relevant real paths. The interrupted
performance data must not be promoted to a completed baseline; finish a controlled
full run and evaluate the repeatability gate separately. Do not merge this WIP
branch into a release solely because it has been pushed.
