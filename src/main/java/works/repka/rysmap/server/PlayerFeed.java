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
 * Раз в секунду каждому игроку с RYSMap уходят места других игроков, которых ему можно видеть: участники своей команды
 * всегда, остальные только при правиле {@link MapRules#players}. Так карта показывает и тех, кто далеко или в другом
 * измерении. Рядом стоящих клиент рисует по своим данным, плавно.
 */
final class PlayerFeed {
    private PlayerFeed() {}

    static void send(MinecraftServer server, MapRules rules) {
        List<ServerPlayer> all = server.getPlayerList().getPlayers();
        if (all.size() < 2) {
            // одному игроку слать нечего, но пустой список гасит старые метки у клиента
            for (ServerPlayer p : all) MapNet.send(p, new MapNet.Players(List.of()));
            return;
        }
        TeamsApi teams = TeamsApi.get();
        UUID[] team = new UUID[all.size()];
        for (int i = 0; i < all.size(); i++) team[i] = teamOf(teams, all.get(i).getUUID());
        for (int i = 0; i < all.size(); i++) {
            ServerPlayer to = all.get(i);
            if (!to.connection.hasChannel(MapNet.Players.TYPE)) continue;
            List<MapNet.PlayerSpot> out = new ArrayList<>();
            for (int j = 0; j < all.size(); j++) {
                if (i == j) continue;
                ServerPlayer p = all.get(j);
                if (p.isSpectator() && !to.isSpectator()) continue;
                boolean same = team[i] != null && Objects.equals(team[i], team[j]);
                if (!same && (!rules.players || p.isInvisible())) continue;
                out.add(new MapNet.PlayerSpot(p.getUUID(), p.getGameProfile().getName(), p.level().dimension().location().toString(),
                    p.getBlockX(), p.getBlockY(), p.getBlockZ(), same));
            }
            MapNet.send(to, new MapNet.Players(out));
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
