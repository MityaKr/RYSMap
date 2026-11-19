package works.repka.rysmap.dev;

import static works.repka.rysmap.dev.MapAutoTest.check;
import static works.repka.rysmap.dev.MapAutoTest.mc;
import static works.repka.rysmap.dev.MapAutoTest.session;
import static works.repka.rysmap.dev.MapAutoTest.shot;
import static works.repka.rysmap.dev.MapAutoTest.step;
import static works.repka.rysmap.dev.MapAutoTest.when;

import java.util.List;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import works.repka.rysmap.RYSMap;
import works.repka.rysmap.client.ClientConfig;
import works.repka.rysmap.hud.Entities;
import works.repka.rysmap.hud.Minimap;
import works.repka.rysmap.hud.MobHeads;
import works.repka.rysmap.screen.MapScreen;
import works.repka.rysmap.scan.Floors;
import works.repka.rysmap.tile.ColorTable;

/**
 * Мобы на картах ({@code gradlew runAutotest -Pheap=3G --offline -Pautotest=mobs}): толпа из 200 мобов шести типов (ферма),
 * головы печёные по типу, цена кадра миникарты с толпой; слои: мобы в пещере не видны с поверхности и наоборот,
 * переключатель «Все слои» показывает всех.
 */
final class MobsAutoTest {
    private static int baseX, baseZ, groundY;
    private static final String[] TYPES = {"cow", "sheep", "pig", "chicken", "husk", "creeper"};

    private MobsAutoTest() {}

    private static List<Entities.Dot> around(Integer floor) {
        var p = mc().player;
        return Entities.collect(p.level().dimension(), p.getX() - 80, p.getZ() - 80, p.getX() + 80, p.getZ() + 80, 1f, floor);
    }

    private static long mobs(List<Entities.Dot> dots) {
        return dots.stream().filter(d -> !d.player()).count();
    }

