# Graph Report - E:/mineclone  (2026-05-20)

## Corpus Check
- 58 files · ~74,327 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 511 nodes · 1296 edges · 17 communities detected
- Extraction: 59% EXTRACTED · 41% INFERRED · 0% AMBIGUOUS · INFERRED: 529 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- [[_COMMUNITY_Sound System|Sound System]]
- [[_COMMUNITY_Chunk & World Management|Chunk & World Management]]
- [[_COMMUNITY_Render Pipeline|Render Pipeline]]
- [[_COMMUNITY_HUD & UI|HUD & UI]]
- [[_COMMUNITY_Window & OpenGL|Window & OpenGL]]
- [[_COMMUNITY_Game Core & Save|Game Core & Save]]
- [[_COMMUNITY_Block Types & World Data|Block Types & World Data]]
- [[_COMMUNITY_Input System|Input System]]
- [[_COMMUNITY_Game Features Design|Game Features Design]]
- [[_COMMUNITY_Architecture Docs & ADRs|Architecture Docs & ADRs]]
- [[_COMMUNITY_Sound Engine Core|Sound Engine Core]]
- [[_COMMUNITY_Held Item Renderer|Held Item Renderer]]
- [[_COMMUNITY_Terrain Noise|Terrain Noise]]
- [[_COMMUNITY_Mesh Primitives|Mesh Primitives]]
- [[_COMMUNITY_Shaders|Shaders]]
- [[_COMMUNITY_Chunk Persistence|Chunk Persistence]]
- [[_COMMUNITY_Ground Movement Plan|Ground Movement Plan]]

## God Nodes (most connected - your core abstractions)
1. `Game` - 55 edges
2. `Hud` - 24 edges
3. `ChunkMesher` - 20 edges
4. `World` - 20 edges
5. `SaveManager` - 18 edges
6. `Chunk` - 18 edges
7. `WaterSimulator` - 18 edges
8. `ChunkLoader` - 17 edges
9. `Sounds` - 16 edges
10. `SkyRenderer` - 16 edges

## Surprising Connections (you probably didn't know these)
- `MenuBackground (Dedicated Mini-World + Orbit Camera)` --conceptually_related_to--> `Chunk Loading (Background Thread Pool)`  [INFERRED]
  docs/superpowers/plans/2026-05-17-main-menu-revamp.md → knowledge/decisions/chunk-loading-strategy.md
- `Render Pipeline (Opaque + Water Passes)` --conceptually_related_to--> `Dual-Mesh Transparent Water Pass`  [INFERRED]
  knowledge/architecture/overview.md → docs/superpowers/specs/2026-05-16-water-rendering-design.md
- `Plan: Water Physics (MC-style, 2026-05-17)` --semantically_similar_to--> `Plan: Water Physics MC-style (2026-05-19)`  [INFERRED] [semantically similar]
  docs/superpowers/plans/2026-05-17-water-physics.md → docs/superpowers/plans/2026-05-19-water-physics.md
- `WaterSimulator (BFS Tick)` --conceptually_related_to--> `Variable-Height Water Rendering (Level-Based topY)`  [INFERRED]
  docs/superpowers/plans/2026-05-15-creative-foundation.md → docs/superpowers/specs/2026-05-15-creative-foundation-design.md
- `BFS Flood-Fill Block Light` --semantically_similar_to--> `WaterSimulator (BFS Tick)`  [INFERRED] [semantically similar]
  docs/superpowers/specs/2026-05-15-game-feel-design.md → docs/superpowers/plans/2026-05-15-creative-foundation.md

## Hyperedges (group relationships)
- **Water System: Simulator + Variable Rendering + Physics** — concept_water_simulator, concept_water_variable_height, concept_dual_mesh [INFERRED 0.85]
- **Save System: SaveManager + Modified Flag + Autosave** — concept_save_manager, concept_chunk_modified_flag, concept_autosave [INFERRED 0.90]
- **Menu Revamp: Background World + Stone Buttons + Title** — concept_menu_background, concept_stone_buttons, concept_mineclone_title [INFERRED 0.88]

## Communities

### Community 0 - "Sound System"
Cohesion: 0.05
Nodes (6): Sounds, AppPaths, Game, Main, P, ParticleSystem

### Community 1 - "Chunk & World Management"
Cohesion: 0.06
Nodes (5): MenuBackground, ChunkLoader, Ready, WaterSimulator, World

### Community 2 - "Render Pipeline"
Cohesion: 0.07
Nodes (6): BlockOutline, Camera, Crosshair, Font, Shader, TextRenderer

### Community 3 - "HUD & UI"
Cohesion: 0.15
Nodes (3): Hud, InventoryAction, UiRenderer

### Community 4 - "Window & OpenGL"
Cohesion: 0.08
Nodes (4): Window, Drawer, SkyRenderer, TextureAtlas

### Community 5 - "Game Core & Save"
Cohesion: 0.08
Nodes (4): LevelData, Options, SaveFormat, SaveManager

