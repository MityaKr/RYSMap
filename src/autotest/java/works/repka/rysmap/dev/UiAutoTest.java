package works.repka.rysmap.dev;

import static works.repka.rysmap.dev.MapAutoTest.check;
import static works.repka.rysmap.dev.MapAutoTest.mc;
import static works.repka.rysmap.dev.MapAutoTest.session;
import static works.repka.rysmap.dev.MapAutoTest.settled;
import static works.repka.rysmap.dev.MapAutoTest.shot;
import static works.repka.rysmap.dev.MapAutoTest.stats;
import static works.repka.rysmap.dev.MapAutoTest.step;
import static works.repka.rysmap.dev.MapAutoTest.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import works.repka.ryscore.map.MapApi;
import works.repka.ryscore.ui.Icons;
import works.repka.rysmap.RYSMap;
import works.repka.rysmap.client.ClientConfig;
import works.repka.rysmap.hud.Minimap;
import works.repka.rysmap.layer.Layers;
import works.repka.rysmap.point.PointStore;
import works.repka.rysmap.screen.MapScreen;
import works.repka.rysmap.tile.ColorTable;

/**
 * Снимки интерфейса ({@code gradlew runAutotest -Pautotest=ui}): миникарта рядом с макетом {@code 01}, окно карты рядом
 * с {@code 10}, меню правой кнопки ({@code 17}). Слой автотеста даёт метку участника, цель, область, пункт меню и строку
 * под миникартой; точки кладутся в {@code points.json} нового мира. Проверки: F1 и F3 прячут миникарту, слой виден,
 * пункт меню и строка на месте, цена кадра миникарты.
 */
final class UiAutoTest {
    static final ResourceLocation LAYER = ResourceLocation.fromNamespaceAndPath(RYSMap.MODID, "autotest");
    private static int baseX, baseZ;

    private UiAutoTest() {}

    /** Слой «участника и участка» вокруг базы: как у RYSTeams и RYSClaims. */
    static final class TestLayer implements MapApi.Layer {
        @Override public ResourceLocation id() { return LAYER; }
        @Override public Component name() { return Component.literal("Команда (тест)"); }
        @Override public Icons.Shape icon() { return Icons.Shape.USERS; }
        @Override public int order() { return 10; }
        @Override public long revision() { return 1; }

        @Override
        public Collection<MapApi.Marker> markers(ResourceKey<Level> dimension) {
            if (!dimension.equals(Level.OVERWORLD)) return List.of();
            List<MapApi.Marker> out = new ArrayList<>();
            out.add(MapApi.Marker.builder(UUID.nameUUIDFromBytes("lena".getBytes())).pos(dimension, baseX + 22, 70, baseZ + 6)
                .kind(MapApi.Kind.PLAYER).icon(Icons.Shape.USERS).color(0xFFE58A6A).label(Component.literal("Lena_K")).build());
            out.add(MapApi.Marker.builder(UUID.nameUUIDFromBytes("far".getBytes())).pos(dimension, baseX + 400, 70, baseZ - 30)
                .kind(MapApi.Kind.PLAYER).icon(Icons.Shape.USERS).color(0xFF6FA8FF).label(Component.literal("Tor4ok")).build());
            return out;
        }

        @Override
        public MapApi.Areas areas() {
            return new MapApi.Areas() {
                @Override
                public long revision(ResourceKey<Level> dimension, int regionX, int regionZ) {
                    return 1;
                }

                @Override
                public void fill(ResourceKey<Level> dimension, int regionX, int regionZ, MapApi.AreaSink sink) {
                    int bx = Math.floorDiv(baseX, 16), bz = Math.floorDiv(baseZ, 16);
                    for (int cz = bz - 2; cz <= bz + 1; cz++) {
                        for (int cx = bx - 2; cx <= bx + 2; cx++) {
                            if (cx >> 5 == regionX && cz >> 5 == regionZ) sink.chunk(cx, cz, 1, 0xFFE58A6A, MapApi.AreaStyle.FILL, false);
                        }
                    }
                }

                @Override
                public List<Component> tooltip(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
                    int bx = Math.floorDiv(baseX, 16), bz = Math.floorDiv(baseZ, 16);
                    if (chunkX < bx - 2 || chunkX > bx + 2 || chunkZ < bz - 2 || chunkZ > bz + 1) return null;
                    return List.of(Component.literal("Репка · База"));
                }
            };
        }
    }

    private static List<PointStore.Point> readPoints() {
        try {
            return PointStore.load(session().store.root().resolve("points.json")).points();
        } catch (java.io.IOException e) {
            return List.of();
        }
    }

    private static void point(String name, String icon, int color, int dx, int dz) {
        PointStore.Point p = new PointStore.Point();
        p.name = name;
        p.icon = icon;
        p.color = color;
        p.x = baseX + dx;
        p.y = 70;
        p.z = baseZ + dz;
        session().points().add(p);
    }

