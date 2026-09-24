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

Later RND-09 work must extract native 32×32 tiles, enforce transparent margins
and coherent palettes, add runtime atlas names only alongside real content,
extend the silhouette/alpha tests, and regenerate `docs/textures.html`.
Neither the source sheets nor this note imply that armor or farming gameplay
has been implemented.
