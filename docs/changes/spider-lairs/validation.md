# Spider lairs: validation, 2 October 2026

Base: `8e7d1241a524` (`codex/field-raids`), including the previous GitHub generation,
survival/farm and finite-population work. This change implements LW-04 and LW-05
behind the existing opt-in `/ecology on`. No user save was used or changed.

## Deterministic checks and packaging

The unchanged baseline passed **872/872** before implementation. Final PowerShell
suite: **904 passed, 0 failed, 0 skipped**, 63.937 seconds. Gradle `test serverZip`
passed the same **904/904** in **62.643 seconds**, including the final peaceful
regression, and rebuilt the final package in a successful **1m 10s** build.
Runner CLI passed listing, filters, environment
precedence, bad filters and the deliberate assertion exit. Production compile passed.
The extracted server package opened TCP, saved `level.dat`, accepted `/stop` and
exited 0. Logs are in ignored `out-test/spider-lairs-*.log`; the extracted package
evidence is recorded in `out-test/spider-lairs-package-final.log`, with extracted
directory `out-test/server-package-0dcc234ada3e4d2a81853d6762cd58a1`.

The 32 added checks comprise LairTests (9), NestBlockTests (8), ColonizerTests (7)
and LairRuntimeTests (8). They cover bounded definitions/records, corpse accounting
before saving, shared hostile caps, physical dawn walking, broken-heart survivors,
daily growth and first-observation replay parity, real chunk/level reopening and
parked IDs, cave light/support/roofs, bases, recent visible paths, sparse index
restoration, web walls/loaded boundaries, unknown tails and protected newer data.
The existing command check now verifies actual read-only nest diagnostics and the
existing guest/disabled-command checks still deny access before model mutation.
Legacy OFF simulation/generation hashes passed without updating their expectations.

Independent review reproduced a first-observation replay defect: a newly placed
heart seen during `/ecology advance` was initially stamped with the final clock day,
skipping its replayed growth. `observe` now receives each logical replay day.
The failing regression and fresh 17/17 model/runtime result are retained in
`out-test/lair-runtime-discovery-before.log` and `lair-runtime-discovery-after.log`.
Initial full-suite failures also caught an outdated protocol document and the old
command-placeholder assertion; both were corrected before the final green runs.

Two final regressions exercise the real lifecycle between frames. A heart broken
immediately before saving used to remain alive in the ecology section; immediate
time advancement could also shift its destruction onto the next day. The host now
refreshes known hearts before saving or moving the clock and on every lair tick.
Before/after evidence is `out-test/lair-heart-save-before.log` and
`lair-heart-save-after.log`. The existing check covers both boundaries.

Native acceptance also found premature silk on a day-8 colony: founding during
replay scheduled from the dispatcher's old cursor, before the heart existed.
The regression observed due tick **25,133** instead of at least **226,216**.
Founding now anchors the first tick to its logical birth, independently of that
cursor. Existing relative block scheduling and bounded late-tick handling keep
their behavior. The regression and 100/100 ecology result are retained in
`out-test/lair-web-found-time-before.log` and `lair-web-found-time-after.log`.

Final review pinned another boundary in the shared WorldSession: killing a spider
then switching to PEACEFUL before the next tick removed its corpse as a living
return, keeping six members instead of five. The removal now accounts dead bodies
before discarding them and folds only living retirement. The existing death check
asserts population five, no represented bodies, repeated-death idempotence and save
consistency. Before/after logs: `out-test/lair-peaceful-death-before.log` (17/1) and
`lair-peaceful-death-after.log` (100/0 ecology). This peaceful-only change was made
after the rendered/NORMAL network runs below; the full final Gradle suite and
package include it. The normal-difficulty path exercised below is unchanged.

## Actual native Game

`java -cp 'out;libs/*' tools/LairGameSmoke.java out-test/lair-game-birth-final` completed
**4,585 native frames**, six actual captures and exit 0 after the birth-time fix.
The tool creates and verifies disposable terrain, then uses Game, WorldSession,
InteractionController, actual held mouse input for the heart, ordinary player
DamageSource/Mob.damage for casualties, owner commands and menu/save/reopen.
It does not call Lair.claim/register or fabricate the lifecycle outcome.

The finite pool of six emitted IDs 135,137,139,141 at night. Tracked bodies moved
towards the heart and all returned at dawn without reducing the pool. Another night
emitted 143,145,147,148 from that same pool. Held left mouse removed the real heart;
all four actual damage/death paths retired their membership once. On three separate
calendar nights the old territory stayed empty, the heart stayed dead and its
remaining archived pool did not grow.

