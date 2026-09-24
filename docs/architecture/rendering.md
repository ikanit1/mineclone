# Rendering

Updated: 2026-09-24. Paths in backticks are relative to the repository root.

`core/Window.java` requests OpenGL 4.4 with a 3.3 fallback. Shader families include
chunk, water, shadow, sky, precipitation, entities, text, UI and multiple post passes;
the renderer is no longer a five-program pipeline. `render/Shaders.java` owns GLSL.
`TextureAtlas` loads authored PNGs, using 32-pixel tiles and 16 tiles per row
(512 x 512 atlas), plus a texture array. `assets/atlas.png` is a debug output.
Do not reintroduce procedural placeholder textures as runtime art.

`GpuTimers` adds seven sequential GPU phase queries with three frames in flight.
Only complete available frames are read; a full ring skips sampling instead of
blocking or overwriting a pending query. F3 and stress output label delayed GPU
samples with their source frame. See [GPU timing](gpu-timers.md) for boundaries,
ring tests and the BenchShaders audit command. The local warmed benchmark measured
0.52% difference between phase sum and enclosing GPU timestamps and 0.0228 ms of
timer CPU overhead per frame; driver and workload details are recorded there.

## Rendering pipeline (all in `render/`)

Свет считается в **линейном** пространстве и копится в HDR-буфер; тонемап и
гамма — только в композите. ADR: `knowledge/decisions/shader-pipeline.md`.

Каждый кадр в `Game.render()`:

0. **Тени** — `ShadowMap`: два ортографических прохода глубины от солнца/луны
   (каскад 0 — радиус 40 блоков, каскад 1 — 110, стык на 30). Кастеры — чанки
   (`SHADOW_VERTEX`, alpha-тест по атласу для листвы) и мобы
   (`MobRenderer.renderShadow`). Пропускается при `shaderQuality == 0`.
   Каскады перестраиваются **лениво** (`ShadowMap.needsRebuild`): ближний раз
   в 2 кадра, дальний раз в 6, плюс немедленно при резком повороте камеры,
   прыжке времени и перестройке мешей (`Game.invalidateShadows()`). Шейдер
   сэмплит `ShadowMap.matrix(c)` — ту матрицу, которой карта реально
   отрисована, а не свежевычисленную.
1. **HDR-цель** — `PostProcess.begin()`: дальше всё рисуется в multisample
   RGBA16F, а не в экран.
2. **Небо** — `SkyRenderer.renderDome()` (полноэкранный градиент зенит →
   горизонт → земля + гало вокруг светила), затем спрайты солнца/луны/звёзд/
   облаков; солнце светит сильно за 1.0, чтобы из него росли bloom и god rays.
3. **Opaque chunks** — `chunkShader` (CHUNK_VERTEX/FRAGMENT в `Shaders`).
   Чанк рисуется, если его не закрыла **окклюзия прошлого кадра**
   (`OcclusionCuller`): коробки ставятся в очередь и выпускаются одной пачкой
   после непрозрачной геометрии, а ответы забираются в начале следующего кадра
   и только готовые — CPU не ждёт GPU никогда. Прежний условный рендер по
   запросу, выпущенному строкой выше, не мог сработать в принципе
   (`GL_QUERY_NO_WAIT`), но стоил больше двух тысяч лишних вызовов GL за кадр.
   Per-vertex `aLight` по-прежнему кодирует AO × face-directional × sky-fraction;
   шейдер делит его на известную `faceShade(N)` и ставит на место направленной
   яркости честный `N·L`. Нормаль — `cross(dFdx(world), dFdy(world))`, считается
   **до** `discard` (иначе производные не определены и листва чернеет).
   `centroid out vec2 vUv` prevents MSAA UV extrapolation bleeding.
4. **Мобы** — `MobRenderer` рисует коробочные модели по скинам из `MobSkins`,
   освещение общее через `SceneLighting`.
5. **Вода** — своя программа `WATER_FRAGMENT`: рябь в нормалях, френель,
   солнечная дорожка.
