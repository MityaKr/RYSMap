package works.repka.rysmap.dev;

import static works.repka.rysmap.dev.MapAutoTest.check;
import static works.repka.rysmap.dev.MapAutoTest.mc;
import static works.repka.rysmap.dev.MapAutoTest.session;
import static works.repka.rysmap.dev.MapAutoTest.shot;
import static works.repka.rysmap.dev.MapAutoTest.step;
import static works.repka.rysmap.dev.MapAutoTest.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import works.repka.ryscore.map.MapApi;
import works.repka.rysmap.RYSMap;
import works.repka.rysmap.api.MapApiImpl;
import works.repka.rysmap.client.ClientConfig;
import works.repka.rysmap.client.ClientEntry;
import works.repka.rysmap.hud.WorldLabels;
import works.repka.rysmap.point.PointStore;
import works.repka.rysmap.tile.ColorTable;

/**
 * Подписи точек в мире ({@code gradlew runAutotest -Pheap=3G --offline -Pautotest=labels}): новый мир, десять точек по
 * кругу радиусом 24 блока вокруг игрока, прицел на точку «Шахта», путь к соседней точке «Портал». Снимки: прицел на
 * точке (раскрыты «Шахта» и цель), зажатая клавиша «показать все точки» (раскрыты все), режим «Цель и гибель»
 * (видна только цель). Проверки: что отобрано и раскрыто, F1 и открытый экран прячут метки, F3 нет.
 */
final class LabelsAutoTest {
    private static final String[] NAMES = {"Дом", "Причал", "Шахта", "Портал", "Ферма", "Склад", "Башня", "Мост", "Озеро", "Кузня"};
    private static final String[] ICONS = {"home", "anchor", "pick", "flag", "star", "chest", "shield", "map", "eye", "key"};
    private static final int AIM = 2, GOAL = 3, RADIUS = 24;
    private static final List<PointStore.Point> POINTS = new ArrayList<>();
    private static int baseX, baseY, baseZ;

    private LabelsAutoTest() {}

    static void build() {
        MapAutoTest.prefix = "labels";
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
            MapAutoTest.world = "rysmap_labels_" + Long.toHexString(System.currentTimeMillis());
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
            ClientConfig.get().worldLabels = "nearby";
            ClientConfig.get().labelFocus = 40;
        }, 40);
        when("colors", () -> ColorTable.INSTANCE.ready(), () -> {}, 2);
        when("settled", MapAutoTest::settled, () -> {
            baseX = mc().player.getBlockX();
            baseY = mc().player.getBlockY() + 8;
            baseZ = mc().player.getBlockZ();
            // десять точек по кругу на высоте игрока: подпись встаёт на блок выше точки, у глаз
            for (int i = 0; i < NAMES.length; i++) {
                double a = Math.PI * 2 * i / NAMES.length;
                PointStore.Point p = new PointStore.Point();
                p.name = NAMES[i];
                p.icon = ICONS[i];
                p.color = i % PointStore.COLORS;
                p.x = baseX + (int) Math.round(Math.sin(a) * RADIUS);
                p.y = baseY - 1;
                p.z = baseZ + (int) Math.round(Math.cos(a) * RADIUS);
                session().points().add(p);
                POINTS.add(p);
            }
            session().savePoints();
            PointStore.Point goal = POINTS.get(GOAL);
            if (MapApi.get() instanceof MapApiImpl api)
                api.trackPoint(goal.id, MapApi.Target.builder(Level.OVERWORLD, goal.x + 0.5, goal.y, goal.z + 0.5).label(Component.literal(goal.name)).build());
            aim(POINTS.get(AIM));
        }, 40);
        step("aim check", () -> {
            RYSMap.LOG.info("[autotest] метки: {}", WorldLabels.debugState());
            check("метки не спрятаны", !WorldLabels.hidden(mc()));
            check("прицел на «Шахте»: она раскрыта", WorldLabels.expanded(POINTS.get(AIM).id));
            check("цель пути «Портал» раскрыта", WorldLabels.expanded(POINTS.get(GOAL).id));
            check("соседняя «Ферма» свёрнута", !WorldLabels.expanded(POINTS.get(4).id));
        }, 2);
        shot("aim");
        step("show all", () -> ClientEntry.SHOW_POINTS.setDown(true), 20);
        step("show all check", () -> {
            int expanded = 0;
            for (PointStore.Point p : POINTS) if (WorldLabels.expanded(p.id)) expanded++;
            RYSMap.LOG.info("[autotest] клавиша: {}, раскрыто из видимых {}", WorldLabels.debugState(), expanded);
            check("клавиша раскрыла видимые метки (раскрыто " + expanded + ")", expanded >= 4);
        }, 2);
        shot("all");
        step("show all off", () -> {
            ClientEntry.SHOW_POINTS.setDown(false);
            ClientConfig.get().worldLabels = "goal";
        }, 20);
        step("goal mode check", () -> {
            RYSMap.LOG.info("[autotest] режим «Цель и гибель»: {}", WorldLabels.debugState());
            check("режим «Цель и гибель»: отобрана одна цель", WorldLabels.debugState().startsWith("chosen=1 "));
            check("цель раскрыта", WorldLabels.expanded(POINTS.get(GOAL).id));
        }, 2);
        shot("goal_mode");
        step("spot goal", () -> {
            ClientConfig.get().worldLabels = "nearby";
            PointStore.Point far = POINTS.get(AIM);
            // путь к месту с карты (не к точке): своя метка с мишенью, раскрыта
            MapApi.get().track(MapApi.Target.builder(Level.OVERWORLD, far.x + 0.5 + 3, far.y, far.z + 0.5 - 3).build());
        }, 20);
        step("spot goal check", () -> check("цель-место раскрыта", WorldLabels.spotGoalExpanded()), 2);
        shot("spot_goal");
        step("f1", () -> mc().options.hideGui = true, 2);
        step("f1 check", () -> {
            check("на F1 метки спрятаны", WorldLabels.hidden(mc()));
            mc().options.hideGui = false;
            mc().getDebugOverlay().toggleOverlay();
        }, 2);
        step("f3 check", () -> {
            check("на F3 метки видны", !WorldLabels.hidden(mc()));
            mc().getDebugOverlay().toggleOverlay();
            mc().setScreen(new net.minecraft.client.gui.screens.ChatScreen(""));
        }, 4);
        step("screen check", () -> {
            check("при открытом экране метки спрятаны", WorldLabels.hidden(mc()));
            mc().setScreen(null);
        }, 4);
        step("finish", () -> {
            ClientConfig.get().worldLabels = "nearby";
            RYSMap.LOG.info(MapAutoTest.failures == 0 ? "[autotest] PASS" : "[autotest] FAILED: {}", MapAutoTest.failures);
            mc().stop();
        }, 0);
    }

    /** Игрок в центре круга, взгляд точно на подпись точки (блок над ней). */
    private static void aim(PointStore.Point p) {
        double eye = baseY + 1.62;
        double dx = p.x + 0.5 - (baseX + 0.5), dy = p.y + 1.0 - eye, dz = p.z + 0.5 - (baseZ + 0.5);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        mc().player.connection.sendCommand(String.format(Locale.ROOT, "tp @s %.1f %d %.1f %.2f %.2f", baseX + 0.5, baseY, baseZ + 0.5, yaw, pitch));
    }
}