Destruction occurred on day 0. After nights 1–3, the owner advanced five more days;
by **day 8** another eligible natural cave had a heart at **(89,27,66)**, 88.48 blocks
horizontally from the old heart. Its block/sky light were zero, it avoided the real
chest base and observed old path, and emitted 149–152. The result accepts the actual
natural cave, rather than requiring an authored second chamber. On founding day 8
there was no new silk; an ordinary owner advance of one day and the real scheduled
tick produced exactly two WEB blocks on day 9. Menu/reopening preserved ecology
bytes, both webs and those same IDs, with no active/pending duplicates.

All six captures were visually inspected. Evidence: [front](evidence/heart-front.png),
[oblique](evidence/heart-oblique.png), [four spiders](evidence/night.png),
[returned at dawn](evidence/dawn.png), [third cleared night](evidence/cleared.png),
[natural colony after reopening](evidence/new-colony.png). The original authored
nest texture remains readable in different views, with silk and spiders in the cave.
Exact markers and poses are in `out-test/lair-game-birth-final.log`.

## Actual older reader

`LairReaderCompatibility write -> rewrite -> verify` passed with current classes
and the **actual reader-8 classes from the base build**, copied before Gradle rebuilt
them to ignored `out-test/pre-lair-classes`. Final fresh fixture: `out-test/lair-reader-compat-final`.
The old EcologyCodec round-tripped typed LAIR bytes as opaque records; its level
writer kept those records, an unknown record and suffixes unchanged. Reader 8
returned `TooNew` for chunk 9 and its attempted bypass write was blocked. The current
reader reopened block ID 64, pool six, body 701, body tail and cave-hint bytes intact.
All three final provenance logs are retained under `out-test/lair-reader-*-final.log`.

This proves optional-level preservation and protected terrain refusal. It does not
promise that a build lacking the new block can play new chunks. Existing chunk
versions 1–8 remain readable by this build; new chunks require minimum reader 9.
Matching multiplayer peers require protocol 13; packet shapes remain unchanged.

## LAN and dedicated server

`run-net-test.ps1 -Lairs` passed on both actual peers after the birth-time fix.
They observed the same initial body IDs **98,99,100,101**. Ordinary guest mouse
input removed the heart; all four wandering identities survived its removal.
Ordinary guest combat killed ID 98; both peers observed a non-null dead pose
with health **-3.7857137**, including the non-attacking host. Absence from a snapshot
is not accepted as proof of death. The old territory stayed closed the next night.
After an actual owner ten-day command both peers saw the other-cave heart at
**(2,30,136)**. Full evidence: `out-test/spider-lairs-lan-final.log`.

`run-server-test.ps1 -Lairs` passed on **NORMAL** with the dedicated server and two
actual Game clients. Both received IDs **12,14,16,17**, witnessed the real death of
ID 12 (including the non-attacker), heart removal and later-night closure. After
the server's actual ten-day command, both saw the replacement at **(80,2,140)**.
The mandatory read-only save verifier passed before `/stop` and again after clean
server exit: clock day 11, old heart AIR, old population **5**, surviving IDs
**14,16,17**, killed ID absent, new heart SPIDER_NEST, **201 saved chunks** and no
duplicate saved identities. Evidence: `out-test/spider-lairs-server-final.log`.

The new network colonies emitted no bodies because the ordinary hostile caps
were occupied; acceptance retains those caps. The native scenario above verifies
the replacement's emission and exact identity persistence. Guest commands remain
denied and guest mirrors do not run Ecology.

## Boundaries

LW-10 now has actual `/ecology lair` and F3 nest diagnostics, but full histories A–C
and LW-11 remain open. Photon and LW-12 hardware/performance gates were not run.
The final native log contains update spikes of **39.4 and 33.3 ms**, mostly in the mob
phase after day changes. Functional acceptance is not an absence-of-hitches claim.
Founding uses already loaded eligible writable terrain; inaccessible, lit or base-
protected caves are not loaded or bypassed to satisfy the 3–10-day criterion.

Agent Validator was attempted and failed **before checks** on retained metrics
closure inventory (`changed console.1.log`) and its exceeded retry limit. No logs
were reset to conceal that evidence. Its configured suite and runner-cli checks
passed directly. Claude CLI review is disabled by repository configuration;
independent source reviews were performed in this session.
