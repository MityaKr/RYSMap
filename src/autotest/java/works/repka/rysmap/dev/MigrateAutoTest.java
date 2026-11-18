package works.repka.rysmap.dev;

import static works.repka.rysmap.dev.MapAutoTest.check;
import static works.repka.rysmap.dev.MapAutoTest.mc;
import static works.repka.rysmap.dev.MapAutoTest.session;
import static works.repka.rysmap.dev.MapAutoTest.shot;
import static works.repka.rysmap.dev.MapAutoTest.step;
import static works.repka.rysmap.dev.MapAutoTest.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelResource;
import works.repka.ryscore.map.MapApi;
import works.repka.ryscore.migrate.Migrations;
import works.repka.rysmap.RYSMap;
import works.repka.rysmap.client.ClientConfig;
import works.repka.rysmap.migrate.FtbPoints;
import works.repka.rysmap.migrate.FtbRegionReader;
import works.repka.rysmap.point.PointStore;
import works.repka.rysmap.screen.MapScreen;

/**
 * Серверная часть и переезд ({@code gradlew runAutotest -Pautotest=migrate}): встроенный сервер шлёт {@code MapHello},
 * папка карты встаёт на UUID мира; в мир кладётся {@code ftbteams/ftbteams.snbt} с id настоящей карты FTB из fixtures,
 * её копия лежит в {@code run/local/ftbchunks}; после перезахода шаг {@code map} переносит точки и регионы, третий вход
 * его не повторяет. Снимок окна карты над перенесённой картой Верхнего мира.
 */
final class MigrateAutoTest {
    private static Path ftbCopy;
    private static String ftbId;
    private static int expectedPoints, expectedRegions;
    private static double viewX, viewZ;
    private static Object lastResult;

    private MigrateAutoTest() {}

    private static void prepare() throws IOException {
        Path fixture = Path.of(System.getProperty("rysmap.ftbFixture", "")).resolve("data");
        Path real = null;
        try (Stream<Path> s = Files.list(fixture)) {
            for (Path d : s.filter(Files::isDirectory).toList()) if (FtbRegionReader.open(d).dimensions.size() > 1) real = d;
        }
        if (real == null) throw new IOException("в fixtures нет настоящей карты FTB");
        ftbId = real.getFileName().toString();
        Path game = mc().gameDirectory.toPath();
        ftbCopy = game.resolve("local/ftbchunks/data").resolve(ftbId);
        if (!Files.isDirectory(ftbCopy)) {
            Path src = real;
            try (Stream<Path> walk = Files.walk(src)) {
                for (Path p : walk.toList()) {
                    Path to = ftbCopy.resolve(src.relativize(p).toString());
                    if (Files.isDirectory(p)) Files.createDirectories(to);
                    else Files.copy(p, to);
                }
            }
        }
        FtbRegionReader.World w = FtbRegionReader.open(ftbCopy);
        List<int[]> ow = new ArrayList<>();
        for (var e : w.dimensions.entrySet()) {
            List<Path> zips = FtbRegionReader.regions(e.getValue());
            if (!ClientConfig.DEFAULT_NO_MAP.contains(e.getKey())) expectedRegions += zips.size();
            if (e.getKey().equals("minecraft:overworld")) for (Path z : zips) ow.add(FtbRegionReader.coords(z.getFileName().toString()));
        }
        expectedPoints = FtbPoints.read(w).size();
        ow.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
        int[] mid = ow.get(ow.size() / 2);
        viewX = mid[0] * 512 + 256;
        viewZ = mid[1] * 512 + 256;
        Path flag = game.resolve(Migrations.FLAG_FILE);
        Files.createDirectories(flag.getParent());
        Files.writeString(flag, "{\"v\": 1, \"from\": \"ftb\", \"steps\": [\"map\"]}", StandardCharsets.UTF_8);
        RYSMap.LOG.info("[autotest] копия карты FTB {}: регионов к переносу {}, точек {}", ftbCopy, expectedRegions, expectedPoints);
    }

    private static void leaveAndReopen(String tag) {
        step("leave " + tag, () -> {
            mc().level.disconnect();
            mc().disconnect(new GenericMessageScreen(Component.literal("saving")));
            mc().setScreen(new TitleScreen());
        }, 60);
        when("left " + tag, () -> mc().level == null && session() == null, () -> lastResult = Migrations.lastResult(), 20);
        step("reopen " + tag, () -> mc().createWorldOpenFlows().openWorld(MapAutoTest.world, () -> mc().setScreen(new TitleScreen())), 10);
        when("rejoined " + tag, () -> mc().player != null && mc().level != null && mc().screen == null && session() != null, () -> {}, 20);
    }

