---
tags: [architecture, core]
status: stable
---

# Architecture Overview

## Слои

```
Main → Window (GLFW+OpenGL 3.3) → Game.run()
```

**Game loop**: переменный delta-time, cap 50 мс. Состояния: `MENU → PLAYING ↔ PAUSED`.

## Мир и чанки

- `World` — `ConcurrentHashMap<Long, Chunk>`, чанки 16×128×16
- Генерация **двухпроходная**: сначала рельеф (высоты в `int[][]`), затем деревья
- `ChunkLoader` — фоновый пул потоков, результаты `Ready`-записями в `Game`
- Skylight: column-flood BFS + blur-проход

## Рендер-пайплайн (за кадр)

1. Opaque chunks — `chunkShader`
2. Block outline — `BlockOutline`
3. Particles — `ParticleSystem`
4. HUD — `Crosshair`, `Hud`

## Мешинг

`ChunkMesher.buildData()` — фоновые потоки. AO таблица: `{1.0, 0.86, 0.74, 0.62}`.
Грань рисуется если сосед: `AIR`, `transparent`, или `cutout`.

## Связанные заметки

- [[decisions/rendering-approach]]
- [[decisions/chunk-loading-strategy]]