    static void build() {
        MapAutoTest.prefix = "ui";
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
            MapAutoTest.world = "rysmap_ui_" + Long.toHexString(System.currentTimeMillis());
            LevelSettings settings = new LevelSettings(MapAutoTest.world, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
            mc().createWorldOpenFlows().createFreshLevel(MapAutoTest.world, settings, new WorldOptions(42L, true, false),
                r -> r.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.NORMAL).value().createWorldDimensions(), mc().screen);
        }, 10);
        when("joined", () -> mc().player != null && mc().level != null && mc().screen == null && session() != null, () -> {
            mc().player.getAbilities().flying = true;
            mc().player.onUpdateAbilities();
            mc().player.connection.sendCommand("gamerule doDaylightCycle false");
            mc().player.connection.sendCommand("gamerule doWeatherCycle false");
            mc().player.connection.sendCommand("time set noon");
            mc().player.connection.sendCommand("tp @s ~ ~12 ~ 35 10");
        }, 40);
        when("colors", () -> ColorTable.INSTANCE.ready(), () -> {}, 2);
        when("settled", MapAutoTest::settled, () -> {
            stats("у спавна");
            baseX = mc().player.getBlockX();
            baseZ = mc().player.getBlockZ();
            MapApi api = MapApi.get();
            api.layer(new TestLayer());
            api.status(ResourceLocation.fromNamespaceAndPath(RYSMap.MODID, "autotest_land"), 0,
                () -> MapApi.Status.of(0xFFE58A6A, null, Component.literal("Репка · База")));
            api.menu(MapApi.MenuItem.builder(ResourceLocation.fromNamespaceAndPath(RYSMap.MODID, "autotest_ping"), Component.literal("Пинг команде"))
                .icon(Icons.Shape.SPARK).order(5).run(spot -> {}).build());
            point("Дом", "home", 1, -14, -8);
            point("Портал", "flag", 4, -18, -40);
            point("Причал", "anchor", 5, -46, 30);
            point("Шахта", "pick", 8, 60, -20);
            session().savePoints();
            api.track(MapApi.Target.builder(Level.OVERWORLD, baseX + 60.5, 70, baseZ - 19.5).label(Component.literal("Шахта")).build());
        }, 40);
        step("hud", () -> {
            Minimap.frameMicrosAndReset();
            check("слой автотеста виден", Layers.shown().stream().anyMatch(l -> l.id().equals(LAYER)));
            check("пункт меню слоя зарегистрирован", MapApi.menus().stream().anyMatch(m -> m.id().getPath().equals("autotest_ping")));
            check("строка под миникартой зарегистрирована", MapApi.statuses().stream().anyMatch(s -> s.id().getPath().equals("autotest_land")));
            check("точки на месте: " + session().points().points().size(), session().points().points().size() == 4);
        }, 40);
        shot("hud");
        step("frame", () -> {
            long us = Minimap.frameMicrosAndReset();
            RYSMap.LOG.info("[autotest] замер кадра миникарты: {} мкс на процессоре", us);
            check("кадр миникарты дешевле 1 мс: " + us + " мкс", us < 1000);
            mc().options.hideGui = true;
        }, 2);
        step("f1", () -> {
            check("на F1 миникарта спрятана", Minimap.hidden(mc()));
            mc().options.hideGui = false;
            mc().getDebugOverlay().toggleOverlay();
        }, 2);
        step("f3", () -> {
            check("на F3 миникарта спрятана", Minimap.hidden(mc()));
            mc().getDebugOverlay().toggleOverlay();
            check("без F1 и F3 миникарта видна", !Minimap.hidden(mc()));
            ClientConfig.get().minimapSize = 128;
            ClientConfig.get().minimapZoom = 2;
        }, 20);
        shot("hud_L_x2");
        step("map open", () -> {
            ClientConfig.get().minimapSize = 0;
            ClientConfig.get().minimapZoom = 1;
            MapScreen.open();
        }, 80);
        shot("map");
        // сайдбар 0.2.12: плитка слоёв, «Мир» на полотне; пещеры с этажом, узкое полотно, длинное имя, темы, сжатая, EN
        sidebarShots();
        step("menu", () -> {
            if (mc().screen instanceof MapScreen s) s.openMenuForTest();
            else check("окно карты открыто", false);
        }, 10);
        shot("map_menu");
        // окна точки (этап 3): панель, новая точка, правка, удаление, место гибели, окно клавиши
        step("panel", () -> {
            MapScreen s = (MapScreen) mc().screen;
            s.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0); // меню правой кнопки закрывается Esc
            check("точка Шахта выбрана", s.selectForTest("Шахта"));
        }, 20);
        shot("point_panel");
        step("new point", () -> ((MapScreen) mc().screen).newPointForTest(), 10);
        shot("point_new");
        step("new point enter", () -> mc().screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, 0), 5);
        step("new point check", () -> {
            PointStore.Point p = session().points().points().stream().filter(q -> q.name.equals("Дубрава")).findFirst().orElse(null);
            check("новая точка Дубрава поставлена со звездой и цветом 2", p != null && p.icon.equals("star") && p.color == 2);
            ((MapScreen) mc().screen).editForTest();
            ((MapScreen) mc().screen).editorNameForTest("Дубрава старая");
        }, 10);
        shot("point_edit");
        step("edit enter", () -> mc().screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, 0), 5);
        step("edit check", () -> {
            check("правка сохранила имя", session().points().points().stream().anyMatch(q -> q.name.equals("Дубрава старая")));
            ((MapScreen) mc().screen).deleteForTest();
        }, 10);
        shot("point_delete");
        step("delete", () -> ((MapScreen) mc().screen).confirmDeleteForTest(), 5);
        step("delete check", () -> {
            check("точка удалена", session().points().points().stream().noneMatch(q -> q.name.startsWith("Дубрава")));
            check("points.json на диске без неё", readPoints().stream().noneMatch(q -> q.name.startsWith("Дубрава")) && readPoints().size() == 4);
            works.repka.rysmap.point.PointsLayer.died();
            ((MapScreen) mc().screen).selectDeathForTest();
        }, 20);
        shot("point_death");
        step("quick", () -> {
            mc().setScreen(null);
            works.repka.rysmap.screen.QuickPointScreen.open();
        }, 15);
        shot("quick_point");
        step("quick enter", () -> mc().screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, 0), 5);
        step("quick check", () -> check("окно клавиши поставило точку: " + session().points().points().size(),
            mc().screen == null && session().points().points().size() == 6), 2);
        step("finish", () -> {
            mc().setScreen(null);
            RYSMap.LOG.info(MapAutoTest.failures == 0 ? "[autotest] PASS" : "[autotest] FAILED: {}", MapAutoTest.failures);
            mc().stop();
        }, 0);
    }

    private static MapScreen map() {
        return (MapScreen) mc().screen;
    }

    private static void sidebarShots() {
        step("sb caves", () -> map().cavesForTest(true, 3), 20);
        shot("sb_caves");
        step("sb panel", () -> check("точка Шахта выбрана", map().selectForTest("Шахта")), 15);
        shot("sb_caves_panel");
        step("sb long dim", () -> {
            map().clearSelectionForTest();
            map().cavesForTest(false, 0);
            map().dimensionForTest("rysmap:very_long_dimension_name_for_the_world_bar_test");
        }, 10);
        shot("sb_long_dim");
        step("sb glass", () -> {
            map().dimensionForTest("");
            works.repka.ryscore.theme.ThemeManager.select("ryscore:glass");
        }, 15);
        shot("sb_glass");
        step("sb vanilla", () -> {
            works.repka.ryscore.theme.ThemeManager.select("ryscore:vanilla");
            map().cavesForTest(true, 3);
        }, 15);
        shot("sb_vanilla_caves");
        step("sb compact", () -> {
            works.repka.ryscore.theme.ThemeManager.select("ryscore:slate");
            mc().setScreen(null);
            org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc().getWindow().getWindow(), 960, 600);
        }, 20);
        step("sb compact open", () -> {
            check("маленькое окно включает сжатую раскладку", works.repka.ryscore.ui.UiScale.compactNeeded());
            MapScreen.open();
        }, 30);
        step("sb compact caves", () -> map().cavesForTest(true, 3), 10);
        shot("sb_compact_caves");
        step("sb compact panel", () -> check("точка Шахта выбрана", map().selectForTest("Шахта")), 10);
        shot("sb_compact_panel");
        step("sb compact rail", () -> {
            map().clearSelectionForTest();
            map().cavesForTest(false, 0);
            map().railForTest(true);
        }, 10);
        shot("sb_compact_rail");
        step("sb big", () -> {
            mc().setScreen(null);
            org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc().getWindow().getWindow(), 1280, 800);
        }, 30);
        step("sb en", () -> {
            mc().options.languageCode = "en_us";
            mc().getLanguageManager().setSelected("en_us");
            mc().reloadResourcePacks();
        }, 20);
        when("sb en loaded", () -> mc().getOverlay() == null, MapScreen::open, 20);
        step("sb en caves", () -> map().cavesForTest(true, 3), 10);
        shot("sb_en_caves");
        step("sb ru", () -> {
            mc().setScreen(null);
            mc().options.languageCode = "ru_ru";
            mc().getLanguageManager().setSelected("ru_ru");
            mc().reloadResourcePacks();
        }, 20);
        when("sb ru loaded", () -> mc().getOverlay() == null, MapScreen::open, 40);
    }
}
