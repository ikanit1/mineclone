# Water Physics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Заменить заглушку водной физики в `Player.java` на MC-подобную силовую модель с инерцией, плавучестью и плавным вертикальным управлением.

**Architecture:** Все изменения локализованы в `Player.java`. Добавляются три константы. Горизонтальная скорость в воде переходит с мгновенного snap на exponential lerp. Вертикаль управляется балансом гравитации, плавучести и drag — без мгновенной установки velocity.

**Tech Stack:** Java 17, LWJGL/GLFW (клавиши уже используются через `input.keyDown`)

---

### Task 1: Добавить константы и переписать водную физику

**Files:**
- Modify: `src/main/java/com/mineclone/game/Player.java:21-24` (константы)
- Modify: `src/main/java/com/mineclone/game/Player.java:58-82` (блок update)

- [ ] **Шаг 1: Добавить три новые константы после `GRAVITY`**

Открыть `src/main/java/com/mineclone/game/Player.java`, найти блок констант (строки 20-24):

```java
    public static final float WALK_SPEED = 4.8f;
    public static final float FLY_SPEED = 12f;
    public static final float JUMP_VELOCITY = 8.4f;
    public static final float GRAVITY = -28f;
```

Заменить на:

```java
    public static final float WALK_SPEED = 4.8f;
    public static final float FLY_SPEED = 12f;
    public static final float JUMP_VELOCITY = 8.4f;
    public static final float GRAVITY = -28f;
    public static final float SWIM_SPEED = 2.0f;
    public static final float SWIM_UP_MAX = 2.5f;
    public static final float SINK_MAX = -2.0f;
```

- [ ] **Шаг 2: Переместить установку горизонтальной скорости внутрь веток**

Найти блок (строки 58-61):

```java
        float speed = flying ? FLY_SPEED : WALK_SPEED;
        velocity.x = wish.x * speed;
        velocity.z = wish.z * speed;
```

Заменить на (устанавливать скорость только для flying/наземного):

```java
        float speed = flying ? FLY_SPEED : WALK_SPEED;
```

Убрать строки `velocity.x = wish.x * speed;` и `velocity.z = wish.z * speed;` — они переедут внутрь веток ниже.

- [ ] **Шаг 3: Заменить блок `if (flying) ... else if (inWater) ...`**

Найти весь блок (строки 64-89):

```java
        if (flying) {
            velocity.y = 0;
            if (input.keyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE))
                velocity.y = speed;
            if (input.keyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT))
                velocity.y = -speed;
        } else if (inWater) {
            velocity.y += GRAVITY * 0.12f * dt;
            velocity.y = Math.max(velocity.y, -3f);
            velocity.x *= (float) Math.pow(0.75, dt * 10);
            velocity.z *= (float) Math.pow(0.75, dt * 10);
            if (input.keyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE))
                velocity.y = 3.5f;
            if (input.keyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT))
                velocity.y = -3.5f;
            onGround = false;
            swimSoundTimer -= dt;
        } else {
            velocity.y += GRAVITY * dt;
            if (input.keyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE) && onGround) {
                velocity.y = JUMP_VELOCITY;
                onGround = false;
            }
        }
```

Заменить на:

```java
        if (flying) {
            velocity.x = wish.x * speed;
            velocity.z = wish.z * speed;
            velocity.y = 0;
            if (input.keyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE))
                velocity.y = speed;
            if (input.keyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT))
                velocity.y = -speed;
        } else if (inWater) {
            // Горизонталь: exponential lerp к wish*SWIM_SPEED (инерция воды)
            float hDrag = (float) Math.pow(0.15, dt);
            velocity.x = velocity.x * hDrag + wish.x * SWIM_SPEED * (1f - hDrag);
            velocity.z = velocity.z * hDrag + wish.z * SWIM_SPEED * (1f - hDrag);

            // Вертикаль: гравитация + плавучесть
            boolean sinking = input.keyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT);
            velocity.y += GRAVITY * 0.08f * dt;
            if (!sinking)
                velocity.y += 2.4f * dt;

            // Вертикальный drag: сдерживает накопление скорости
            velocity.y *= (float) Math.pow(0.5, dt);

            // Управление вертикалью
            if (input.keyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE))
                velocity.y = Math.min(velocity.y + 12f * dt, SWIM_UP_MAX);
            if (sinking)
                velocity.y = Math.max(velocity.y - 8f * dt, SINK_MAX);

            velocity.y = Math.max(velocity.y, SINK_MAX);
            onGround = false;
            swimSoundTimer -= dt;
        } else {
            velocity.x = wish.x * speed;
            velocity.z = wish.z * speed;
            velocity.y += GRAVITY * dt;
            if (input.keyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE) && onGround) {
                velocity.y = JUMP_VELOCITY;
                onGround = false;
            }
        }
```

- [ ] **Шаг 4: Скомпилировать и проверить**

```powershell
$libs = Join-Path $PWD 'libs'
$out  = Join-Path $PWD 'out'
$jars = (Get-ChildItem $libs -Filter *.jar | ForEach-Object { $_.FullName }) -join ';'
$sources = Get-ChildItem -Path 'src\main\java' -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$srcList = Join-Path $out 'sources.txt'
[IO.File]::WriteAllLines($srcList, [string[]]$sources, [System.Text.UTF8Encoding]::new($false))
javac -encoding UTF-8 -d $out -cp $jars "@$srcList"
```

Ожидание: **0 ошибок**, только возможные предупреждения.

- [ ] **Шаг 5: Запустить игру и проверить поведение вручную**

```powershell
.\run.ps1
```

Чек-лист проверки:
- [ ] Зайти в воду — горизонтальное движение медленнее (~2 м/с), есть ощущение инерции
- [ ] Стоять без кнопок в воде — игрок медленно всплывает
- [ ] Удерживать SPACE — быстрый подъём, останавливается у поверхности
- [ ] Удерживать SHIFT — погружение, игрок не всплывает
- [ ] Выйти из воды — обычная ходьба с полной скоростью восстановлена
- [ ] Прыжок на суше — работает как прежде
- [ ] Полёт (F) — работает как прежде

- [ ] **Шаг 6: Зафиксировать изменения**

```powershell
git add src/main/java/com/mineclone/game/Player.java
git commit -m "feat(player): MC-like water physics — buoyancy, inertia, force model"
```

---

## Ожидаемое поведение после реализации

| Ситуация | Было | Стало |
|---|---|---|
| Движение в воде | Полная скорость (4.8 м/с) | ~2.0 м/с с инерцией |
| Без ввода в воде | Медленно тонет | Медленно всплывает |
| SPACE в воде | Мгновенный импульс 3.5 м/с | Плавный разгон до 2.5 м/с |
| SHIFT в воде | Мгновенный импульс -3.5 м/с | Плавное погружение до -2.0 м/с |
| Выход из воды | Без изменений | Без изменений |
