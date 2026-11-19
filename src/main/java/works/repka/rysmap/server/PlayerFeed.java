package works.repka.rysmap.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import works.repka.ryscore.team.TeamsApi;
import works.repka.rysmap.net.MapNet;

/**
 * Раз в секунду каждому игроку с RYSMap уходят места других игроков, которых ему можно видеть далеко: участники своей
 * команды всегда, союзники при правиле «Союз» и выше, остальные только при «Все и везде» ({@link MapRules#players}).
 * При «Только рядом» чужих мест сервер не шлёт: клиент рисует тех, кого игра и так ему показывает.
 */
final class PlayerFeed {
    /**
     * Что и когда ушло каждому (разбор логики 19.11, п. 7): тот же список повторно не шлётся, только подтверждение раз в
     * {@link #KEEP_MS} (клиент считает места старше 5 с устаревшими), пустой список один раз.
     */
    private static final java.util.Map<UUID, List<MapNet.PlayerSpot>> LAST = new java.util.HashMap<>();
    private static final java.util.Map<UUID, Long> LAST_AT = new java.util.HashMap<>();
    static final long KEEP_MS = 4000;

    private PlayerFeed() {}

    static void clear() {
        LAST.clear();
        LAST_AT.clear();
    }

    /** Игрок вошёл: первый список уходит ему целиком, даже если он такой же, как до выхода. */
    static void forget(UUID player) {
        LAST.remove(player);
        LAST_AT.remove(player);
    }

    /** Нужно ли слать этот список: изменился, или пора подтвердить непустой. Чистая проверка для тестов. */
    static boolean due(@org.jetbrains.annotations.Nullable List<MapNet.PlayerSpot> last, long lastAt, List<MapNet.PlayerSpot> now, long time) {
        if (last == null || !last.equals(now)) return true;
        return !now.isEmpty() && time - lastAt >= KEEP_MS;
    }

    private static void push(ServerPlayer to, List<MapNet.PlayerSpot> out) {
        long time = System.currentTimeMillis();
        UUID id = to.getUUID();
        if (!due(LAST.get(id), LAST_AT.getOrDefault(id, 0L), out, time)) return;
        LAST.put(id, out);
        LAST_AT.put(id, time);
        MapNet.send(to, new MapNet.Players(out));
    }

    static void send(MinecraftServer server, MapRules rules) {
        List<ServerPlayer> all = server.getPlayerList().getPlayers();
        // ушедшие из памяти
        if (LAST.size() > all.size()) {
            java.util.Set<UUID> online = new java.util.HashSet<>();
            for (ServerPlayer p : all) online.add(p.getUUID());
            LAST.keySet().retainAll(online);
            LAST_AT.keySet().retainAll(online);
        }
        if (all.size() < 2) {
            // одному игроку слать нечего, но пустой список (один раз) гасит старые метки у клиента
            for (ServerPlayer p : all) if (p.connection.hasChannel(MapNet.Players.TYPE)) push(p, List.of());
            return;
        }
        TeamsApi teams = TeamsApi.get();
        UUID[] team = new UUID[all.size()];
        for (int i = 0; i < all.size(); i++) team[i] = teamOf(teams, all.get(i).getUUID());
        java.util.Map<UUID, java.util.Collection<UUID>> allies = new java.util.HashMap<>();
        for (int i = 0; i < all.size(); i++) {
            ServerPlayer to = all.get(i);
            if (!to.connection.hasChannel(MapNet.Players.TYPE)) continue;
            List<MapNet.PlayerSpot> out = new ArrayList<>();
            for (int j = 0; j < all.size(); j++) {
                if (i == j) continue;
                ServerPlayer p = all.get(j);
                if (p.isSpectator() && !to.isSpectator()) continue;
                boolean same = team[i] != null && Objects.equals(team[i], team[j]);
                if (!same) {
                    if (p.isInvisible()) continue;
                    boolean ally = false;
                    if (rules.players == MapRules.Players.ALLIES && team[i] != null && team[j] != null) {
                        java.util.Collection<UUID> a = allies.computeIfAbsent(team[i], t -> {
                            try {
                                return teams.allies(t);
                            } catch (Throwable e) {
                                return java.util.List.of();
                            }
                        });
                        ally = a.contains(team[j]);
                    }
                    if (rules.players != MapRules.Players.ALL && !ally) continue;
                }
                out.add(new MapNet.PlayerSpot(p.getUUID(), p.getGameProfile().getName(), p.level().dimension().location().toString(),
                    p.getBlockX(), p.getBlockY(), p.getBlockZ(), same));
            }
            push(to, out);
        }
    }

    /** Команда игрока; соло (команда из одного или своя UUID) считается без команды. */
    static UUID teamOf(TeamsApi teams, UUID player) {
        try {
            UUID t = teams.teamOf(player);
            if (t == null || t.equals(player)) return null;
            return teams.members(t).size() > 1 ? t : null;
        } catch (Throwable e) {
            return null;
        }
    }
}
