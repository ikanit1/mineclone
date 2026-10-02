# Fields, rabbits and rainy hearing

LW-06 connects the existing finite wildlife model to a visible survival choice:
fields support more rabbits, rabbits walk to accessible maturing crops and eat
them, wolves limit their population, and closed fences protect the harvest.
Opening a gate gives a rabbit a real route into the field. An eaten plant becomes
a seedling and regrows normally; it does not create loot.

LW-09 makes weather affect mob awareness. Rain and thunder at an exposed sound
source shorten its hearing radius. Shelter, dry biomes and snowfall retain the
usual radius. The graphical host uses the weather it is actually showing, while
the dedicated server uses the deterministic front from the shared seed and clock.

Both changes use the existing opt-in Living World rule. They add no block IDs,
player controls or protocol packets. Last observed field size persists in an
optional bounded section, so leaving an area does not instantly erase its food
contribution. Appetite and navigation remain transient.

This change covers LW-06 and LW-09. It does not implement spider nests, nest
repopulation or burn-scar regrowth, complete the A–C story set, or close the LW-12
hardware performance gate. LW-06 and LW-09 acceptance is complete on the frozen
build: headless rules, actual native Game input, matching LAN peers, the NORMAL
dedicated server and packaged launch are recorded in [validation.md](validation.md).
