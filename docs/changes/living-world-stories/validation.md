# Living World stories: validation, 2 October 2026

Base: `4915406b6d6d5c87ec7fdf0f905337bc0a7e6a37` (`codex/burn-scars`),
including the previous GitHub generation/survival batch and LW-01…09.
The primary checkout and user saves are preserved. Fixtures belong below
this worktree's ignored `out-test/`.

## Baseline and diagnostic regressions

Unchanged baseline: **935 passed, 0 failed, 0 skipped**, **65.718 seconds**,
`out-test/living-stories-baseline.log`.

New `EcologyDiagnosticsTests`: **8/8**, with the existing command suite **10/10**.
They cover unobserved-region purity, repeated F3 over changed loaded fields,
read-only land, unchanged save bytes/cursors/schedules, exact signed stored
model trends and capacities, real model deltas, separate direct births/deaths,
bounded nearest-location lookup and deterministic ties across negative borders,
scar age/future dates/planted history, opaque/future protection and HUD overflow.
Evidence: `living-diagnostics-focused.log`, `living-diagnostics-commands.log`.

## Actual HUD/GL layout

`EcologyHudPreview` renders the actual Hud/Font/UiRenderer from restored typed
ecology state at 1280×720, 640×360 and 320×180. Known and unobserved reports give
**six frames**, with no GL errors and identical saved state before/after reading.
All six were visually inspected. At 1280 all rows fit; 640 retains all species
and shows five hidden rows; 320 retains nearest nest/scar rows and explicitly
counts sixteen hidden rows. Width clipping uses ellipses.

- [Known snapshot, 1280×720](evidence/ecology-known-1280x720.png).
- [Known snapshot, 640×360](evidence/ecology-known-640x360.png).
- [Known snapshot, 320×180](evidence/ecology-known-320x180.png).
- [Unobserved region, 1280×720](evidence/ecology-unknown-1280x720.png).
- [Unobserved region, 640×360](evidence/ecology-unknown-640x360.png).
- [Unobserved region, 320×180](evidence/ecology-unknown-320x180.png).

Evidence: `out-test/ecology-hud-current.log` and `ecology-hud-current/`.
These are layout previews, not native Game/story acceptance. The first preview
used unchanged baseline classes in `out-test/`, revealing the old info path
seeding an unobserved region. Those frames remain in `ecology-hud-final/`;
current previews explicitly compile the frozen diagnostic sources first.

## Deterministic shared valley

New `LivingWorldTests`: **5/5**, `out-test/living-valley-focused.log`.
One common valley contains fields A, caves B, grove C and a grass trail. A matched
unhunted control proves the predator effect; ordinary rabbit brains eat reachable
crops while a closed plot remains intact. The comparison folds far wolf bodies
back into the model before its next day, respecting the existing protection for
represented predators. Fire callbacks consume mature roots; ordinary scar/plant
schedules and cow navigation produce recovery and physical retreat.

The same fixture destroys and retires a finite nest, proves that two days do not
create a replacement, then discovers a new eligible cave beside forest recovery.
Actual SaveManager close/reopen preserves terrain, trail pressure, plant history,
active/parked animal and spider IDs, unknown level sections and record suffixes.
A loaded but unprocessed returning scar is saved and reopened in the production
ecology-before-chunk-snapshot order. Thirty days away retain bounded histories
and a full active-plus-pending identity census. Ordinary daily steps and owner
advance give identical ecology bytes and surface terrain; cave silk is checked
for presence and bounded catch-up, rather than claiming identical cave pixels.

## Actual native Game journey

`LivingWorldGameSmoke` completed **11,109 actual frames**,
`out-test/living-stories-game-return.log`, twelve raw captures in
`out-test/stories-game-return/`. It uses the normal Game/update/render/Input
path in one fresh valley, seed `20261002`, with Ecology enabled by owner command.
Nine useful captures are retained here; all twelve raw frames were inspected.

- [Day-zero F3+G report](evidence/stories-01-diagnostics-three-stories-day-zero.png)
  and [actual RMB flame beside the original cow](evidence/stories-02-real-input-fire.png).
  Four player-source wolf deaths are retired once; 318 actual ignitions consume
  six original mature root cells and create ten scar records / 192 sites.
  Surviving crown fragments are allowed. Habitat capacity falls **107 → 105**.
