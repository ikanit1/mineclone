# Entity Skins

Generated through Higgsfield, job `74e27f18-3beb-42c5-a28a-39b76fc8a531`.
Original: `assets/higgsfield-mobs-source.png`.

`cow.png`, `pig.png`, `sheep.png`, `chicken.png`, `zombie.png` and
`player.png` are the runtime textures. Each uses a 4-column, 2-row layout
of 32-pixel tiles: head front, head side, head top, body side, body top,
limb, accent, spare. Player limb and accent contain the arm; body top is
the fist cap. `atlas.png` combines the six skins vertically in that order.

Import with `tools/ImportMobAtlas.java` using the compiled game and library
classpath. The importer retains existing files in `pre-higgsfield` and
removes source-sheet borders using reviewed cell boundaries.

Animation is implemented on the cuboid parts in `MobAnimation` and
`MobRenderer`; it does not require animated texture frames.
