# Tuning reference

Moved from the pre-foundation CLAUDE.md on 2026-09-24; structure placement corrected against code. Values are defaults, not performance guarantees. Check the named source constant before changing a knob.

## World and generation

| Location | Constant | Effect |
|---|---|---|
| `WaterSimulator` | `scanned` | Чанк осматривается один раз после загрузки |
| `Chunk` | `clearMask` | Маска прозрачности для небесного света |
| `ChunkMesher` | `FACE_LIGHT[]` | Per-face directional brightness |
| `ChunkMesher` | `AO_TABLE[]` | AO corner darkening curve |
| `World` | `SEA_LEVEL = 50` | Water/sand threshold |
| `BiomeProvider` | `CONT/TEMP/HUM_FREQ` | Biome region size |
| `BiomeProvider` | `C_OCEAN, T_COLD, T_HOT, H_DRY, H_WET` | Biome rarity thresholds |
| `Biome` | `baseHeight / amplitude / treesPer128` | Per-biome terrain & vegetation |
| `World` | `LEAF_REACH` | На сколько крона выходит за ствол и за границу чанка |
| `World` | `TREE_DENSITY_NUM / TREE_ROLL_RANGE` | Поправка плотности леса после снятия отступа от края |
| `World` | `placeOak / placeSpruce / leafDisc` | Форма кроны; ствол не ниже шести — листва твёрдая |
| `MobType` | per-species row | Габариты, HP, скорости, звуковая папка, цвет частиц |
| `MobSpawner` | `PEACEFUL_CAP / HOSTILE_CAP` | Сколько мобов живёт вокруг игрока (12 / 8) |
| `MobSpawner` | `HOSTILE_LIGHT_MAX` | Порог темноты для спавна враждебных (0.3); на поверхности вырождается в «ночь», под землёй работает всегда |
| `MobSpawner` | `HOSTILE_BAND_DOWN / UP` | Вертикальная полоса поиска тёмной площадки (28 / 12) |
| `Weather` | `FRONT_LENGTH / TRANSITION` | Длина атмосферного фронта и время перетекания в следующий |
| `Weather` | `Kind` | Таблица видов погоды: осадки, облачность, ветер, буря |
| `Weather` | `SNOW_ALTITUDE / MIN_VISIBILITY` | С какой высоты дождь становится снегом и предел видимости в буре |
| `NightSky` | `moonPhase() / PHASES` | Фаза луны по номеру суток; из-за неё `/time set` не нормализует оборот |
| `NightSky` | `moonlight()` | Сколько света даёт луна в этой фазе |
| `NightSky` | `AURORA_CHANCE / AURORA_WARM_BIOME` | Как часто бывает сияние и насколько оно слабее в тёплых биомах |
| `ItemEntity` | `MAGNET_RANGE / PICKUP_RANGE / MERGE_RANGE` | С какого расстояния предмет летит к игроку, подбирается и сливается с соседним |
| `StructureStability` | `SEARCH_LIMIT` | Потолок обхода при проверке опоры тяжёлой постройки |
| `FluidThermodynamics` | `viscosity()` | Во сколько раз лава течёт медленнее воды |
| `MobType` | `Temper` | Нрав вида: скотина, дичь, хищник, нежить |
| `MobTactics` | `morale() / routine()` | Когда моб зовёт на помощь или бежит и чем занят в это время суток |
| `LimbDamage` | `speedMultiplier() / attackMultiplier()` | Во что обходятся перебитые ноги и руки |
| `PathFinder` | `MAX_LEAP_GAP / LEAP_COST` | Самый широкий перепрыгиваемый провал и его цена |
| `PathFinder` | `LIT_CELL / LIGHT_COST` | С какого блочного света клетка «под факелом» и насколько нежить её обходит |
| `Lightning` | `CELL / WINDOW / CELL_CHANCE` | Ячейка грозы, окно розыгрыша и шанс ячейки за окно (удар раз в 14–40 с) |
| `Lightning` | `STRIKE_THRESHOLD / CLOUD_HEIGHT` | Ниже какой бури не бьёт и с какой высоты падает разряд |
| `Biome` | `musicMood()` | В каком из шести краёв звучит биом |
| `WorldSimulation` | `WATER_TICK / FURNACE_TICK / FURNACE_RADIUS` | Темп воды и печей и радиус их работы |
| `Rivers` | `FREQ / WIDTH / BANK` | Масштаб русла, его ширина и где начинается берег |
| `Rivers` | `MAX_ABOVE` | Выше скольки блоков над морем река уже не пробивается |
| `Rivers` | `LAKE_FREQ / LAKE_THRESHOLD / LAKE_DEPTH` | Размер, редкость и глубина озёр |
| `Caves` | `THRESHOLD` | Толщина и частота тоннелей (8 % подземного объёма) |
| `Caves` | `FREQ_H / FREQ_V` | Форма ходов; выше 1.5x по вертикали — тоннели вырождаются в плоские линзы |
| `Caves` | `SEABED_GUARD` | Толщина дна под водой; пробитое дно осушает океан |
| `Structures` | `ALL` | Четыре шаблона: руины, хижина, обелиск, подземелье |
| `Structures` | `REGION_CHUNKS / MIN_CHUNK_GAP / candidateKind()` | Один кандидат на регион 10x10 с шансом 3/4, минимум 7 чанков между кандидатами |
| `BlockTicker` | `TICK_INTERVAL / SAMPLES_PER_CHUNK / RADIUS_CHUNKS` | Темп жизни мира (0.25 с, 3 позиции, 4 чанка) |
| `BlockTicker` | `GRASS_LIGHT_MIN` | Свет, при котором трава живёт и расползается |
| `BlockTicker` | `LEAF_SUPPORT_RANGE` | На каком расстоянии листва держится за ствол |
| `BlockTicker` | `RAIN_EXTINGUISH / SNOWFALL_MIN` | Пороги осадков для тушения огня и снегопада |
| `BlockTicker` | `SNOW_MAX_LEVEL` | Предельная толщина покрова (7 = почти полный блок) |
| `LavaSimulator` | `TICK_INTERVAL` | Темп волны лавы (1.5 с) и три клетки в стороны |
| `FallingBlocks` | `MAX_ACTIVE / STARTS_PER_FRAME` | Сколько блоков падает разом и сколько колонн стартует за кадр |
| `Recipes` | `shaped() / fits()` | Шаблон рецепта, зеркальность и в какую сетку он лезет |

