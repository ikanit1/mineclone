- [x] Implement the change described by these files:
  - [proposal.md](proposal.md)
  - [specs/mob-persistence/spec.md](specs/mob-persistence/spec.md)
  - [specs/stable-entity-ids/spec.md](specs/stable-entity-ids/spec.md)
  - [design.md](design.md)

Done 2026-09-27: every scenario has a test (`MobPersistenceTests` MP-1…MP-9,
ID-1…ID-4; `NetworkTests` ID-5, ID-6), first seen red. Real paths: LAN pair, dedicated
server with two clients (animals on disk after `/stop`), single-player autopilot (herd
back after a 600-block walk and after reopening the world).
