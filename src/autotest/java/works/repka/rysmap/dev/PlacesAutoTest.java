package works.repka.rysmap.dev;

import static works.repka.rysmap.dev.MapAutoTest.check;
import static works.repka.rysmap.dev.MapAutoTest.mc;
import static works.repka.rysmap.dev.MapAutoTest.session;
import static works.repka.rysmap.dev.MapAutoTest.shot;
import static works.repka.rysmap.dev.MapAutoTest.step;
import static works.repka.rysmap.dev.MapAutoTest.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelResource;
import works.repka.ryscore.migrate.Migrations;
import works.repka.rysmap.RYSMap;
import works.repka.rysmap.client.PlacesClient;
import works.repka.rysmap.net.PlaceNet;
import works.repka.rysmap.places.PlaceRules;
import works.repka.rysmap.screen.MapScreen;
import works.repka.rysmap.server.PlaceStore;
import works.repka.rysmap.tile.ColorTable;

/**
 * «Места» в игре ({@code gradlew runAutotest -Pheap=3G --offline -Pautotest=places}): команды дома и мест сервера, имя в
 * кавычках ({@code /warp "boos apoth"}), «назад», пауза, разогрев правилом оператора, правка места с клиента, шаг
 * {@code places} на копии данных FTB Essentials из fixtures три раза подряд. Снимки рядом с макетами Essentials
 * {@code 04} (карта, группа «Места», панель места), {@code 05} (запрос, свой запрос, разогрев, отказ), {@code 06} (меню
 * игрока), {@code 07} (блок «Перемещения»), плюс колесо.
 */
final class PlacesAutoTest {
    private static int baseX, baseY, baseZ;
    private static double beforeX, beforeZ;
    private static final UUID TOR = UUID.fromString("5b2c6f3e-1111-4a2b-9c3d-000000000001"), LENA = UUID.fromString("5b2c6f3e-1111-4a2b-9c3d-000000000002");
    private static Migrations.Outcome outcome;
    private static int placesAfterFirst = -1;

    private PlacesAutoTest() {}

    private static MapScreen screen() {
        return (MapScreen) mc().screen;
    }

    private static void cmd(String c) {
        mc().player.connection.sendCommand(c);
    }

    private static double distTo(double x, double z) {
        return Math.hypot(mc().player.getX() - x, mc().player.getZ() - z);
    }

    private static MinecraftServer server() {
        return mc().getSingleplayerServer();
    }

    /** Снять отметку шага places в {@code world/rys/migrate.json}. */
    private static void unmark() {
        Path f = server().getWorldPath(LevelResource.ROOT).resolve("rys").resolve("migrate.json");
        try {
            var o = com.google.gson.JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            if (o.has("steps")) o.getAsJsonObject("steps").remove("places");
            Files.writeString(f, o.toString());
        } catch (Exception ex) {
            RYSMap.LOG.warn("[autotest] отметка places не снялась: {}", ex.toString());
        }
    }