## Rendering

| Location | Constant | Effect |
|---|---|---|
| `ParticleSystem` | `setBudget()` | Потолок живых частиц; ноль выключает их целиком |
| `ShadowMap` | `PERIOD` | Через сколько кадров каскад перестраивается (2 / 6) |
| `ShadowMap` | `DRIFT_LIMIT` | Насколько центр каскада может уехать до принудительной перестройки |
| `ShadowMap` | `LIGHT_LIMIT` | Порог поворота светила, который считается прыжком времени |
| `SunLight` | `CASCADE0_RADIUS / CASCADE1_RADIUS / CASCADE_SPLIT` | Геометрия каскадов теней (40 / 110 / 30 блоков) |
| `SunLight` | `MIN_ELEVATION` | Насколько светило приподнято над горизонтом (иначе каскад вырождается) |
| `SunLight` | `shadowStrength()` | Как быстро тени включаются после восхода |
| `SunLight` | `lightColor()` | Цвет солнца/луны: закат оранжевый, полдень белый |
| `SunLight` | `skyAmbient / groundAmbient` | Полусферный ambient |
| `SceneLighting` | `torchColor` | Цвет блочного света (тёплый оранжевый) |
| `PostProcess.Settings` | `bloomStrength / bloomThreshold` | Сила и порог засветки |
| `PostProcess.Settings` | `rayStrength / rayDensity / rayDecay` | God rays |
| `Shaders.LIB_SHADOW` | `uShadowTaps` | 1 → PCF 3×3, 2 → 5×5 |
| `TextureAtlas` | `TILE = 32` | Во сколько раз апскейлится 16×16 мастер |
| `Shaders.CHUNK_FRAGMENT` | `pow(l, 0.75)` | Gamma/tone curve |
| `PrecipitationRenderer` | число инстансов | Плотность осадков; буферы при этом не пересоздаются |
| `RopeRenderer` | `RADIUS / MAX_LENGTH` | В каком радиусе ищутся связки и какой длины они симулируются |
| `BodyRotation` | `MAX_OFFSET` | Конус головы относительно корпуса: 75°, как в Minecraft |
| `BodyRotation` | `TURN_RATE / MOVING_SPEED` | Скорость доворота корпуса и порог, ниже которого игрок считается стоящим |
| `DecalRenderer` | `MAX / LIFT / FADE_TAIL` | Потолок следов, подъём над гранью и доля жизни на затухание |
| `ItemIcons` | `ICON_YAW / ICON_SPIN` | Поворот кубика в покое и скорость вращения |
| `ItemIcons` | `shapeFaces()` | Форма иконки по блоку: ступени, слои, жидкости |
| `UiRenderer` | `INITIAL_QUADS` | Начальная ёмкость пакета интерфейса |
| `PostProcess.Settings` | `damage / damageColor` | Вспышка урона по краям кадра |
| `PostProcess.Settings` | `dofStrength / dofFocus / dofRange` | Глубина резкости; ноль в strength выключает выборки целиком |
| `Thumbnail` | `WIDTH / HEIGHT` | Размер превью мира (256×144) |

