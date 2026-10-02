# Design

Use existing `EcologyRegion` population/capacity/trend values and typed lair/scar
runtime histories as the diagnostic source. Trends are already persisted by
`EcologyCodec`; diagnostics must not add a second simulation or invent a history.
Keep formatting bounded and separate observation from simulation mutation.
The F3 ecology page replaces the usual debug column, leaving gameplay keys alone.

New `LivingWorldTests` exercises interactions in an actual shared WorldSession,
with production blocks/brains/calendar advancement and real SaveManager/ChunkLoader
round trips. New diagnostic tests pin state purity and real command authority.
Existing focused suites retain their individual boundary coverage.

Native acceptance drives Game/update/render/Input and owner commands in one
disposable journey. Network acceptance extends the existing optional scenario
mechanism and uses one combined authoritative fixture. Preparing terrain and the
initial cast before enabling Ecology is allowed; writing successful meals,
recovery, replacement nests or returned finite bodies is not.

A dedicated verifier reads the saved combined fixture after live saving and
after stopping, rather than inferring persistence from in-memory success.
Protected data, OFF hashes, existing caps and ordinary network messages remain
unchanged. Failed fixtures and logs remain available below ignored `out-test/`.
