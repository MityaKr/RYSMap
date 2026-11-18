package works.repka.rysmap.server;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import works.repka.ryscore.migrate.Snbt;
import works.repka.rysmap.RYSMap;
import works.repka.rysmap.net.MapNet;

/**
 * Серверная часть карты (план 9.1): {@code MapHello} при входе игрока, правила сервера ({@link MapRules}) и их правка
 * оператором, телепорт ({@link Teleports}), раз в секунду места игроков, которых получателю можно видеть. UUID мира живёт в
 * {@code world/data/rysmap.dat} и не меняется, пока жив мир: по нему клиент держит папку карты, хоть сервер и сменит
 * адрес. Старый id FTB берётся из {@code world/ftbteams/ftbteams.snbt}: файл остаётся в мире и после удаления FTB Teams,
 * им названа папка карты FTB Chunks у клиента.
 */
public final class MapServer {
    private static final String NAME = "rysmap";

    private MapServer() {}

    /** UUID мира: создаётся при первом обращении и пишется с миром. */
    public static final class WorldId extends SavedData {
        final UUID id;

        WorldId(UUID id) {
            this.id = id;
        }

        static WorldId load(CompoundTag tag, HolderLookup.Provider registries) {
            return new WorldId(tag.hasUUID("world") ? tag.getUUID("world") : UUID.randomUUID());
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putInt("v", 1);
            tag.putUUID("world", id);
            return tag;
        }
    }

    private static MapRules rules = new MapRules();
    private static int feedTick;

    public static void init() {
        TeamPings.listen();
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer player) {
                hello(player);
                sendRules(player);
            }
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStartingEvent e) -> {
            rules = MapRules.load(rulesFile(e.getServer()));
            Teleports.clear();
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.PermissionsChangedEvent e) -> {
            // событие идёт до смены уровня: правила уходят на следующем тике, когда уровень уже новый
            if (e.getEntity() instanceof ServerPlayer player && player.getServer() != null)
                player.getServer().execute(() -> sendRules(player));
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) -> {
            if (++feedTick >= 20) {
                feedTick = 0;
                PlayerFeed.send(e.getServer(), rules);
            }
        });
    }

    public static MapRules rules() {
        return rules;
    }

    static Path rulesFile(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("serverconfig").resolve("rysmap-server.json");
    }

    /** Оператор (уровень 2): может править правила, телепорт без паузы. */
    public static boolean op(ServerPlayer player) {
        return player.hasPermissions(2);
    }

    /** Правило телепорта пускает игрока. */
    public static boolean canTeleport(ServerPlayer player) {
        return switch (rules.teleport) {
            case OFF -> false;
            case OPS -> op(player);
            case ALL -> true;
        };
    }

    public static void sendRules(ServerPlayer player) {
        MapRules r = rules;
        MapNet.send(player, new MapNet.Rules(r.teleport.ordinal(), r.pause, r.minimap, r.caves, r.players != MapRules.Players.NEVER, r.mobs,
            op(player), canTeleport(player), Teleports.pauseLeft(player), PlayerFeed.teamOf(works.repka.ryscore.team.TeamsApi.get(), player.getUUID()) != null,
            r.players.ordinal()));
    }

    /** Правка правил с клиента: только оператор. Новые правила пишутся в файл и уходят всем. */
    public static void setRules(ServerPlayer player, MapNet.SetRules set) {
        MinecraftServer server = player.getServer();
        if (server == null || !op(player)) {
            RYSMap.LOG.warn("[rysmap] {} пытался изменить правила карты без прав оператора", player.getGameProfile().getName());
            return;
        }
        MapRules r = rules.copy();
        MapRules.Teleport[] modes = MapRules.Teleport.values();
        r.teleport = modes[Math.max(0, Math.min(modes.length - 1, set.teleport()))];
        r.pause = MapRules.clampPause(set.pause());
        r.minimap = set.minimap();
        r.caves = set.caves();
        MapRules.Players[] pm = MapRules.Players.values();
        r.players = pm[Math.max(0, Math.min(pm.length - 1, set.playersMode()))];
        r.mobs = set.mobs();
        rules = r;
        r.save(rulesFile(server));
        RYSMap.LOG.info("[rysmap] {} изменил правила карты: телепорт {}, пауза {} с, миникарта {}, пещеры {}, игроки {}, мобы {}",
            player.getGameProfile().getName(), r.teleport, r.pause, r.minimap, r.caves, r.players, r.mobs);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) sendRules(p);
    }

    public static UUID worldId(MinecraftServer server) {
        WorldId data = server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(() -> {
            WorldId w = new WorldId(UUID.randomUUID());
            w.setDirty();
            return w;
        }, WorldId::load), NAME);
        return data.id;
    }

    /** id сервера FTB или пустая строка: файла нет или он не читается. */
    public static String ftbId(MinecraftServer server) {
        Path file = server.getWorldPath(LevelResource.ROOT).resolve("ftbteams").resolve("ftbteams.snbt");
        if (!Files.isRegularFile(file)) return "";
        try {
            JsonObject o = Snbt.readObject(file);
            return Snbt.string(o, "id", "");
        } catch (Exception e) {
            RYSMap.LOG.warn("[rysmap] {} не прочитался: {}", file, e.toString());
            return "";
        }
    }

    private static void hello(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        MapNet.sendHello(player, new MapNet.Hello(MapNet.VERSION, worldId(server), ftbId(server)));
    }
}
