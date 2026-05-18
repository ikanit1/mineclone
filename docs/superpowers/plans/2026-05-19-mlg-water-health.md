# MLG Water Bucket & Health System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Добавить систему здоровья, урон от падения, сброс урона при касании воды (MLG), MC-точный fluid drag и экран смерти.

**Architecture:** Вся HP/fall-логика в `Player.java` (Approach A). `Game.java` только читает `player.isDead()` и меняет state на `DEAD`. HUD рисует сердечки и экран смерти. Проект не имеет тестов — верификация ручная.

**Tech Stack:** Java 17, OpenGL 3.3 (LWJGL), JOML

---

### Task 1: Player.java — поля здоровья и методы

**Files:**
- Modify: `src/main/java/com/mineclone/game/Player.java:13-15` (поля), `:137+` (новые методы)

- [ ] **Шаг 1.1: Добавить поля здоровья в Player**

В `Player.java`, в блоке полей (после строки `public boolean eyeInWater = false;`, ~строка 14):

```java
    public float health = 20f;
    public static final float MAX_HEALTH = 20f;
    public float fallDistance = 0f;
    private boolean wasOnGround = false;
    private float regenTimer = 0f;
```

- [ ] **Шаг 1.2: Добавить методы в конец класса (перед закрывающей `}`)**

Добавить перед последней `}` класса Player:

```java
    public void takeDamage(float amount) {
        health = Math.max(0f, health - amount);
    }

    public void respawn() {
        health = MAX_HEALTH;
        fallDistance = 0f;
        regenTimer = 0f;
        position.set(8, 90, 8);
        velocity.set(0, 0, 0);
        onGround = false;
    }

    public boolean isDead() {
        return health <= 0f;
    }
```

- [ ] **Шаг 1.3: Скомпилировать**

```powershell
$libs = Join-Path $PWD 'libs'; $out = Join-Path $PWD 'out'
$jars = (Get-ChildItem $libs -Filter *.jar | ForEach-Object { $_.FullName }) -join ';'
$sources = Get-ChildItem -Path 'src\main\java' -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$srcList = Join-Path $out 'sources.txt'
[IO.File]::WriteAllLines($srcList, [string[]]$sources, [System.Text.UTF8Encoding]::new($false))
javac -encoding UTF-8 -d $out -cp $jars "@$srcList"
```

Ожидается: Exit 0, нет ошибок.

- [ ] **Шаг 1.4: Коммит**

```powershell
git add src/main/java/com/mineclone/game/Player.java
git commit -m "feat(player): add health system fields and methods"
```

---

### Task 2: Player.java — физика (fall tracking + fluid drag)

**Files:**
- Modify: `src/main/java/com/mineclone/game/Player.java`

Удаляем: `private boolean prevInWater`, строку `justEnteredWater = ...`, хак `velocity.y * 0.4`.  
Заменяем: вертикальную формулу воды на MC-точную.  
Добавляем: `prevY` и fallDistance/regen логику после moveAxis.

- [ ] **Шаг 2.1: Удалить `prevInWater` поле**

Найди строку (~строка 16):
```java
    private boolean prevInWater = false;
```
Удали её.

- [ ] **Шаг 2.2: Удалить `justEnteredWater` и хак поглощения**

В методе `update()` найди:
```java
        boolean justEnteredWater = inWater && !prevInWater;
```
Удали эту строку.

Найди и удали блок:
```java
        // Погружение: поглощаем вертикальную скорость при входе в воду
        if (justEnteredWater && velocity.y < 0f)
            velocity.y = Math.max(velocity.y * 0.4f, -4f);
```

- [ ] **Шаг 2.3: Заменить вертикальную водную физику на MC-точную**

Найди блок вертикального drag в `else if (inWater)`:
```java
        // Вертикаль: увеличенная гравитация + меньше плавучести = быстрее тонет
        velocity.y += GRAVITY * 0.12f * dt;
        if (!sinking)
            velocity.y += 1.0f * dt;

        // Вертикальный drag
        velocity.y *= (float) Math.pow(0.25, dt);
```

Замени на:
```java
        // MC-faithful: vy = vy * 0.8 - 0.02 per 50ms tick → continuous form
        float waterVDrag = (float) Math.pow(0.8, dt / 0.05f);
        velocity.y = velocity.y * waterVDrag - 0.4f * dt;
        if (!sinking)
            velocity.y += 1.0f * dt;
```

- [ ] **Шаг 2.4: Добавить prevY и fallDistance/regen после moveAxis**

