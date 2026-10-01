# Roadmap 1.1 source sheets

RND-09 part 1, 24 September 2026. Generated with the built-in OpenAI `image_gen`
tool. Exact prompts and observations are in each adjacent JSON. These are source
sheets for the existing offline import pipeline; no network generation happens
at runtime.

| Sheet | Layout | Contents |
|---|---|---|
| `armor-2026-09-24.png` | 4 columns × 4 rows | Helmet, chestplate, leggings, boots; leather, iron, gold, diamond |
| `hoes-2026-09-24.png` | 3 columns × 2 rows | Wood, stone, copper, iron, gold, diamond hoes |
| `crops-2026-09-24.png` | 4 columns × 3 rows | Four stages each of wheat, carrot and potato |

All three were visually inspected. Armor and crop sheets have transparent
backgrounds; hoe silhouettes are present but their soft fringe needs removal
during import. Cell rectangles must use rounded proportional boundaries because
the generator did not make the armor image dimensions divisible by four.

RND-09 part 2, 1 October 2026: `tools/ImportRoadmapTextures.java` now imports
34 native 32×32 sprites. It checks these files against the SHA-256 values in
their adjacent JSONs, samples logical pixel centres, requires alpha >= 240,
sets surviving alpha to 255 and clears the outside row/column. Hoes share the
reviewed wooden silhouette with six material palettes; crop stages keep their
original size and ground alignment. The source bytes remain unchanged.

Runtime atlas indices 140–155 are armor icons, 156–161 hoes, 162–173 crop stages.
These atlas entries prepare the rendering assets; item definitions, equipment
logic and farming gameplay are still upcoming tasks. `EquipmentTextureTests`
checks resolution, hard alpha, margins, hoe silhouettes and growth stages.
`RenderRoadmapTexturePreview` renders the actual game atlas/UI under OpenGL;
`ExportTextureCatalog` exports the real runtime PNGs into `docs/textures.html`.

Reproduce from the repository root with a JDK:

```bash
java tools/ImportRoadmapTextures.java
# An optional first argument selects a scratch output folder for comparison.
java tools/ImportCopperOreTexture.java
java -cp 'out-test:libs/*' tools/ExportTextureCatalog.java
```

Copper ore uses the exact stone base and iron-ore fleck mask of the existing
reviewed equipment pack, mapped to a three-stop copper ramp by
`ImportCopperOreTexture`. It is tile 139 and is already an obtainable block.
Remaining RND-09 work includes armor model sheets, other item icons, construction,
farmland, saplings, ground vegetation and status icons. This note does not imply
that armor or farming gameplay has been implemented.
