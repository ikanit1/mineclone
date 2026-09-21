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

`player.png` has since moved to a sheet of its own — the camera gets closer
to the player than to anything else, and sharing eight tiles with five mobs
was costing them resolution. Source: `assets/higgsfield-player-source.png`,
importer `tools/ImportPlayerSkin.java`, previous file kept in `pre-import`.
That importer stretches the middle column of the two long arm tiles across
the tile: an image model draws an arm as an object on a background, but a
cube face has to be filled edge to edge.

Editing by hand: `player.png` is the file the game loads. Delete it and the
procedural skin in `render/PlayerSkin.java` takes over. The texture is
uploaded once at start-up, so a change needs a restart to show.

Animation is implemented on the cuboid parts in `MobAnimation` and
`MobRenderer`; it does not require animated texture frames.
