# Spider nest

`blocks/spider_nest.png` is original 32 × 32 pixel art made for this project.
Dark interlocked decayed twigs surround five chalk-colored silk egg sacs,
with asymmetric silk strands anchored to the rim. No external game assets
or source images were used. All pixels are opaque and the artwork remains
readable with the existing nearest-neighbor atlas sampling.

The authored palette, shapes and pixel positions live in
`tools/DrawSpiderNestTexture.java`; run `java tools/DrawSpiderNestTexture.java`
from the repository root to reproduce this one sprite. Runtime rendering
reads the PNG, never this drawing tool. The append-only atlas tile is 205.