### Community 6 - "Block Types & World Data"
Cohesion: 0.14
Nodes (3): byId(), Chunk, ChunkMesher

### Community 7 - "Input System"
Cohesion: 0.1
Nodes (4): Input, Player, Hit, Raycaster

### Community 8 - "Game Features Design"
Cohesion: 0.12
Nodes (25): BFS Flood-Fill Block Light, Block Metadata System (byte[] meta per Chunk), Creative Inventory Menu (E key, Block Grid), Day/Night Cycle (uDaylight Uniform), Death Screen and Respawn State (DEAD), Door Open/Close Toggle (Camera-Facing Meta), MC-Like Ground Movement (Exponential Accel), Health System (HP, Fall Damage, MLG, Regen) (+17 more)

### Community 9 - "Architecture Docs & ADRs"
Cohesion: 0.12
Nodes (25): ADR: Chunk Loading Strategy, ADR: Chunk Rendering Approach, Architecture Overview, Autosave (Pause/Periodic/Quit + Manual Save Button), Chunk Loading (Background Thread Pool), Chunk Modified Flag (Post-Generation Edits), Dual-Mesh Transparent Water Pass, MenuBackground (Dedicated Mini-World + Orbit Camera) (+17 more)

### Community 10 - "Sound Engine Core"
Cohesion: 0.18
Nodes (2): SoundEngine, MeshData

### Community 11 - "Held Item Renderer"
Cohesion: 0.33
Nodes (1): HeldItemRenderer

### Community 12 - "Terrain Noise"
Cohesion: 0.39
Nodes (1): PerlinNoise

### Community 13 - "Mesh Primitives"
Cohesion: 0.33
Nodes (1): Mesh

### Community 14 - "Shaders"
Cohesion: 0.67
Nodes (1): Shaders

### Community 15 - "Chunk Persistence"
Cohesion: 0.67
Nodes (1): ChunkSnapshot

### Community 16 - "Ground Movement Plan"
Cohesion: 1.0
Nodes (1): Plan: MC-Like Ground Movement

## Knowledge Gaps
- **11 isolated node(s):** `P`, `Plan: MC-Like Ground Movement`, `Plan: Water Physics (MC-style, 2026-05-17)`, `MC-Like Ground Movement (Exponential Accel)`, `Creative Inventory Menu (E key, Block Grid)` (+6 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **Thin community `Sound Engine Core`** (15 nodes): `SoundEngine.java`, `SoundEngine`, `.destroy()`, `.init()`, `.loadBuffer()`, `.play()`, `.playAt()`, `.playOneOf()`, `.playOneOfAt()`, `.tick()`, `MeshData.java`, `MeshData`, `.isEmpty()`, `.MeshData()`, `.upload()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Held Item Renderer`** (12 nodes): `HeldItemRenderer`, `.clamp01()`, `.createBlockMesh()`, `.createItemMesh()`, `.createSolidBox()`, `.createTorchMesh()`, `.destroy()`, `.emitBox()`, `.HeldItemRenderer()`, `.toFloatArray()`, `.toIntArray()`, `HeldItemRenderer.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Terrain Noise`** (8 nodes): `PerlinNoise.java`, `PerlinNoise`, `.fade()`, `.fbm()`, `.grad()`, `.lerp()`, `.noise()`, `.PerlinNoise()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Mesh Primitives`** (6 nodes): `Mesh.java`, `Mesh`, `.destroy()`, `.getIndexCount()`, `.Mesh()`, `.render()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Shaders`** (3 nodes): `Shaders.java`, `Shaders`, `.Shaders()`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Chunk Persistence`** (3 nodes): `ChunkSnapshot`, `.ChunkSnapshot()`, `ChunkSnapshot.java`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Ground Movement Plan`** (1 nodes): `Plan: MC-Like Ground Movement`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Game` connect `Sound System` to `Chunk & World Management`, `Render Pipeline`, `HUD & UI`, `Game Core & Save`?**
  _High betweenness centrality (0.100) - this node is a cross-community bridge._
- **Why does `TextureAtlas` connect `Window & OpenGL` to `Render Pipeline`, `HUD & UI`?**
  _High betweenness centrality (0.023) - this node is a cross-community bridge._
- **What connects `P`, `Plan: MC-Like Ground Movement`, `Plan: Water Physics (MC-style, 2026-05-17)` to the rest of the system?**
  _11 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Sound System` be split into smaller, more focused modules?**
  _Cohesion score 0.05 - nodes in this community are weakly interconnected._
- **Should `Chunk & World Management` be split into smaller, more focused modules?**
  _Cohesion score 0.06 - nodes in this community are weakly interconnected._
- **Should `Render Pipeline` be split into smaller, more focused modules?**
  _Cohesion score 0.07 - nodes in this community are weakly interconnected._
- **Should `Window & OpenGL` be split into smaller, more focused modules?**
  _Cohesion score 0.08 - nodes in this community are weakly interconnected._