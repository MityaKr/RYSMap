package works.repka.rysmap.dev;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import works.repka.ryscore.map.MapApi;
import works.repka.rysmap.RYSMap;
import works.repka.rysmap.client.ClientEntry;
import works.repka.rysmap.client.MapSession;
import works.repka.rysmap.tile.ColorTable;

/**
 * Проверка М1 в игре ({@code gradlew runAutotest}): новый мир, сканер у спавна и после перелёта, регионы на диске,
 * повторный вход читает их без пересканирования, {@code MapApi.drawTiles} рисует фон. Замеры главного потока на чанк
 * и памяти пишутся в лог строками {@code [autotest]}. Включается свойством {@code rysmap.autotest}, в jar не входит.
 */
@EventBusSubscriber(modid = RYSMap.MODID, value = Dist.CLIENT)
public final class MapAutoTest {
    private record Step(String name, BooleanSupplier ready, Runnable action, int pause) {}

    private static final boolean ON = System.getProperty("rysmap.autotest") != null;

    static {
        // Сценарий server идёт в копии сборки: в запуске разработки игра считает себя «в IDE» и включает GameTest,
        // а моды с тестами разработки (Binnie) без своих классов роняют загрузку. Класс грузится при сборке модов,
        // раньше регистрации GameTest в конструкторе Minecraft.
        if ("server".equals(System.getProperty("rysmap.autotest"))) net.minecraft.SharedConstants.IS_RUNNING_IN_IDE = false;
    }
    private static final List<Step> STEPS = new ArrayList<>();
    private static boolean built;
    static int index, wait, failures, shots = 1, settle, stepTicks;
    /** Сколько тиков шаг ждёт готовности, потом считается проваленным. */
    static int limit = 20 * 240;
    static String world;
    private static int spawnX, spawnZ;
    private static long firstSnapshots, firstScanned;
    private static boolean tilesDrawn;
    static String prefix = "map";