## Audio and music

| Location | Constant | Effect |
|---|---|---|
| `AmbientSound` | `WIND_THRESHOLD / WIND_MIN / WIND_MAX` | С какого ветра слышны порывы и как часто |
| `RoomAcoustics` | `DECAY_MIN / DECAY_SPAN` | Хвост в закутке и насколько он удлиняется к пещере |
| `RoomAcoustics` | `SEND_MAX / WET_MIN / WET_SPAN / DAMP_*` | Влажность эха по замкнутости и глушение верха по размеру |
| `AcousticProbe` | `Room(closed, size)` | Замкнутость ведёт влажность, размер — время затухания |
| `MusicSense` | `REGION_HOLD` | Сколько надо пробыть в новом краю, чтобы музыка его признала |
| `MusicDirector` | `GAP_MIN / GAP_MAX` | Тишина в мире после трека (150–420 с): доля времени с музыкой |
| `MusicDirector` | `MENU_FIRST_* / MENU_GAP_* / MENU_RETURN_*` | Когда начинается музыка меню и сколько молчит между треками |
| `MusicDirector` | `Moment` | Шанс и задержка каждого момента, который начинает трек раньше |
| `MusicDirector` | `MIN_SILENCE / MOMENT_COOLDOWN` | Тишина после трека, до которой моменты молчат, и кулдаун жребия |
| `MusicDirector` | `MISMATCH_GRACE / DANGER_DUCK_DELAY / DANGER_DUCK / DANGER_STOP` | Реакция на смену обстановки и бой |
| `MusicDirector` | `PAUSE_DUCK / WATER_DUCK` | Насколько тише музыка на паузе и под водой |
| `MusicLibrary` | `CATALOG` | Настроения и поправка громкости каждого трека |
| `MusicPlayer` | `MUSIC_BASE` | Уровень музыки относительно звуков (0,7) |
| `MusicPlayer` | `BUFFERS / CHUNK_SECONDS` | Запас очереди OpenAL (6 × 0,25 с) |
| `MusicSense` | `UNDERGROUND_* / SHELTER_* / DANGER_RANGE / TRAVEL_* / BUILD_*` | Что считается пещерой, домом, опасностью, путём и стройкой |

## Menus and containers

| Location | Constant | Effect |
|---|---|---|
| `SettingsModel` | `applyPreset()` | Что именно выставляют «Быстро», «Красиво» и «Ультра» |
| `SettingsModel` | `SCALE_MIN/MAX`, `ENTITY_MIN/MAX` | Пределы ползунков масштаба рендера и дальности существ |
| `ContainerScreen` | `SLOT / GAP / PAD` | Сторона слота, зазор и поля панели окна |
| `ContainerScreen` | пружины `open`, `cursorPos`, `tooltipPos` | Появление окна, инерция курсора и подсказки |
| `ItemFlights` | `TIME` | Сколько летит иконка при Shift-переносе (0,18 с) |
| `TooltipLayout` | `gap / margin` | Отступ подсказки от слота и от края экрана |
| `Tooltip` | `MAX_TAGS` | Сколько тегов помещается до многоточия |
| `Spring` | `MAX_STEP` | Предельный подшаг интегрирования (1/240 с) |
| `InventoryScreen` | `CRAFT_MAX / CRAFT_ROW_H` | Сколько рецептов помещается на полку |
| `CreativeScreen` | `COLUMNS / VISIBLE_ROWS` | Сетка источника и высота окна прокрутки |
| `ScreenStack` | `FADE_OUT / FADE_IN / RISE` | Сколько гаснет старый экран, проявляется новый и насколько он всплывает |
| `MenuTheme` | палитра `TEXT / ACCENT / GOOD / DANGER` | Цвета меню |
| `MenuTheme` | `HOVER_RATE / DOUBLE_CLICK / ROW_H` | Пружина наведения, окно двойного клика, высота строки виджета |
| `TextField` | `REPEAT_DELAY / REPEAT_RATE` | Через сколько и как часто повторяет зажатый Backspace |
| `ScrollState` | `EASE` | Как быстро прокрутка догоняет колесо |
| `TitleScreen` | `SPLASHES` | Строки под логотипом |
| `LoadingScreen` | `TIPS / TIP_SECONDS` | Советы на экране загрузки и как долго держится каждый |
| `CraftingGrid` | ширина 2 или 3 | Сетка инвентаря и сетка верстака |

