---
tags: [decision, rendering]
status: stable
date: 2026-05-20
---

# ADR: Рендеринг чанков

## Решение

Весь GLSL — строки в `Shaders.java`. Пять программ: `CHUNK`, `LINE`, `PARTICLE`, `TEXT`, `UI`.

## Почему

- Нет отдельных .glsl файлов → нет проблем с путями к ресурсам при запуске из разных CWD
- Строки легко редактировать и видно diff в git

## Ключевые детали

- `centroid out vec2 vUv` — предотвращает UV-блид при MSAA
- `pow(l, 0.75)` — гамма/тон-кривая в `CHUNK_FRAGMENT`
- `uAmbient = 0.22f` — нижний порог теней
- Atlas фильтр: `GL_NEAREST`, без мипмапов (края тайлов вплотную)

## Связанные заметки

- [[architecture/overview]]
