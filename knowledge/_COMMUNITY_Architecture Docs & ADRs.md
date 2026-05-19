---
type: community
cohesion: 0.12
members: 25
---

# Architecture Docs & ADRs

**Cohesion:** 0.12 - loosely connected
**Members:** 25 nodes

## Members
- [[ADR Chunk Loading Strategy]] - document - knowledge/decisions/chunk-loading-strategy.md
- [[ADR Chunk Rendering Approach]] - document - knowledge/decisions/rendering-approach.md
- [[Architecture Overview]] - document - knowledge/architecture/overview.md
- [[Autosave (PausePeriodicQuit + Manual Save Button)]] - document - docs/superpowers/specs/2026-05-16-save-system-and-menu-revamp-design.md
- [[Chunk Loading (Background Thread Pool)]] - document - knowledge/decisions/chunk-loading-strategy.md
- [[Chunk Modified Flag (Post-Generation Edits)]] - document - docs/superpowers/plans/2026-05-16-world-persistence.md
- [[Dual-Mesh Transparent Water Pass]] - document - docs/superpowers/specs/2026-05-16-water-rendering-design.md
- [[Layered MINECLONE Title (Gold Fill, Outline, Shadow)]] - document - docs/superpowers/plans/2026-05-17-main-menu-revamp.md
- [[MenuBackground (Dedicated Mini-World + Orbit Camera)]] - document - docs/superpowers/plans/2026-05-17-main-menu-revamp.md
- [[Mineclone Knowledge Base]] - document - knowledge/README.md
- [[New World Flow (Confirm Dialog, Delete Save, Relaunch)]] - document - docs/superpowers/plans/2026-05-17-main-menu-revamp.md
- [[Options Persistence (options.dat)]] - document - docs/superpowers/plans/2026-05-17-main-menu-revamp.md
- [[Plan Main Menu Revamp + Settings Persistence]] - document - docs/superpowers/plans/2026-05-17-main-menu-revamp.md
- [[Plan Underwater Effects]] - document - docs/superpowers/plans/2026-05-19-underwater-effects.md
- [[Plan Water Rendering]] - document - docs/superpowers/plans/2026-05-16-water-rendering.md
- [[Plan World Persistence]] - document - docs/superpowers/plans/2026-05-16-world-persistence.md
- [[Render Pipeline (Opaque + Water Passes)]] - document - knowledge/architecture/overview.md
- [[SaveManager (Disk IO, GZIP, Background Executor)]] - document - docs/superpowers/plans/2026-05-16-world-persistence.md
- [[Spec Save System and Menu Revamp Design]] - document - docs/superpowers/specs/2026-05-16-save-system-and-menu-revamp-design.md
- [[Spec Underwater Effects Design]] - document - docs/superpowers/specs/2026-05-19-underwater-effects-design.md
- [[Spec Water Rendering Design]] - document - docs/superpowers/specs/2026-05-16-water-rendering-design.md
- [[Stone-Textured Beveled Menu Buttons]] - document - docs/superpowers/plans/2026-05-17-main-menu-revamp.md
- [[Underwater FOV Reduction (Smooth Interpolation)]] - document - docs/superpowers/specs/2026-05-19-underwater-effects-design.md
- [[Water Surface Visible From Below (Disable Backface Culling)]] - document - docs/superpowers/specs/2026-05-19-underwater-effects-design.md
- [[com.mineclone.save Package (SaveManager, LevelData, ChunkSnapshot, Options)]] - document - docs/superpowers/specs/2026-05-16-save-system-and-menu-revamp-design.md

## Live Query (requires Dataview plugin)

```dataview
TABLE source_file, type FROM #community/Architecture_Docs_&_ADRs
SORT file.name ASC
```
