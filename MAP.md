# Карта проекта RYSMap

Собрана `_shared/tools/make-map.py` 2026-11-18. Руками не править: после структурных правок пересобрать.

Java: 33 файлов, 4508 строк. Переводы: `src/main/resources/assets/rysmap/lang/en_us.json`, `src/main/resources/assets/rysmap/lang/ru_ru.json`.
Читать сначала: `CLAUDE.md`, `README.md`.

## Точки входа
- `@Mod`: `works.repka.rysmap.RYSMap` (`src/main/java`)
- миксины: `src/main/resources/rysmap.mixins.json`

## Папки
- `docs/` — 1 файлов
- `gradle/` — 2 файлов
- `src/` — 37 файлов

## `src/autotest/java` — пакет `works.repka.rysmap.dev`

**(корень)** (1)
- `MapAutoTest` 246 — Проверка М1 в игре (gradlew runAutotest): новый мир, сканер у спавна и после перелёта, регионы на диске, повторный вход читает их без пересканирования, MapApi.drawTiles рисует фон.

## `src/main/java` — пакет `works.repka.rysmap`

**(корень)** (1)
- `RYSMap` 23 — Точка входа RYSMap.

**api** (1)
- `MapApiImpl` 47 — Реализация ядрового MapApi.

**client** (3)
- `ClientConfig` 99 — Настройки игрока: config/rysmap/client.json.
- `ClientEntry` 114 — Клиентская часть: сессия карты на вход в мир, события чанков, тик, кадр, строка F3, реализация MapApi.
- `MapSession` 387 — Карта одного входа в мир.

**compat** (1)
- `SablePlots` 37 — Участки Sable: блоки летающих построек лежат в далёких чанках того же измерения.

**migrate** (1)
- `FtbRegionReader` 277 — Читатель разведанной карты FTB Chunks 2101.1.22 (local/ftbchunks/data//).

**mixin** (1)
- `ClientLevelMixin` 23 · миксин — Правки блоков на клиенте для карты.

**scan** (5)
- `ChunkQueue` 87 — Чанки, ждущие снимка, по расстоянию до игрока: ближний первым.
- `ChunkSnapshot` 105 — Снимок чанка для рабочего потока.
- `DirtyColumns` 48 — Чанки с правками блоков.
- `MapWorker` 71 — Единственный рабочий поток карты: разбор снимков чанков, пекарь, чтение и запись регионов, позже переезд с FTB.
- `SurfaceScanner` 99 — Разбор снимка в рабочем потоке: на столбец высота, видимый блок, биом и глубина воды.

**tile** (9)
- `Baker` 166 — Пекарь: столбцы региона в пиксели ABGR (порядок байтов текстуры игры).
- `ColorTable` 332 — Цвета блоков и биомов для карты.
- `DimensionMap` 202 — Карта одного измерения у рабочего потока: палитра, кэш сырых регионов, пекарь.
- `MapColors` 32 — Цвета для пекаря по строковым id: реализация в игре читает атлас и биомы, тесты дают свои.
- `Palette` 164 — Номера блоков и биомов одного измерения: palette.json рядом с регионами.
- `RegionCache` 177 — Сырые регионы одного измерения в куче с жёстким потолком.
- `RegionData` 143 — Один слой региона 512×512 блоков (32×32 чанка) в памяти: высота, номер блока, номер биома, вода и свет на столбец, карта разведанных чанков и время скана каждого чанка.
- `RegionFile` 267 — Файл региона r...rmap.
- `TileTextures` 315 — Текстуры регионов 512×512 в видеопамяти, только главный поток.

**world** (2)
- `MapStore` 76 — Папка карты одного мира: /rysmap//.
- `WorldKey` 49 — Ключ мира: имя папки карты в rysmap/.

## `src/test/java` — пакет `works.repka.rysmap`

**(корень)** (1)
- `MiscTest` 93

**migrate** (2)
- `FtbFixtureTest` 141 — Настоящая карта FTB Chunks клиента автора на 18.11 (_shared/_saved/fixtures/ftb-2026-11-18/client/ftbchunks).
- `FtbRegionReaderTest` 171 — Заготовка того же формата, что у FTB Chunks 2101.1.22, собирается в тесте: в git настоящих файлов нет.

**tile** (5)
- `BakerTest` 98
- `DimensionMapTest` 107
- `PaletteTest` 88
- `RegionCacheTest` 103
- `RegionFileTest` 121
