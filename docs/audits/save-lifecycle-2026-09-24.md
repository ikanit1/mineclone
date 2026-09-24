# Save lifecycle native review — 2026-09-24

The hidden native `tools/SaveLifecycleSmoke.java` runs the real `Game.run()` loop,
including normal loading, menu rendering, input handling, save/close/reopen and
GPU queries. All worlds are disposable fixtures under `build/review-smoke`.

Result: **PASS, 183 frames, 182 completed GPU samples**. The check established:

- A corrupt world is refused in the rendered menu, with identical original bytes.
- A delayed session backup keeps rendering the progress screen.
- Precise world-clock ticks/fraction and an unknown section survive save, close
  and reopening; the player's 17 diamonds also survive.
- Opening twice creates two session snapshots.
- Mouse input on “Восстановить как копию” restores and opens a separate world.
- The backup list, refusal dialog, progress screen and restored world were
  captured and inspected visually at 960×540.

Artifacts from the successful run are in `build/review-smoke/results-4/`:
`corrupt-refusal.png`, `backup-progress.png`, `world-before-reopen.png`,
`backups-ui.png`, and `restored-copy.png`. The renderer is exercised at radius 2;
this is a lifecycle/graphics correctness check, not a normal-radius performance
measurement.

## Defects found and fixed

The first playing frame originally raised `GL_INVALID_VALUE` in
`Crosshair.render` at `glLineWidth(2)`. A synchronous OpenGL debug callback located
the exact call. Forward-compatible core contexts do not guarantee wide lines.
The crosshair now uses two 2-pixel triangle rectangles, retaining its thickness.
The block outline uses portable width 1. The successful native run checks
`glGetError` every frame and completed without GL errors.

The dedicated server previously rewrote the loaded local owner's checkpoint as
an empty player on autosave. It now keeps the loaded inventory (including tool
wear), pending items, position, view, selected slot and vitals while updating the
server clock and world metadata. `DedicatedServerSaveTests` exercises the actual
server world-open/save path with transports disabled and verifies those fields,
the unknown section and the exact encoded clock.

The review also found that the game read a same-id reopen request before
capturing the active world's latest state. The Game integration now captures
that state first; the level read barrier waits for its queued write before
retaining the data used to reopen.

The dedicated-server checkpoint regression was run independently and passed.
GPU phase review found one `next` per measured phase in each mutually exclusive
menu/world render branch. The native smoke completed 182 query samples while
switching those branches repeatedly, with no overlapping-query or reused-slot
errors after the crosshair fix.

After correcting the smoke tool's ownership-check property name, the final
native rerun against the current `out-test` build also passed: **180 frames,
179 GPU samples**, with `mineclone.checkThreadOwnership=true`, no ownership
exceptions and no GL errors. Its separate evidence is retained under
`build/review-smoke/ownership-guard-final/`; clock/unknown-section roundtrip,
corrupt-byte preservation and backup UI restoration all passed again.