- [Scorched valley](evidence/stories-03-scorched-and-wolf-losses.png),
  [finite night spiders](evidence/stories-04-night-finite-spiders.png) and
  [twenty actual W passes](evidence/stories-05-native-walked-trail.png).
  Four bodies leave each of two nights; the first group physically returns at
  dawn. Actual held breaking then destroys the heart during the second outing.
  Ordinary player-source deaths retire its
  wanderers, leaving a destroyed pool of two through three distinct cleared nights.
  Walking gives pressure **20**, stage **5**, with the ground still GRASS.
- [Seven-day young forest](evidence/stories-06-day-seven-recovered-forest.png):
  **119** recovered scar cells and **three** actual new natural roots, while the
  tracked cow **155** physically walks away and survives. Rabbits rise **20 → 36**;
  [ordinary raids](evidence/stories-07-open-raided-fenced-protected.png) damage
  the open plot while the closed plot remains untouched. Opening its gate permits
  a meal. Trail pressure fades **20 → 6** on ordinary arrival at the fields.
- [New cave and first silk](evidence/stories-09-other-cave-first-scheduled-silk.png):
  genuine candidate discovery founds the heart at **89,27,66** on day **8**,
  with zero birth webs. Two scheduled webs appear on day **9**. Ordinary finite
  materialization supplies fresh IDs **189,190,191**, excluding the entire initial
  animal cast; this is population return, not a claim that the original cow teleports home.
- [Return after thirty days away](evidence/stories-12-thirty-days-away-return.png):
  real menu/save/reopening, a **600-block** departure, verified valley unloading,
  owner advance **30**, another save/reopen and return advance the calendar **9 → 39**.
  The protected IDs **154,155,171,172,189,190,191** remain healthy/persistent with
  reservations in the actual disk snapshot. The reopened live allocator also
  remains ahead of them; dedicated verification below reads the disk allocator.
  The complete
  active/pending/saved census is unique. Forest and live colony survive; the
  abandoned lane becomes GRASS / meta **0** / pressure **0** on ordinary field arrival.
  Crop supports/blocks and stages remain valid; normal crops may regrow during absence.

Fixture setup authors only initial terrain/cast while Ecology is OFF. Fire and
ash use accelerated production callbacks; F3+G, ignition, heart breaking and
walking use actual Input. Initial wolf/wanderer combat uses ordinary player-source
damage; native gate opening uses ordinary block metadata, with actual replicated
RMB gate use proved separately by LAN/server. The meal observation temporarily
holds crop-growth callbacks, restoring them before day eight; ordinary rabbit
brains and navigation remain active. Spawn timing is reset, while existing
materialization caps remain in force. No successful recovery, colony or returned
animal is manually authored. The immediate raw reopen frame retains the preceding
overview, so saved field/body proof comes from disk/state assertions, not that image.

The first two attempts stopped at trail assertions while the observer was still
at the distant grove. Pressure reads are intentionally pure; the ordinary loaded
trail tick needs field arrival. The driver now visits that terrain and waits real
frames before both checks. Failed evidence remains in `stories-game/` and
`stories-game-arrival/`. Flame and trail captures were also corrected to wait for
the actual rendered pose rather than capturing a stale frame. Production gameplay
was unchanged by those fixture fixes.

## Actual matching LAN peers

`run-net-test.ps1 -Stories`: **both sides passed**, matching current protocol-13
builds, `out-test/living-stories-lan-stable.log` and `out-test/stories-net/`.
The common valley contains A fields, a live B nest and C scars simultaneously.
Actual W walking gives stage five through ordinary block snapshots. After seven
owner-advanced days, both peers see **four** actual young roots, recovered grass
and faded trail metadata. Actual replicated RMB opens the approach gate; ordinary
rabbit navigation damages the open plot while the fenced crop remains stage seven.
The live nest produces real scheduled webs (**six/five** at the peers' separate
readiness moments; this is asynchronous observation, not simultaneous byte parity).
Ordinary materialization
returns fresh finite ID **179** to the recovered grove; both peers see the same
healthy pose through S_MOBS, excluding the entire initial cast. The guest remains
without a local Ecology model and its owner-only commands are refused.

- [Host young forest](evidence/lan-host-day-seven.png) and
  [guest young forest](evidence/lan-guest-day-seven.png).
