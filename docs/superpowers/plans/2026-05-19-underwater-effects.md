# Underwater Effects Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Показывать поверхность воды при взгляде снизу и плавно уменьшать FOV когда камера полностью под водой.

**Architecture:** Оба изменения — только в `Game.java`. Для поверхности воды: отключаем backface culling на время прозрачного прохода. Для FOV: добавляем поле `currentFov`, которое плавно интерполирует к целевому значению каждый кадр.

**Tech Stack:** Java, OpenGL 3.3 (LWJGL), JOML

---

### Task 1: Отключить backface culling в прозрачном проходе воды

**Files:**
- Modify: `src/main/java/com/mineclone/game/Game.java:891-937`

Сейчас `GL_CULL_FACE` включён глобально (`Window.java:63`). Водяные top-face'ы имеют winding CCW сверху → они back-face снизу и кулируются. Нужно отключить culling на время water pass, чтобы поверхность рендерилась с обеих сторон.

- [ ] **Шаг 1.1: Найти начало прозрачного прохода**

Открой `src/main/java/com/mineclone/game/Game.java`, найди строку с комментарием `// --- Transparent (water) pass ---` (~строка 891).

- [ ] **Шаг 1.2: Добавить `glDisable(GL_CULL_FACE)` после включения blend**

В методе `render()`, сразу после строки `glDepthMask(false);` внутри water pass:

```java
        // --- Transparent (water) pass ---
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false);
        glDisable(GL_CULL_FACE);   // water faces visible from both sides
```

- [ ] **Шаг 1.3: Восстановить culling после прохода**

Сразу после `glDisable(GL_BLEND);` в конце water pass:

```java
        glDepthMask(true);
        glDisable(GL_BLEND);
        glEnable(GL_CULL_FACE);    // restore global state
        // --- End water pass ---
```

- [ ] **Шаг 1.4: Скомпилировать**

```powershell
$libs = Join-Path $PWD 'libs'
$out  = Join-Path $PWD 'out'
$jars = (Get-ChildItem $libs -Filter *.jar | ForEach-Object { $_.FullName }) -join ';'
$sources = Get-ChildItem -Path 'src\main\java' -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$srcList = Join-Path $out 'sources.txt'
[IO.File]::WriteAllLines($srcList, [string[]]$sources, [System.Text.UTF8Encoding]::new($false))
javac -encoding UTF-8 -d $out -cp $jars "@$srcList"
```

Ожидается: выход без ошибок.

- [ ] **Шаг 1.5: Ручная проверка**

Запусти `.\run.ps1`. Зайди в воду, нырни, посмотри вверх. Должна быть видна полупрозрачная рябящая плёнка поверхности воды.

- [ ] **Шаг 1.6: Коммит**

```powershell
git add src/main/java/com/mineclone/game/Game.java
git commit -m "fix(water): render water surface from both sides (disable culling in water pass)"
```

---

### Task 2: Добавить плавный FOV под водой

**Files:**
- Modify: `src/main/java/com/mineclone/game/Game.java:23-25` (поле), `:107` (инициализация), `:847` (проекция)

- [ ] **Шаг 2.1: Добавить поля `currentFov` и `lastDt`**

`render()` — parameterless метод, `dt` доступен только в `run()`. Нужно хранить его в поле.

В `Game.java`, в блоке полей (~строка 22), после строки `private int fovDegrees;`:

```java
    private int fovDegrees;
    private float currentFov;
    private float lastDt = 0.016f;
```

- [ ] **Шаг 2.2: Инициализировать `currentFov` в конструкторе**

В конструкторе `Game(Window window, boolean regenAtlas)` (~строка 107), после строки `this.fovDegrees = opts.fovDegrees;`:

```java
        this.fovDegrees = opts.fovDegrees;
        this.currentFov = opts.fovDegrees;
```

- [ ] **Шаг 2.3: Сохранять `dt` в поле перед вызовом `render()`**

В методе `run()` (~строка 229), строки выглядят так:
```java
            render();
```

Замени на:
```java
            this.lastDt = dt;
            render();
```

- [ ] **Шаг 2.4: Интерполировать FOV в `render()` перед построением проекции**

В методе `render()`, найди строку (~строка 847):
```java
        Matrix4f proj = player.camera.getProjection(window.getAspect(), fovDegrees, 0.1f, 600f);
```

Замени её на:
```java
        float targetFov = player.eyeInWater ? fovDegrees * 0.85f : fovDegrees;
        currentFov += (targetFov - currentFov) * (1f - (float) Math.exp(-lastDt * 8f));
        Matrix4f proj = player.camera.getProjection(window.getAspect(), currentFov, 0.1f, 600f);
```

- [ ] **Шаг 2.5: Убедиться что `menuBackground.render` использует `fovDegrees` а не `currentFov`**

В методе `render()` (~строка 830) ветка `State.MENU`:
```java
            menuBackground.render(chunkShader, atlas, window.getAspect(), fovDegrees);
```
Эта строка должна остаться с `fovDegrees` (не `currentFov`) — в меню подводный эффект не нужен.

- [ ] **Шаг 2.6: Скомпилировать**

```powershell
$libs = Join-Path $PWD 'libs'
$out  = Join-Path $PWD 'out'
$jars = (Get-ChildItem $libs -Filter *.jar | ForEach-Object { $_.FullName }) -join ';'
$sources = Get-ChildItem -Path 'src\main\java' -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$srcList = Join-Path $out 'sources.txt'
[IO.File]::WriteAllLines($srcList, [string[]]$sources, [System.Text.UTF8Encoding]::new($false))
javac -encoding UTF-8 -d $out -cp $jars "@$srcList"
```

Ожидается: выход без ошибок.

- [ ] **Шаг 2.7: Ручная проверка**

Запусти `.\run.ps1`. Зайди в воду по шею (eyeInWater = true). Поле зрения должно плавно сузиться (~за 0.3 с). При выходе из воды — плавно вернуться к исходному значению.

- [ ] **Шаг 2.8: Коммит**

```powershell
git add src/main/java/com/mineclone/game/Game.java
git commit -m "feat(camera): smooth FOV reduction when camera is submerged"
```
