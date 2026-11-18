package works.repka.rysmap.dev;

import static works.repka.rysmap.dev.MapAutoTest.check;
import static works.repka.rysmap.dev.MapAutoTest.mc;
import static works.repka.rysmap.dev.MapAutoTest.session;
import static works.repka.rysmap.dev.MapAutoTest.shot;
import static works.repka.rysmap.dev.MapAutoTest.step;
import static works.repka.rysmap.dev.MapAutoTest.when;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.common.NeoForge;
import works.repka.ryscore.map.MapApi;
import works.repka.rysmap.RYSMap;
import works.repka.rysmap.client.ClientConfig;
import works.repka.rysmap.hud.WorldLabels;
import works.repka.rysmap.layer.Layers;
import works.repka.rysmap.point.PointStore;
import works.repka.rysmap.screen.MapScreen;

/**
 * Вход на настоящий сервер RepYS с RYSClaims ({@code gradlew runAutotest -Pautotest=server -PrunDir=run-repys
 * -Pplan=<plan.json>}): игровая папка это копия клиента сборки (моды в {@code run-repys/mods}, RYSClaims там jar).
 * План лежит в JSON: адрес, ожидаемый ключ мира, точки базы и чужой земли. Проверки: ключ мира из MapHello, шаг
 * {@code map} перенёс точки FTB, слой «Участки», строка «чья земля», окно «Земля», подписи в мире, отказ сломать блок,
 * открыть сундук и дёрнуть рычаг на чужой земле (замок у прицела), своя земля с прогрузкой. Сервер останавливают
 * снаружи, клиент выходит сам после разрыва. Игрок оператор, обход защиты не включается.
 */
final class ServerAutoTest {
    private static JsonObject plan;
    private static int breakTicks;
    private static BlockState leverBefore;

    private ServerAutoTest() {}

    private static int i(JsonObject o, String k) {
        return o.get(k).getAsInt();
    }

    private static BlockPos pos(String k) {
        var a = plan.getAsJsonObject("stranger").getAsJsonArray(k);
        return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
    }

