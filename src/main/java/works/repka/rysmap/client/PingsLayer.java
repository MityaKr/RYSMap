package works.repka.rysmap.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import works.repka.ryscore.map.MapApi;
import works.repka.ryscore.ui.Icons;
import works.repka.rysmap.RYSMap;
import works.repka.rysmap.net.MapNet;

/**
 * Слой «Пинги» (Т2): пинги участников команды и союзников, пришедшие с сервера RYSMap, висят на миникарте и карте
 * минуту, за краем миникарты прижаты к рамке, расходятся кольцом. Подпись в мире у пинга рисует RYSTeams (сервер
 * отдал ему тот же пинг), поэтому здесь её нет: две подписи на одном месте не нужны. Только главный поток.
 */
public final class PingsLayer implements MapApi.Layer {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(RYSMap.MODID, "pings");
    private static final long LIFE_MS = 60_000;
    private static final List<Entry> PINGS = new ArrayList<>();
    private static long revision;

    private record Entry(MapNet.PingShow ping, long at, UUID id) {}

    public static void register() {
        MapApi.get().layer(new PingsLayer());
    }

    public static void add(MapNet.PingShow p) {
        PINGS.removeIf(e -> e.ping().by().equals(p.by())); // у игрока один пинг: новый заменяет прежний
        PINGS.add(new Entry(p, System.currentTimeMillis(), UUID.randomUUID()));
        revision++;
    }

    public static void clear() {
        PINGS.clear();
        revision++;
    }

    @Override public ResourceLocation id() { return ID; }
    @Override public Component name() { return Component.translatable("rysmap.layer.pings"); }
    @Override public Icons.Shape icon() { return Icons.Shape.BELL; }
    @Override public int order() { return 4; }

    @Override
    public long revision() {
        long now = System.currentTimeMillis();
        if (PINGS.removeIf(e -> now - e.at() > LIFE_MS)) revision++;
        return revision;
    }

    @Override
    public Collection<MapApi.Marker> markers(ResourceKey<Level> dimension) {
        String dim = dimension.location().toString();
        List<MapApi.Marker> out = new ArrayList<>();
        for (Entry e : PINGS) {
            MapNet.PingShow p = e.ping();
            if (!p.dimension().equals(dim)) continue;
            Component kind = Component.translatable("rysmap.ping.kind." + p.kind());
            out.add(MapApi.Marker.builder(e.id()).pos(dimension, p.x() + 0.5, p.y(), p.z() + 0.5).kind(MapApi.Kind.PING)
                .icon(Icons.Shape.BELL).color(p.color()).label(Component.literal(p.name())).note(kind)
                .click(() -> MapApi.get().track(MapApi.Target.builder(dimension, p.x() + 0.5, p.y(), p.z() + 0.5)
                    .label(Component.literal(p.name())).build()))
                .build());
        }
        return out;
    }
}
