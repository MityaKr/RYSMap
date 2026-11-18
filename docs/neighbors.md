# Как соседи подключаются к карте (Т2–Т5)

Всё идёт через контракты ядра 1.13 (`works.repka.ryscore.map.MapApi`, `works.repka.ryscore.world.Labels`), на клиенте, из главного потока. Регистрировать можно когда угодно, хоть до загрузки RYSMap: реестры живут в ядре. Без RYSMap вызовы ничего не делают.

## RYSTeams: команда и пинги (Т2)

Что карта уже делает сама, без правки RYSTeams:
- участники своей команды видны головами в синей рамке (слой «Игроки» RYSMap, места от сервера RYSMap по `TeamsApi.teamOf`), клик по игроку на карте ведёт к нему путь;
- пункт меню «Пинг команде» (id `rysmap:ping`): сервер RYSMap зовёт `TeamsApi.ping(team, dim, x, y, z, "here", имя)` (лента и подпись в мире у RYSTeams) и сам показывает пинг на картах участников и союзников минуту (слой «Пинги»).

Чего без RYSTeams не сделать (нужна правка RYSTeams):
- пинги, поставленные клавишей RYSTeams, на карте не видны: данные пинга живут только в клиенте RYSTeams (`TeamClient.PINGS`). Надо: слой `MapApi.layer` с метками `Kind.PING` (или источник `Labels.source` с `Labels.Kind.PING`, тогда подпись в мире рисует RYSMap и `TeamHud.pingLabel` можно выключить при `Labels.present()`);
- постоянные метки команды (`Team.Mark`) и «чем занят»: слой с метками `Kind.POINT`, `Marker.editor` для «Кто видит», `Marker.tooltip` для статуса;
- свой пункт пинга с выбором вида: зарегистрировать `MapApi.menu` с id `rysmap:ping`, тогда пункт карты уступает место ему.

## RYSQuests: цели квестов (Т3)

```java
MapApi api = MapApi.get();
api.layer(new MapApi.Layer() { ... id "rysquests:goals", name «Цели квестов», icon TARGET ...
    markers(dim) -> Marker.builder(questUuid).pos(dim, x, y, z).kind(MapApi.Kind.GOAL)
        .item(значок квеста).color(цвет главы).label(имя квеста).radius(радиус района поиска)
        .panel(() -> MapApi.Panel.builder(имя).subtitle("Цель квеста").text(описание)
            .row(«Глава», глава).row(«Награда», награда)
            .action(Action.builder(«Открыть квест», () -> книга.open(квест)).primary(true).build())
            .action(Action.builder(«Сохранить точкой», () -> MapApi.get().newPoint(Target.builder(dim, x, y, z).label(имя).build())).build())
            .build())
        .build()
    search(query) -> List<Hit> по именам квестов
});
```

«Показать на карте» в книге: `MapApi.get().open(Target)`. Подпись цели в мире: `Labels.source(...)` с `Labels.Kind.GOAL` (закреплена, видна всегда).

Публичного чтения прогресса в `rysquests.api.QuestsApi` нет (запрос сборки: KubeJS читает `ProgressStore.allProgress()` через `Java.loadClass`). Это класс RYSQuests, правится в сессии RYSQuests; предложение: `QuestsApi.isCompleted(UUID owner, ResourceLocation quest)` и `QuestsApi.completed(UUID owner) -> Set<ResourceLocation>`.

## RYScope: «где найти» (Т4)

```java
MapApi.get().highlight(MapApi.Highlight.builder(id, Component.literal("Равнины, y 60–90"))
    .biomes(набор ResourceKey<Biome>).heights(60, 90).color(0xFFE4B23C).build());
MapApi.get().open(Target у игрока); // окно карты с подсветкой
```

Карта приглушается, подходящие места закрашены, сверху плашка с крестиком (снимает `highlight(null)`). Высоты сравниваются с поверхностью столбца.

## RYSPulse: тревоги базы (Т5)

Кода Pulse нет; точка подключения готова:
- слой «Тревоги базы»: метки `Kind.ALERT` (пульсируют красным, на миникарте за краем прижаты к рамке), `Marker.panel` для карточки машины;
- строка под миникартой: `MapApi.status(id, 20, () -> Status.of(цвет, Icons.Shape.WARN, текст))`;
- подпись в мире: `Labels.source` с `Labels.Kind.ALERT` (видна в любом режиме игрока);
- «Показать путь»: `MapApi.get().track(Target)`.
