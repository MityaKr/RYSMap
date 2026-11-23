package works.repka.rysmap.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import works.repka.rysmap.RYSMap;
import works.repka.rysmap.net.MapNet;

/**
 * Что сервер разрешил карте (пакет {@link MapNet.Rules}) и ответы на телепорт. Без серверной части RYSMap правил нет:
 * карта, миникарта, свои точки и сущности рядом работают, телепорта нет. Только главный поток.
 */
public final class ServerRules {
    private static MapNet.@Nullable Rules rules;
    /** Когда пришли правила: от этой метки считается остаток паузы. */
    private static long rulesAt;
    /** Строка сообщения карты (макет {@code 24}): заголовок жирным, пояснение следом. */
    public record Message(Component title, Component text) {}

    /** Сообщение карты для окна и до какого времени показывать. */
    private static @Nullable Message message;
    private static long messageUntil;
    private static boolean pending;
    /** Игроки из последнего пакета сервера по UUID. */
    private static Map<UUID, MapNet.PlayerSpot> players = Map.of();
    private static long playersAt;

    private ServerRules() {}

    public static void reset() {
        rules = null;
        message = null;
        pending = false;
        players = Map.of();
    }

    public static MapNet.@Nullable Rules rules() {
        return rules;
    }

    public static void accept(MapNet.Rules r) {
        rules = r;
        rulesAt = System.currentTimeMillis();
        RYSMap.LOG.info("[rysmap] server rules: teleport {}, cooldown {} s, minimap {}, caves {}, other players {}, mobs {}, operator {}",
            r.teleport(), r.pause(), r.minimap(), r.caves(), r.playersMode(), r.mobs(), r.op());
    }


    // ---- что разрешено ----

    /** Миникарта у игроков: без серверной части да. */
    public static boolean minimapAllowed() {
        return rules == null || rules.minimap();
    }

    public static boolean cavesAllowed() {
        return rules == null || rules.caves();
    }

    /** Чужие игроки: без серверной части видны те, кого клиент и так видит рядом; правило «Никогда» прячет и их. */
    public static boolean playersAllowed() {
        return rules == null || rules.playersMode() != 0;
    }

    /** Слой «Слизни»: только с серверной частью RYSMap 0.2.3 (сид знает сервер), если оператор не выключил. */
    public static boolean slimeAllowed() {
        return rules != null && rules.slime();
    }

    public static boolean mobsAllowed() {
        return rules == null || rules.mobs();
    }

    /** Правило сервера пускает этого игрока к телепорту (пауза не учтена). */
    public static boolean teleportAllowed() {
        return rules != null && rules.canTeleport();
    }

    /** Игрок в команде RYSTeams (сервер с RYSMap 0.1.0-m8): есть пункт «Пинг команде». */
    public static boolean inTeam() {
        return rules != null && rules.team();
    }

    /** Пинг команде с карты. */
    public static void ping(ResourceKey<Level> dimension, int x, int y, int z) {
        if (!sendToServer(new MapNet.Ping(dimension.location().toString(), x, y, z, "here")))
            message(new Message(Component.translatable("rysmap.ping.fail"), Component.translatable("rysmap.tp.no_server")));
    }

    /** Может ли игрок править правила сервера (раздел «Сервер» в настройках). */
    public static boolean op() {
        return rules != null && rules.op();
    }

    /** Сколько секунд паузы осталось. */
    public static int pauseLeft() {
        if (rules == null || rules.pauseLeft() <= 0) return 0;
        long left = rulesAt + rules.pauseLeft() * 1000L - System.currentTimeMillis();
        return left <= 0 ? 0 : (int) Math.ceil(left / 1000.0);
    }

    // ---- телепорт ----

