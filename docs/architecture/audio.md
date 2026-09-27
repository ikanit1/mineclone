# Sound and acoustic space

Updated: 2026-09-27. Paths in backticks are relative to the repository root.

`audio/SoundEngine.java` owns OpenAL, while acoustic scheduling and gain rules are
plain Java. Rain, thunder, material sounds, occlusion and EFX room reverb must be
checked at their actual listener position. An absent audio device is a supported
headless path; it must not prevent deterministic tests.

## Sound

`SoundEngine` wraps OpenAL. `Sounds` maps `BlockType` → OGG file lists under `assets/sounds/`. Sounds play one-shot with randomised pitch/volume.

`AmbientSound` — расписание фона: пещера, дождь, гром, ветер, вода. Гремит
только гроза, а не всякий сильный дождь; порывы ветра копятся независимо от
осадков (в бурю слышно и то и другое) и только под открытым небом. Решает **что**
играть, а не как (ни OpenAL, ни файлов), поэтому проверяется обычными
тестами — а расписание здесь и есть вся механика. Приоритет вода → дождь →
пещера; таймер пещеры не копится на поверхности; звук пещеры играется из
случайной тёмной точки рядом, а не в голове. **Дождь — слышимый, а не
выпадающий** (`AmbientSound.heardRain`): осадки биома одинаковы в пещере и над
ней, поэтому они умножаются на открытость неба по небесному свету у головы
(≤ 3 — тишина, ≥ 12 — полностью). Без этого ливень наверху звучал в глубине
пещеры и перебивал её фон (`knowledge/bugs/rain-heard-in-caves.md`).

**Шум дождя и ветер грозы — сшитые петли, а не одна запись** (`RainAmbience`,
`StormAmbience`, `SoundEngine.updateLoopAllOf`, `SeamlessLoop`). Каждая запись
входит из тишины и уходит в тишину. Закольцованная сама по себе, она
проваливалась на каждом круге: дождь (восемь `rain*.ogg` по ~2 с) — на 30 дБ
каждые 2 с, ветер (четыре стерео `wind*.ogg` по 7,6–11,4 с, каждая один порыв)
— в 2–3 с затишья, треть круга тише −6 дБ. Фоновый декодер режет у всех
записей вход и выход (окна 10 мс не тише медианы на 1 дБ), выравнивает тела по
громкости и сшивает их равномощными кроссфейдами, включая шов конца с началом;
каналы сохраняются.

- Дождь: швы по 100 мс, петля 12,4 с, сборка 16 мс. Швы по громкости
  неотличимы от самого дождя: худший 1,7 дБ против 2,6 дБ естественной
  неровности записей.
- Ветер: швы по 0,5 с (короткий шов на медленном гуле слышен сменой тембра),
  стереопетля 22,1 с, порывы сохранены (до +5,2 дБ над медианой). Швы садятся
  в ложбины между порывами: там ветер на полсекунды стихает на 5–7 дБ, как
  естественное затишье, а тише −6 дБ он лишь 4 % времени вместо трети.

Разбор: `knowledge/bugs/rain-loop-pulsed.md`. Отдельные порывы вне грозы
(`AmbientSound`, событие `WIND`) по-прежнему играют записи ветра разово.

`AcousticProbe` шестью лучами по осям отдаёт **две** величины, `Room(closed,
size)`, и разделять их обязательно: реверберация зависит от них независимо.
Замкнутость решает, сколько звука вернётся, размер — как долго он затухает.
Маленькая комната замкнута сильно, а звучит **коротко**; пещерный зал замкнут
так же, а тянется секунды. Пока обе выводились из одного числа, комната 5×5
получала 1.000 против 0.801 у пещеры 24×24 — то есть хвост в 4.2 с внутри
деревянного дома, длиннее пещерного, отчего крик моба в комнате и бил по
ушам. Сейчас 0.64 с против 2.12 с. Луч вверх проверяется первым: земля под
ногами неизбежно перекрывает нижний луч — если над головой открытый воздух,
send ровно нулевой. `RoomAcoustics` переводит замер в параметры EFX чистой
арифметикой, без единого вызова OpenAL, поэтому «комната короче пещеры» —
обычный тест, а не спор на слух. EFX опционален: нет у драйвера — источники
играют как раньше. Перезамер раз в полсекунды; размер сглаживается своим
темпом, чтобы выход из чулана в зал слышался как удлинение хвоста, а не как
переключение. ADR: `knowledge/decisions/ambient-and-reverb.md`.

## Checks and tuning

`run-tests.ps1 -Only audio,weather` checks scheduling and mixing rules; `SeamlessLoopTests` measures loudness around the whole rain and wind loops, wrap included. `tools/RainAudioSmoke.java` and `tools/StormAudioSmoke.java` exercise playback (both also check that OpenAL loops the stitched bed, not one recording, and crosses its end without stopping); use `tools/AnalyzeMusic.java` for PCM/LUFS measurements.

Knobs: [TUNING.md](../TUNING.md). Entry points: [PROJECT_MAP.md](../PROJECT_MAP.md).
