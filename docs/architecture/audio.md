# Sound and acoustic space

Updated: 2026-09-24. Paths in backticks are relative to the repository root.

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

`run-tests.ps1 -Only audio,weather` checks scheduling and mixing rules. `tools/RainAudioSmoke.java` and `tools/StormAudioSmoke.java` exercise playback; use `tools/AnalyzeMusic.java` for PCM/LUFS measurements.

Knobs: [TUNING.md](../TUNING.md). Entry points: [PROJECT_MAP.md](../PROJECT_MAP.md).
