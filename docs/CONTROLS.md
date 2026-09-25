# Controls

## Клавиши, добавленные поверх базовых

Игровые действия — движение, прыжок, спуск, бег, полёт, инвентарь, бросок,
осмотр, консоль, слоты 1–9 — переназначаются в «Настройки → Управление →
Назначение клавиш». F-клавиши и Esc — системные и не переназначаются.

| Клавиша | Что делает |
|---|---|
| `F1` | Убирает весь интерфейс (режим для скриншотов); меню и пауза остаются |
| `F5` | Первое лицо → вид из-за спины → вид в лицо |
| `F6` | Фоторежим: свободная камера, скрытый интерфейс, глубина резкости. WASD + пробел/Shift — полёт, Ctrl быстрее, Alt точнее, колесо — дистанция фокуса |
| `F11` | Полноэкранный режим |
| `F3+H` | Расширенные подсказки: id, числа прочности, теги |
| `F3+средняя` | Отладочный перебор meta блока под прицелом |
| Средняя | Пипетка: взять в руку блок под прицелом; с Ctrl в креативе — вместе с начинкой |
| `Q` | Бросить предмет. Удерживать — зарядить бросок (видна дуга), Ctrl+Q — бросить всю стопку |
| `OreGenerator` | `VEINS` | Глубина, редкость и размер жил (уголь 85/чанк, алмаз 3.3) |
| `SceneLighting` | `pointColor / pointRadius` | Свет от источника в руке; нулевой цвет = источника нет |
| `Shaders.LIB_LIGHT` | `pointLight()` | Затухание и всенаправленная доля 0.35 факела в руке |
| `MobSpawner` | `MIN_RADIUS / MAX_RADIUS / DESPAWN_RADIUS` | Кольцо спавна 20–48 и деспавн на 72 |
| `Mob` | `AGGRO_RANGE / LOSE_RANGE / ATTACK_RANGE` | Дистанции агра зомби (16 / 24 / 1.5) |
| `Combat` | `HAND_DAMAGE / HAND_SPEED` | Урон и темп голой руки; менять нельзя без пересчёта мобов |
| `Combat` | `MIN_CHARGED` | Во что превращается удар в самом начале отката |
| `Bow` | `DRAW_TIME / MIN_DRAW` | Полное натяжение и порог, ниже которого выстрела нет |
| `Bow` | `MIN_SPEED / MAX_SPEED / MIN_DAMAGE / MAX_DAMAGE` | Вилки скорости и урона стрелы |
| `Projectile` | `STEP` | Подшаг трассировки; больше половины блока — стрела пройдёт сквозь стену |
| `Projectile` | `GRAVITY / LIFETIME / PICKUP_RANGE` | Настильность, время жизни промаха и радиус подбора |
| `Mob` | `SHOOT_INTERVAL / SHOOT_MIN_RANGE / SHOOT_SPEED / SHOOT_DAMAGE` | Перезарядка стрелка, ближний предел, скорость и урон его стрелы |
| `MobType` | `rangedRange` | Дальность стрельбы вида; 0 — дерётся только вблизи |
| `MobType` | `HOSTILE` / `explodes()` | Список нежити для спавнера и кто взрывается вместо удара |
| `Mob` | `FUSE_TIME / FUSE_RANGE / FUSE_LOSE` | Сколько горит фитиль, с какого расстояния поджигается и на каком гаснет |
| `Explosion` | `RADIUS / MAX_DAMAGE / TOUGH` | Радиус взрыва, урон в эпицентре и прочность, которую он не берёт |
| `MobSpawner` | `hostileFor()` | Доли нежити наверху и под землёй |
| `BlockType` | `requiredToolLevel()` | Порог инструмента, ниже которого блок не даёт дроп |
| `BlockType` | `preferredTool()` | Каким классом блок копается быстро |
| `Chunk` | `CHEST_SLOTS` | Сколько стопок держит сундук |
| `SleepRules` | `NIGHT_DAYLIGHT / MONSTER_RANGE` | Когда уже ночь и в каком радиусе монстры не дают уснуть |
| `SleepRules` | `nextDawn()` | Куда переводится время; всегда вперёд, ровно на одни сутки |
| `BedrollBehavior` | `META` | Высота спальника в meta (3 → половина блока) |
| `Smelting` | `COOK_TIME` | Сколько секунд плавится одна единица |
| `Smelting` | `result() / fuelSeconds()` | Что во что переплавляется и что сколько горит |
| `Game` | `FURNACE_TICK / FURNACE_RADIUS` | Темп тиков печей и радиус, в котором они работают |
| `Player` | `HUNGER_IDLE_DRAIN / HUNGER_SPRINT_MUL` | Темп голода в покое и на бегу |
| `Player` | `REGEN_HUNGER_MIN` | Порог сытости, ниже которого здоровье не восстанавливается |
| `Player` | `STARVE_DAMAGE / STARVE_FLOOR` | Урон от голода и предел, ниже которого он не бьёт |
| `MobType` | `drop() / dropCount()` | Id предмета, который падает с моба, и сколько |
| `items/*.json` | `tool / food / fuel / mass` | Числа предметов: класс и уровень инструмента, сытость, топливо |
| `Recipes` | `table()` | Таблица рецептов; порядок задаёт их вид на полке |
| `TagRegistry` | `COMPUTED` | Теги, состав которых считает код, а не файл |
| `Game` | `MOB_DROP_RANGE` | Дальше этого добыча с убитого моба не доходит |
| `PathFinder` | `MAX_NODES / MAX_PATH / MAX_FALL` | Бюджет A*, предел длины пути и высота безопасного спуска |
| `PathFinder` | `JUMP_COST / FALL_COST / WATER_COST / FIRE_COST` | Что маршрут считает неудобным |
| `Mob` | `REPATH_INTERVAL / REPATH_FAIL_DELAY` | Как часто перестраивается маршрут и пауза после отказа |
| `Mob` | `DIRECT_RANGE / PATH_RANGE` | Вилка дистанций, в которой вообще запускается A* |
| `Mob` | `FLANK_OFFSET / FLANK_RANGE` | Насколько и до какой дистанции моб заходит сбоку |
| `Mob` | `SHELTER_RADIUS / SHELTER_HOLD` | Где горящий ищет тень и сколько в ней пережидает |
| `MobHerd` | `RANGE` | Дальше этого сородич уже не сосед по стаду |
| `Mob` | `HERD_COMFORT / HERD_PULL_SPAN` | С какого удаления и как сильно тянет к своим |
| `Mob` | `SIGHT_HALF_ANGLE / SIGHT_CLOSE` | Конус зрения (±60°) и радиус, внутри которого угол не проверяется |
| `Mob` | `DARK_SIGHT_FACTOR` | Во сколько раз падает дальность обнаружения в темноте (0.7) |
| `Mob` | `INVESTIGATE_TIME / INVESTIGATE_REACH` | Сколько моб идёт на шум и когда считает, что дошёл |
| `Game` | `NOISE_BREAK / LAND / PLACE` | Радиусы слышимости событий игрока: разлом, приземление, постановка |
| `FootstepFeedback` | `NOISE_SPRINT / NOISE_WALK` | Радиусы слышимости шагов: бег и ходьба |
| `AmbientSound` | `CAVE_MIN / CAVE_MAX` | Пауза между звуками пещеры (45–160 с) |
| `AmbientSound` | `RAIN_THRESHOLD / THUNDER_THRESHOLD / THUNDER_CHANCE` | Пороги дождя и грома |
| `AmbientSound` | `RAIN_SKY_SILENT / RAIN_SKY_FULL` | Небесный свет у головы, при котором дождя не слышно (3) и слышно полностью (12) |
| `AcousticProbe` | `MAX_DISTANCE` | Дальше этого луч считается ушедшим в открытое пространство |
| `AmbientEffects` | `REVERB_INTERVAL / CAVE_SOUND_RADIUS` | Темп перезамера эха и радиус точки звука пещеры |
| `Mob` | `ATTACK_DAMAGE` | Урон зомби игроку (3 = 1.5 сердца) |
| `Mob` | `STUCK_TIME / SIDESTEP_TIME` | Через сколько упора в стену идти вбок и как долго |
| `Mob` | `SAFE_FALL / STEP_DISTANCE` | Безопасное падение (3 блока) и путь между шагами |
| `Game` | `MOB_REACH / HAND_DAMAGE` | Дальность и урон удара рукой по мобу |
| `Game` | `ATTACK_COOLDOWN / CRIT_MULTIPLIER / SPRINT_KNOCKBACK` | Темп удара, крит в падении, отброс в спринте |
| `Mob` | `INVULN_TIME` | Окно неуязвимости моба — защита от закликивания |
| `Player` | `HURT_INVULN_TIME` | То же окно у игрока: стая бьёт не быстрее одного |
| `Mob` | `BURN_DAYLIGHT` | Порог daylight, выше которого зомби горит (0.35 = «уже день») |
| `MobRenderer` | `buildModel()` | Таблицы частей тела (габариты моделей) |
| `PixelArt` | `M` / `BAYER4` | Мастер-разрешение тайла скина (16) и матрица дизеринга |
| `PixelArt` | `jitter()` | Насколько крап ломает регулярность шахматки |
| `PlayerSkin` | `generate()` | Скин игрока — рука первого лица; override `assets/mobs/player.png` |
| `HeldItemRenderer` | `armPose()` / `itemPose()` | Позы руки и предмета от первого лица |
| `HeldItemRenderer` | `ARM_W / ARM_L` | Габариты бокса руки |
| `MobSkins` | `TILE` | Разрешение тайла скина (32 → текстура 128×64); графика в долях тайла, поднимается без правок |
| `MobSkins` | `generate()` | Процедурные скины по правилам `PixelArt`; override — `assets/mobs/<type>.png` |
| `Mob` | `DEATH_TIME` | Сколько моб валится набок перед исчезновением |
| `Player` | `REGEN_INTERVAL / REGEN_DELAY_AFTER_HIT` | Темп регена и пауза после удара |
