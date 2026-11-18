package works.repka.rysmap.layer;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.map.MapApi;
import works.repka.rysmap.RYSMap;
import works.repka.rysmap.client.ClientConfig;
import works.repka.rysmap.tile.RegionData;

/**
 * Слои карты: чужие из реестра ядра ({@link MapApi#layers()}) с глазом игрока. Метки пересобираются, только когда
 * у слоя растёт {@code revision}; области по чанкам кэшируются на регион 32×32 чанка и тоже по номеру ревизии.
 * Только главный поток. Ошибка чужого слоя пишется в лог один раз и слой пропускается.
 */
public final class Layers {
    /** Чанк области, готовый к рисованию. */
    public record AreaChunk(int chunkX, int chunkZ, int group, int color, MapApi.AreaStyle style, boolean badge) {}

    private record MarkerCache(long revision, String dimension, Collection<MapApi.Marker> markers) {}

    private record AreaCache(long revision, List<AreaChunk> chunks) {}

    private static final Map<ResourceLocation, MarkerCache> MARKERS = new HashMap<>();
    private static final Map<String, Long2ObjectOpenHashMap<AreaCache>> AREAS = new HashMap<>();
    private static final java.util.Set<ResourceLocation> BROKEN = new java.util.HashSet<>();

    private Layers() {}

    public static boolean visible(MapApi.Layer layer) {
        Boolean on = ClientConfig.get().layers.get(layer.id().toString());
        return on != null ? on : layer.onByDefault();
    }

    public static void setVisible(MapApi.Layer layer, boolean on) {
        ClientConfig.get().layers.put(layer.id().toString(), on);
        ClientConfig.get().save();
    }

    /** Слои, которые сейчас видны. */
    public static List<MapApi.Layer> shown() {
        List<MapApi.Layer> out = new ArrayList<>();
        for (MapApi.Layer l : MapApi.layers()) if (!BROKEN.contains(l.id()) && visible(l)) out.add(l);
        return out;
    }

    /** Метки слоя в измерении; при неизменной ревизии из кэша. */
    public static Collection<MapApi.Marker> markers(MapApi.Layer layer, ResourceKey<Level> dimension) {
        try {
            long rev = layer.revision();
            String dim = dimension.location().toString();
            MarkerCache c = MARKERS.get(layer.id());
            if (c == null || c.revision != rev || !c.dimension.equals(dim)) {
                c = new MarkerCache(rev, dim, List.copyOf(layer.markers(dimension)));
                MARKERS.put(layer.id(), c);
            }
            return c.markers;
        } catch (Throwable t) {
            broken(layer, t);
            return List.of();
        }
    }

    /** Чанки областей слоя в регионе 32×32 чанка. */
    public static List<AreaChunk> areas(MapApi.Layer layer, ResourceKey<Level> dimension, int regionX, int regionZ) {
        MapApi.Areas areas;
        try {
            areas = layer.areas();
            if (areas == null) return List.of();
            long rev = areas.revision(dimension, regionX, regionZ);
            String key = layer.id() + "|" + dimension.location();
            Long2ObjectOpenHashMap<AreaCache> map = AREAS.computeIfAbsent(key, k -> new Long2ObjectOpenHashMap<>());
            long rk = RegionData.key(regionX, regionZ);
            AreaCache c = map.get(rk);
            if (c == null || c.revision != rev) {
                List<AreaChunk> list = new ArrayList<>();
                areas.fill(dimension, regionX, regionZ, (cx, cz, group, color, style, badge) -> list.add(new AreaChunk(cx, cz, group, color, style, badge)));
                c = new AreaCache(rev, List.copyOf(list));
                map.put(rk, c);
                if (map.size() > 256) map.clear(); // потолок кэша: окно карты ушло далеко
            }
            return c.chunks;
        } catch (Throwable t) {
            broken(layer, t);
            return List.of();
        }
    }

    /** Подсказка чанка от всех видимых слоёв с областями. */
    public static List<net.minecraft.network.chat.Component> tooltip(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        List<net.minecraft.network.chat.Component> out = new ArrayList<>();
        for (MapApi.Layer l : shown()) {
            try {
                MapApi.Areas a = l.areas();
                if (a == null) continue;
                List<net.minecraft.network.chat.Component> lines = a.tooltip(dimension, chunkX, chunkZ);
                if (lines != null) out.addAll(lines);
            } catch (Throwable t) {
                broken(l, t);
            }
        }
        return out;
    }

    public static Collection<MapApi.AreaLabel> labels(MapApi.Layer layer, ResourceKey<Level> dimension) {
        try {
            MapApi.Areas a = layer.areas();
            return a == null ? List.of() : a.labels(dimension);
        } catch (Throwable t) {
            broken(layer, t);
            return List.of();
        }
    }

    private static void broken(MapApi.Layer layer, Throwable t) {
        if (BROKEN.add(layer.id())) RYSMap.LOG.error("[rysmap] слой {} упал и выключен до перезапуска", layer.id(), t);
    }

    /** Выход из мира: кэши слоёв не нужны. */
    public static void clear() {
        MARKERS.clear();
        AREAS.clear();
    }

    public static @Nullable MapApi.Layer find(ResourceLocation id) {
        for (MapApi.Layer l : MapApi.layers()) if (l.id().equals(id)) return l;
        return null;
    }
}
