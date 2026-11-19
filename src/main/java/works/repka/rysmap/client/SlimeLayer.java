package works.repka.rysmap.client;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.map.MapApi;
import works.repka.ryscore.ui.Icons;
import works.repka.rysmap.RYSMap;
import works.repka.rysmap.net.MapNet;

/**
 * Слой «Слизни» (план карты, раздел 5 «Потом»; 0.2.3): чанки, где под Y 40 появляются слизни, штриховкой. Сид мира знает
 * только сервер: регион 32×32 чанка клиент просит пакетом {@link MapNet.SlimeAsk} при первом показе и держит ответ до
 * выхода из мира. По умолчанию слой выключен; без серверной части RYSMap 0.2.3 или при запрете оператора пуст.
 */
public final class SlimeLayer implements MapApi.Layer, MapApi.Areas {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(RYSMap.MODID, "slime");
    private static final int COLOR = 0xFF6FCF8F;
    private static final Map<String, long[]> REGIONS = new HashMap<>();
    /** Когда регион просили, мс: без ответа (сервер срезал частоту) просьба повторяется через 3 с. */
    private static final Map<String, Long> ASKED = new HashMap<>();
    private static long revision;

    static String key(String dim, int rx, int rz) {
        return dim + "|" + rx + "|" + rz;
    }

    /** Бит чанка {@code (x, z)} региона, координаты внутри региона 0–31. */
    public static boolean slime(long[] bits, int x, int z) {
        int i = z * 32 + x;
        return (bits[i >> 6] >>> (i & 63) & 1L) != 0;
    }

    public static void accept(MapNet.SlimeRegion r) {
        REGIONS.put(key(r.dimension(), r.regionX(), r.regionZ()), r.bits());
        revision++;
    }

    public static void clear() {
        REGIONS.clear();
        ASKED.clear();
        revision++;
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public Component name() {
        return Component.translatable("rysmap.layer.slime");
    }

    @Override
    public Icons.Shape icon() {
        return Icons.Shape.CUBE;
    }

    @Override
    public int order() {
        return 60;
    }

    @Override
    public boolean onByDefault() {
        return false;
    }

    @Override
    public long revision() {
        return revision;
    }

    @Override
    public Collection<MapApi.Marker> markers(ResourceKey<Level> dimension) {
        return List.of();
    }

    @Override
    public MapApi.Areas areas() {
        return this;
    }

    @Override
    public long revision(ResourceKey<Level> dimension, int regionX, int regionZ) {
        long[] bits = REGIONS.get(key(dimension.location().toString(), regionX, regionZ));
        if (!ServerRules.slimeAllowed()) return -1;
        // нет ответа: раз в 3 с регион перезаполняется и просьба уходит снова
        return bits == null ? -2 - System.currentTimeMillis() / 3000 : 1;
    }

    @Override
    public void fill(ResourceKey<Level> dimension, int regionX, int regionZ, MapApi.AreaSink sink) {
        if (!ServerRules.slimeAllowed()) return;
        String dim = dimension.location().toString();
        long[] bits = REGIONS.get(key(dim, regionX, regionZ));
        if (bits == null) {
            ask(dim, regionX, regionZ);
            return;
        }
        for (int z = 0; z < 32; z++)
            for (int x = 0; x < 32; x++)
                if (slime(bits, x, z)) sink.chunk(regionX * 32 + x, regionZ * 32 + z, 1, COLOR, MapApi.AreaStyle.HATCH, false);
    }

    @Override
    public @Nullable List<Component> tooltip(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        long[] bits = REGIONS.get(key(dimension.location().toString(), Math.floorDiv(chunkX, 32), Math.floorDiv(chunkZ, 32)));
        if (bits == null || !slime(bits, Math.floorMod(chunkX, 32), Math.floorMod(chunkZ, 32))) return null;
        return List.of(Component.translatable("rysmap.slime.tip"));
    }

    private static void ask(String dim, int rx, int rz) {
        String k = key(dim, rx, rz);
        long now = System.currentTimeMillis();
        Long at = ASKED.get(k);
        if (at != null && now - at < 3000) return;
        ASKED.put(k, now);
        var conn = Minecraft.getInstance().getConnection();
        if (conn == null || !conn.hasChannel(MapNet.SlimeAsk.TYPE)) return;
        PacketDistributor.sendToServer(new MapNet.SlimeAsk(dim, rx, rz));
    }
}
