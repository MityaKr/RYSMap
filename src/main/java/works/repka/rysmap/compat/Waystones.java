package works.repka.rysmap.compat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import works.repka.ryscore.map.MapApi;
import works.repka.ryscore.ui.Icons;
import works.repka.rysmap.RYSMap;

/**
 * Камни пути Waystones на карте и миникарте (скептик s2, план 10.8: раньше их рисовал {@code ftb-xmod-compat} на карте
 * FTB). Без зависимости от Waystones: события его клиента слушаются через Balm отражением.
 *
 * <p>Что проверено по CFR (Waystones 21.1.46, Balm 21.0.66): сервер шлёт клиенту известные игроку камни пакетом
 * {@code KnownWaystonesMessage}, клиент поднимает в шине Balm событие {@code WaystonesListReceivedEvent(type, List<Waystone>)}
 * на каждый тип камня, правку одного камня {@code WaystoneUpdateReceivedEvent(Waystone)} и удаление
 * {@code WaystoneRemoveReceivedEvent(type, uuid, destroyed)}; так же их читает связка Waystones с JourneyMap. Подписка:
 * {@code Balm.getEvents().onEvent(Class, Consumer)}. У камня {@code getWaystoneUid}, {@code getEffectiveName},
 * {@code getDimension}, {@code getPos}, {@code getWaystoneType}, {@code isTransient}. Берутся обычные камни и камни
 * общего пользования (sharestone), временные (warp plate) нет, как у JourneyMap.
 *
 * <p>Телепорт через сам Waystones с клиента не открыт (его телепорт идёт из меню камня или предмета с проверками цены и
 * паузы на сервере), поэтому в панели камня «В путь» и телепорт карты по правилу сервера RYSMap.
 */
public final class Waystones {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(RYSMap.MODID, "waystones");
    public static final int COLOR = 0xFF8F7BE8;

    record Stone(UUID id, String name, ResourceKey<Level> dimension, BlockPos pos, String type) {}

    private static final Map<UUID, Stone> STONES = new LinkedHashMap<>();
    private static long revision;
    private static boolean active;
    private static Method uid, name, dim, pos, type, transientM, listType, list, updated, removedId;

    private Waystones() {}

    /** Подписка на события Waystones и слой карты; без Waystones ничего. Клиент, при запуске. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void init() {
        if (!ModList.get().isLoaded("waystones") || !ModList.get().isLoaded("balm")) return;
        try {
            ClassLoader cl = Waystones.class.getClassLoader();
            Class<?> waystone = Class.forName("net.blay09.mods.waystones.api.Waystone", false, cl);
            uid = waystone.getMethod("getWaystoneUid");
            name = waystone.getMethod("getEffectiveName");
            dim = waystone.getMethod("getDimension");
            pos = waystone.getMethod("getPos");
            type = waystone.getMethod("getWaystoneType");
            transientM = waystone.getMethod("isTransient");
            Class<?> listEvent = Class.forName("net.blay09.mods.waystones.api.event.WaystonesListReceivedEvent", false, cl);
            listType = listEvent.getMethod("getWaystoneType");
            list = listEvent.getMethod("getWaystones");
            Class<?> updateEvent = Class.forName("net.blay09.mods.waystones.api.event.WaystoneUpdateReceivedEvent", false, cl);
            updated = updateEvent.getMethod("getWaystone");
            Class<?> removeEvent = Class.forName("net.blay09.mods.waystones.api.event.WaystoneRemoveReceivedEvent", false, cl);
            removedId = removeEvent.getMethod("getWaystoneId");
            Class<?> balm = Class.forName("net.blay09.mods.balm.api.Balm", false, cl);
            Object events = balm.getMethod("getEvents").invoke(null);
            Method onEvent = Class.forName("net.blay09.mods.balm.api.event.BalmEvents", false, cl).getMethod("onEvent", Class.class, Consumer.class);
            onEvent.invoke(events, listEvent, (Consumer) e -> safe(() -> onList(e)));
            onEvent.invoke(events, updateEvent, (Consumer) e -> safe(() -> onUpdate(e)));
            onEvent.invoke(events, removeEvent, (Consumer) e -> safe(() -> onRemove(e)));
            MapApi.get().layer(new Layer());
            active = true;
            RYSMap.LOG.info("[rysmap] камни пути Waystones на карте: связка включена");
        } catch (Throwable t) {
            RYSMap.LOG.warn("[rysmap] связка с Waystones не встала, камней на карте не будет: {}", t.toString());
        }
    }

    public static boolean active() {
        return active;
    }

    private static void safe(Runnable r) {
        Minecraft.getInstance().execute(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                RYSMap.LOG.warn("[rysmap] событие Waystones не разобралось: {}", t.toString());
            }
        });
    }

    private static boolean supported(String t) {
        return t.equals("waystones:waystone") || t.startsWith("waystones:sharestone");
    }

    private static Stone stone(Object w) throws Exception {
        String t = type.invoke(w).toString();
        if (!supported(t) || (boolean) transientM.invoke(w)) return null;
        @SuppressWarnings("unchecked") ResourceKey<Level> d = (ResourceKey<Level>) dim.invoke(w);
        return new Stone((UUID) uid.invoke(w), ((Component) name.invoke(w)).getString(), d, (BlockPos) pos.invoke(w), t);
    }

    private static void onList(Object e) {
        try {
            String t = listType.invoke(e).toString();
            STONES.values().removeIf(s -> s.type().equals(t));
            for (Object w : (List<?>) list.invoke(e)) {
                Stone s = stone(w);
                if (s != null) STONES.put(s.id(), s);
            }
            revision++;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static void onUpdate(Object e) {
        try {
            Object w = updated.invoke(e);
            Stone s = stone(w);
            UUID id = (UUID) uid.invoke(w);
            if (s == null) STONES.remove(id);
            else STONES.put(id, s);
            revision++;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static void onRemove(Object e) {
        try {
            STONES.remove((UUID) removedId.invoke(e));
            revision++;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /** Выход из мира: камни другого мира не нужны. */
    public static void clear() {
        STONES.clear();
        revision++;
    }

