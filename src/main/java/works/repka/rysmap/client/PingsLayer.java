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
        for (Entry e : PINGS) if (e.ping().by().equals(p.by())) works.repka.ryscore.world.Labels.removePing(e.id());
        PINGS.removeIf(e -> e.ping().by().equals(p.by())); // у игрока один пинг: новый заменяет прежний
        Entry entry = new Entry(p, System.currentTimeMillis(), UUID.randomUUID());
        PINGS.add(entry);
        revision++;
        // в мире пинг рисуется подписью с кольцом через ядро (1.15): тот же срок, что на карте
        ResourceLocation dim = ResourceLocation.tryParse(p.dimension());
        if (dim != null) works.repka.ryscore.world.Labels.ping(works.repka.ryscore.world.Labels.Label.builder(entry.id())
            .pos(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dim), p.x() + 0.5, p.y() + 1.0, p.z() + 0.5)
            .kind(works.repka.ryscore.world.Labels.Kind.PING).icon(Icons.Shape.BELL).color(p.color())
            .text(Component.literal(p.name() + " · " + Component.translatable("rysmap.ping.kind." + p.kind()).getString()))
            .ring(true).lifetime(LIFE_MS / 1000.0).build());
    }

    public static void clear() {
        for (Entry e : PINGS) works.repka.ryscore.world.Labels.removePing(e.id());
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
        // пинги других модов через ядро (Labels.ping, 1.15): ревизия по их набору
        long foreign = 0;
        for (works.repka.ryscore.world.Labels.Label l : works.repka.ryscore.world.Labels.pings()) foreign = foreign * 31 + l.id().hashCode();
        return revision * 1_000_003L + foreign;
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
        // пинги других модов через ядро; свои уже выше
        java.util.Set<UUID> own = new java.util.HashSet<>();
        for (Entry e : PINGS) own.add(e.id());
        for (works.repka.ryscore.world.Labels.Label l : works.repka.ryscore.world.Labels.pings()) {
            if (own.contains(l.id()) || !l.dimension().equals(dimension)) continue;
            out.add(MapApi.Marker.builder(l.id()).pos(dimension, l.x(), l.y(), l.z()).kind(MapApi.Kind.PING)
                .icon(l.icon() != null ? l.icon() : Icons.Shape.BELL).color(l.color()).label(l.text())
                .click(() -> MapApi.get().track(MapApi.Target.builder(dimension, l.x(), l.y(), l.z()).label(l.text()).build()))
                .build());
        }
        return out;
    }
}