Найди блок в `update()` (после удаления `justEnteredWater` выглядит так):
```java
        // Move with collisions axis by axis (AABB sweep)
        moveAxis(world, velocity.x * dt, 0, 0);
        moveAxis(world, 0, velocity.y * dt, 0);
        moveAxis(world, 0, 0, velocity.z * dt);

        prevInWater = inWater;
        camera.position.set(position.x, position.y + EYE_HEIGHT, position.z);
```

Замени на:
```java
        float prevY = position.y;

        // Move with collisions axis by axis (AABB sweep)
        moveAxis(world, velocity.x * dt, 0, 0);
        moveAxis(world, 0, velocity.y * dt, 0);
        moveAxis(world, 0, 0, velocity.z * dt);

        // Fall distance tracking (position-based, not velocity-based — more stable)
        if (!onGround && !inWater && !flying && position.y < prevY)
            fallDistance += prevY - position.y;

        // MLG: touching water resets fall damage counter
        if (inWater)
            fallDistance = 0f;

        // Landing: apply fall damage (guard !inWater covers same-frame water+ground)
        if (onGround && !wasOnGround) {
            if (!inWater) {
                float dmg = Math.max(0f, fallDistance - 3f);
                if (dmg > 0f) takeDamage(dmg);
            }
            fallDistance = 0f;
        }
        wasOnGround = onGround;

        // Slow HP regen (~0.5 HP per 4 s)
        regenTimer += dt;
        if (regenTimer >= 4f && health < MAX_HEALTH) {
            health = Math.min(MAX_HEALTH, health + 0.5f);
            regenTimer = 0f;
        }

        camera.position.set(position.x, position.y + EYE_HEIGHT, position.z);
```

- [ ] **Шаг 2.5: Скомпилировать**

```powershell
$libs = Join-Path $PWD 'libs'; $out = Join-Path $PWD 'out'
$jars = (Get-ChildItem $libs -Filter *.jar | ForEach-Object { $_.FullName }) -join ';'
$sources = Get-ChildItem -Path 'src\main\java' -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$srcList = Join-Path $out 'sources.txt'
[IO.File]::WriteAllLines($srcList, [string[]]$sources, [System.Text.UTF8Encoding]::new($false))
javac -encoding UTF-8 -d $out -cp $jars "@$srcList"
```

Ожидается: Exit 0.

- [ ] **Шаг 2.6: Ручная проверка — MLG**

Запусти `.\run.ps1`. Встань на высоте ~10 блоков над водой, прыгни. Должен войти в воду без урона (MLG). Прыгни с той же высоты на землю — должен получить ~7 HP урона.

- [ ] **Шаг 2.7: Коммит**

```powershell
git add src/main/java/com/mineclone/game/Player.java
git commit -m "feat(player): fall damage tracking, MLG water negation, MC-accurate fluid drag"
```

---

### Task 3: Hud.java — сердечки и экран смерти

**Files:**
- Modify: `src/main/java/com/mineclone/game/Hud.java`

- [ ] **Шаг 3.1: Добавить `RESPAWN` в MenuAction**

В `Hud.java`, в enum `MenuAction` (~строка 20), добавь `RESPAWN`:

```java
    public enum MenuAction {
        NONE,
        CONTINUE, NEW_WORLD, NEW_WORLD_CONFIRM, CANCEL,
        SAVE, MAIN_MENU,
        RESUME, SETTINGS, SETTINGS_BACK, QUIT,
        RESPAWN
    }
```

- [ ] **Шаг 3.2: Добавить метод `drawHearts()`**

Добавь метод в `Hud.java` после метода `drawHotbar()` (~строка 135):

```java
    public void drawHearts(int screenW, int screenH, float health) {
        float hs = 9, gap = 2;
        float x0 = 4, y0 = screenH - 82f;
        ui.begin(screenW, screenH);
        for (int i = 0; i < 10; i++) {
            float x = x0 + i * (hs + gap);
            ui.quad(x, y0, hs, hs, 0.23f, 0f, 0f, 0.9f); // empty heart
            float hp = health - i * 2f;
            if (hp >= 2f) {
                ui.quad(x, y0, hs, hs, 0.86f, 0f, 0f, 1f); // full heart
            } else if (hp >= 1f) {
                ui.quad(x, y0, hs * 0.5f, hs, 0.86f, 0f, 0f, 1f); // half heart
            }
        }
        ui.end();
    }
```

- [ ] **Шаг 3.3: Добавить метод `drawDeathScreen()`**

Добавь метод в `Hud.java` после `drawHearts()`:

