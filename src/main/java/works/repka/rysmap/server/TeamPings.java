package works.repka.rysmap.server;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import works.repka.ryscore.team.TeamsApi;
import works.repka.rysmap.RYSMap;
import works.repka.rysmap.net.MapNet;

/**
 * Пинг команде с карты (Т2, план 8): пункт меню «Пинг команде» шлёт серверу место. Сервер отдаёт его RYSTeams
 * ({@link TeamsApi#ping}: лента, подпись в мире у участников, как у пинга клавишей RYSTeams) и сам рассылает
 * участникам команды и союзникам с RYSMap пакет {@link MapNet.PingShow}: метка на миникарте и карте на минуту.
 * Без RYSTeams команды нет и пункта меню тоже.
 */
public final class TeamPings {
    private static final long COOLDOWN_MS = 3000;
    private static final java.util.Set<String> KINDS = java.util.Set.of("here", "danger", "resource", "help");
    private static final Map<UUID, Long> LAST = new HashMap<>();

    private TeamPings() {}

    public static void request(ServerPlayer player, MapNet.Ping ping) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        UUID team = PlayerFeed.teamOf(TeamsApi.get(), player.getUUID());
        if (team == null) return;
        long now = System.currentTimeMillis();
        Long last = LAST.get(player.getUUID());
        if (last != null && now - last < COOLDOWN_MS) return;
        LAST.put(player.getUUID(), now);
        // вид пинга только из известных RYSTeams; чужая строка от клиента становится «здесь» (перепроверка 18.11, пункт 10)
        String kind = KINDS.contains(ping.kind()) ? ping.kind() : "here";
        String by = player.getGameProfile().getName();
        TeamsApi teams = TeamsApi.get();
        try {
            teams.ping(team, ping.dimension(), ping.x(), ping.y(), ping.z(), kind, by);
        } catch (Throwable e) {
            RYSMap.LOG.warn("[rysmap] ping to RYSTeams failed: {}", e.toString());
        }
        int color;
        try {
            color = teams.color(team) | 0xFF000000;
        } catch (Throwable e) {
            color = 0xFF4FA3D9;
        }
        Set<UUID> targets = new LinkedHashSet<>(teams.members(team));
        try {
            for (UUID ally : teams.allies(team)) targets.addAll(teams.members(ally));
        } catch (Throwable ignored) {
            // союзов нет
        }
        MapNet.PingShow show = new MapNet.PingShow(player.getUUID(), by, ping.dimension(), ping.x(), ping.y(), ping.z(), kind, color);
        for (UUID id : targets) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) MapNet.send(p, show);
        }
    }

    /** Состав команд меняется: правила (флаг «в команде») уходят всем заново. */
    public static void listen() {
        TeamsApi.listen(new TeamsApi.Listener() {
            @Override public void onJoined(UUID team, UUID player, boolean carryProgress) { resend(); }
            @Override public void onLeft(UUID team, UUID player, boolean keepCopy) { resend(); }
            @Override public void onDisbanded(UUID team) { resend(); }
            @Override public void onMerged(UUID from, UUID into) { resend(); }
        });
    }

    private static void resend() {
        MinecraftServer server = works.repka.ryscore.RYSCore.server();
        if (server == null) return;
        server.execute(() -> {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) MapServer.sendRules(p);
        });
    }
}