- [Guest's trail and actually walking host](evidence/lan-guest-trail-and-walking-host.png).
- [Host's live nest and scheduled silk](evidence/lan-host-nest-silk.png).

These final frames were inspected. The first LAN passed its state assertions
(fresh ID 192), but peer overlap obscured its captures. A subsequent capture run
was interrupted when real desktop cursor movement turned the scripted camera and
prevented the gate input. Previous output is archived under checked `out-test`
siblings. Final Stories poses separate the peers and wait 2.5 seconds for rendering;
a property-guarded presentation hook requires isolated saves/shots and suppresses
runtime mouse rotation only in that test. Ordinary gameplay controls are unchanged.
LAN checks a live nest/silk; full B clearing/recolonization and thirty-day departure
remain the native and deterministic journeys, rather than a network discovery claim.

## NORMAL dedicated server and actual saved world

`run-server-test.ps1 -Stories`: **server and both clients passed**,
`out-test/living-stories-server-final.log`, config `difficulty=normal`, seed
`20261006`, anchor **70,76,70**. Both real guests refuse owner commands and
observe actual walking, replicated RMB gate use, ordinary raid/protected stage
seven, scheduled silk, four young roots and common fresh finite ID **87**.
[Guest zero's young forest](evidence/server-guest0-day-seven.png) and
[guest one's nest/silk](evidence/server-guest1-nest-silk.png) were inspected.
Web readiness moments differ (six/five), as in LAN; the actual saved world has six.

The explicit live `/save` and post-`/stop` invocations independently read current
published level/chunk files, with fresh managers and separate success flags.
Both `stories-save-live.log` and `stories-save-after-stop.log` report:

- day **7**, actual open crop damage **1**, protected crop stage **7**;
- live heart and **six** scheduled webs;
- saved trail day **7**, pressure **6**, matching grass metadata;
- original cow **14**, rabbit **15**, fresh peer-observed finite ID **87**,
  all healthy/persistent with reservations, valid allocator and unique saved IDs;
- **ten** scars / **192** sites, **145** grass cells, **four** grown natural
  roots and recovered capacity **185** (initial **177 → 175** after fire).

The actual `/stop` exits normally with **341** chunk files. An earlier server
run passed post-stop proof (ID 65) but lacked the separately intended live-save
gate; the wrapper was extended and this fresh run proves both gates. A prior
live success cannot bypass a failed final snapshot. The live retry loop has a
20-second deadline; synchronous verifier calls themselves have no separate
process timeout. This is a tool limitation, not a frame-time measurement.

## Runner and review evidence

Full PowerShell runner: **948 passed, 0 failed, 0 skipped**, **33.220 seconds**,
`out-test/living-stories-tests-final.log`. Runner CLI passed listing, filters,
environment/argument precedence, invalid filters and real assertion exit 1,
`out-test/living-stories-cli.log`. Production compilation passed separately.
Final frozen production and test sources also passed Gradle: **948 passed,
0 failed, 0 skipped**, **32.156 seconds**; `test serverZip` completed successfully
in **36 seconds**, `out-test/living-stories-gradle.log`. The extracted Windows
server package booted, saved and stopped normally,
`out-test/living-stories-package.log`. Save formats **11/9**, protocol **13** and
the protected block-ordinal prefix are unchanged. PROJECT_MAP regeneration reports
**402** declarations in **27** packages.

The required `agent-validate run` was attempted once from Git Bash. It stopped
before checks with the retained metrics closure inventory conflict (`console.1.log`)
and retry limit, run 9 / allowed 4. Prior evidence was not reset or removed.
`out-test/living-stories-validator.log` retains this failure; it is not a validator
PASS. The configured checks were run directly, with independent source reviews
covering diagnostic purity, authoritative gate input, saved-state ordering,
control validity, identity census, thirty-day arrival and runner compatibility.

## Completion and open boundaries

LW-10/11 are complete: diagnostic reads, combined A–C regressions, actual native
journey/arrival, matching LAN and NORMAL/two-client replication, common saved
state, full runners and extracted package. The roadmap count is **77/125**;
**48** tasks remain, **41** core and **seven** stretch.

LW-12 budgets and rare frame hitches remain open. Scripted accelerated callbacks,
calendar jumps and fixture preparation do not establish normal-time rates or
hardware frame budgets. Photon, remote CI and the owner's LW-13 playtest/verdict
are not claimed. The retained captures are not the required day-zero/day-twenty
same-view pair for that owner gate.
