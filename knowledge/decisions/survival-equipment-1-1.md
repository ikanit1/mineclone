# Survival, equipment and player ownership in 1.1

Accepted: 2026-10-02. Covers SURV-02..08, CMB-01..03 and the protocol-12 batch.

Passive hunger made inactivity costly and all meals equivalent. Exhaustion now
represents sprint distance, jumps, mining, attacks, damage and healing. Saturation
is spent before hunger; the regeneration threshold moves from 14 to 18. The
21-minute deterministic activity check consumes two or three cooked portions.
This intentional balance change leaves movement and stair auto-step unchanged.

Keep the 36-cell inventory format. Four armor cells plus offhand use Equipment
and the existing optional player section; old worlds migrate empty equipment.
Host personal menus execute equipment/crafting gestures with the same ownership
transfer as shared containers. Preserve future equipment tails in template records.
Armor uses the specified nonlinear formula: raw 3 against iron 15 is 1.38. The
old roadmap example of 1.2 conflicted with that formula and is corrected.

World rules belong to the host and are versioned in level.dat. Host-generated
effects belong to the host checkpoint; the client runs their gameplay against
client-owned vitals. Host timers advance only for connected guests. Known client
food effects retain the prior consumption trust model. Unknown names, damaged
sections and host-issued achievements survive stale client checkpoints.

Do not upgrade saved terrain flags implicitly. New worlds enable vegetation and
initial herds; existing worlds keep their flags until their explicit generation
upgrade. Herd generation has a saved empty receipt, stable long IDs and whole-batch
validation before publication. Killing/unloading a herd cannot regenerate it.
Death drops transfer inventory/equipment/pending once and supplement item lifetime
in an optional section, keeping legacy ordinary items at five minutes.

Armor uses four separately cached model textures. Color and shadow parts share
transforms; one material is an instanced draw with reusable uniform buffers.
Hardware milestone/performance baseline gates remain separate from functional
acceptance in the container; software GL measurements are reported as such.