5.5. **Осадки** — `PrecipitationRenderer`: снег и дождь целиком на
   видеокарте. Одна статическая VBO со случайными «семенами», а положение,
   снос ветром и закрутка считаются в вершинном шейдере. **Снос — это
   пройденный путь (`WeatherDrift`), а не «ветер × время»**: ветер пульсирует
   порывами, и произведение качало разом весь снегопад и весь объёмный туман
   (`knowledge/bugs/precipitation-swayed-with-gusts.md`); плотность
   задаётся числом отрисованных инстансов, поэтому усиление метели плавное и
   не пересоздаёт ни одного буфера. Коробка частиц привязана к миру и
   сворачивается вокруг камеры — снежинка не едет вместе с игроком, игрок
   проходит сквозь снегопад. Крыши знает `PrecipitationField`: карта высот, ниже
   которых осадки не падают, — без неё снег шёл бы сквозь крышу дома и сыпался
   в пещеру.
5.6. **Предметы, обломки, верёвки** — `ItemRenderer` (блок — кубик, инструмент
   и еда — пластинка; стопка рисуется двумя-тремя копиями вразбежку, иначе
   она неотличима от одного блока), `DebrisRenderer` (обломки — настоящие
   кубы с куском текстуры в четверть тайла: плоский квадратик лицом к камере
   в блочном мире выдаёт себя сразу) и `RopeRenderer` (верле-связки
   `ROPE`/`CHAIN` в радиусе 13 блоков). Все трое рисуются тем же кубом и
   шейдером, что мобы, — значит свет, тени и туман у них ровно те же, что у
   мира вокруг.
6. **Block outline / трещины / следы / частицы**. Рамка выделения едет через
   `OutlineAnimator`: появляется и гаснет по альфе, а при переводе прицела на
   соседний блок доезжает, а не прыгает — скачок читается как мерцание.
   `TrajectoryRenderer` показывает пунктирную дугу заряжаемого броска.
   `DecalRenderer` кладёт следы плашмя на грань: тот же шейдер, что у частиц (`PARTICLE_*`), но
   базис квада горизонтальный, а не лицом к камере. Глубина читается, но не
   пишется, отсечение задних граней на время снято — горизонтальный квад
   поворачивается курсом, и половина направлений отбраковывалась бы как
   изнанка. Следы остаются только на снегу и песке.
6.5. **Игрок** — `PlayerRenderer` рисует модель от третьего лица тем же кубом,
   шейдером и раскладкой скина, что и мобы (иначе они разъедутся по стилю).
   Скин — `PlayerSkin`; ноги берут запасной тайл `T_SPARE`, в нём лежат штаны.
   Размах шага гасится отдельной амплитудой: `walkedDistance` монотонен, и без
   неё остановившийся игрок застывает с раскинутыми ногами.
   **Голова и корпус поворачиваются по отдельности** (`BodyRotation`), как в
   Minecraft: голова всегда на курсе камеры, корпус доворачивается к
   направлению движения, а между ними держится конус `MAX_OFFSET` (75°). За
   пределом корпус подтягивается ровно до предела, а не до головы. Предел
   проверяется и на ходу: при шаге боком направление движения отстоит от
   взгляда на прямой угол, и без проверки шея выворачивалась бы. Модель
   повёрнута на `-bodyYaw`, голова добавляет к этому разницу с курсом
   взгляда — иерархия «корпус → голова». Одним классом живут и свой игрок, и
   чужие в комнате: курс корпуса выводится из движения, поэтому по сети он не
   едет.
7. **First person** — `HeldItemRenderer` чистит z-буфер и рисует руку (текстурированный бокс из `PlayerSkin`, тот же куб и шейдер, что у мобов) и предмет в ней. Рука видна всегда, включая держание блока; позы вынесены в статические `armPose()` / `itemPose()` — чистая матричная математика, считается без GL и покрыта тестом «hand stays on screen».
8. **Пост** — `PostProcess.render()`: композит — единственный проход, который
   пишет в окно, поэтому **масштаб рендера** виден только здесь: сцена
   рисуется в буфер меньшего размера и растягивается на окно, а HUD остаётся
   чётким (он рисуется после и в полном разрешении). Дальше: глубина резкости (только в фоторежиме),
   bright-pass → гаусс → bloom, радиальное размытие от экранного солнца →
   god rays, ACES-тонемап, эффект Пуркинье, виньетка, обратная гамма,
   дизеринг. Глубина резольвится отдельным блитом в обычную текстуру:
   multisample-renderbuffer сэмплить нельзя, а DoF читает глубину попиксельно.
   **Глубина мира резольвится до руки**: рука от первого лица чистит z-буфер
   под себя, и после неё в буфере остаётся только она — а глубина резкости и
   объёмный туман читают глубину мира. Порядок вызовов:
   `begin` → мир → `resolveDepth` → рука → `resolveHandDepth` (только если
   нужна маска резкости) → `resolveColor` → `render`.
   При `dofStrength == 0` композит не делает ни одной лишней выборки.
