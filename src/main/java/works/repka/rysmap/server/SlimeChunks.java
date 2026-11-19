package works.repka.rysmap.server;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import works.repka.rysmap.net.MapNet;

/**
 * Чанки слизней для слоя карты (план карты, раздел 5 «Потом»; 0.2.3). Клиент просит регион 32×32 чанка, сервер считает
 * той же формулой, что игра при появлении слизня ({@code Slime.checkSlimeSpawnRules}: {@code WorldgenRandom.seedSlimeChunk}
 * с солью 987234911, {@code nextInt(10) == 0}), и отвечает битами. Сид мира клиенту не уходит. Оператор выключает слой
 * правилом {@link MapRules#slime}. Не чаще 40 регионов в секунду на игрока.
 */
public final class SlimeChunks {
    private static final long SALT = 987234911L;
    private static final int PER_SECOND = 40;
    private static final Map<UUID, long[]> RATE = new HashMap<>();

    private SlimeChunks() {}

    public static void ask(ServerPlayer player, MapNet.SlimeAsk a) {
        if (!MapServer.rules().slime) return;
        long now = System.currentTimeMillis() / 1000;
        long[] r = RATE.computeIfAbsent(player.getUUID(), k -> new long[2]);
        if (r[0] != now) {
            r[0] = now;
            r[1] = 0;
        }
        if (++r[1] > PER_SECOND) return;
        ResourceLocation loc = ResourceLocation.tryParse(a.dimension());
        ServerLevel level = loc == null ? null : player.server.getLevel(ResourceKey.create(Registries.DIMENSION, loc));
        if (level == null) return;
        MapNet.send(player, new MapNet.SlimeRegion(a.dimension(), a.regionX(), a.regionZ(), bits(level.getSeed(), a.regionX(), a.regionZ())));
    }

    /** Биты региона: бит {@code z * 32 + x} у чанка слизней. */
    static long[] bits(long seed, int regionX, int regionZ) {
        long[] out = new long[16];
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                int cx = regionX * 32 + x, cz = regionZ * 32 + z;
                if (WorldgenRandom.seedSlimeChunk(cx, cz, seed, SALT).nextInt(10) == 0) {
                    int i = z * 32 + x;
                    out[i >> 6] |= 1L << (i & 63);
                }
            }
        }
        return out;
    }

    static void clear() {
        RATE.clear();
    }
}
