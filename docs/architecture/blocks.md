# Stable block IDs and properties

`BlockType.ordinal()` is the persisted and replicated block ID. Existing values
must never move or be removed; append new values at the end. The baseline has
**48** blocks, as measured from the tagged code, rather than the estimated 50 in
the roadmap. `fixtures/block-ordinals.txt` records that immutable prefix.

`BlockProps` centralizes cross/layer/gravity/soil/flammable flags, grip, movement
speed, preferred tool and required tool tier. Its exhaustive switch builds a
cached array indexed by ordinal. Adding a block requires an explicit properties
entry at compile time. `BlockType` methods delegate to that table; geometry,
hardness, textures and drops keep their current APIs pending later BLK tasks.

`BlockOrdinalTests` compares both the name/ordinal prefix and every baseline
property in `fixtures/block-properties-v1.txt`. The property fixture was captured
from compiled pre-refactor classes, so it detects accidental behavior changes
rather than restating the new switch.
