# Current equipment pack (2026-09-24)

The current equipment and stone pack is generated with Higgsfield and imported
by `python tools/import_equipment_pack.py` (Pillow required). It writes 36 native
32x32 PNGs: 24 tools, three ingots, a stick and eight stone/ore tiles. The original
images, full prompts, job IDs and checksums are in `equipment-2026-09-24.json`.

Inventory, dropped items, first-person and third-person tools all use the same
sprite. Material variation is baked into assets; held tools no longer remap
iron geometry to a different icon or an opaque material swatch.

The four silhouettes are shared across wood, stone, iron, copper, gold and
diamond. A saturated magenta key removes the generated background without
erasing dark outlines. Nearest-neighbour sampling preserves square pixels.
The four ores keep the same stone base between their mineral clusters.

Review with `tools/RenderEquipmentReview.java` after `run-tests.ps1`. See
`docs/audits/2026-09-24-equipment-textures.md` for captures and remaining findings.

# Historical pack (2026-09-20)

`ImportToolTextures.java` below is the historical importer. Rerunning it would
overwrite some current equipment assets; use `import_equipment_pack.py` instead.

Tool and lava textures generated with the Higgsfield plugin, model `gpt_image_2_5`.
Job: `2a91ff77-17a8-4936-9ad7-0c2209648e34` (2026-09-20).

`higgsfield-tools.png` is the original 4-column, 2-row sheet: lava, flowing lava,
gold tools, copper tools and a stick. `tools/ImportToolTextures.java` crops the
sheet to native 32x32 sprites and writes opaque material swatches used by the
first-person 3D meshes.