    static void build() {
        MapAutoTest.prefix = "mobs";
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
            MapAutoTest.world = "rysmap_mobs_" + Long.toHexString(System.currentTimeMillis());
            LevelSettings settings = new LevelSettings(MapAutoTest.world, GameType.CREATIVE, false, Difficulty.EASY, true, new GameRules(), WorldDataConfiguration.DEFAULT);
            mc().createWorldOpenFlows().createFreshLevel(MapAutoTest.world, settings, new WorldOptions(42L, true, false),
                r -> r.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.NORMAL).value().createWorldDimensions(), mc().screen);
        }, 10);
        when("joined", () -> mc().player != null && mc().level != null && mc().screen == null && session() != null, () -> {
            mc().player.getAbilities().flying = true;
            mc().player.onUpdateAbilities();
            mc().player.connection.sendCommand("gamerule doDaylightCycle false");
            mc().player.connection.sendCommand("gamerule doMobSpawning false");
            mc().player.connection.sendCommand("time set noon");
        }, 40);
        when("colors", () -> ColorTable.INSTANCE.ready(), () -> {}, 2);
        when("settled", MapAutoTest::settled, () -> {
            baseX = mc().player.getBlockX();
            baseZ = mc().player.getBlockZ();
            groundY = mc().level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, baseX, baseZ);
            // площадка фермы на высоте земли
            mc().player.connection.sendCommand(String.format("fill %d %d %d %d %d %d stone", baseX - 12, groundY - 1, baseZ - 12, baseX + 12, groundY - 1, baseZ + 12));
            mc().player.connection.sendCommand(String.format("fill %d %d %d %d %d %d air", baseX - 12, groundY, baseZ - 12, baseX + 12, groundY + 6, baseZ + 12));
            mc().player.connection.sendCommand(String.format("tp @s %d %d %d 0 70", baseX, groundY + 6, baseZ));
        }, 30);
        step("farm", () -> {
            // 200 мобов: сетка 20×10, тип по столбцу
            int n = 0;
            for (int i = 0; i < 20; i++) {
                for (int j = 0; j < 10; j++) {
                    String type = TYPES[(i + j) % TYPES.length];
                    mc().player.connection.sendCommand(String.format("summon minecraft:%s %d %d %d {NoAI:1b,PersistenceRequired:1b,Silent:1b}",
                        type, baseX - 10 + i, groundY, baseZ - 5 + j));
                    n++;
                }
            }
            // пещера под фермой с крипером и свиньёй
            mc().player.connection.sendCommand(String.format("fill %d -30 %d %d -27 %d air", baseX - 6, baseZ - 6, baseX + 6, baseZ + 6));
            mc().player.connection.sendCommand(String.format("summon minecraft:creeper %d -30 %d {NoAI:1b,PersistenceRequired:1b,Silent:1b}", baseX + 3, baseZ + 2));
            mc().player.connection.sendCommand(String.format("summon minecraft:pig %d -30 %d {NoAI:1b,PersistenceRequired:1b,Silent:1b}", baseX - 3, baseZ - 2));
            // одиночные значки и босс (отзыв автора 19.11): зомби, корова, иссушитель в стороне от фермы
            mc().player.connection.sendCommand(String.format("summon minecraft:zombie %d %d %d {NoAI:1b,PersistenceRequired:1b,Silent:1b}", baseX + 9, groundY, baseZ + 9));
            mc().player.connection.sendCommand(String.format("summon minecraft:cow %d %d %d {NoAI:1b,PersistenceRequired:1b,Silent:1b}", baseX - 9, groundY, baseZ + 10));
            mc().player.connection.sendCommand(String.format("summon minecraft:wither %d %d %d {NoAI:1b,PersistenceRequired:1b,Silent:1b}", baseX + 10, groundY + 1, baseZ - 10));
            RYSMap.LOG.info("[autotest] ферма: {} мобов", n);
            ClientConfig c = ClientConfig.get();
            c.minimapSize = 128;
            c.minimapZoom = 2;
            c.mobsRange = 16;
            c.mobsView = 1;
        }, 60);
        when("heads", () -> MobHeads.count() >= 5, () -> {
            check("головы испечены: " + MobHeads.count() + " типов, " + MobHeads.bakeMicros() + " мкс на тип", MobHeads.count() >= 5);
            Minimap.frameMicrosAndReset();
        }, 100);
        step("crowd frame", () -> {
            long us = Minimap.frameMicrosAndReset();
            long surface = mobs(around(null));
            RYSMap.LOG.info("[autotest] замер кадра миникарты с толпой ({} мобов на поверхности): {} мкс", surface, us);
            // ферма 200 и естественные мобы мира в ±16 блоков (ночь 19.11: 205); что пещера не видна, проверяет «Все слои» ниже
            check("с поверхности (±16 от игрока) видна ферма: " + surface, surface >= 190 && surface <= 230);
            check("кадр миникарты с толпой дешевле 2 мс: " + us + " мкс", us < 2000);
            long cave = mobs(around(Floors.of(-30)));
            check("на этаже пещеры только мобы пещеры: " + cave, cave == 2);
            ClientConfig.get().mobsRange = 0;
            long all = mobs(around(null));
            check("«Все слои»: видны все: " + all, all >= 200);
            check("с поверхности пещера не видна: все слои " + all + ", поверхность " + surface + ", пещера " + cave, all >= surface + cave);
            ClientConfig.get().mobsRange = 16;
            ClientConfig.get().mobsHostile = false;
            long peaceful = mobs(around(null));
            check("без враждебных видны только мирные: " + peaceful, peaceful > 0 && peaceful < surface);
            ClientConfig.get().mobsHostile = true;
        }, 4);
        shot("minimap_crowd");
        step("map", () -> {
            ClientConfig.get().minimapSize = 0;
            ClientConfig.get().minimapZoom = 1;
            MapScreen.open();
        }, 40);
        step("map zoom", () -> ((MapScreen) mc().screen).setZoom(6), 20);
        shot("map_crowd");
        step("map zoom out", () -> ((MapScreen) mc().screen).setZoom(2), 20);
        shot("map_piles");
        step("menu", () -> {
            // пункты в группе «Пинг» (как у RYSTeams 0.2.9) и пункт без группы: подменю и разделители
            works.repka.ryscore.map.MapApi api = works.repka.ryscore.map.MapApi.get();
            String[] kinds = {"Сюда", "Опасно", "Ресурс", "Помогите"};
            for (int i = 0; i < kinds.length; i++) {
                api.menu(works.repka.ryscore.map.MapApi.MenuItem.builder(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(RYSMap.MODID, "autotest_ping_" + i),
                        net.minecraft.network.chat.Component.literal(kinds[i]))
                    .icon(i == 1 ? works.repka.ryscore.ui.Icons.Shape.WARN : works.repka.ryscore.ui.Icons.Shape.BELL).order(11 + i)
                    .group(works.repka.ryscore.map.MapApi.PING_GROUP).run(spot -> {}).build());
            }
            MapScreen s = (MapScreen) mc().screen;
            s.openMenuForTest();
            List<String> rows = s.openSubForTest();
            RYSMap.LOG.info("[autotest] меню: {}", rows);
            check("пинги собраны в «Пинг ›» с четырьмя пунктами", rows.contains("Пинг") && rows.contains("  Помогите") && !rows.contains("Помогите"));
        }, 10);
        shot("map_menu_ping");
        step("menu close", () -> mc().screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE, 0, 0), 5);
        step("cave", () -> {
            mc().setScreen(null);
            mc().player.connection.sendCommand(String.format("tp @s %d -30 %d 0 10", baseX, baseZ));
        }, 60);
        when("cave floor", () -> session().caveFloor() != null, () -> {}, 40);
        shot("minimap_cave_mobs");
        step("finish", () -> {
            mc().setScreen(null);
            RYSMap.LOG.info(MapAutoTest.failures == 0 ? "[autotest] PASS" : "[autotest] FAILED: {}", MapAutoTest.failures);
            mc().stop();
        }, 0);
    }
}
