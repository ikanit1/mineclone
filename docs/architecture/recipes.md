# Recipes and smelting

`ItemRegistry.load(DataPack)` resolves items, then item tags, then the immutable
`RecipeRegistry`. A bad recipe fails loading with `JsonException` naming the JSON
file and key. `Recipes` and `Smelting` use the registry belonging to the current
`Items` instance; replacing that instance also replaces their tables.

Each `assets/data/<namespace>/recipes/**/*.json` or `smelting/**/*.json` file is
an object mapping recipe IDs to definitions. Bare item, tag and recipe names use
the file's namespace. IDs are unique across both directories. Recipes sort by
integer `order` (default `0`), then full recipe ID. This also resolves overlapping
smelting recipes: the first matching input wins. Files may contain any supported
type; `recipes` defaults to `shaped`, `smelting` defaults to `smelting`.

```json
{
  "wooden_pickaxe": {
    "type": "shaped", "order": 4, "group": "tools",
    "pattern": ["MMM", ".S.", ".S."],
    "key": {"M": "#mineclone:planks", "S": "stick"},
    "result": {"item": "wooden_pickaxe", "count": 1},
    "mirrored": false
  },
  "planks": {
    "type": "shapeless", "order": 0,
    "ingredients": ["log"],
    "result": {"item": "planks", "count": 4}
  },
  "smelt_iron_ore": {
    "type": "smelting", "input": "iron_ore",
    "result": "iron_ingot", "time": 8.0
  }
}
```

Shaped patterns have one to three equally wide rows of one to three characters.
A dot or space is an empty cell. Every other character must have exactly one
legend entry, and unused legend entries are rejected. Pattern dimensions are
preserved, including empty border cells: the migration keeps the old three-wide
shovel/sword placement behavior. Shapes can shift within a grid, and reflect
horizontally only when `mirrored` is true. Every cell outside or empty within the
shape must remain empty.

Shapeless recipes require one to nine ingredients. Each ingredient consumes one
occupied crafting cell, regardless of that cell's stack count. Inventory crafting
can draw several units from the same stack. Ingredient strings accept a registered
item or `#tag`; tags must exist and have at least one item. Bipartite matching
reserves an exact ingredient's item when a broader tag could also consume it.
Missing materials or no output capacity leave the inventory unchanged.

There is no two-ingredient restriction. The old `Recipe.need/handle` preview API
contains the two most frequent representative items; equal counts preserve first
occurrence in the pattern. A tag's representative is the first member sorted by
full item ID. `ingredientAt`/`ingredients` hold actual predicates, while `at`/`pattern`
return preview items for existing interfaces. Arrays and registry lists cannot be
mutated by callers.

Results accept an item string (one item) or `{ "item": "id", "count": N }` with
count from one through the item's stack limit. Smelting `time` defaults to eight
seconds and must be finite and positive. Furnace progress, produced quantities
and output capacity use the matched data recipe. Fuel duration remains an item
property. Unknown fields are rejected.

The original 35 crafting recipes live in `basic.json`, `tools.json` and
`building.json`; all nine smelting mappings live in `food.json` and `ores.json`.
Their orders, shapes, reflection flags, outputs and durations are checked against
the independently captured pre-migration tables in
`src/test/resources/recipes/legacy-{crafting,smelting}.tsv`. Capture happened before
removing either Java table, from compiled `Recipes.all()` and `Smelting.result()`.
The source blob IDs at capture were:

- `Recipes.java`: `96b9d69cf4c1105ddacfeb627ea05af721db9ecf`
- `Smelting.java`: `a30b5f9800c8ec8f64e2a16a3b55089291b8fa63`

`RecipeDataTests` checks both goldens, every legacy shape at every fitting grid
offset and supported reflection, overlapping tags, three ingredients, full-output
atomicity, JSON furnace timing/counts, and twelve malformed fixtures with exact
file/key diagnostics. It also rejects hardcoded item-ID literals in `Recipes.java`.
Run `./run-tests.ps1 -Only data,inventory,core,feature` for these and the existing
crafting/furnace checks.
