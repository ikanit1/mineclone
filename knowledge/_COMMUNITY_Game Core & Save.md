---
type: community
cohesion: 0.08
members: 41
---

# Game Core & Save

**Cohesion:** 0.08 - loosely connected
**Members:** 41 nodes

## Members
- [[.Game()]] - code - game\Game.java
- [[.LevelData()]] - code - save\LevelData.java
- [[.Options()]] - code - save\Options.java
- [[.SaveFormat()]] - code - save\SaveFormat.java
- [[.SaveManager()]] - code - save\SaveManager.java
- [[.chunkFile()]] - code - save\SaveManager.java
- [[.chunkFileName()]] - code - save\SaveFormat.java
- [[.chunksDir()]] - code - save\SaveManager.java
- [[.copyBlocks()]] - code - world\Chunk.java
- [[.copyMeta()]] - code - world\Chunk.java
- [[.defaultInventory()]] - code - save\LevelData.java
- [[.defaults()]] - code - save\Options.java
- [[.deleteRecursive()]] - code - save\SaveManager.java
- [[.deleteWorld()]] - code - save\SaveManager.java
- [[.findDefaultSpawn()]] - code - game\Game.java
- [[.flushAndAwait()]] - code - save\SaveManager.java
- [[.getHandle()]] - code - core\Window.java
- [[.hasSave()]] - code - save\SaveManager.java
- [[.levelFile()]] - code - save\SaveManager.java
- [[.loadChunk()]] - code - save\SaveManager.java
- [[.loadLevel()]] - code - save\SaveManager.java
- [[.loadOptions()]] - code - save\SaveManager.java
- [[.normalizeInventory()]] - code - save\LevelData.java
- [[.optionsFile()]] - code - save\SaveManager.java
- [[.reset()]] - code - world\WaterSimulator.java
- [[.saveAll()]] - code - game\Game.java
- [[.saveChunkAsync()]] - code - save\SaveManager.java
- [[.saveChunkBlocking()]] - code - save\SaveManager.java
- [[.saveChunkIfModified()]] - code - game\Game.java
- [[.saveLevel()]] - code - save\SaveManager.java
- [[.saveOptions()]] - code - save\SaveManager.java
- [[.startNewWorld()]] - code - game\Game.java
- [[.worldDir()]] - code - save\SaveManager.java
- [[LevelData]] - code - save\LevelData.java
- [[LevelData.java]] - code - save\LevelData.java
- [[Options]] - code - save\Options.java
- [[Options.java]] - code - save\Options.java
- [[SaveFormat]] - code - save\SaveFormat.java
- [[SaveFormat.java]] - code - save\SaveFormat.java
- [[SaveManager]] - code - save\SaveManager.java
- [[SaveManager.java]] - code - save\SaveManager.java

## Live Query (requires Dataview plugin)

```dataview
TABLE source_file, type FROM #community/Game_Core_&_Save
SORT file.name ASC
```

## Connections to other communities
- 11 edges to [[_COMMUNITY_Sound System]]
- 6 edges to [[_COMMUNITY_HUD & UI]]
- 5 edges to [[_COMMUNITY_Chunk & World Management]]
- 3 edges to [[_COMMUNITY_Block Types & World Data]]
- 1 edge to [[_COMMUNITY_Window & OpenGL]]
- 1 edge to [[_COMMUNITY_Input System]]

## Top bridge nodes
- [[.saveAll()]] - degree 8, connects to 3 communities
- [[.flushAndAwait()]] - degree 5, connects to 3 communities
- [[.findDefaultSpawn()]] - degree 4, connects to 3 communities
- [[.startNewWorld()]] - degree 9, connects to 2 communities
- [[.saveChunkIfModified()]] - degree 6, connects to 2 communities