---
type: community
cohesion: 0.06
members: 71
---

# Chunk & World Management

**Cohesion:** 0.06 - loosely connected
**Members:** 71 nodes

## Members
- [[.ChunkLoader()]] - code - world\ChunkLoader.java
- [[.MenuBackground()]] - code - game\MenuBackground.java
- [[.Ready()]] - code - world\ChunkLoader.java
- [[.WaterSimulator()]] - code - world\WaterSimulator.java
- [[.World()]] - code - world\World.java
- [[.activateAround()]] - code - world\WaterSimulator.java
- [[.activateChunkIfWater()]] - code - world\WaterSimulator.java
- [[.applySnapshot()]] - code - world\ChunkLoader.java
- [[.chunkHasWater()]] - code - world\WaterSimulator.java
- [[.cleanup()]] - code - game\Game.java
- [[.countLoadedChunks()]] - code - game\Game.java
- [[.countSourceNeighbors()]] - code - world\WaterSimulator.java
- [[.daemon()]] - code - world\ChunkLoader.java
- [[.destroy()_2]] - code - game\MenuBackground.java
- [[.destroy()_13]] - code - render\UiRenderer.java
- [[.drainLightFlood()]] - code - world\ChunkLoader.java
- [[.drainReady()]] - code - world\ChunkLoader.java
- [[.enqueueBlockLight()]] - code - world\World.java
- [[.ensureChunksLoaded()]] - code - game\Game.java
- [[.ensureRadius()]] - code - world\ChunkLoader.java
- [[.evictDistantChunks()]] - code - game\Game.java
- [[.floodFillAdd()]] - code - world\World.java
- [[.floodFillRemove()]] - code - world\World.java
- [[.forget()]] - code - world\ChunkLoader.java
- [[.forgetChunk()]] - code - world\WaterSimulator.java
- [[.generate()]] - code - world\World.java
- [[.getBlockLight()]] - code - world\Chunk.java
- [[.getBlockLightWorld()]] - code - world\World.java
- [[.getChunk()]] - code - world\World.java
- [[.getChunkIfExists()]] - code - world\World.java
- [[.getLoadedChunks()]] - code - world\World.java
- [[.getSkyLight()_1]] - code - world\World.java
- [[.hasPendingLightFlood()]] - code - world\ChunkLoader.java
- [[.hasSupport()]] - code - world\WaterSimulator.java
- [[.injectNeighbourLight()]] - code - world\World.java
- [[.invalidateNeighbours()]] - code - world\ChunkLoader.java
- [[.isFullyReady()]] - code - world\ChunkLoader.java
- [[.isPendingGen()]] - code - world\ChunkLoader.java
- [[.key()]] - code - world\World.java
- [[.markMeshed()]] - code - world\ChunkLoader.java
- [[.markNeighbourDirty()]] - code - world\WaterSimulator.java
- [[.mix()]] - code - world\World.java
- [[.neighboursReady()]] - code - world\ChunkLoader.java
- [[.pack()]] - code - world\WaterSimulator.java
- [[.remeshNeighbour()]] - code - world\World.java
- [[.removeChunk()]] - code - world\World.java
- [[.restore()]] - code - world\Chunk.java
- [[.sampleTerrainTop()]] - code - game\MenuBackground.java
- [[.seedLoadedWaterChunks()]] - code - world\WaterSimulator.java
- [[.setBlock()]] - code - world\World.java
- [[.setBlockLightWorld()]] - code - world\World.java
- [[.setBlockSafe()]] - code - world\WaterSimulator.java
- [[.shutdown()]] - code - world\ChunkLoader.java
- [[.submitGen()]] - code - world\ChunkLoader.java
- [[.submitMesh()]] - code - world\ChunkLoader.java
- [[.tick()_1]] - code - world\WaterSimulator.java
- [[.trySpread()]] - code - world\WaterSimulator.java
- [[.unpackX()]] - code - world\WaterSimulator.java
- [[.unpackY()]] - code - world\WaterSimulator.java
- [[.unpackZ()]] - code - world\WaterSimulator.java
- [[.update()_2]] - code - game\MenuBackground.java
- [[.updateDirtyMeshes()]] - code - game\Game.java
- [[ChunkLoader]] - code - world\ChunkLoader.java
- [[ChunkLoader.java]] - code - world\ChunkLoader.java
- [[MenuBackground]] - code - game\MenuBackground.java
- [[MenuBackground.java]] - code - game\MenuBackground.java
- [[Ready]] - code - world\ChunkLoader.java
- [[WaterSimulator]] - code - world\WaterSimulator.java
- [[WaterSimulator.java]] - code - world\WaterSimulator.java
- [[World]] - code - world\World.java
- [[World.java]] - code - world\World.java

## Live Query (requires Dataview plugin)

```dataview
TABLE source_file, type FROM #community/Chunk_&_World_Management
SORT file.name ASC
```

## Connections to other communities
- 36 edges to [[_COMMUNITY_Sound System]]
- 32 edges to [[_COMMUNITY_Block Types & World Data]]
- 9 edges to [[_COMMUNITY_Input System]]
- 7 edges to [[_COMMUNITY_HUD & UI]]
- 6 edges to [[_COMMUNITY_Sound Engine Core]]
- 5 edges to [[_COMMUNITY_Game Core & Save]]
- 4 edges to [[_COMMUNITY_Render Pipeline]]
- 1 edge to [[_COMMUNITY_Terrain Noise]]

## Top bridge nodes
- [[.tick()_1]] - degree 18, connects to 4 communities
- [[.updateDirtyMeshes()]] - degree 16, connects to 4 communities
- [[.getBlockLightWorld()]] - degree 9, connects to 4 communities
- [[.getSkyLight()_1]] - degree 7, connects to 4 communities
- [[.getChunkIfExists()]] - degree 25, connects to 3 communities