8.5. **Фон интерфейса** — `Backdrop` снимает размытую копию готового кадра
   между композитом и HUD: в ней уже есть мир, но ещё нет самого интерфейса,
   и именно она видна сквозь стеклянные панели. Экранный буфер
   многосэмпловый, а блит из многосэмплового с масштабированием запрещён — поэтому сначала
   полноразмерное схлопывание, потом уменьшение вчетверо и два прохода
   гаусса. На четверти разрешения это дешевле одного полноэкранного прохода.
9. **HUD** — `Crosshair`, `Hud` (hotbar, menus, debug overlay via `Font`/`TextRenderer`/`UiRenderer`). Рисуется поверх композита, в sRGB — пост его не трогает.
   Визуальный язык — тёмное полупрозрачное стекло: подложка, светлое ребро
   сверху, тёмное снизу. Блоки в слотах рисуются изометрическим кубиком
   (`Hud.drawBlockIcon` поверх `UiRenderer.texQuad4`), яркость граней взята из
   `ChunkMesher.FACE_LIGHT` — иначе блок в интерфейсе и блок в мире выглядят
   из разных игр. Кресты, слои, вода и предметы остаются плоскими.
   Наверху по центру — лента компаса (`Hud.drawCompass`): верх ленты работает
   горизонтом, светило идёт по дуге над ним, справа часы. У шрифта два кегля:
   22 px для заголовков и 14 px (`Hud.small`) для счётчиков стопок, часов и
   подписей. ADR: `knowledge/decisions/hud-glass-and-compass.md`.

`SceneLighting` — единственный источник правды по свету кадра: чанки, вода,
мобы, рука и фон меню получают одни и те же юниформы через `apply(Shader)`.
`SunLight` — вся математика светила и каскадов, без единого GL-вызова, поэтому
покрыта обычными тестами.

Если драйвер не собрал HDR-буфер, `PostProcess.isReady()` отдаёт false,
`uLinearOut` становится 0 и каждый мировой шейдер тонемапит сам — игра
продолжает работать без пост-эффектов.

**Офлайновая проверка картинки:** `java -cp "out;libs/*" tools\RenderShaderPreview.java`
рендерит настоящий кусок мира в пятнадцати кадрах (времена суток, пещера,
костёр, снег, третье лицо, урон, постройка, туман, следы на снегу, река,
фоторежим с глубиной резкости) в `out-test/previews/` и падает, если кадр
вышел однотонным. `tools\RenderMobPreview.java` — то же для
мобов и руки, `tools\RenderHandPreview.java` — отдельно первое лицо,
`tools\RenderBiomePreview.java` — настоящая сгенерированная местность и
падающие блоки.

**Снимки погоды и неба:** `java -cp "out;libs/*" tools\RenderAtmospherePreview.java` —
снегопад, метель, ливень, северное сияние и фазы луны тем же конвейером, что
в игре, но с заданной погодой. Проверяет то же, что соседи: кадр не
однотонный, отличается от предыдущего, GL без ошибок.

**Импорт внешней графики:** `tools\ImportTerrainAtlas.java` и
`tools\ImportMobAtlas.java` режут готовый лист 4×4 в существующий реестр
тайлов — так в игру попадают перерисованные вручную текстуры, не ломая
индексов. `tools\ImportPlayerSkin.java` — отдельный лист 4×2 под игрока:
камера подходит к нему ближе всех, и делить тайлы с пятью мобами ему дорого.
Длинные грани руки на входе растягиваются центральным столбцом: генератор
изображений рисует руку предметом на фоне, а грань куба обязана быть залита
целиком. `tools\ImportToolTextures.java` и `tools\ImportBiomeTextures.java` —
листы 4×2 с инструментами и биомной почвой. `tools\DrawPixelParticles.java`
рисует частицы масками 8×8 и увеличивает их без сглаживания: сглаженная
частица в блочном мире выглядит грязным пятном, а не пикселем.
`tools\GenBiomeSprites.java` — разовый генератор биомных спрайтов.

