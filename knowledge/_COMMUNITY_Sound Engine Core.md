---
type: community
cohesion: 0.18
members: 15
---

# Sound Engine Core

**Cohesion:** 0.18 - loosely connected
**Members:** 15 nodes

## Members
- [[.MeshData()]] - code - render\MeshData.java
- [[.destroy()]] - code - audio\SoundEngine.java
- [[.init()]] - code - audio\SoundEngine.java
- [[.isEmpty()]] - code - render\MeshData.java
- [[.loadBuffer()]] - code - audio\SoundEngine.java
- [[.play()]] - code - audio\SoundEngine.java
- [[.playAt()]] - code - audio\SoundEngine.java
- [[.playOneOf()]] - code - audio\SoundEngine.java
- [[.playOneOfAt()]] - code - audio\SoundEngine.java
- [[.tick()]] - code - audio\SoundEngine.java
- [[.upload()]] - code - render\MeshData.java
- [[MeshData]] - code - render\MeshData.java
- [[MeshData.java]] - code - render\MeshData.java
- [[SoundEngine]] - code - audio\SoundEngine.java
- [[SoundEngine.java]] - code - audio\SoundEngine.java

## Live Query (requires Dataview plugin)

```dataview
TABLE source_file, type FROM #community/Sound_Engine_Core
SORT file.name ASC
```

## Connections to other communities
- 8 edges to [[_COMMUNITY_Sound System]]
- 6 edges to [[_COMMUNITY_Chunk & World Management]]
- 4 edges to [[_COMMUNITY_Block Types & World Data]]
- 2 edges to [[_COMMUNITY_HUD & UI]]
- 2 edges to [[_COMMUNITY_Render Pipeline]]

## Top bridge nodes
- [[.isEmpty()]] - degree 14, connects to 5 communities
- [[.playOneOf()]] - degree 6, connects to 3 communities
- [[.playOneOfAt()]] - degree 8, connects to 2 communities
- [[SoundEngine]] - degree 11, connects to 1 community
- [[.loadBuffer()]] - degree 4, connects to 1 community