## Network and server

| Location | Constant | Effect |
|---|---|---|
| `RoomCode` | `ALPHABET / LENGTH / REGION_BASE` | Алфавит кода комнаты, его длина и где в алфавите начинаются регионы |
| `Backoff` | `DELAYS / ATTEMPTS` | Паузы между попытками подключения (0,5 / 1,5 / 4 с) |
| `ConnectLadder` | `defaultSteps()` | Чем и в каком порядке пробуем дойти до облака |
| `RegionProbe` | `TIMEOUT_MS` | Сколько ждём ответа одного региона при замере |
| `NatPmp` | `LIFETIME_SECONDS / TIMEOUT_MS` | На сколько просим отображение порта и сколько ждём шлюз |
| `UpnpGateway` | `DISCOVER_MS / LEASE_SECONDS` | Сколько слушаем рассылку и на сколько просим порт |
| `PublicAddress` | `STUN_SERVERS` | Кого спрашиваем «как меня видно снаружи» |
| `LanBeacon` | `PORT / INTERVAL / STALE` | Порт объявлений, темп и через сколько мир пропадает из списка |
| `LanTransport` | `HEARTBEAT_SECONDS / SILENCE_SECONDS` | Через сколько тишины напомнить о себе и считать соединение мёртвым |
| `CompositeTransport` | `HOST_ACTOR` | Номер хозяина; остальные раздаются по таблице |
| `Multiplayer` | `RESUME_WINDOW` | Сколько участник пытается вернуться, прежде чем потерять мир |
| `DedicatedServer / WorldClock` | `TICK_RATE / TIME_SCALE` | 20 Гц сервера и общий темп суток 0,005 радиана/с |
| `ServerConfig` | `server.properties` | Порт, мир, сид, режим, регион, код комнаты, радиус, автосохранение |
| `NetProto` | `VERSION / TICK_RATE` | Версия протокола и частота кадра сети (12 Гц) |
| `NetProto` | `TIME_SYNC_INTERVAL / CHAT_LIMIT / NAME_LIMIT` | Как часто едет время суток, длина реплики и имени |
| `NetChannel` | `SOFT_LIMIT` | При каком размере копилка уходит в сеть, не дожидаясь конца тика |
| `Multiplayer` | `CHUNK_REQUESTS_PER_TICK` | Сколько дельт чанков участник просит за тик |
| `Multiplayer` | `SILENCE_LIMIT / ITEM_SYNC_INTERVAL` | Когда молчащий игрок считается ушедшим и темп рассылки предметов |
| `RemotePlayer` | `CATCH_UP / FULL_STRIDE_SPEED / STRIDE_DECAY` | Как чужой игрок догоняет снимок и как гаснет его шаг |
| `PhotonTransport` | `MAX_PLAYERS` | Сколько игроков вмещает комната Photon |
| `PhotonCodes` | `NAME_SERVER_WSS / LIB_VERSION` | Точка входа Photon и версия клиента в строке запроса |
| `LanTransport` | `DEFAULT_PORT / MAX_PLAYERS` | Порт прямого соединения и потолок участников |

## Game, saves and controls

