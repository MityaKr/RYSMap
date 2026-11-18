package works.repka.rysmap.dev;

import static works.repka.rysmap.dev.MapAutoTest.check;
import static works.repka.rysmap.dev.MapAutoTest.mc;
import static works.repka.rysmap.dev.MapAutoTest.session;
import static works.repka.rysmap.dev.MapAutoTest.shot;
import static works.repka.rysmap.dev.MapAutoTest.step;
import static works.repka.rysmap.dev.MapAutoTest.when;

import java.util.Collection;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
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
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.ui.Icons;
import works.repka.ryscore.ui.Kit;
import works.repka.rysmap.RYSMap;
import works.repka.rysmap.client.ClientConfig;
import works.repka.rysmap.client.ServerRules;
import works.repka.rysmap.hud.Entities;
import works.repka.rysmap.screen.MapScreen;
import works.repka.rysmap.server.SafeSpot;
import works.repka.rysmap.tile.ColorTable;

/**
 * Правки 18.11 вечером ({@code gradlew runAutotest -Pheap=3G --offline -Pautotest=fixes}): земля на миникарте без сетки
 * при дробном месте игрока (два снимка со сдвигом в полблока), буква севера над рамкой, правила сервера у оператора,
 * мобы и их счёт, круг и поворот по взгляду, режим и вкладка мода в окне карты (ядро 1.12), раздел «Сервер» в
 * настройках, отказ телепорта строкой сообщения и телепорт в неразведанное место на твёрдый блок.
 */
final class FixesAutoTest {
    static final ResourceLocation LAYER = ResourceLocation.fromNamespaceAndPath(RYSMap.MODID, "autotest_land");
    private static int baseX, baseZ;
    private static double tpX, tpZ;

    private FixesAutoTest() {}

    /** Земля 5×4 чанка вокруг базы одной группой: как у RYSClaims. */
    static final class Land implements MapApi.Layer, MapApi.Areas {
        @Override public ResourceLocation id() { return LAYER; }
        @Override public Component name() { return Component.literal("Участки (тест)"); }
        @Override public Icons.Shape icon() { return Icons.Shape.SHIELD; }
        @Override public int order() { return 20; }
        @Override public long revision() { return 1; }
        @Override public Collection<MapApi.Marker> markers(ResourceKey<Level> dimension) { return List.of(); }
        @Override public MapApi.Areas areas() { return this; }
        @Override public long revision(ResourceKey<Level> dimension, int regionX, int regionZ) { return 1; }

        @Override
        public void fill(ResourceKey<Level> dimension, int regionX, int regionZ, MapApi.AreaSink sink) {
            int bx = Math.floorDiv(baseX, 16), bz = Math.floorDiv(baseZ, 16);
            for (int cz = bz - 2; cz <= bz + 1; cz++)
                for (int cx = bx - 2; cx <= bx + 2; cx++)
                    if (cx >> 5 == regionX && cz >> 5 == regionZ) sink.chunk(cx, cz, 7, 0xFFE5534B, MapApi.AreaStyle.FILL, false);
        }
    }

    /** Режим мода: сетка чанков и панель справа. */
    static final class TestMode implements MapApi.Mode {
        @Override public ResourceLocation id() { return ResourceLocation.fromNamespaceAndPath(RYSMap.MODID, "autotest_mode"); }
        @Override public Component name() { return Component.literal("Приват"); }
        @Override public Icons.Shape icon() { return Icons.Shape.SHIELD; }

        @Override
        public MapApi.ModeView open(MapApi.Surface surface) {
            return new MapApi.ModeView() {
                @Override
                public void render(GuiGraphics g, Theme t, MapApi.Surface s, double mx, double my) {
                    double ppb = s.pixelsPerBlock();
                    int x0 = (int) Math.floor(s.blockX(s.x()) / 16), x1 = (int) Math.ceil(s.blockX(s.x() + s.width()) / 16);
                    for (int cx = x0; cx <= x1; cx++) {
                        float x = (float) s.screenX(cx * 16.0);
                        Draw.line(g, x, s.y(), x, s.y() + s.height(), 0.5f, 0x80FFFFFF);
                    }
                    int z0 = (int) Math.floor(s.blockZ(s.y()) / 16), z1 = (int) Math.ceil(s.blockZ(s.y() + s.height()) / 16);
                    for (int cz = z0; cz <= z1; cz++) {
                        float y = (float) s.screenY(cz * 16.0);
                        Draw.line(g, s.x(), y, s.x() + s.width(), y, 0.5f, 0x80FFFFFF);
                    }
                    check0("ppb > 0", ppb > 0);
                }

                @Override public float minChunkSize() { return 12; }
                @Override public Component message() { return Component.literal("Режим мода: строка сообщения"); }

                @Override
                public MapApi.PanelView panel() {
                    return (g, t, x, y, w, h, hits, mx, my) -> Draw.label(g, "Панель режима", x + 9, y + 14, 14, works.repka.ryscore.ui.Type.Weight.SEMIBOLD, t.c("text.primary"));
                }
            };
        }
    }

