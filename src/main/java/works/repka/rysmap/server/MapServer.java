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
 * Серверная часть карты (план 9.1, 13.2): только {@code MapHello} при входе игрока. UUID мира живёт в
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

    public static void init() {
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer player) hello(player);
        });
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
