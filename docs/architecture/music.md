# Music

Updated: 2026-09-24. Paths in backticks are relative to the repository root.

`audio/MusicLibrary.CATALOG` is the track list: 14 MP3 files currently ship in
`assets/music/`. `MusicDirector` chooses and times them; `MusicPlayer` owns the
streaming OpenAL queue; `game/MusicSense` samples world context.

## Музыка

14 MP3 из `assets/music/` играют по ситуации, в ритме Minecraft: трек, потом
тишина 2,5–7 мин. В меню музыка звучит всегда: первый трек через 1–2 с, между
треками 4–10 с. ADR: `knowledge/decisions/music.md`.

- `MusicLibrary.CATALOG` — трек → настроения (`MusicMood`: меню, части суток,
  пещера, опасность, путь, стройка, дом, полёт, **край**) и поправка громкости
  по LUFS. Файл не из таблицы играет как трек дня и меню. Новый трек меряется
  `java -cp "libs/*" tools\AnalyzeMusic.java`.
- **Край, а не биом**: одиннадцать биомов схлопываются в шесть краёв
  (`WOODS / ARID / FROZEN / WETLAND / SEA / ASHEN`) методом
  `Biome.musicMood()` — switch **без `default`**, чтобы новый биом ронял
  сборку, а не тихо замолкал. В каталоге 14 треков; края выбирают их по настроению, поэтому в пустыне
  звучит не то же, что в тундре. Край стоит между занятием и временем суток:
  опасность и пещера важнее, вечер — нет.
- **Ловушка биомной музыки — не выбор трека, а граница.** Биом квантуется по
  четыре блока, и идущий вдоль опушки пересекает её десятки раз в минуту.
  `MusicSense` признаёт новый край, только если игрок продержался в нём
  `REGION_HOLD`; первый край берётся сразу — при входе в мир ждать нечего.
- `MusicDirector` — чистая логика без OpenAL, часы игры моделируются в тестах.
  Ситуация решает, что играть: первое подходящее настроение — фильтр, остальные —
  вес. Моменты (прибытие, пробуждение, возрождение, пещера, рассвет, закат, дом,
  путь, стройка, полёт, опасность) только иногда сокращают тишину — по жребию и
  с кулдауном. В бою спокойный трек сначала приглушается, гаснет только после
  12 с. Не терпимый ситуацией трек гаснет через 20 с. Трек меню доигрывает в
  мире, если миру подходит.
- `MusicPlayer` — поток `mineclone-music`: JLayer декодирует кусками по 0,25 с в
  очередь из 6 буферов. Подвисания кадра музыку не рвут. Главный поток видит
  запрос сразу (поколения), поток музыки сообщает только «поколение доиграло».
- `game/MusicSense` — пещера, укрытие, опасность, путь и стройка: мировые пробы
  дважды в секунду, занятия — по событиям.
- F3 — строка `Music:`; консоль — `/music` и `/music next`.

## Checks and tuning

`run-tests.ps1 -Only audio` includes MusicTests and arbitrary MP3 read chunk sizes. New tracks need catalog moods, gain analysis and actual listening checks.

Knobs: [TUNING.md](../TUNING.md). Entry points: [PROJECT_MAP.md](../PROJECT_MAP.md).