**Снимки интерфейса:** `java -cp "out;libs/*" tools\RenderHudPreview.java` —
26 кадров: хотбар с сердцами, сытостью и прочностью, инвентарь с полкой крафта,
сундук, печь, творческое меню, компас на четырёх временах суток, витрина темы
меню и все экраны меню (титул, список миров с превью, удаление, переименование,
пустой список, создание мира, три вкладки настроек, клавиши с конфликтом и
захватом, загрузка на трёх этапах, пауза, смерть) — в реальном GL. Кадр падает,
если вышел однотонным или совпал с предыдущим. Заводился после того, как HUD
несколько раз правился вслепую; первый же прогон вскрыл три бага, невидимых из
кода, а первый прогон экранов меню — ещё пять.

**Автопилот меню в настоящей игре:**
`java '-Dmineclone.autopilot=out-test/autopilot/shots' '-Dmineclone.savesDir=out-test/autopilot/saves' -cp 'out;libs/*' com.mineclone.Main` —
скрытое окно, сейвы и options.dat во временной папке. Проходит по всем пяти
кадрам кинематографа фона, снимает каждый с двух точек траектории и требует
ноля незагруженных чанков на каждом показанном кадре (`missingChunks`) —
математику коридора проверяет обычный тест, а вот что коридор успел
загрузиться до показа, видно только в живой игре; проверяет и наплыв между
кадрами. Дальше: фон меню на закате и ночью, титул, миры, создание, загрузку,
игру, паузу, настройки, клавиши и возврат в меню; настоящим Esc проверяет
переходы паузы и запись превью мира. Проверяет и музыку: трек меню играет и идёт, `/music next` его
меняет, после выхода из мира снова звучит трек меню. Код выхода ненулевой при провале. Ловит то, чего не видно в
офлайновых снимках: сборку кадра меню игрой, контраст над живым фоном,
порядок обновления и отрисовки.

**Замер интерфейса:** `java -cp "out;libs/*" tools\BenchUi.java` — 300 кадров
каждого окна с `glFinish`, печать `мс/кадр` и `draw call/кадр`. После перехода
на пакетный рендер и каркас окон: инвентарь 476 вызовов и 2,33 мс → 1 и 0,16;
сундук 633 и 3,05 → 1 и 0,10; креатив 355 и 1,45 → 1 и 0,11.

**Сравнение снимков:** `java -cp "out;libs/*" tools\ComparePreviews.java <эталон> <новые>` —
попиксельное сравнение двух папок превью. Пиксель «разный», если канал уехал
больше чем на 40; кадр падает, если разных больше 0,3 %. Нужен там, где
правка обязана ничего не менять на экране — например при смене рисовальщика.

**Замер воды:** `java -cp "out;libs/*" tools\BenchWater.java` — тик
`WaterSimulator` над самым мокрым из шести сидов, все чанки разбужены каждый
тик (так делает стриминг). До правки 11.4 мс и пять раз в секунду, после — 4.8
мс в худшем случае и ноль в установившейся игре.

**Замер рывков в живой игре:** `java '-Dmineclone.stress=60' -cp 'out;libs/*' com.mineclone.Main` —
`StressFlight` создаёт мир, бежит по прямой, крутит головой и копает, а в конце
печатает не средний кадр, а перцентили, двадцать худших кадров с разбивкой по
фазам и паузы сборщика мусора за тот же прогон. Автопилот игрока почти не
двигает, а рывки живут ровно там, где он бежит: стриминг, выгрузка дальних
чанков, перестройка каскадов, мусор от мешей.

**Замер стоимости кадра:** `java -cp "out;libs/*" tools\BenchShaders.java` —
169 чанков / 321k треугольников, glFinish после каждого кадра, первый прогон
каждого режима выбрасывается (на нём драйвер компилирует пайплайн).
Замер на машине разработки, 1920x1080: HDR+тонемап 1.0 мс, +bloom 1.3,
Fancy (тени 1024, PCF 3x3) 1.7, Ultra (2048, PCF 5x5) 2.1. До ленивых
каскадов Ultra стоил 2.8, а карта 3072 добавляла ещё 0.6 мс филла.
Главное, что показал замер: проход теней упирается не в разрешение карты
(1024 и 1536 стоили одинаково), а в число draw-call'ов — отсюда лень.