    static void build() {
        MapAutoTest.prefix = "places";
        step("title", () -> {
            mc().options.pauseOnLostFocus = false;
            mc().options.onboardAccessibility = false;
            mc().options.guiScale().set(2);
            mc().options.tutorialStep = net.minecraft.client.tutorial.TutorialSteps.NONE;
            mc().getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
            mc().options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
            mc().resizeDisplay();
            if (!(mc().screen instanceof TitleScreen)) mc().setScreen(new TitleScreen());
        }, 30);
        step("world", () -> {
            MapAutoTest.world = "rysmap_places_" + Long.toHexString(System.currentTimeMillis());
            LevelSettings settings = new LevelSettings(MapAutoTest.world, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
            mc().createWorldOpenFlows().createFreshLevel(MapAutoTest.world, settings, new WorldOptions(42L, true, false),
                r -> r.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.NORMAL).value().createWorldDimensions(), mc().screen);
        }, 10);
        when("joined", () -> mc().player != null && mc().level != null && mc().screen == null && session() != null, () -> {
            cmd("gamerule doDaylightCycle false");
            cmd("gamerule doWeatherCycle false");
            cmd("time set noon");
        }, 40);
        when("colors", () -> ColorTable.INSTANCE.ready(), () -> {}, 2);
        when("places arrived", () -> PlacesClient.data().present, () -> {
            PlacesClient.Data d = PlacesClient.data();
            check("места пришли: FTB Essentials не стоит, домов 0", !d.ftb && d.homes.isEmpty());
            check("правила по умолчанию: дом 10 с, назад 30 с, случайное 600 с", d.rules.row(PlaceRules.Fn.HOME).pause == 10
                && d.rules.row(PlaceRules.Fn.BACK).pause == 30 && d.rules.row(PlaceRules.Fn.RANDOM).pause == 600);
            check("хозяин мира оператор: без паузы и правит места сервера", d.bypass && d.manage && d.op);
        }, 2);
        when("settled", MapAutoTest::settled, () -> {
            baseX = mc().player.getBlockX();
            baseY = mc().player.getBlockY();
            baseZ = mc().player.getBlockZ();
            cmd("sethome");
        }, 20);
        step("home set", () -> {
            check("дом поставлен командой /sethome", PlacesClient.data().homes.size() == 1);
            cmd(String.format("tp @s %d ~ %d", baseX + 60, baseZ - 40));
        }, 30);
        step("warp quoted", () -> cmd("setwarp \"boos apoth\""), 10);
        step("warp tp1", () -> cmd(String.format("tp @s %d ~ %d", baseX - 70, baseZ + 50)), 30);
        step("warp oil", () -> cmd("setwarp oil"), 10);
        step("warp tp2", () -> cmd(String.format("tp @s %d ~ %d", baseX + 120, baseZ + 90)), 30);
        step("warp ru", () -> cmd("setwarp \"Деревня\""), 10);
        step("warp tp3", () -> cmd(String.format("tp @s %d ~ %d", baseX - 40, baseZ - 110)), 30);
        step("warp ru2", () -> {
            cmd("setwarp \"Рынок\"");
            cmd("setwarp Arena");
        }, 10);
        step("warps check", () -> {
            check("пять мест сервера (кириллица и пробел в кавычках): " + PlacesClient.data().places.size(), PlacesClient.data().places.size() == 5);
            cmd(String.format("tp @s %d ~ %d", baseX + 300, baseZ + 300));
        }, 30);
        step("home go", () -> cmd("home"), 40);
        step("home check", () -> {
            check("/home вернул домой (" + Math.round(distTo(baseX + 0.5, baseZ + 0.5)) + " бл.)", distTo(baseX + 0.5, baseZ + 0.5) < 2);
            cmd("warp \"boos apoth\"");
        }, 40);
        step("warp check", () -> {
            check("/warp \"boos apoth\" перенёс к месту (" + Math.round(distTo(baseX + 60.5, baseZ - 39.5)) + " бл.)", distTo(baseX + 60.5, baseZ - 39.5) < 3);
            check("история «назад» записала дом", !PlacesClient.data().back.isEmpty());
            cmd("back");
        }, 40);
        step("back check", () -> {
            check("/back вернул к дому (" + Math.round(distTo(baseX + 0.5, baseZ + 0.5)) + " бл.)", distTo(baseX + 0.5, baseZ + 0.5) < 3);
            // правило оператора: дом с разогревом 2 с, паузы у хозяина мира нет (оператор)
            PlaceRules r = PlacesClient.data().rules.copy();
            r.row(PlaceRules.Fn.HOME).warmup = 2;
            PlacesClient.op("rules", r.toJson());
        }, 20);
        step("warmup start", () -> {
            check("разогрев дома 2 с дошёл до клиента", PlacesClient.data().rules.row(PlaceRules.Fn.HOME).warmup == 2);
            cmd(String.format("tp @s %d ~ %d", baseX + 200, baseZ - 200));
        }, 30);
        step("warmup home", () -> {
            beforeX = mc().player.getX();
            beforeZ = mc().player.getZ();
            cmd("home");
        }, 15);
        step("warmup wait", () -> check("во время разогрева игрок ещё на месте", Math.hypot(mc().player.getX() - beforeX, mc().player.getZ() - beforeZ) < 1), 50);
        step("warmup done", () -> {
            check("после разогрева дома (" + Math.round(distTo(baseX + 0.5, baseZ + 0.5)) + " бл.)", distTo(baseX + 0.5, baseZ + 0.5) < 3);
            PlaceRules r = PlacesClient.data().rules.copy();
            r.row(PlaceRules.Fn.HOME).warmup = 0;
            PlacesClient.op("rules", r.toJson());
            // правка места с клиента: описание и цвет
            com.google.gson.JsonObject e = new com.google.gson.JsonObject();
            e.addProperty("name", "Деревня");
            e.addProperty("newName", "Деревня");
            e.addProperty("icon", "flag");
            e.addProperty("color", -1);
            e.addProperty("note", "Жители, торговля, сюда же попадают новички. Земля сервера, строить нельзя.");
            e.addProperty("vis", "all");
            PlacesClient.op("editwarp", e);
        }, 20);
        step("edit check", () -> {
            var v = PlacesClient.data().places.stream().filter(p -> p.name().equals("Деревня")).findFirst();
            check("описание места пришло с сервера", v.isPresent() && v.get().note().startsWith("Жители"));
        }, 2);
        // шаг places на копии данных FTB Essentials из fixtures, три раза подряд
        step("ftb copy", () -> {
            Path src = Path.of(System.getProperty("rysmap.ftbEssentials", ""));
            Path dst = server().getWorldPath(LevelResource.ROOT).resolve("ftbessentials");
            try (Stream<Path> s = Files.walk(src)) {
                for (Path f : s.toList()) {
                    Path t = dst.resolve(src.relativize(f).toString());
                    if (Files.isDirectory(f)) Files.createDirectories(t);
                    else Files.copy(f, t, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                check("копия FTB Essentials в мире", Files.isRegularFile(dst.resolve("data.snbt")));
            } catch (Exception ex) {
                check("копия FTB Essentials: " + ex, false);
            }
        }, 2);
        for (int run = 1; run <= 3; run++) {
            int n = run;
            // второй прогон упирается в отметку «сделано»; перед третьим отметка снимается, и шаг идёт заново по уже
            // перенесённому: так проверяется, что повтор ничего не добавляет
            step("ftb run " + n, () -> server().execute(() -> {
                if (n == 3) unmark();
                outcome = Migrations.runManual("places", server());
            }), 30);
            step("ftb check " + n, () -> {
                int places = PlaceStore.get(server()).book().places().size();
                RYSMap.LOG.info("[autotest] шаг places, прогон {}: {}; {}", n, outcome, PlaceStore.summary(PlaceStore.get(server()).book()));
                check("шаг places прогон " + n + " прошёл: " + (outcome == null ? "нет итога" : outcome.status()), outcome != null && !outcome.status().name().equals("FAILED"));
                if (n == 1) {
                    placesAfterFirst = places;
                    check("первый прогон добавил места FTB к пяти своим: " + places, places > 5);
                } else {
                    check("прогон " + n + " ничего не добавил: " + places, places == placesAfterFirst);
                }
            }, 10);
        }
        // макет 04: карта, группа «Места», панель места
        step("map", () -> {
            mc().player.getAbilities().flying = true;
            mc().player.onUpdateAbilities();
            MapScreen.open();
        }, 30);
        step("map panel", () -> {
            screen().setZoom(2);
            check("панель места «Деревня» открылась", screen().placePanelForTest("Деревня"));
        }, 30);
        shot("map_places_04");
        step("map home", () -> check("панель дома открылась", screen().placePanelForTest("Дом")), 20);
        shot("map_home");
        // макет 06: меню игрока
        step("player menu", () -> {
            screen().placePanelForTest("Дом");
            screen().keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0);
            screen().playerMenuForTest(TOR, "Kot_Boris", false, baseX + 25, baseZ - 18);
        }, 10);
        shot("map_player_06");
        step("team menu", () -> screen().playerMenuForTest(LENA, "Lena_K", true, baseX - 20, baseZ + 15), 10);
        shot("map_player_team");
        // макет 07: блок «Перемещения»
        step("settings", () -> {
            screen().keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0);
            screen().settingsForTest();
        }, 15);
        // 0.2.10: «Перемещения» своя вкладка, «Сервер» соседняя
        step("settings places tab", () -> screen().settingsTabForTest("places"), 10);
        shot("settings_places_07");
        step("settings server tab", () -> screen().settingsTabForTest("server"), 10);
        shot("settings_places_07b");
        // макет 05: запрос, свой запрос, разогрев, отказ
        step("hud", () -> {
            mc().setScreen(null);
            PlacesClient.request(new PlaceNet.Request(TOR, "Tor4ok", false, true, "minecraft:the_nether|Репка", 48));
            PlacesClient.request(new PlaceNet.Request(LENA, "Lena_K", true, false, "minecraft:overworld", 41));
            PlacesClient.note(new PlaceNet.Note(1, Component.literal("Домой"), 3, "home"));
            PlacesClient.note(new PlaceNet.Note(0, Component.literal("Дом на земле Барсуков, вход закрыт"), 0, "home"));
        }, 15);
        shot("hud_requests_05");
        step("hud answer", () -> {
            check("запросов два: входящий и свой", PlacesClient.requests().size() == 2);
            PlacesClient.request(new PlaceNet.Request(TOR, "Tor4ok", false, true, "", 0));
            PlacesClient.request(new PlaceNet.Request(LENA, "Lena_K", true, false, "", 0));
            check("погасшие запросы ушли", PlacesClient.requests().isEmpty());
        }, 40);
        // колесо: кольцо «Моё» с домом, назад, спавном и местами сервера
        step("wheel", () -> works.repka.ryscore.action.Actions.openWheel(), 15);
        shot("wheel_places");
        step("wheel close", () -> {
            var a = works.repka.ryscore.action.Actions.get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("rysmap", "home"));
            check("действие rysmap:home есть и готово: " + (a == null ? "нет" : a.state().kind()), a != null && a.state().usable());
            var list = works.repka.ryscore.action.Actions.get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("rysmap", "places"));
            check("список мест сервера в колесе: " + (list == null ? 0 : list.list().size()), list != null && list.list().size() == placesAfterFirst);
            mc().setScreen(null);
        }, 10);
        // экспорт вида в PNG (0.2.4) и слои подсветки
        step("export", () -> {
            MapScreen.open();
        }, 20);
        step("export run", () -> screen().exportForTest(false), 100);
        step("export check", () -> {
            Path dir = mc().gameDirectory.toPath().resolve("screenshots").resolve("rysmap");
            long n = 0;
            try (Stream<Path> s = Files.exists(dir) ? Files.list(dir) : Stream.empty()) {
                n = s.filter(f -> f.toString().endsWith(".png")).count();
            } catch (Exception ignored) {
                // нет папки
            }
            check("экспорт вида в PNG записал файл: " + n, n > 0);
            works.repka.rysmap.layer.Layers.setVisible(works.repka.ryscore.map.MapApi.layers().stream()
                .filter(l -> l.id().getPath().equals("heights")).findFirst().orElseThrow(), true);
        }, 60);
        shot("map_heights");
        step("light layer", () -> {
            for (var l : works.repka.ryscore.map.MapApi.layers()) {
                if (l.id().getPath().equals("heights")) works.repka.rysmap.layer.Layers.setVisible(l, false);
                if (l.id().getPath().equals("light") || l.id().getPath().equals("slime")) works.repka.rysmap.layer.Layers.setVisible(l, true);
            }
        }, 60);
        shot("map_light_slime");
        step("finish", () -> {
            mc().setScreen(null);
            RYSMap.LOG.info(MapAutoTest.failures == 0 ? "[autotest] PASS" : "[autotest] FAILED: {}", MapAutoTest.failures);
            mc().stop();
        }, 0);
    }
}