    private MapAutoTest() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post e) {
        if (!ON) return;
        if (!built) {
            built = true;
            build();
        }
        if (wait > 0) {
            wait--;
            return;
        }
        if (index >= STEPS.size()) return;
        Step s = STEPS.get(index);
        stepTicks++;
        if (!s.ready.getAsBoolean()) {
            if (stepTicks > limit) {
                failures++;
                RYSMap.LOG.error("[autotest] шаг {} не дождался за {} с", s.name, limit / 20);
                stepTicks = 0;
                index++;
            }
            return;
        }
        stepTicks = 0;
        try {
            s.action.run();
        } catch (Throwable t) {
            failures++;
            RYSMap.LOG.error("[autotest] шаг {} упал", s.name, t);
        }
        wait = s.pause;
        index++;
    }

    static Minecraft mc() {
        return Minecraft.getInstance();
    }

    static MapSession session() {
        return ClientEntry.session();
    }

    static void step(String name, Runnable action, int pause) {
        STEPS.add(new Step(name, () -> true, action, pause));
    }

    static void when(String name, BooleanSupplier ready, Runnable action, int pause) {
        STEPS.add(new Step(name, ready, action, pause));
    }

    static void check(String what, boolean ok) {
        if (!ok) failures++;
        RYSMap.LOG.info("[autotest] {} {}", ok ? "OK  " : "FAIL", what);
    }

    static void shot(String name) {
        step("shot " + name, () -> Screenshot.grab(mc().gameDirectory, String.format("%s_%02d_%s.png", prefix, shots++, name), mc().getMainRenderTarget(), m -> {}), 4);
    }

    /** Сканер успокоился: очередь пуста 40 тиков подряд. */
    static boolean settled() {
        MapSession s = session();
        if (s == null || !ColorTable.INSTANCE.ready()) return false;
        settle = s.queued() == 0 ? settle + 1 : 0;
        return settle > 40;
    }

    static void stats(String tag) {
        MapSession s = session();
        Runtime rt = Runtime.getRuntime();
        RYSMap.LOG.info("[autotest] замер {}: снимков {}, главный поток в среднем {} мкс на чанк, худший {} мкс; рабочий поток {} мкс на чанк; "
                + "сырых регионов {} ({} МБ), текстур {} ({} МБ); куча {} из {} МБ; {}",
            tag, s.snapshotCount(), s.snapshotMicrosAverage(), s.snapshotMicrosMax(), s.scanMicrosAverage(),
            s.rawRegions(), s.rawBytes() >> 20, s.tiles.textures(), s.tiles.bytes() >> 20,
            (rt.totalMemory() - rt.freeMemory()) >> 20, rt.maxMemory() >> 20, s.debugLine());
    }

    /** Окно на весь экран с фоном карты через ядровый MapApi, как у окна земли RYSClaims. */
    static final class TilesScreen extends Screen {
        TilesScreen() {
            super(Component.literal("tiles"));
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            g.fill(0, 0, width, height, 0xFF15171C);
            var p = Minecraft.getInstance().player;
            if (p == null) return;
            boolean drew = MapApi.get().drawTiles(g, p.level().dimension(), p.getX(), p.getZ(), 1.0, 0, 0, width, height);
            tilesDrawn |= drew;
            g.fill(width / 2 - 1, height / 2 - 1, width / 2 + 2, height / 2 + 2, 0xFFFF3040);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }

    private static void build() {
        if ("ui".equals(System.getProperty("rysmap.autotest"))) {
            UiAutoTest.build();
            return;
        }
        if ("migrate".equals(System.getProperty("rysmap.autotest"))) {
            MigrateAutoTest.build();
            return;
        }
        if ("server".equals(System.getProperty("rysmap.autotest"))) {
            ServerAutoTest.build();
            return;
        }
        if ("fixes".equals(System.getProperty("rysmap.autotest"))) {
            FixesAutoTest.build();
            return;
        }
        if ("labels".equals(System.getProperty("rysmap.autotest"))) {
            LabelsAutoTest.build();
            return;
        }
        step("title", () -> {
            mc().options.pauseOnLostFocus = false;
            mc().options.onboardAccessibility = false;
            mc().options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
            if (!(mc().screen instanceof TitleScreen)) mc().setScreen(new TitleScreen());
        }, 30);
        step("world", () -> {
            world = "rysmap_autotest_" + Long.toHexString(System.currentTimeMillis());
            LevelSettings settings = new LevelSettings(world, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
            mc().createWorldOpenFlows().createFreshLevel(world, settings, new WorldOptions(42L, true, false),
                r -> r.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.NORMAL).value().createWorldDimensions(), mc().screen);
        }, 10);
        when("joined", () -> mc().player != null && mc().level != null && mc().screen == null && session() != null, () -> {
            check("RYSMap отвечает за карту", MapApi.get().present());
            spawnX = mc().player.getBlockX();
            spawnZ = mc().player.getBlockZ();
            mc().player.getAbilities().flying = true;
            mc().player.onUpdateAbilities();
            mc().player.connection.sendCommand("gamerule doDaylightCycle false");
            mc().player.connection.sendCommand("time set noon");
        }, 20);
        when("colors", () -> ColorTable.INSTANCE.ready(), () -> check("цвета блоков готовы: " + ColorTable.INSTANCE.blockCount(), ColorTable.INSTANCE.blockCount() > 500), 2);
        when("spawn settled", MapAutoTest::settled, () -> {
            stats("у спавна");
            check("чанки сняты: " + session().scannedChunks(), session().scannedChunks() > 100);
            check("главный поток на чанк в среднем меньше 300 мкс: " + session().snapshotMicrosAverage(), session().snapshotMicrosAverage() < 300);
        }, 2);
        step("tiles open", () -> {
            tilesDrawn = false;
            mc().setScreen(new TilesScreen());
        }, 60);
        shot("spawn_tiles");
        step("tiles check", () -> {
            check("drawTiles нарисовал фон", tilesDrawn);
            mc().setScreen(null);
        }, 2);
        long[] before = {0};
        step("fly", () -> {
            before[0] = session().scannedChunks();
            mc().player.connection.sendCommand("tp @s ~700 200 ~300");
        }, 40);
        when("far settled", () -> session().scannedChunks() > before[0] + 50 && settled(), () -> stats("после перелёта"), 2);
        step("tiles far", () -> {
            tilesDrawn = false;
            mc().setScreen(new TilesScreen());
        }, 60);
        shot("far_tiles");
        step("f3", () -> {
            mc().setScreen(null);
            mc().getDebugOverlay().toggleOverlay();
        }, 10);
        shot("f3");
        step("f3 off", () -> mc().getDebugOverlay().toggleOverlay(), 2);
        boolean[] flushed = {false};
        step("flush", () -> session().flushNow(() -> flushed[0] = true), 2);
        when("flushed", () -> flushed[0], () -> {
            String dim = mc().level.dimension().location().toString();
            int srx = spawnX >> 9, srz = spawnZ >> 9;
            int frx = mc().player.getBlockX() >> 9, frz = mc().player.getBlockZ() >> 9;
            check("регион спавна на диске " + srx + "," + srz, session().regionOnDisk(dim, srx, srz));
            check("регион после перелёта на диске " + frx + "," + frz, session().regionOnDisk(dim, frx, frz));
            check("палитра на диске", java.nio.file.Files.isRegularFile(session().store.palette(dim)));
            firstSnapshots = session().snapshotCount();
            firstScanned = session().scannedChunks();
            check("строка F3: " + session().debugLine(), session().debugLine().startsWith("RYSMap: "));
        }, 2);
        step("leave", () -> {
            mc().level.disconnect();
            mc().disconnect(new GenericMessageScreen(Component.literal("saving")));
            mc().setScreen(new TitleScreen());
        }, 60);
        when("left", () -> mc().level == null && session() == null, () -> check("сессия закрыта при выходе", true), 20);
        step("reopen", () -> mc().createWorldOpenFlows().openWorld(world, () -> mc().setScreen(new TitleScreen())), 10);
        when("rejoined", () -> mc().player != null && mc().level != null && mc().screen == null && session() != null, () -> {}, 40);
        when("reentry settled", MapAutoTest::settled, () -> {
            stats("повторный вход");
            long again = session().snapshotCount();
            check("повторный вход не пересканирует: снимков " + again + " против " + firstSnapshots + " в первый раз", again * 5 < firstSnapshots);
        }, 2);
        step("tiles reentry", () -> {
            tilesDrawn = false;
            mc().setScreen(new TilesScreen());
        }, 80);
        shot("reentry_tiles");
        step("tiles reentry check", () -> {
            check("после повторного входа фон рисуется с диска", tilesDrawn);
            mc().setScreen(null);
        }, 2);
        step("finish", () -> {
            RYSMap.LOG.info(failures == 0 ? "[autotest] PASS" : "[autotest] FAILED: {}", failures);
            mc().stop();
        }, 0);
    }
}
