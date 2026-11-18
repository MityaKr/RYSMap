package works.repka.rysmap.point;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.map.MapApi;
import works.repka.ryscore.ui.Icons;
import works.repka.rysmap.RYSMap;
import works.repka.rysmap.client.ClientEntry;
import works.repka.rysmap.client.MapSession;

/**
 * Свой слой «Точки»: личные точки и места гибели из {@code points.json} текущего мира, клик ведёт путь к точке.
 * Пункты меню карты «Точка здесь» и «В путь сюда». Окна точки (макеты {@code 09}, {@code 15}, {@code 16}) это этап М3;
 * до него точка из меню получает имя по номеру.
 */
public final class PointsLayer implements MapApi.Layer {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(RYSMap.MODID, "points");
    private static final int DEATH_COLOR = 0xFFE5484D;

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public Component name() {
        return Component.translatable("rysmap.layer.points");
    }

    @Override
    public Icons.Shape icon() {
        return Icons.Shape.FLAG;
    }

    @Override
    public int order() {
        return 0;
    }

    @Override
    public long revision() {
        MapSession s = ClientEntry.session();
        return s == null ? -1 : s.points().revision() * 31 + s.hashCode();
    }

    @Override
    public Collection<MapApi.Marker> markers(ResourceKey<Level> dimension) {
        MapSession s = ClientEntry.session();
        if (s == null) return List.of();
        String dim = dimension.location().toString();
        List<MapApi.Marker> out = new ArrayList<>();
        for (PointStore.Point p : s.points().points()) {
            if (!p.dimension.equals(dim)) continue;
            boolean death = p.death();
            int color = death ? DEATH_COLOR : 0xFF000000 | PointStore.PALETTE[Math.floorMod(p.color, PointStore.COLORS)];
            String name = p.name.isEmpty() ? Component.translatable(death ? "rysmap.point.death" : "rysmap.point.unnamed").getString() : p.name;
            out.add(MapApi.Marker.builder(p.id).pos(dimension, p.x + 0.5, p.y, p.z + 0.5)
                .kind(death ? MapApi.Kind.ALERT : MapApi.Kind.POINT).icon(icon(p.icon)).color(color)
                .label(Component.literal(name)).minimap(p.minimap)
                .click(() -> MapApi.get().track(MapApi.Target.builder(dimension, p.x + 0.5, p.y, p.z + 0.5).label(Component.literal(name)).build()))
                .build());
        }
        return out;
    }

    static Icons.Shape icon(String name) {
        return switch (name) {
            case "home" -> Icons.Shape.HOME;
            case "skull" -> Icons.Shape.SKULL;
            case "pick" -> Icons.Shape.PICK;
            case "star" -> Icons.Shape.STAR;
            default -> Icons.Shape.FLAG;
        };
    }

    /** Слой и пункты меню: при запуске клиента. */
    public static void register() {
        MapApi api = MapApi.get();
        api.layer(new PointsLayer());
        api.menu(MapApi.MenuItem.builder(ResourceLocation.fromNamespaceAndPath(RYSMap.MODID, "point_here"), Component.translatable("rysmap.menu.point_here"))
            .icon(Icons.Shape.FLAG).order(0).run(spot -> {
                MapSession s = ClientEntry.session();
                if (s == null) return;
                PointStore.Point p = new PointStore.Point();
                p.dimension = spot.dimension().location().toString();
                p.x = spot.x();
                p.y = spot.y();
                p.z = spot.z();
                p.name = Component.translatable("rysmap.point.numbered", s.points().points().size() + 1).getString();
                p.color = s.points().points().size() % PointStore.COLORS;
                s.points().add(p);
                s.savePoints();
            }).build());
        api.menu(MapApi.MenuItem.builder(ResourceLocation.fromNamespaceAndPath(RYSMap.MODID, "route_here"), Component.translatable("rysmap.menu.route_here"))
            .icon(Icons.Shape.TARGET).order(10)
            .run(spot -> api.track(MapApi.Target.builder(spot.dimension(), spot.x() + 0.5, spot.y(), spot.z() + 0.5).build())).build());
    }

    /** Экран гибели открылся: место гибели там, где стоит игрок (в списке остаются три последних). */
    public static void died() {
        MapSession s = ClientEntry.session();
        LocalPlayer player = Minecraft.getInstance().player;
        if (s == null || player == null) return;
        PointStore.Point p = new PointStore.Point();
        p.kind = "death";
        p.icon = "skull";
        p.dimension = player.level().dimension().location().toString();
        p.x = player.getBlockX();
        p.y = player.getBlockY();
        p.z = player.getBlockZ();
        s.points().add(p);
        s.savePoints();
    }

    static @Nullable ResourceKey<Level> key(String id) {
        ResourceLocation loc = ResourceLocation.tryParse(id);
        return loc == null ? null : ResourceKey.create(Registries.DIMENSION, loc);
    }

    static Path file(MapSession s) {
        return s.store.root().resolve("points.json");
    }
}