```java
    public MenuAction drawDeathScreen(int screenW, int screenH,
                                      double mx, double my, boolean clicked) {
        float bw = 300, bh = 50;
        float bx = screenW / 2f - bw / 2f;
        float by = screenH / 2f + 20;
        ui.begin(screenW, screenH);
        ui.quad(0, 0, screenW, screenH, 0.35f, 0f, 0f, 0.7f); // red overlay
        boolean hover = stoneButton(bx, by, bw, bh, "Возродиться",
                screenW, screenH, mx, my, true, true);
        ui.end();
        String title = "Вы умерли";
        float tw = font.textWidth(title);
        text.drawShadowed(font, title, screenW / 2f - tw / 2f,
                screenH / 2f - 20f, screenW, screenH, 1f, 0.3f, 0.3f);
        if (clicked && hover) return MenuAction.RESPAWN;
        return MenuAction.NONE;
    }
```

- [ ] **Шаг 3.4: Скомпилировать**

```powershell
$libs = Join-Path $PWD 'libs'; $out = Join-Path $PWD 'out'
$jars = (Get-ChildItem $libs -Filter *.jar | ForEach-Object { $_.FullName }) -join ';'
$sources = Get-ChildItem -Path 'src\main\java' -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$srcList = Join-Path $out 'sources.txt'
[IO.File]::WriteAllLines($srcList, [string[]]$sources, [System.Text.UTF8Encoding]::new($false))
javac -encoding UTF-8 -d $out -cp $jars "@$srcList"
```

Ожидается: Exit 0.

- [ ] **Шаг 3.5: Коммит**

```powershell
git add src/main/java/com/mineclone/game/Hud.java
git commit -m "feat(hud): add hearts display and death screen"
```

---

### Task 4: Game.java — DEAD state + death detection + respawn

**Files:**
- Modify: `src/main/java/com/mineclone/game/Game.java`

- [ ] **Шаг 4.1: Добавить `DEAD` в State enum**

Найди в `Game.java` (~строка 29):
```java
    private enum State {
        MENU, LOADING, PLAYING, PAUSED, CREATIVE_MENU
    }
```

Замени на:
```java
    private enum State {
        MENU, LOADING, PLAYING, PAUSED, CREATIVE_MENU, DEAD
    }
```

- [ ] **Шаг 4.2: Добавить вызов `hud.drawHearts()` в PLAYING HUD**

В `drawUi()`, внутри `case PLAYING` (~строка 1047), найди строку:
```java
                hud.drawHotbar(w, h, hotbar, selectedSlot);
```

Добавь сразу после неё:
```java
                hud.drawHearts(w, h, player.health);
```

- [ ] **Шаг 4.3: Добавить death detection в `updatePlaying()`**

В конце метода `updatePlaying(float dt)`, перед последней `}`, после `updateDirtyMeshes();`:

```java
        if (player.isDead()) {
            state = State.DEAD;
            input.grabCursor(false);
        }
```

- [ ] **Шаг 4.4: Добавить `case DEAD` в `drawUi()`**

В методе `drawUi()`, в switch(state), найди `case CREATIVE_MENU -> {` блок. После закрывающей `}` этого блока, перед закрывающей `}` switch'а (~строка 1129), добавь:

```java
            case DEAD -> {
                boolean clicked = !swallowMouseUntilUp
                        && input.mousePressed(GLFW.GLFW_MOUSE_BUTTON_LEFT);
                double mx = input.getCursorX(), my = input.getCursorY();
                Hud.MenuAction a = hud.drawDeathScreen(w, h, mx, my, clicked);
                if (a == Hud.MenuAction.RESPAWN) {
                    player.respawn();
                    state = State.PLAYING;
                    input.grabCursor(true);
                    swallowMouseUntilUp = true;
                }
            }
```

- [ ] **Шаг 4.5: Скомпилировать**

```powershell
$libs = Join-Path $PWD 'libs'; $out = Join-Path $PWD 'out'
$jars = (Get-ChildItem $libs -Filter *.jar | ForEach-Object { $_.FullName }) -join ';'
$sources = Get-ChildItem -Path 'src\main\java' -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$srcList = Join-Path $out 'sources.txt'
[IO.File]::WriteAllLines($srcList, [string[]]$sources, [System.Text.UTF8Encoding]::new($false))
javac -encoding UTF-8 -d $out -cp $jars "@$srcList"
```

Ожидается: Exit 0.

- [ ] **Шаг 4.6: Ручная проверка — полный flow**

Запусти `.\run.ps1`:
1. Убедись что видны 10 сердечек снизу слева
2. Прыгни с высоты >23 блоков на землю — должен появиться экран "Вы умерли" с кнопкой "Возродиться"
3. Нажми "Возродиться" — должны вернуться на (8, 90, 8) с полными 20 HP
4. Прыгни с той же высоты, но в воду — должен выжить (MLG)

- [ ] **Шаг 4.7: Коммит**

```powershell
git add src/main/java/com/mineclone/game/Game.java
git commit -m "feat(game): DEAD state, death detection, death screen, respawn"
```