| Location | Constant | Effect |
|---|---|---|
| `Game` / `Options` | `renderRadius` | Configured chunk draw distance; persisted in options.dat |
| `Game` | `shadowMapSize()` | Разрешение карты теней: 1024 (низкие) / 2048 (средние и высокие) |
| `Game` | `scaled()` | Масштаб рендера: во сколько процентов от окна рисуется сцена |
| `Game` | `particleBudget() / weatherScale() / entityRange()` | Во что превращаются настройки частиц, осадков и дальности существ |
| `Options.Video` | `windowMode / resolutionIndex / renderScale / antialiasing` | Настройки экрана (options.dat v7) |
| `Options.Graphics` | `shadows / bloom / godRays / volumetricFog / waterReflections / particles / weather / entityDistance / occlusion / chunkLod` | Настройки качества по отдельности |
| `Options.Gameplay` | `cameraShake / screenEffects / contextHints / fpsDisplay` | Настройки игры |
| `Game.fillPostSettings` | `exposure / saturation / vignette` | Тон финального композита |
| `Options` | `shaderQuality` | 0=Fast, 1=Fancy, 2=Ultra (ползунок «Shaders») |
| `GenBlockTextures` | `R_*` рампы | Палитры блоков: 5 стопов, hue shifting |
| `GenBlockTextures` | `BAYER4` / `q()` | Сила и порядок дизеринга |
| `Game.render()` | `uAmbient = 0.22f` | Shadow floor |
| `WeatherDrift` | `MAX_STEP / SNOW_STORM_CARRY / *_STORM_FALL` | Предел шага сноса, во сколько метель несёт снег сильнее и насколько буря ускоряет падение |
| `Game` | `THROW_CHARGE_TIME` | За сколько секунд бросок заряжается полностью |
| `Storm` | `FLASH_TIME / BOLT_TIME / SOUND_SPEED` | Длина вспышки, жизнь болта и скорость звука (42 блока/с) |
| `Storm` | `RANGE / IGNITE_RANGE` | Дальше этого удар не ищется и в каком радиусе занимается огонь |
| `Game` | `FLASH_LIGHT` | Насколько ярко разряд освещает мир в пике вспышки |
| `Game` | `NET_CHAT_LINGER / NET_JOIN_TIMEOUT` | Сколько держатся строки чата и сколько ждём мир от хозяина |
| `Game` | `FIRE_DAMAGE_PER_SECOND` | Урон от стояния в огне (2/с, столько же у мобов) |
| `Game` | `THIRD_PERSON_DISTANCE` | Отход камеры в третьем лице (упирается в стену) |
| `Game` | `DAMAGE_FLASH_TIME / DAMAGE_SHAKE_TIME` | Длительность красной виньетки и толчка камеры |
| `Game` | `HEALTH_GHOST_DELAY` | Пауза перед тем, как «тень» потери на сердцах начнёт оседать |
| `Game` | `FOOTPRINT_LIFE / FOOTPRINT_SPREAD` | Сколько держится след и насколько разнесены ноги |
| `Game` | `holdsFootprint()` | На каком грунте след вообще остаётся |
| `Hud` | `selectSpring()` | Пружина выделения слота в хотбаре |
| `Hud` | `COMPASS_W / COMPASS_H / COMPASS_ARC` | Габариты ленты компаса и высота дуги светила |
| `Hud` | `COMPASS_DEG_PX` | Масштаб ленты: сколько пикселей на градус курса |
| `Hud` | `heading()` | Курс от севера; восток — это −Z, потому что там встаёт солнце |
| `Hud` | `clockText()` | Игровые часы; ту же формулу показывает `/time` |
| `Game` | `TOAST_Y` | Откуда начинаются тосты — верх по центру занят компасом |
| `Game` | `PHOTO_SPEED / PHOTO_FAST / PHOTO_SLOW` | Скорость свободной камеры и её модификаторы |
| `Game` | `PHOTO_DOF / PHOTO_RANGE` | Радиус размытия вне фокуса и полуширина резкой зоны |
| `KeyBindings` | `Action` / `RESERVED` | Переназначаемые действия с клавишами по умолчанию и системные клавиши |
| `MenuShot` | `LOAD_RADIUS / FOG_START / FOG_END` | Коридор фона меню (9 чанков) и где в нём дымка (96 → 128) |
| `MenuShot` | `HALF_ANGLE / NEAR_KEEP` | Конус видимости коридора (±85°) и кольцо, которое держится всегда |
| `MenuShot` | `EASE_K` | Доля пути кадра на разгон и на торможение |
| `MenuScout` | `SCAN_RADIUS / SCAN_STEP / PROBE` | Где и как часто разведка ищет пейзажи |
| `MenuScout` | `DIST_FROM / DIST_TO / SWEEP / BULGE` | Откуда, куда и по какой дуге летит камера кадра |
| `MenuScout` | `TAN_MAX / TAN_MIN / MAX_ABOVE / CEILING` | Пределы наклона взгляда и высоты камеры |
| `MenuScout` | `air()` | Время суток, погода и тон каждой из пяти категорий |
| `MenuBackground` | `PRELOAD_LEAD / DISSOLVE / DISSOLVE_ARM` | За сколько грузим следующий кадр, длина наплыва, когда снимать |
| `MenuBackground` | `UPLOADS_HURRY / UPLOADS_CALM / RELEASE_PER_FRAME` | Бюджеты выгрузки мешей и освобождения чанков |
| `SurvivalProgress` | `SAVE_SECTION` | Своя секция сейва: формат уровня из-за целей не поднимается |
| `StressFlight` | `-Dmineclone.stress` | Сколько секунд бежать в замере рывков |
| `Game` | `LOADING_TAIL_ALLOWANCE / loadingRadius()` | Сколько чанков достраивается уже в игре и какой радиус загрузка закрывает |
