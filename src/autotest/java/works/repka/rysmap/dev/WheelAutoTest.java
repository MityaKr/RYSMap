package works.repka.rysmap.dev;

import static works.repka.rysmap.dev.MapAutoTest.check;
import static works.repka.rysmap.dev.MapAutoTest.mc;
import static works.repka.rysmap.dev.MapAutoTest.session;
import static works.repka.rysmap.dev.MapAutoTest.shot;
import static works.repka.rysmap.dev.MapAutoTest.step;
import static works.repka.rysmap.dev.MapAutoTest.when;

import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import works.repka.ryscore.action.Action;
import works.repka.ryscore.action.Actions;
import works.repka.ryscore.action.Ring;
import works.repka.ryscore.perm.Roles;
import works.repka.ryscore.ui.Icons;
import works.repka.rysmap.RYSMap;

/**
 * Колесо с вкладками колец (баг автора 23.11: цифра «1» висела между «Моё» и «Оператор»). Без RYSHelm кольцо оператора
 * заполняется заглушками с id RYSHelm. Снимки: {@code wheel_mine}, {@code wheel_operator} (роль оператор),
 * {@code wheel_player} (роль игрок: вкладок нет, кольца оператора нет).
 */
final class WheelAutoTest {
    private WheelAutoTest() {}

    private static void stub(String path, String label, Icons.Shape icon) {
        Action.builder(ResourceLocation.fromNamespaceAndPath("ryshelm", path)).label(Component.literal(label)).icon(icon)
            .group(Action.Group.OPERATOR).run(() -> {}).register();
    }

    static void build() {
        MapAutoTest.prefix = "wheel";
        step("title", () -> {
            mc().options.pauseOnLostFocus = false;
            mc().options.onboardAccessibility = false;
            mc().options.guiScale().set(2);
            mc().options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
            mc().resizeDisplay();
            if (!(mc().screen instanceof TitleScreen)) mc().setScreen(new TitleScreen());
        }, 30);
        step("world", () -> {
            MapAutoTest.world = "rysmap_wheel_" + Long.toHexString(System.currentTimeMillis());
            LevelSettings settings = new LevelSettings(MapAutoTest.world, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
            mc().createWorldOpenFlows().createFreshLevel(MapAutoTest.world, settings, new WorldOptions(42L, true, false),
                r -> r.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.NORMAL).value().createWorldDimensions(), mc().screen);
        }, 10);
        when("joined", () -> mc().player != null && mc().level != null && mc().screen == null && session() != null, () -> {
            mc().player.connection.sendCommand("time set noon");
            stub("fly", "Полёт", Icons.Shape.FEATHER);
            stub("god", "Бессмертие", Icons.Shape.SHIELD);
            stub("heal", "Лечение", Icons.Shape.HEART);
            stub("feed", "Сытость", Icons.Shape.FORK);
            stub("speed", "Скорость", Icons.Shape.SPEED);
            stub("invsee", "Инвентарь", Icons.Shape.CHEST);
            stub("vanish", "Невидимость", Icons.Shape.EYE);
            Roles.setClient(Roles.OPERATOR);
        }, 60);
        step("wheel mine", () -> {
            boolean op = Actions.availableRings().stream().anyMatch(r -> r.id().equals(Ring.OPERATOR));
            check("оператору кольцо «Оператор» видно", op);
            Actions.openWheel();
        }, 20);
        shot("wheel_mine");
        step("ring operator", () -> {
            var s = mc().screen;
            if (s != null) s.mouseScrolled(s.width / 2.0, s.height / 2.0, 0, -1);
        }, 20);
        shot("wheel_operator");
        step("as player", () -> {
            mc().setScreen(null);
            Roles.setClient(Roles.PLAYER);
            boolean op = Actions.availableRings().stream().anyMatch(r -> r.id().equals(Ring.OPERATOR));
            check("игроку без ОП кольца «Оператор» нет", !op);
            check("игроку без ОП одно кольцо: " + Actions.availableRings().size(), Actions.availableRings().size() == 1);
            Actions.openWheel();
        }, 20);
        shot("wheel_player");
        step("finish", () -> {
            mc().setScreen(null);
            Roles.setClient(null);
            RYSMap.LOG.info(MapAutoTest.failures == 0 ? "[autotest] PASS" : "[autotest] FAILED: {}", MapAutoTest.failures);
            mc().stop();
        }, 0);
    }
}
