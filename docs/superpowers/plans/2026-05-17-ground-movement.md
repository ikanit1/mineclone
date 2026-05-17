# MC-Like Ground Movement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Заменить мгновенный snap наземной скорости в `Player.java` на MC-подобную модель экспоненциального приближения с разным темпом для земли и воздуха.

**Architecture:** Все изменения в ветке `else` метода `Player.update` (наземное движение + воздух). Добавляются 2 константы. Скорость приближается к цели `wish * WALK_SPEED` через dt-независимый коэффициент `1 - exp(-rate*dt)`, где `rate` зависит от `onGround`. Ветки `flying` и `inWater` не затрагиваются.

**Tech Stack:** Java 17, LWJGL/GLFW. Тестового фреймворка нет — верификация через компиляцию (PowerShell javac) и ручной запуск `.\run.ps1`.

---

### Task 1: MC-подобное наземное передвижение

**Files:**
- Modify: `src/main/java/com/mineclone/game/Player.java` (константы рядом с `SWIM_SPEED`; ветка `else` в `update`)

- [ ] **Шаг 1: Добавить константы `GROUND_ACCEL` и `AIR_ACCEL`**

Найти блок констант:

```java
    public static final float SWIM_SPEED = 2.0f;
    public static final float SWIM_UP_MAX = 2.5f;
    public static final float SINK_MAX = -2.0f;
```

Заменить на:

```java
    public static final float SWIM_SPEED = 2.0f;
    public static final float SWIM_UP_MAX = 2.5f;
    public static final float SINK_MAX = -2.0f;
    public static final float GROUND_ACCEL = 14f;
    public static final float AIR_ACCEL = 2.5f;
```

- [ ] **Шаг 2: Заменить мгновенный snap в ветке `else` на экспоненциальное приближение**

Найти блок (ветка `else` после `inWater`):

```java
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

Заменить на:

```java
        } else {
            // MC-подобное движение: экспоненциальное приближение к цели.
            // Сильный разгон/торможение на земле, слабый контроль в воздухе.
            float targetX = wish.x * WALK_SPEED;
            float targetZ = wish.z * WALK_SPEED;
            float rate = onGround ? GROUND_ACCEL : AIR_ACCEL;
            float t = 1f - (float) Math.exp(-rate * dt);
            velocity.x += (targetX - velocity.x) * t;
            velocity.z += (targetZ - velocity.z) * t;
            velocity.y += GRAVITY * dt;
            if (input.keyDown(org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE) && onGround) {
                velocity.y = JUMP_VELOCITY;
                onGround = false;
            }
        }
```

Примечание: переменная `speed` (строка `float speed = flying ? FLY_SPEED : WALK_SPEED;`) остаётся — она используется в ветке `flying`. Не удалять.

- [ ] **Шаг 3: Скомпилировать проект**

Выполнить в PowerShell из корня `e:\mineclone`:

```powershell
$libs = Join-Path $PWD 'libs'; $out = Join-Path $PWD 'out'
if (-not (Test-Path $out)) { New-Item -ItemType Directory -Force $out | Out-Null }
$jars = (Get-ChildItem $libs -Filter *.jar | ForEach-Object { $_.FullName }) -join ';'
$sources = Get-ChildItem -Path 'src\main\java' -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$srcList = Join-Path $out 'sources.txt'
[IO.File]::WriteAllLines($srcList, [string[]]$sources, [System.Text.UTF8Encoding]::new($false))
javac -encoding UTF-8 -d $out -cp $jars "@$srcList" 2>&1
```

Ожидание: **никакого вывода ошибок** (0 errors).

- [ ] **Шаг 4: Запустить игру и проверить вручную**

```powershell
.\run.ps1
```

Чек-лист:
- [ ] Старт с места (нажать W) → плавный разгон за ~0.2 с, не мгновенный
- [ ] Отпустить W → короткое скольжение ~0.2 с, без «ледяного» эффекта
- [ ] Прыжок вбок (A/D + SPACE) → горизонтальная инерция сохраняется, в воздухе направление почти не меняется
- [ ] Бег по прямой → итоговая скорость ~4.8 м/с (как раньше)
- [ ] Полёт (F) → мгновенный отклик как прежде (ветка не тронута)
- [ ] Плавание в воде → инерция воды как прежде (ветка не тронута)

- [ ] **Шаг 5: Зафиксировать изменения**

```powershell
git add src/main/java/com/mineclone/game/Player.java
git commit -m "feat(player): MC-like ground movement — accel ramp + weak air control"
```

---

## Ожидаемое поведение после реализации

| Ситуация | Было | Стало |
|---|---|---|
| Старт движения | Мгновенно полная скорость | Плавный разгон ~0.2 с |
| Остановка | Мгновенно 0 | Скольжение ~0.2 с |
| Управление в воздухе | Полный мгновенный контроль | Слабый — инерция прыжка сохраняется (MC) |
| Максимальная скорость | 4.8 м/с | 4.8 м/с (без изменений) |
| Полёт / вода | — | Не затронуты |