    private static double[] from(String k) {
        var a = plan.getAsJsonObject("stranger").getAsJsonArray(k + "From");
        return new double[] {a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
    }

    private static void aimFrom(String k, BlockPos target, double dy) {
        double[] f = from(k);
        aim(f[0], f[1], f[2], target, dy);
    }

    private static void cmd(String c) {
        RYSMap.LOG.info("[autotest] команда /{}", c);
        mc().player.connection.sendCommand(c);
    }

    /** Игрок встаёт в {@code (x, y, z)} и смотрит в центр блока {@code target}. */
    private static void aim(double x, double y, double z, BlockPos target, double dyTarget) {
        double eye = y + 1.62;
        double dx = target.getX() + 0.5 - x, dy = target.getY() + dyTarget - eye, dz = target.getZ() + 0.5 - z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        cmd(String.format(Locale.ROOT, "tp @s %.2f %.2f %.2f %.2f %.2f", x, y, z, yaw, pitch));
    }

    /** Земля под чанком по кэшу RYSClaims (отражением: RYSClaims здесь только jar в папке модов). */
    private static String landAt(Level level, int bx, int bz) {
        try {
            Class<?> cache = Class.forName("works.repka.rysclaims.client.LandCache");
            Object st = cache.getMethod("at", String.class, int.class, int.class).invoke(null, level.dimension().location().toString(), bx >> 4, bz >> 4);
            if (st == null) return "";
            Object team = st.getClass().getMethod("team").invoke(st);
            return String.valueOf(team.getClass().getMethod("name").invoke(team));
        } catch (ReflectiveOperationException e) {
            RYSMap.LOG.warn("[autotest] LandCache не прочитался: {}", e.toString());
            return "?";
        }
    }

    private static String denyText() {
        try {
            var f = Class.forName("works.repka.rysclaims.client.DenyFeedback").getDeclaredField("text");
            f.setAccessible(true);
            Object t = f.get(null);
            return t instanceof net.minecraft.network.chat.Component c ? c.getString() : "";
        } catch (ReflectiveOperationException e) {
            return "?";
        }
    }

    private static void clearDeny() {
        try {
            var f = Class.forName("works.repka.rysclaims.client.DenyFeedback").getDeclaredField("text");
            f.setAccessible(true);
            f.set(null, null);
        } catch (ReflectiveOperationException ignored) {
            // не главное
        }
    }

    private static boolean mapStepDone() {
        var s = session();
        if (s == null) return false;
        Path f = s.store.root().resolve("migrate.json");
        if (!Files.isRegularFile(f)) return false;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            var steps = o.getAsJsonObject("steps");
            return steps != null && steps.has("map") && steps.getAsJsonObject("map").has("done") && steps.getAsJsonObject("map").get("done").getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    private static void fly() {
        if (mc().player == null) return;
        mc().player.getAbilities().mayfly = true;
        mc().player.getAbilities().flying = true;
        mc().player.onUpdateAbilities();
    }

    static void build() {
        MapAutoTest.prefix = "server";
        try {
            plan = JsonParser.parseString(Files.readString(Path.of(System.getProperty("rysmap.autotest.plan")), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            throw new IllegalStateException("план автотеста server не прочитался", e);
        }
        NeoForge.EVENT_BUS.addListener((ClientChatReceivedEvent e) -> RYSMap.LOG.info("[autotest] чат: {}", e.getMessage().getString()));
        String address = plan.get("address").getAsString();
        JsonObject base = plan.getAsJsonObject("base"), st = plan.getAsJsonObject("stranger"), wild = plan.getAsJsonObject("wild");
        String owner = st.get("name").getAsString();

        when("title", () -> mc().getOverlay() == null && mc().screen != null && mc().level == null, () -> {
            mc().options.pauseOnLostFocus = false;
            mc().options.onboardAccessibility = false;
            mc().options.guiScale().set(2);
            mc().options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
            mc().resizeDisplay();
        }, 60);
        step("connect", () -> ConnectScreen.startConnecting(mc().screen, mc(), ServerAddress.parseString(address),
            new ServerData("RepYS test", address, ServerData.Type.OTHER), false, null), 20);
        when("joined", () -> mc().player != null && mc().level != null && session() != null
            && !(mc().screen instanceof net.minecraft.client.gui.screens.ReceivingLevelScreen), () -> {
            if (mc().screen != null) {
                RYSMap.LOG.info("[autotest] после входа открыт экран {}, закрыт", mc().screen.getClass().getName());
                mc().setScreen(null);
            }
            check("MapApi у RYSMap", MapApi.get().present());
            cmd("effect give @s minecraft:night_vision infinite 0 true");
            cmd("effect give @s minecraft:resistance infinite 4 true");
            cmd("gamemode creative");
            MapAutoTest.limit = 20 * 600;
        }, 100);
        step("world key", () -> {
            String key = session().store.key().id();
            RYSMap.LOG.info("[autotest] ключ мира {} (папка {})", key, session().store.root());
            check("ключ мира из MapHello совпал с rysmap.dat: " + key, key.equals(plan.get("worldKey").getAsString()));
        }, 2);
        when("map step", ServerAutoTest::mapStepDone, () -> {
            PointStore ps = session().points();
            int ftb = 0, deaths = 0;
            boolean stranger = false;
            for (PointStore.Point p : ps.points()) {
                if ("ftb".equals(p.from)) ftb++;
                if ("ftb_death".equals(p.note)) deaths++;
                if (p.name.equals(st.get("point").getAsString()) && p.x == i(st, "x") && p.z == i(st, "z")) stranger = true;
                RYSMap.LOG.info("[autotest] точка «{}» {} {} {} {} from={} note={} world={}", p.name, p.dimension, p.x, p.y, p.z, p.from, p.note, p.world);
            }
            check("шаг map: точек из FTB 13 (" + ftb + "), из них мест гибели " + deaths, ftb == 13);
            check("точка чужого привата на месте после шага map", stranger);
            check("всего точек 14: " + ps.points().size(), ps.points().size() == 14);
            MapAutoTest.limit = 20 * 240;
        }, 2);

        // база VENIK: строка под миникартой, окно «Земля», карта со слоем «Участки»
        step("to base", () -> fly(), 2);
        step("to base tp", () -> cmd("tp @s " + i(base, "x") + " " + i(base, "y") + " " + i(base, "z") + " 0 30"), 200);
        when("base land", () -> !landAt(mc().level, i(base, "x"), i(base, "z")).isEmpty(), () -> {
            String land = landAt(mc().level, mc().player.getBlockX(), mc().player.getBlockZ());
            check("под игроком земля VENIK: " + land, land.equals("VENIK"));
        }, 40);
        shot("base_hud");
        step("inventory", () -> mc().setScreen(new InventoryScreen(mc().player)), 30);
        shot("inventory_panel");
        step("land screen", () -> {
            mc().setScreen(null);
            try {
                Class.forName("works.repka.rysclaims.client.LandScreen").getMethod("open").invoke(null);
            } catch (ReflectiveOperationException e) {
                check("окно «Земля» открылось: " + e, false);
            }
        }, 60);
        step("land screen check", () -> check("окно «Земля» открыто: " + (mc().screen == null ? "нет" : mc().screen.getClass().getSimpleName()),
            mc().screen != null && mc().screen.getClass().getSimpleName().equals("LandScreen")), 2);
        shot("land_screen");
        step("map", () -> {
            mc().setScreen(null);
            boolean layer = false;
            for (MapApi.Layer l : MapApi.layers()) if (l.id().toString().equals("rysclaims:claims")) layer = Layers.visible(l);
            check("слой «Участки» есть и включён", layer);
            MapScreen.open();
        }, 80);
        shot("map_base");
        step("map end", () -> {
            mc().setScreen(null);
            MapApi.get().open(MapApi.Target.builder(Level.END, 0, 64, 0).build());
        }, 60);
        shot("map_end_deaths");
        step("map nether", () -> {
            mc().setScreen(null);
            MapApi.get().open(MapApi.Target.builder(Level.NETHER, 546, 65, 3001).build());
        }, 60);
        shot("map_nether_death");

        // чужая земля: подпись точки издалека, строка «чья земля», отказы
        int sx = i(st, "x"), sy = i(st, "y"), sz = i(st, "z");
        step("stranger approach", () -> {
            mc().setScreen(null);
            ClientConfig.get().worldLabels = "nearby";
            cmd(String.format(Locale.ROOT, "tp @s %d.5 %d %d.5 0 12", sx, sy + 3, sz - 45));
        }, 200);
        step("label check", () -> {
            RYSMap.LOG.info("[autotest] метки у чужой земли: {}", WorldLabels.debugState());
            check("подписи в мире не спрятаны", !WorldLabels.hidden(mc()));
            String land = landAt(mc().level, mc().player.getBlockX(), mc().player.getBlockZ());
            check("перед чужой землёй чанк ничей: «" + land + "»", land.isEmpty());
        }, 2);
        shot("stranger_label");
        step("stranger enter", () -> cmd(String.format(Locale.ROOT, "tp @s %d.5 %d %d.5 0 25", sx, sy, sz - 4)), 60);
        when("stranger land", () -> !landAt(mc().level, sx, sz).isEmpty(), () -> {
            String land = landAt(mc().level, mc().player.getBlockX(), mc().player.getBlockZ());
            check("под игроком земля " + owner + ": «" + land + "»", land.equals(owner));
        }, 20);
        shot("stranger_hud");
        step("survival", () -> cmd("gamemode survival"), 20);
        BlockPos torch = pos("torch"), planks = pos("planks"), chest = pos("chest"), lever = pos("lever");
        step("aim torch", () -> {
            clearDeny();
            aimFrom("torch", torch, 0.3);
        }, 20);
        step("break torch", () -> mc().gameMode.startDestroyBlock(torch, Direction.SOUTH), 10);
        shot("torch_denied");
        step("torch check", () -> {
            check("факел на чужой земле не сломался: " + mc().level.getBlockState(torch), mc().level.getBlockState(torch).is(net.minecraft.world.level.block.Blocks.TORCH));
            check("замок у прицела: «" + denyText() + "»", !denyText().isEmpty());
        }, 20);
        step("aim planks", () -> {
            clearDeny();
            aimFrom("planks", planks, 0.5);
            breakTicks = 0;
        }, 20);
        when("dig planks", () -> {
            if (breakTicks == 0) mc().gameMode.startDestroyBlock(planks, Direction.NORTH);
            else mc().gameMode.continueDestroyBlock(planks, Direction.NORTH);
            mc().player.swing(InteractionHand.MAIN_HAND);
            return ++breakTicks >= 120;
        }, () -> mc().gameMode.stopDestroyBlock(), 20);
        shot("planks_denied");
        step("planks check", () -> {
            check("доски на чужой земле целы после 6 секунд копания: " + mc().level.getBlockState(planks), mc().level.getBlockState(planks).is(net.minecraft.world.level.block.Blocks.OAK_PLANKS));
            check("замок у прицела после копания: «" + denyText() + "»", !denyText().isEmpty());
        }, 2);
        step("aim chest", () -> {
            clearDeny();
            aimFrom("chest", chest, 0.5);
        }, 20);
        step("open chest", () -> mc().gameMode.useItemOn(mc().player, InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(chest), Direction.SOUTH, chest, false)), 10);
        shot("chest_denied");
        step("chest check", () -> {
            check("сундук на чужой земле не открылся: экран " + (mc().screen == null ? "нет" : mc().screen.getClass().getSimpleName()), mc().screen == null);
            check("замок у прицела у сундука: «" + denyText() + "»", !denyText().isEmpty());
            if (mc().screen != null) mc().setScreen(null);
        }, 2);
        step("aim lever", () -> {
            clearDeny();
            leverBefore = mc().level.getBlockState(lever);
            aimFrom("lever", lever, 0.1);
        }, 20);
        step("use lever", () -> mc().gameMode.useItemOn(mc().player, InteractionHand.MAIN_HAND,
            new BlockHitResult(new Vec3(lever.getX() + 0.5, lever.getY() + 0.1, lever.getZ() + 0.5), Direction.UP, lever, false)), 30);
        step("lever check", () -> {
            BlockState now = mc().level.getBlockState(lever);
            boolean same = now.hasProperty(LeverBlock.POWERED) && leverBefore.hasProperty(LeverBlock.POWERED)
                && now.getValue(LeverBlock.POWERED).equals(leverBefore.getValue(LeverBlock.POWERED));
            check("рычаг на чужой земле не переключился: " + now, same);
            cmd("claims info");
            cmd("claims admin for " + owner + " log");
        }, 40);

        // своя земля игрока теста с прогрузкой: билет стоит, пока игрок в сети
        step("wild", () -> {
            cmd("gamemode creative");
            fly();
            cmd("tp @s " + i(wild, "x") + " " + i(wild, "y") + " " + i(wild, "z"));
        }, 200);
        step("own claim", () -> cmd("claims claim"), 40);
        step("own load", () -> cmd("claims load"), 40);
        step("own list", () -> {
            cmd("claims list");
            cmd("claims admin loaded");
            cmd("tp @s " + i(base, "x") + " " + i(base, "y") + " " + i(base, "z"));
        }, 200);
        step("loaded far", () -> cmd("claims admin loaded"), 60);
        step("finish", () -> {
            RYSMap.LOG.info(MapAutoTest.failures == 0 ? "[autotest] PASS" : "[autotest] FAILED: {}", MapAutoTest.failures);
            RYSMap.LOG.info("[autotest] ждёт остановки сервера");
        }, 0);
        when("disconnected", () -> mc().level == null || MapAutoTest.stepTicks > 20 * 230, () -> {
            RYSMap.LOG.info("[autotest] сервер остановлен, клиент выходит");
            mc().stop();
        }, 0);
    }
}
