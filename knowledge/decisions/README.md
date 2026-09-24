# Architecture decision records

Dates and statuses below are copied from each ADR, not inferred completion claims.
Current subsystem behavior lives in [docs/architecture](../../docs/architecture/).
Historical linked plans are preserved under [docs/archive](../../docs/archive/superpowers/).

| Date | Recorded status | Decision |
|---|---|---|
| 2026-09-24 | accepted | [Save preservation before extensibility](save-preservation-and-sections.md) |
| 2026-09-15 (реверберация пересмотрена 2026-09-22) | принято | [Фоновая атмосфера и реверберация](ambient-and-reverb.md) |
| 2026-09-15 | stable | [Меш чанка строится в фоне, а не в кадре](async-chunk-meshing.md) |
| 2026-09-15 | принято | [Объёмный туман, осадки на GPU, иней, стекло с размытием](atmosphere-rendering.md) |
| 2026-06-11 | принято | [Система биомов — климатические шумы + сглаживание параметров](biome-system.md) |
| 2026-09-14 | принято | [Пещеры, руды и свет от факела в руке](caves-and-ores.md) |
| 2026-09-15 | принято | [Сундуки и контейнеры](chests-and-containers.md) |
| 2026-05-20 | stable | [Стратегия загрузки чанков](chunk-loading-strategy.md) |
| 2026-09-15 | принято | [Печь и переплавка](furnace-and-smelting.md) |
| 2026-09-15 | принято | [«Сочность» — отклик мира на каждое действие](game-feel.md) |
| 2026-09-20 | stable | [Настройки графики — ручки, а не один рубильник](graphics-settings.md) |
| 2026-09-15 | принято | [Стеклянный интерфейс, объёмные иконки и компас](hud-glass-and-compass.md) |
| 2026-09-14 | принято | [Голод и еда](hunger-and-food.md) |
| 2026-09-20 | stable | [Небесный свет правится точечно, а не заливается заново](incremental-sky-light.md) |
| 2026-09-16 | принято | [Окна инвентаря](inventory-windows.md) |
| 2026-09-16 | принято | [Реестр предметов и компоненты стопки](item-registry-and-components.md) |
| 2026-09-14 | принято | [Живой мир — тики блоков, огонь, снежный покров](living-world.md) |
| 2026-09-15 | stable | [Меню отдельным пакетом `com.mineclone.ui`](menu-architecture.md) |
| 2026-09-22 | stable | [Кинематограф главного меню](menu-cinematics.md) |
| 2026-09-15 | принято | [Навигация и дерево поведения мобов](mob-navigation.md) |
| 2026-09-14 | принято | [Восприятие мобов — конус зрения, свет и слух](mob-perception.md) |
| 2026-09-21 | принято | [Игра по сети через Photon](multiplayer-photon.md) |
| 2026-09-15 | принято | [Музыка — треки в меню и в мире](music.md) |
| 2026-09-22 | принято | [чтобы подключение получалось](network-hardening.md) |
| 2026-05-20 | stable | [Рендеринг чанков](rendering-approach.md) |
| 2026-09-15 | принято | [Реки и озёра](rivers-and-lakes.md) |
| 2026-09-14 | принято | [Шейдерный конвейер — линейный HDR, каскадные тени, пост-обработка](shader-pipeline.md) |
| 2026-09-15 | принято | [Сон и спальник](sleep-and-bedroll.md) |
| 2026-09-14 | принято | [Инструменты и крафт](tools-and-crafting.md) |
| 2026-09-15 | принято | [Кроны деревьев и листва через границу чанка](tree-canopies.md) |
| 2026-09-20 | stable | [Спокойная вода не должна стоить ничего](water-simulation-cost.md) |
| 2026-09-15 | принято | [Погода фронтами, фазы луны и северное сияние](weather-and-night-sky.md) |
| 2026-09-15 | принято | [Дикие звери, ярость, страх света и прыжки через провал](wildlife-and-mob-moods.md) |