    static void build() {
        MapAutoTest.prefix = "migrate";
        step("title", () -> {
            mc().options.pauseOnLostFocus = false;
            mc().options.onboardAccessibility = false;
            mc().options.guiScale().set(2);
            mc().options.tutorialStep = net.minecraft.client.tutorial.TutorialSteps.NONE;
            mc().getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
            mc().options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
            mc().resizeDisplay();
            try {
                prepare();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            if (!(mc().screen instanceof TitleScreen)) mc().setScreen(new TitleScreen());
        }, 30);
        step("world", () -> {
            MapAutoTest.world = "rysmap_migrate_" + Long.toHexString(System.currentTimeMillis());
            LevelSettings settings = new LevelSettings(MapAutoTest.world, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
            mc().createWorldOpenFlows().createFreshLevel(MapAutoTest.world, settings, new WorldOptions(42L, true, false),
                r -> r.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.NORMAL).value().createWorldDimensions(), mc().screen);
        }, 10);
        when("joined", () -> mc().player != null && mc().level != null && mc().screen == null && session() != null, () -> {}, 60);
        step("hello", () -> {
            String id = session().store.key().id();
            check("MapHello дал ключ по UUID мира: " + id, id.startsWith("w-"));
            Path maps = mc().gameDirectory.toPath().resolve("rysmap");
            check("папка по имени сохранения не осталась (переехала или не создавалась)", !Files.exists(maps.resolve(works.repka.rysmap.world.WorldKey.singleplayer(MapAutoTest.world).id())));
            check("имя мира в ключе: " + session().store.key().name(), !session().store.key().name().isBlank());
            try {
                Path ftbteams = mc().getSingleplayerServer().getWorldPath(LevelResource.ROOT).resolve("ftbteams");
                Files.createDirectories(ftbteams);
                Files.writeString(ftbteams.resolve("ftbteams.snbt"), "{\n\tid: \"" + ftbId + "\"\n}\n", StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            check("без id FTB шаг не шёл", session().points().points().isEmpty());
        }, 2);
        String[] keyBefore = {""};
        step("key", () -> keyBefore[0] = session().store.key().id(), 2);
        leaveAndReopen("1");
        long[] started = {0};
        step("started", () -> {
            started[0] = System.currentTimeMillis();
            check("тот же ключ после перезахода", session().store.key().id().equals(keyBefore[0]));
        }, 2);
        when("migrated", () -> Migrations.lastResult() != null && Migrations.lastResult() != lastResult && Migrations.lastResult().outcome("map") != null, () -> {
            Migrations.Outcome o = Migrations.lastResult().outcome("map");
            RYSMap.LOG.info("[autotest] замер переезда: {} за {} с", o, (System.currentTimeMillis() - started[0]) / 1000);
            check("шаг map прошёл: " + o, o.status() == Migrations.Status.DONE);
            List<PointStore.Point> pts = session().points().points();
            check("точки FTB перенесены: " + pts.size() + " из " + expectedPoints, pts.size() == expectedPoints);
            check("места гибели FTB стали обычными точками с пометкой", pts.stream().noneMatch(PointStore.Point::death)
                && pts.stream().filter(p -> FtbPoints.NOTE_DEATH.equals(p.note)).count() > 0);
            int files = 0;
            try (Stream<Path> s = Files.walk(session().store.root())) {
                files = (int) s.filter(p -> p.toString().endsWith(".rmap")).count();
            } catch (IOException e) {
                check("регионы читаются: " + e, false);
            }
            check("регионы на диске: " + files + " из " + expectedRegions + " (плюс снятые сканером)", files >= expectedRegions);
            check("отметка шага в папке карты", Files.isRegularFile(session().store.root().resolve("migrate.json")));
            Runtime rt = Runtime.getRuntime();
            RYSMap.LOG.info("[autotest] замер кучи после переезда: {} из {} МБ; {}", (rt.totalMemory() - rt.freeMemory()) >> 20, rt.maxMemory() >> 20, session().debugLine());
        }, 20);
        step("map open", () -> {
            MapScreen.open(MapApi.Target.builder(Level.OVERWORLD, viewX, 70, viewZ).build());
            if (mc().screen instanceof MapScreen s) s.setZoom(0.5);
        }, 100);
        shot("ftb_overworld");
        // страница настроек (этап 4): строка о переезде, образец миникарты
        step("settings", () -> {
            if (mc().screen instanceof MapScreen s) s.settingsForTest();
        }, 20);
        shot("settings");
        step("settings changed", () -> {
            ClientConfig c = ClientConfig.get();
            c.minimapCorner = "TOP_LEFT";
            c.minimapSize = 128;
            c.minimapZoom = 2;
            c.deathsKept = 1;
            c.save();
        }, 20);
        shot("settings_changed");
        step("hud corner", () -> mc().setScreen(null), 30);
        shot("hud_top_left");
        step("settings restore", () -> {
            ClientConfig c = ClientConfig.get();
            check("угол миникарты из настроек: " + works.repka.rysmap.hud.Minimap.corner(), works.repka.rysmap.hud.Minimap.corner() == works.repka.ryscore.ui.Anchors.Corner.TOP_LEFT);
            c.minimapCorner = null;
            c.minimapSize = 0;
            c.minimapZoom = 1;
            c.deathsKept = 3;
            c.save();
        }, 2);
        step("close", () -> mc().setScreen(null), 5);
        leaveAndReopen("2");
        step("again", () -> {}, 100);
        step("finish", () -> {
            Migrations.Result r = Migrations.lastResult();
            Migrations.Outcome o = r == null ? null : r.outcome("map");
            check("третий вход шаг не повторил: " + o, o != null && o.status() == Migrations.Status.ALREADY_DONE);
            check("точек столько же: " + session().points().points().size(), session().points().points().size() == expectedPoints);
            try {
                Files.writeString(mc().gameDirectory.toPath().resolve(Migrations.FLAG_FILE), "{\"v\": 1, \"from\": \"ftb\", \"steps\": []}", StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // флаг остался: в новых мирах без id FTB шаг всё равно не идёт
            }
            mc().setScreen(null);
            RYSMap.LOG.info(MapAutoTest.failures == 0 ? "[autotest] PASS" : "[autotest] FAILED: {}", MapAutoTest.failures);
            mc().stop();
        }, 0);
    }
}