    /**
     * Просьба о телепорте. Отказ, который клиент видит сам (сервер без RYSMap, правило, пауза), сразу идёт строкой
     * сообщения карты, без пакета; остальное решает сервер.
     *
     * @param y высота ног-подсказка или {@code Integer.MIN_VALUE}
     */
    public static void teleport(ResourceKey<Level> dimension, int x, int y, int z) {
        if (rules == null) {
            message(fail(Component.translatable("rysmap.tp.no_server")));
            return;
        }
        if (!rules.canTeleport()) {
            message(fail(Component.translatable(rules.teleport() == 0 ? "rysmap.tp.off" : "rysmap.tp.ops")));
            return;
        }
        int left = pauseLeft();
        if (left > 0) {
            message(pauseText(left));
            return;
        }
        if (pending) return;
        if (!sendToServer(new MapNet.Teleport(dimension.location().toString(), x, y, z))) {
            message(fail(Component.translatable("rysmap.tp.no_server")));
            return;
        }
        pending = true;
    }

    public static void teleported(MapNet.TeleportResult r) {
        pending = false;
        if (r.ok()) {
            message = null;
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof works.repka.rysmap.screen.MapScreen s) s.arrived();
            return;
        }
        // отказ с причиной словами (приват, 0.2.0) приходит строкой уведомления мест
        if (r.reason().equals("denied")) return;
        message(r.reason().equals("pause") ? pauseText(r.seconds())
            : fail(Component.translatable("rysmap.tp." + r.reason())));
    }


    private static Message pauseText(int seconds) {
        int pause = rules == null ? seconds : rules.pause();
        return fail(Component.translatable("rysmap.tp.pause", duration(pause), clock(seconds)));
    }

    /** «5 минут», «30 секунд». */
    public static String duration(int seconds) {
        if (seconds % 60 == 0 && seconds >= 60) return works.repka.rysmap.screen.Words.plural(seconds / 60, "rysmap.time.minutes");
        return works.repka.rysmap.screen.Words.plural(seconds, "rysmap.time.seconds");
    }

    /** «2:40». */
    public static String clock(int seconds) {
        return seconds / 60 + ":" + String.format(java.util.Locale.ROOT, "%02d", seconds % 60);
    }

    // ---- строка сообщения карты ----

    public static void message(Message m) {
        message = m;
        messageUntil = System.currentTimeMillis() + 8000;
    }

    /** «Телепорт не прошёл.» и причина. */
    private static Message fail(Component reason) {
        return new Message(Component.translatable("rysmap.tp.fail"), reason);
    }

    public static @Nullable Message message() {
        if (message != null && System.currentTimeMillis() > messageUntil) message = null;
        return message;
    }

    public static void closeMessage() {
        message = null;
    }

    // ---- игроки от сервера ----

    public static void players(MapNet.Players p) {
        Map<UUID, MapNet.PlayerSpot> map = new HashMap<>();
        for (MapNet.PlayerSpot s : p.players()) map.put(s.id(), s);
        players = map;
        playersAt = System.currentTimeMillis();
    }


    /** Игроки от сервера; старше 5 секунд не считаются (сервер замолчал). */
    public static Map<UUID, MapNet.PlayerSpot> players() {
        return System.currentTimeMillis() - playersAt > 5000 ? Map.of() : players;
    }

    public static List<MapNet.PlayerSpot> playerList() {
        return List.copyOf(players().values());
    }

    private static boolean sendToServer(CustomPacketPayload payload) {
        var conn = Minecraft.getInstance().getConnection();
        if (conn == null || !conn.hasChannel(payload.type())) return false;
        PacketDistributor.sendToServer(payload);
        return true;
    }

    /** Оператор меняет правила сервера. */
    public static void setRules(int teleport, int pause, boolean minimap, boolean caves, int playersMode, boolean mobs) {
        sendToServer(new MapNet.SetRules(teleport, pause, minimap, caves, playersMode != 0, mobs, playersMode, rules == null ? 0 : rules.slime() ? 2 : 1));
    }

    /** Оператор включает или выключает слой «Слизни». */
    public static void setSlime(boolean on) {
        MapNet.Rules r = rules;
        if (r == null) return;
        sendToServer(new MapNet.SetRules(r.teleport(), r.pause(), r.minimap(), r.caves(), r.playersMode() != 0, r.mobs(), r.playersMode(), on ? 2 : 1));
    }
}
