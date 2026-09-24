# Menus, HUD and input

Updated: 2026-09-24. Paths in backticks are relative to the repository root.

`ui/ScreenStack` owns menu navigation; `game/Hud` is the in-world overlay.
`TitleScreen.VERSION` and the HUD resolve `core/BuildInfo.VERSION`, generated from
`gradle.properties`; game and server logs use the same BuildInfo summary.
Unreadable worlds route to `WorldLoadErrorScreen` and never enter regeneration;
see [save safety](saves.md).

## Entry point & game loop

`Main` → creates `Window` (GLFW+OpenGL 3.3 core) → `Game.run()`. The game loop is in `Game` and ticks at variable delta-time with a 50 ms cap. State machine: `MENU → LOADING → PLAYING ↔ PAUSED`, плюс `DEAD` и окна инвентаря, сундука, печи. В `MENU`, `LOADING`, `PAUSED` и `DEAD` интерфейс рисует стек экранов меню (ниже).

## Меню (`com.mineclone.ui`)

Меню — отдельный пакет, а не `Hud`: `MenuTheme` (весь визуальный язык),
`ScreenStack` (push / back / reset, фейды, Esc) и класс на экран — `TitleScreen`,
`WorldSelectScreen`, `WorldCreateScreen`, `SettingsScreen`, `KeybindScreen`,
`LoadingScreen`, `PauseScreen`, `DeathScreen`. `Game` видит только стек и одно
`MenuAction` за кадр; переходы между экранами стек разбирает сам, назад с корня
решает игра. ADR: `knowledge/decisions/menu-architecture.md`.

- **Виджеты немедленного режима**: вызов рисует и сразу отвечает, нажали ли;
  состояние (пружина наведения, зажатая кнопка, фокус, тянущийся слайдер)
  живёт в теме по строковому id. Текст откладывается и сбрасывается на границе
  слоя — поэтому диалог перекрывает текст под собой. Клик — по отпусканию.
- **Ввод — снимок `UiInput` без GLFW**: экраны водят тесты, офлайновые снимки и
  автопилот. В кадре, где меню открылось из игры, ввод пустой — иначе Esc,
  поставивший паузу в обновлении, тут же закрывал её в отрисовке.
- **Экран снимается со стека сразу, а закрывается (`closed`), когда догаснет**:
  пока гаснет, его рисуют — текстуры превью миров ещё нужны.
- **Настройки** — пять вкладок: «Графика», «Экран», «Игра», «Управление»,
  «Звук». `SettingsModel`: экран меняет поля, игра применяет сразу
  (`Game.applySettings`), options.dat пишется при закрытии экрана. Модель
  зеркалит поля игры каждый кадр меню: F11 меняет режим окна в обход меню.
  Пресет качества («Быстро / Красиво / Ультра») — заготовка, а не режим: он
  один раз выставляет отдельные ручки и уходит, а ручная правка любой из них
  переводит подпись в «Свои». ADR: `knowledge/decisions/graphics-settings.md`.
- **Клавиши** — `core/KeyBindings`: `Input.down/pressed/released(Action)` вместо
  жёстких `GLFW_KEY_*`. Переназначаются только клавиши клавиатуры; Esc, F1,
  F3–F6, F11 — системные. Конфликт подсвечивается у обоих действий, но не
  запрещается: иначе две клавиши местами не поменять.
- **Миры**: список от последнего сыгранного, превью `saves/<id>/icon.png`
  (`render/Thumbnail` снимает кадр из резольва `Backdrop` после сохранения),
  размер на диске считается в фоновом потоке, копия и переименование идут
  прямо в `SaveManager`.
- **Фон меню — кинематограф** (`MenuBackground`): серия пролётов камеры над
  пейзажами мира меню. Пейзажи ищет `MenuScout` по сиду — рельеф и биомы
  чистые функции, чанки для поиска не нужны, и разведка укладывается в 56 мс
  в фоновом потоке конструктора. Кадр — `MenuShot`: траектория (кривая Безье
  плюс отдельно едущая цель взгляда, трапеция скорости, едва заметное
  дрожание курса) и `Air` — время суток и его ход, облачность, буря, дождь,
  снег, ветер, низовая мгла, дымка, экспозиция, насыщенность, глубина
  резкости, сияние. Кадр меню (`Game.renderMenu`) рисует тени, купол,
  светила, чанки, воду, осадки и пост тем же порядком, что игра; палитра неба
  общая — `render/SkyPalette`. Экран загрузки стоит над этим фоном.
  ADR: `knowledge/decisions/menu-cinematics.md`.
- **Дыре в фоне взяться неоткуда, и это проверяемое свойство.** Шейдер чанка
  мешает цвет с туманом через `smoothstep(uFogStart, uFogEnd, d)`, а это ровно
  единица при `d >= FOG_END`: дальше геометрия неотличима от тумана.
  `MenuShot.requiredChunks()` собирает всё, что ближе, — объединение по 256
  выборкам траектории чанков в конусе ±85° вокруг курса плюс кольцо вокруг
  камеры, — и кадр не выходит на экран, пока каждый из них не смешен и не
  выгружен. Низ купола красится **цветом тумана**, поэтому за краем коридора
  не видно даже стыка. Гарантированный радиус (`LOAD_RADIUS × 16` = 144)
  берётся с запасом над `FOG_END` (128). Если следующий кадр не готов к концу
  текущего, текущий идёт **обратно** по своей траектории: продолжить полёт
  вперёд значило бы вывести камеру из загруженного коридора.
- **Наплыв между кадрами** — `render/MenuDissolve`: два пролёта стоят в разных
  местах мира, и нарисовать их в один буфер нельзя — глубина перемешала бы
  два пейзажа. Снимок берётся за несколько кадров до подмены (`missing(next)
  <= 12`), а не в момент готовности: подмена идёт в обновлении, снимок — в
  отрисовке.
- **Уходя в мир, фон отпускает память** (`MenuBackground.trim()`): коридор
  вдвое больше прежнего мирка меню, и предзагруженный следующий кадр всю
  партию не нужен никому.

## Checks and tuning

`run-tests.ps1 -Only menu,creative,inventory` checks menu behavior. Actual navigation, focus, scrolling and screenshots require a running game. Container UI is described in [containers.md](containers.md).

Knobs: [TUNING.md](../TUNING.md). Entry points: [PROJECT_MAP.md](../PROJECT_MAP.md).
