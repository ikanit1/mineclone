# Regional population and body ownership

Population is the total, including represented animals. A reservation keyed by the
existing world mob id holds an animal out of the available abstract population.
Materializing or saving a body does not increase the total. Death removes its
reservation and decreases the total once. Player, predator, fire and other losses
are recorded separately. Saved or parked bodies retain their reservation; only a
body with no loaded chunk to save can return to the abstract count.

The shared host/server session parks distant animals as unchanged pending chunk
records, and adopts them again when a participant returns. This frees the old active
caps on servers that keep distant chunks loaded. Existing bodies from older worlds
are adopted without deleting them. Population may initially rise to the number of
such bodies, but materialization cannot raise it.

The pure model operates on copied snapshots in stable coordinate order. Each day
applies logistic births, predation of unrepresented prey by unrepresented wolves,
then simultaneous migration of excess animals into cardinal neighbours. Randomness
depends on world seed, region, species and day. Existing bodies are protected from
abstract deaths. Lazy regions start at 70% of climate capacity; loaded land updates
256 greenery samples, with unseen chunks retaining their previous habitat estimate.
Catch-up replays the final 60 missing days at most and never rewinds a future region.

`ecology` is an optional bounded level section. Species ids and opaque future records
survive a roundtrip. Unknown layouts disable Living World and stay byte-identical.
Damaged bytes must be durably preserved after the session backup before rebuilding;
failure to preserve them disables the feature and retains the original section.
The `rules` extension is an append-only six-byte suffix: LWR1 magic, version, boolean.
Existing unknown rule bytes stay intact.

Animals appear on already loaded terrain, outside every living participant's view,
at least 32 blocks away, with collision-safe footprints and existing local/global
caps. Their bodies travel through ordinary `S_MOBS`; there is no protocol change.