## Meshing

`ChunkMesher.buildData()` runs on background threads (returns `MeshData`, uploaded on main thread). Per-vertex AO uses `AO_TABLE = {1.0, 0.86, 0.74, 0.62}`. Face culling rule: a face is drawn when the neighbour is `AIR`, `transparent`, or `cutout` (the `cutout` case covers leaves — they have holes but are not `transparent`).

Обход идёт **y → z → x** — ровно так ячейки лежат в памяти
(`idx = (y * SIZE_Z + z) * SIZE_X + x`); прежний x, y, z шагал через
шестнадцать элементов и мимо каждой кэш-линии.

`Mesh` — **одна чересстрочная вершинная область** на чанк (10 float на
вершину: позиция, UV, свет, блочный свет, повтор) плюс индексы. Раньше
атрибутов было пять, каждый в своём буфере: семь объектов GL и шесть
`glBufferData` на чанк, причём `repeat` выделял массив нулей размером с
позиции, даже когда повторов не было. Загрузка меша идёт в главном потоке, и
каждый такой вызов — поход в менеджер памяти драйвера.

## Texture atlas

`TextureAtlas` **loads** sprites — it does not draw them. The source of truth is `assets/textures/blocks/<name>.png`, one PNG per entry of `TextureAtlas.TILE_NAMES` (the array index *is* the tile index used by `BlockType`). Each sprite is rescaled nearest-neighbour to `TILE` (32 px) and packed into a 512×512 atlas. A missing PNG becomes a magenta/black checkerboard and a stderr warning instead of a crash. Filter is `GL_NEAREST` (no mipmaps — the atlas tiles are edge-to-edge and mipmaps cause bleed). `--regen-atlas` only re-dumps `assets/atlas.png` for inspection; it is never read back.

The sprites themselves are generated by `tools/GenBlockTextures.java` (run `java tools\GenBlockTextures.java` from the repo root). **Генератор дописывает
только недостающие спрайты и не трогает уже лежащие в папке** — часть из них
перерисована вручную в 32×32, и слепой прогон затирал их процедурными 16×16
(`knowledge/bugs/texture-generator-overwrote-art.md`). Перегенерировать весь
набор, дамп атласа и `docs/textures.html` — `--force`. Pixel-art master resolution is 16×16 — the engine's ×2 upscale keeps it crisp. House rules baked into the generator: indexed **5-stop colour ramps** with hue shifting (shadows cold, highlights warm), **ordered 4×4 Bayer dithering** instead of blends or partial alpha, and **seamless tiling** (all noise runs on a torus with a lattice period dividing 16). Composite tiles combine two ramps; ramp B lives at indices 5–9. The tool also writes `docs/textures.html` — a pixelated 256×256 preview with 2×2 seam check, the ramps and the raw index matrices.

## Shaders

All GLSL is inlined as Java string literals in `Shaders.java`. Общие куски
вынесены в приватные константы `LIB_COLOR` (гамма/ACES), `LIB_SHADOW` (PCF по
каскадам) и `LIB_LIGHT` (направленный свет, полусферный ambient, факелы, туман)
и склеиваются конкатенацией с `#version`.

Программы: `CHUNK`, `WATER`, `SHADOW` + `SHADOW_MOB` (проход карты теней),
`SKYDOME`, `SKY` (спрайты), `MOB` (мобы и рука первого лица), `LINE` (block
outline), `CRACK` (mining overlay), `PARTICLE`, `HUD`, `TEXT`, `UI`, и четыре
пост-программы: `POST_BRIGHT`, `POST_BLUR`, `POST_GODRAY`, `POST_COMPOSITE`
(общий `POST_VERTEX` рисует полноэкранный треугольник из `gl_VertexID`, без VBO).

У всех «мировых» программ есть `uLinearOut`: 1.0 — писать линейный HDR
(дальше пост), 0.0 — тонемапить и гаммить самому (аварийный путь).

## Checks and tuning

`run-tests.ps1 -Only optimization,animation,menu` checks pure invariants. Run `OptimizationGlSmoke` and the relevant preview/native smoke for actual GL behavior; compilation alone does not validate rendering.

Knobs: [TUNING.md](../TUNING.md). Entry points: [PROJECT_MAP.md](../PROJECT_MAP.md).
