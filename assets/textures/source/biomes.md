Biome materials generated with the Higgsfield plugin, model `gpt_image_2_5`.
Job: `04e471c6-0f36-40ca-9cac-05903a5591f8` (2026-09-20).

`higgsfield-biomes.png` is the original 4-column, 2-row sheet. Rows contain:

1. podzol, peat, dry grass, red sand;
2. terracotta, limestone, basalt, gravel.

Run `java tools/ImportBiomeTextures.java` from the repository root to reproduce
the eight 32x32 PNG tiles using nearest-neighbour samples. No procedural replacement
textures are used. The source model coerced the requested 2:1 sheet to 16:9;
each rectangular cell is resampled to one square game tile.