    static final class TestTab implements MapApi.Tab {
        @Override public ResourceLocation id() { return ResourceLocation.fromNamespaceAndPath(RYSMap.MODID, "autotest_tab"); }
        @Override public Component name() { return Component.literal("Земля"); }

        @Override
        public void render(GuiGraphics g, Theme t, int x, int y, int w, int h, boolean compact, Kit.Hits hits, double mx, double my) {
            Draw.label(g, "Вкладка мода: вся область под шапкой", x + 20, y + 24, 16, works.repka.ryscore.ui.Type.Weight.SEMIBOLD, t.c("text.primary"));
        }
    }

    private static boolean modeRendered;

    private static void check0(String what, boolean ok) {
        if (!modeRendered) {
            modeRendered = true;
            check("режим мода рисуется: " + what, ok);
        }
    }

    private static MapScreen screen() {
        return (MapScreen) mc().screen;
    }

    static void build() {
        MapAutoTest.prefix = "fixes";
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
            MapAutoTest.world = "rysmap_fixes_" + Long.toHexString(System.currentTimeMillis());
            LevelSettings settings = new LevelSettings(MapAutoTest.world, GameType.CREATIVE, false, Difficulty.EASY, true, new GameRules(), WorldDataConfiguration.DEFAULT);
            mc().createWorldOpenFlows().createFreshLevel(MapAutoTest.world, settings, new WorldOptions(42L, true, false),
                r -> r.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.NORMAL).value().createWorldDimensions(), mc().screen);
        }, 10);
        when("joined", () -> mc().player != null && mc().level != null && mc().screen == null && session() != null, () -> {
            mc().player.getAbilities().flying = true;
            mc().player.onUpdateAbilities();
            mc().player.connection.sendCommand("gamerule doDaylightCycle false");
            mc().player.connection.sendCommand("gamerule doWeatherCycle false");
            mc().player.connection.sendCommand("gamerule doMobSpawning false");
            mc().player.connection.sendCommand("time set noon");
            mc().player.connection.sendCommand("tp @s ~ ~12 ~ 0 10");
        }, 40);
        when("colors", () -> ColorTable.INSTANCE.ready(), () -> {}, 2);
        when("settled", MapAutoTest::settled, () -> {
            baseX = mc().player.getBlockX();
            baseZ = mc().player.getBlockZ();
            MapApi api = MapApi.get();
            api.layer(new Land());
            api.mode(new TestMode());
            api.tab(new TestTab());
            ClientConfig c = ClientConfig.get();
            c.minimapSize = 128;
            c.minimapZoom = 2;
            // дробное место: тайлы и земля должны ехать вместе, без сетки по чанкам
            mc().player.connection.sendCommand(String.format(java.util.Locale.ROOT, "tp @s %.2f ~ %.2f 0 10", baseX + 0.37, baseZ + 0.21));
        }, 30);
        shot("grid_a");
        step("grid b", () -> mc().player.connection.sendCommand(String.format(java.util.Locale.ROOT, "tp @s %.2f ~ %.2f 0 10", baseX + 0.87, baseZ + 0.71)), 20);
        shot("grid_b");
        step("rules", () -> {
            var r = ServerRules.rules();
            check("правила сервера пришли", r != null);
            check("одиночная игра с читами: оператор, телепорт можно", r != null && r.op() && r.canTeleport());
            mc().player.connection.sendCommand("summon minecraft:cow ~6 ~-12 ~4 {NoAI:1b,PersistenceRequired:1b}");
            mc().player.connection.sendCommand("summon minecraft:pig ~-8 ~-12 ~2 {NoAI:1b,PersistenceRequired:1b}");
            mc().player.connection.sendCommand("summon minecraft:creeper ~4 ~-12 ~-9 {NoAI:1b,PersistenceRequired:1b}");
            mc().player.connection.sendCommand("summon minecraft:spider ~-5 ~-12 ~-6 {NoAI:1b,PersistenceRequired:1b}");
        }, 40);
        step("entities", () -> {
            var p = mc().player;
            List<Entities.Dot> dots = Entities.collect(p.level().dimension(), p.getX() - 64, p.getZ() - 64, p.getX() + 64, p.getZ() + 64, 1f);
            long hostile = dots.stream().filter(d -> d.kind() == Entities.Kind.HOSTILE).count();
            long passive = dots.stream().filter(d -> d.kind() == Entities.Kind.PASSIVE).count();
            check("мобы на карте: враждебных " + hostile + ", мирных " + passive, hostile >= 2 && passive >= 2);
        }, 4);
        shot("entities");
        step("night", () -> mc().player.connection.sendCommand("time set midnight"), 30);
        shot("night");
        step("day", () -> {
            check("ночью множитель карты меньше 1: " + works.repka.rysmap.client.MapSession.nightBrightness(mc().level, 1f),
                works.repka.rysmap.client.MapSession.nightBrightness(mc().level, 1f) < 0.6f);
            mc().player.connection.sendCommand("time set noon");
        }, 30);
        step("circle", () -> {
            ClientConfig.get().minimapCircle = true;
            mc().player.connection.sendCommand("tp @s ~ ~ ~ 0 10");
        }, 15);
        shot("circle");
        step("circle rotate", () -> {
            ClientConfig.get().minimapRotate = true;
            mc().player.connection.sendCommand("tp @s ~ ~ ~ 35 10");
        }, 15);
        shot("circle_rotate");
        step("square rotate", () -> ClientConfig.get().minimapCircle = false, 10);
        shot("square_rotate");
        // этажи пещер (М5): пещера на y −30…−27 под игроком
        step("cave dig", () -> {
            ClientConfig.get().minimapRotate = false;
            ClientConfig.get().minimapCircle = false;
            mc().player.connection.sendCommand("fill ~-8 -30 ~-8 ~8 -27 ~8 air");
            mc().player.connection.sendCommand("fill ~-3 -30 ~-12 ~3 -28 ~-9 air");
        }, 20);
        step("cave enter", () -> mc().player.connection.sendCommand("tp @s ~ -30 ~ 0 10"), 20);
        when("cave floor", () -> session().caveFloor() != null, () ->
            check("под землёй миникарта на этаже игрока: " + session().caveFloor(), session().caveFloor() == works.repka.rysmap.scan.Floors.of(-30)), 120);
        shot("cave_minimap");
        step("cave map", () -> MapScreen.open(), 40);
        shot("cave_map");
        step("cave leave", () -> {
            mc().setScreen(null);
            mc().player.connection.sendCommand(String.format(java.util.Locale.ROOT, "tp @s %d 120 %d 0 10", baseX, baseZ));
        }, 40);
        step("map", () -> {
            ClientConfig.get().minimapRotate = false;
            ClientConfig.get().minimapSize = 0;
            ClientConfig.get().minimapZoom = 1;
            MapScreen.open();
        }, 60);
        step("map zoom", () -> screen().setZoom(4), 20);
        shot("map_entities");
        step("map far", () -> screen().setZoom(0.15), 80);
        shot("map_far");
        step("map near", () -> screen().setZoom(4), 10);
        step("mode", () -> {
            MapApi.get().openMode(ResourceLocation.fromNamespaceAndPath(RYSMap.MODID, "autotest_mode"), null);
        }, 20);
        shot("map_mode");
        step("tab", () -> {
            check("режим мода нарисован", modeRendered);
            MapApi.get().openTab(ResourceLocation.fromNamespaceAndPath(RYSMap.MODID, "autotest_tab"));
        }, 20);
        shot("map_tab");
        step("search", () -> {
            screen().setTab(null);
            screen().setMode(null);
            String first = screen().searchForTest("120 −40");
            check("поиск координат: " + first, first.contains("120"));
        }, 10);
        shot("map_search");
        step("marker panel", () -> {
            screen().keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0);
            var p = mc().player;
            MapApi.Marker goal = MapApi.Marker.builder(java.util.UUID.nameUUIDFromBytes("goal".getBytes())).pos(p.level().dimension(), p.getX() + 20, 64, p.getZ() - 10)
                .kind(MapApi.Kind.GOAL).icon(Icons.Shape.TARGET).color(0xFFE4B23C).label(Component.literal("Найти деревню"))
                .panel(() -> MapApi.Panel.builder(Component.literal("Найти деревню")).subtitle(Component.literal("Цель квеста"))
                    .text(Component.literal("Деревня где-то в этом круге. Поговори с жителем."))
                    .row(Component.literal("Глава"), Component.literal("Начало")).row(Component.literal("Награда"), Component.literal("8 изумрудов"))
                    .note(Component.literal("Пример панели чужой метки из ядра 1.12"))
                    .action(MapApi.Action.builder(Component.literal("Открыть квест"), () -> {}).primary(true).build())
                    .action(MapApi.Action.builder(Component.literal("Сохранить точкой"), () -> {}).build()).build()).build();
            screen().markerPanelForTest(goal);
        }, 10);
        shot("map_marker_panel");
        // соседи (Т3, Т5): цель квеста с районом и предметом, тревога базы, подпись тревоги в мире
        step("neighbors", () -> {
            screen().keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0);
            screen().setZoom(2);
            var p = mc().player;
            var dim = p.level().dimension();
            double gx = p.getX() + 30, gz = p.getZ() - 12, ax = p.getX() - 18, az = p.getZ() + 10;
            MapApi.get().layer(new MapApi.Layer() {
                @Override public ResourceLocation id() { return ResourceLocation.fromNamespaceAndPath(RYSMap.MODID, "autotest_goals"); }
                @Override public Component name() { return Component.literal("Цели и тревоги (тест)"); }
                @Override public Icons.Shape icon() { return Icons.Shape.TARGET; }
                @Override public int order() { return 30; }
                @Override public long revision() { return 1; }
                @Override
                public Collection<MapApi.Marker> markers(ResourceKey<Level> d) {
                    if (!d.equals(dim)) return List.of();
                    return List.of(
                        MapApi.Marker.builder(java.util.UUID.nameUUIDFromBytes("goal2".getBytes())).pos(d, gx, 64, gz).kind(MapApi.Kind.GOAL)
                            .item(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD)).color(0xFFE4B23C)
                            .label(Component.literal("Деревня")).radius(24).build(),
                        MapApi.Marker.builder(java.util.UUID.nameUUIDFromBytes("alert".getBytes())).pos(d, ax, 64, az).kind(MapApi.Kind.ALERT)
                            .icon(Icons.Shape.WARN).color(0xFFE5534B).label(Component.literal("Дробилка встала")).build());
                }
            });
            works.repka.ryscore.world.Labels.source(new works.repka.ryscore.world.Labels.Source() {
                @Override public ResourceLocation id() { return ResourceLocation.fromNamespaceAndPath(RYSMap.MODID, "autotest_alert"); }
                @Override
                public Collection<works.repka.ryscore.world.Labels.Label> labels(ResourceKey<Level> d) {
                    return List.of(works.repka.ryscore.world.Labels.Label.builder(java.util.UUID.nameUUIDFromBytes("alert".getBytes()))
                        .pos(d, ax, 66, az).kind(works.repka.ryscore.world.Labels.Kind.ALERT).icon(Icons.Shape.WARN).color(0xFFE5534B)
                        .text(Component.literal("Дробилка встала")).build());
                }
            });
        }, 20);
        shot("map_neighbors");
        step("neighbors hud", () -> mc().setScreen(null), 20);
        shot("hud_neighbors");
        step("neighbors back", () -> MapScreen.open(), 20);
        step("highlight", () -> {
            screen().keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0);
            screen().setZoom(1);
            var biome = mc().level.getBiome(mc().player.blockPosition()).unwrapKey().orElseThrow();
            MapApi.get().highlight(MapApi.Highlight.builder(ResourceLocation.fromNamespaceAndPath(RYSMap.MODID, "autotest_hl"),
                Component.literal(biome.location().getPath())).biome(biome).color(0xFFE4B23C).build());
        }, 60);
        shot("map_highlight");
        step("list", () -> {
            MapApi.get().highlight(null);
            for (int i = 0; i < 14; i++) {
                works.repka.rysmap.point.PointStore.Point p = new works.repka.rysmap.point.PointStore.Point();
                p.name = "Точка " + (char) ('А' + i);
                p.x = baseX + i * 7 - 40;
                p.y = 70;
                p.z = baseZ - i * 5;
                p.dimension = "minecraft:overworld";
                p.created = 1_000_000L + i;
                session().points().add(p);
            }
            session().savePoints();
            screen().listForTest(true, 1);
        }, 10);
        shot("map_list");
        step("list back", () -> screen().listForTest(false, 0), 2);
        step("settings", () -> {
            screen().keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0);
            screen().settingsForTest();
        }, 20);
        shot("settings_server");
        // отказ: оператор выключает телепорт, просьба даёт строку сообщения без пакета
        step("rules off", () -> {
            var r = ServerRules.rules();
            ServerRules.setRules(0, r.pause(), r.minimap(), r.caves(), r.playersMode(), r.mobs());
            mc().setScreen(null);
        }, 20);
        step("deny", () -> {
            check("правило «выключен» дошло", ServerRules.rules() != null && !ServerRules.rules().canTeleport());
            MapScreen.open();
            ServerRules.teleport(Level.OVERWORLD, baseX + 300, SafeSpot.NO_HINT, baseZ + 300);
            var m = ServerRules.message();
            check("отказ строкой сообщения: " + (m == null ? "нет" : m.title().getString() + " " + m.text().getString()), m != null);
        }, 20);
        shot("map_message");
        step("rules on", () -> {
            var r = ServerRules.rules();
            ServerRules.setRules(1, r.pause(), r.minimap(), r.caves(), r.playersMode(), r.mobs());
            ServerRules.closeMessage();
        }, 20);
        // темы (М9): окно карты в glass и vanilla, затем сжатая раскладка в маленьком окне
        step("theme glass", () -> {
            works.repka.ryscore.theme.ThemeManager.select("ryscore:glass");
            MapScreen.open();
        }, 30);
        shot("theme_glass_map");
        step("theme vanilla", () -> works.repka.ryscore.theme.ThemeManager.select("ryscore:vanilla"), 20);
        shot("theme_vanilla_map");
        step("compact", () -> {
            works.repka.ryscore.theme.ThemeManager.select("ryscore:slate");
            mc().setScreen(null);
            org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc().getWindow().getWindow(), 960, 600);
        }, 20);
        step("compact open", () -> {
            check("маленькое окно включает сжатую раскладку", works.repka.ryscore.ui.UiScale.compactNeeded());
            MapScreen.open();
        }, 30);
        shot("compact_map");
        step("compact rail", () -> screen().railForTest(true), 10);
        shot("compact_rail");
        step("compact settings", () -> {
            screen().railForTest(false);
            screen().settingsForTest();
        }, 10);
        shot("compact_settings");
        step("compact hud", () -> mc().setScreen(null), 10);
        shot("compact_hud");
        step("big again", () -> org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc().getWindow().getWindow(), 1280, 800), 30);
        step("teleport", () -> {
            tpX = baseX + 300;
            tpZ = baseZ + 300;
            mc().player.getAbilities().flying = false;
            mc().player.onUpdateAbilities();
            ServerRules.teleport(Level.OVERWORLD, (int) tpX, SafeSpot.NO_HINT, (int) tpZ);
        }, 60);
        step("teleport check", () -> {
            var p = mc().player;
            double d = Math.hypot(p.getX() - tpX - 0.5, p.getZ() - tpZ - 0.5);
            RYSMap.LOG.info("[autotest] телепорт: игрок {} {} {}, до столбца {} блоков, на земле {}", p.getX(), p.getY(), p.getZ(), d, p.onGround());
            check("телепорт в неразведанное место: рядом со столбцом (" + Math.round(d) + " бл.)", d <= SafeSpot.RADIUS + 1);
            check("стоит на земле, не в воде и не в лаве", p.onGround() && !p.isInWater() && !p.isInLava());
            check("сообщения об отказе нет", ServerRules.message() == null);
        }, 20);
        shot("teleported");
        step("finish", () -> {
            mc().setScreen(null);
            RYSMap.LOG.info(MapAutoTest.failures == 0 ? "[autotest] PASS" : "[autotest] FAILED: {}", MapAutoTest.failures);
            mc().stop();
        }, 0);
    }
}