    /** Слой «Камни пути». */
    static final class Layer implements MapApi.Layer {
        @Override public ResourceLocation id() { return ID; }
        @Override public Component name() { return Component.translatable("rysmap.layer.waystones"); }
        @Override public Icons.Shape icon() { return Icons.Shape.PORTAL; }
        @Override public int order() { return 3; }
        @Override public long revision() { return revision; }

        @Override
        public Collection<MapApi.Marker> markers(ResourceKey<Level> dimension) {
            List<MapApi.Marker> out = new ArrayList<>();
            for (Stone s : STONES.values()) {
                if (!s.dimension().equals(dimension)) continue;
                double x = s.pos().getX() + 0.5, y = s.pos().getY(), z = s.pos().getZ() + 0.5;
                Component label = Component.literal(s.name().isEmpty() ? "?" : s.name());
                MapApi.Target target = MapApi.Target.builder(dimension, x, y + 1, z).radius(3).label(label).build();
                out.add(MapApi.Marker.builder(s.id()).pos(dimension, x, y, z).kind(MapApi.Kind.POINT).icon(Icons.Shape.PORTAL)
                    .color(COLOR).label(label).note(Component.translatable(s.type().startsWith("waystones:sharestone")
                        ? "rysmap.waystone.sharestone" : "rysmap.waystone.waystone"))
                    .panel(() -> panel(s, label, target)).build());
            }
            return out;
        }

        @Override
        public List<MapApi.Hit> search(String query) {
            List<MapApi.Hit> out = new ArrayList<>();
            String q = query.toLowerCase(java.util.Locale.ROOT);
            for (Stone s : STONES.values()) {
                if (!s.name().toLowerCase(java.util.Locale.ROOT).contains(q)) continue;
                out.add(MapApi.Hit.builder(Component.literal(s.name()),
                    MapApi.Target.builder(s.dimension(), s.pos().getX() + 0.5, s.pos().getY(), s.pos().getZ() + 0.5).build())
                    .icon(Icons.Shape.PORTAL).color(COLOR).build());
            }
            return out;
        }
    }

    private static MapApi.Panel panel(Stone s, Component label, MapApi.Target target) {
        MapApi.Panel.Builder b = MapApi.Panel.builder(label)
            .subtitle(Component.translatable(s.type().startsWith("waystones:sharestone") ? "rysmap.waystone.sharestone" : "rysmap.waystone.waystone"))
            .row(Component.translatable("rysmap.point.coords"), Component.literal(s.pos().getX() + " · " + s.pos().getY() + " · " + s.pos().getZ()))
            .note(Component.translatable("rysmap.waystone.note"))
            .action(MapApi.Action.builder(Component.translatable("rysmap.point.go"), () -> MapApi.get().track(target)).primary(true).build());
        if (works.repka.rysmap.client.ServerRules.teleportAllowed()) {
            b.action(MapApi.Action.builder(Component.translatable("rysmap.point.teleport"),
                () -> works.repka.rysmap.client.ServerRules.teleport(s.dimension(), s.pos().getX(), s.pos().getY() + 1, s.pos().getZ())).build());
        }
        b.action(MapApi.Action.builder(Component.translatable("rysmap.waystone.save"), () -> MapApi.get().newPoint(target)).build());
        return b.build();
    }
}
