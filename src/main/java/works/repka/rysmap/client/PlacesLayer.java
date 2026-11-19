package works.repka.rysmap.client;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.map.MapApi;
import works.repka.ryscore.ui.Icons;
import works.repka.rysmap.RYSMap;
import works.repka.rysmap.places.PlaceRules;
import works.repka.rysmap.point.PointStore;
import works.repka.rysmap.point.PointsLayer;
import works.repka.rysmap.screen.Words;

/**
 * Слой «Места» (макет {@code 04}, план Essentials 4.2): свои дома, дом команды, места сервера. Это не копии точек: одно
 * место видно на карте, в колесе и в командах чата. Клик открывает панель места: «Телепорт» главной (бесплатно, с паузой),
 * «В путь», у своего дома и у места сервера для модератора «Сюда» (переставить туда, где стоишь) и «Удалить», у места
 * сервера «Изменить». Пункты меню ПКМ «Дом здесь» и «Место сервера здесь» (модератору).
 */
public final class PlacesLayer implements MapApi.Layer {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(RYSMap.MODID, "places");
    public static final ResourceLocation HOME_HERE = ResourceLocation.fromNamespaceAndPath(RYSMap.MODID, "home_here");
    public static final ResourceLocation PLACE_HERE = ResourceLocation.fromNamespaceAndPath(RYSMap.MODID, "place_here");
    public static final int HOME_COLOR = 0xFF6FA8FF, TEAM_COLOR = 0xFFE58A6A;

    /** Окно карты: открыть правку места сервера (его показывает {@code MapScreen}). */
    public interface Editor {
        void editPlace(PlacesClient.@Nullable Place place, ResourceKey<Level> dim, int x, int y, int z);
    }

    private static @Nullable Editor editor;

    public static void editor(@Nullable Editor e) {
        editor = e;
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public Component name() {
        return Component.translatable("rysmap.layer.places");
    }

    @Override
    public Icons.Shape icon() {
        return Icons.Shape.HOME;
    }

    @Override
    public int order() {
        return -10;
    }

    @Override
    public long revision() {
        return PlacesClient.revision();
    }

    @Override
    public Collection<MapApi.Marker> markers(ResourceKey<Level> dimension) {
        PlacesClient.Data d = PlacesClient.data();
        if (!d.present) return List.of();
        String dim = dimension.location().toString();
        List<MapApi.Marker> out = new ArrayList<>();
        for (PlacesClient.Place p : all(d)) if (p.dim().equals(dim)) out.add(marker(p, dimension));
        return out;
    }

    /** Поиск окна карты ({@code /}): дома, дом команды, места сервера по части имени или описания. */
    @Override
    public List<MapApi.Hit> search(String query) {
        PlacesClient.Data d = PlacesClient.data();
        String q = query.strip().toLowerCase(java.util.Locale.ROOT);
        if (!d.present || q.isEmpty()) return List.of();
        List<MapApi.Hit> out = new ArrayList<>();
        for (PlacesClient.Place p : all(d)) {
            String name = name(p);
            if (!name.toLowerCase(java.util.Locale.ROOT).contains(q) && !p.note().toLowerCase(java.util.Locale.ROOT).contains(q)) continue;
            String kind = Component.translatable(switch (p.kind()) {
                case "home" -> "rysmap.place.panel.home";
                case "team_home" -> "rysmap.place.team_home";
                default -> "rysmap.place.panel.place";
            }).getString();
            out.add(MapApi.Hit.builder(Component.literal(name), MapApi.Target.builder(p.key(), p.x(), p.y(), p.z()).label(Component.literal(name)).build())
                .note(Component.literal(kind)).icon(icon(p)).color(color(p)).build());
        }
        return out;
    }

    /** Дома, дом команды, места сервера. */
    public static List<PlacesClient.Place> all(PlacesClient.Data d) {
        List<PlacesClient.Place> out = new ArrayList<>(d.homes);
        if (d.teamHome != null) out.add(d.teamHome);
        out.addAll(d.places);
        return out;
    }

    public static MapApi.Marker marker(PlacesClient.Place p) {
        return marker(p, p.key());
    }

    static MapApi.Marker marker(PlacesClient.Place p, ResourceKey<Level> dimension) {
        return MapApi.Marker.builder(id(p)).pos(dimension, p.x(), p.y(), p.z()).kind(MapApi.Kind.POINT).icon(icon(p)).color(color(p))
            .label(Component.literal(name(p))).minimap(true).panel(() -> panel(p)).build();
    }

    public static UUID id(PlacesClient.Place p) {
        return UUID.nameUUIDFromBytes(("rysmap:" + p.kind() + ":" + p.name().toLowerCase(java.util.Locale.ROOT)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Подпись: дом без имени «Дом», дом команды «Дом команды», место сервера по имени. */
    public static String name(PlacesClient.Place p) {
        return switch (p.kind()) {
            case "home" -> p.name().isEmpty() || p.name().equalsIgnoreCase("home") ? Component.translatable("rysmap.place.home").getString() : p.name();
            case "team_home" -> Component.translatable("rysmap.place.team_home").getString();
            case "spawn" -> Component.translatable("rysmap.place.spawn").getString();
            case "bed" -> Component.translatable("rysmap.place.bed").getString();
            case "back" -> p.death() ? Component.translatable("rysmap.place.death").getString()
                : Component.translatable("rysmap.place.back_from", reasonName(p.reason())).getString();
            default -> p.name();
        };
    }

    static String reasonName(String reason) {
        String key = "rysmap.reason." + reason;
        String s = Component.translatable(key).getString();
        return s.equals(key) ? reason : s;
    }

    public static Icons.Shape icon(PlacesClient.Place p) {
        return switch (p.kind()) {
            case "home" -> Icons.Shape.HOME;
            case "team_home" -> Icons.Shape.USERS;
            case "back" -> p.death() ? Icons.Shape.SKULL : Icons.Shape.HISTORY;
            case "spawn" -> Icons.Shape.FLAG;
            case "bed" -> Icons.Shape.BED;
            default -> p.icon().equals("flag") ? Icons.Shape.SERVER : PointsLayer.icon(p.icon());
        };
    }

    public static int color(PlacesClient.Place p) {
        return switch (p.kind()) {
            case "home" -> HOME_COLOR;
            case "team_home" -> TEAM_COLOR;
            case "back" -> p.death() ? PointsLayer.DEATH_COLOR : 0xFF9AA3B5;
            default -> p.color() >= 0 ? 0xFF000000 | PointStore.PALETTE[Math.floorMod(p.color(), PointStore.COLORS)] : dimensionColor(p.dim());
        };
    }

    /** Цвет измерения для мест без своего цвета: Верхний мир светлый, Нижний оранжевый, Край сиреневый. */
    public static int dimensionColor(String dim) {
        return switch (dim) {
            case "minecraft:the_nether" -> 0xFFE58A6A;
            case "minecraft:the_end" -> 0xFFB78CF0;
            default -> 0xFFE6E9EF;
        };
    }

    /** Функция правил для вида места. */
    public static PlaceRules.Fn fn(PlacesClient.Place p) {
        return switch (p.kind()) {
            case "home" -> PlaceRules.Fn.HOME;
            case "team_home" -> PlaceRules.Fn.TEAM_HOME;
            case "back" -> PlaceRules.Fn.BACK;
            case "spawn", "bed" -> PlaceRules.Fn.SPAWN;
            default -> PlaceRules.Fn.PLACE;
        };
    }

    /** Телепорт к месту. */
    public static void teleport(PlacesClient.Place p) {
        switch (p.kind()) {
            case "home" -> PlacesClient.op("home", PlacesClient.obj("name", p.name()));
            case "team_home" -> PlacesClient.op("teamhome", new JsonObject());
            case "back" -> {
                JsonObject o = new JsonObject();
                o.addProperty("index", p.index());
                PlacesClient.op("back", o);
            }
            case "spawn" -> PlacesClient.op("spawn", new JsonObject());
            case "bed" -> PlacesClient.op("bed", new JsonObject());
            default -> PlacesClient.op("warp", PlacesClient.obj("name", p.name()));
        }
    }

    /** Почему телепорт сейчас нельзя (для кнопки и колеса) или null. */
    public static @Nullable String blocked(PlacesClient.Place p) {
        PlacesClient.Data d = PlacesClient.data();
        if (!d.present) return Component.translatable("rysmap.place.no_server").getString();
        PlaceRules.Fn fn = fn(p);
        if (!d.allowed(fn)) return Component.translatable("rysmap.travel.off").getString();
        if (d.pvpLeft() > 0) return Component.translatable("rysmap.travel.pvp", d.pvpLeft()).getString();
        if (p.lock() != null && !p.death()) return Component.translatable("rysmap.travel.claimed", p.lock()).getString();
        return null;
    }

    /** «Телепорт» или «Телепорт через 0:24». */
    public static String teleportLabel(PlacesClient.Place p) {
        int left = PlacesClient.data().left(fn(p));
        return left > 0 ? Component.translatable("rysmap.place.teleport_in", PlacesClient.clock(left)).getString()
            : Component.translatable("rysmap.place.teleport").getString();
    }

    static boolean canEdit(PlacesClient.Place p) {
        PlacesClient.Data d = PlacesClient.data();
        return switch (p.kind()) {
            case "home" -> true;
            case "team_home" -> d.teamHomePerm;
            case "place" -> d.manage;
            default -> false;
        };
    }

    /** Панель места (макет {@code 04}). */
    static MapApi.Panel panel(PlacesClient.Place p) {
        PlacesClient.Data d = PlacesClient.data();
        String subtitle = switch (p.kind()) {
            case "home" -> Component.translatable("rysmap.place.panel.home").getString();
            case "team_home" -> d.teamName.isEmpty() ? Component.translatable("rysmap.place.team_home").getString()
                : Component.translatable("rysmap.place.panel.team", d.teamName).getString();
            default -> p.by().isEmpty() ? Component.translatable("rysmap.place.panel.place").getString()
                : Component.translatable("rysmap.place.panel.place_by", p.by()).getString();
        };
        MapApi.Panel.Builder b = MapApi.Panel.builder(Component.literal(name(p))).subtitle(Component.literal(subtitle));
        if (!p.note().isEmpty()) b.text(Component.literal(p.note()));
        b.row(Component.translatable("rysmap.point.coords"), Component.literal((int) Math.floor(p.x()) + " · " + (int) Math.floor(p.y()) + " · " + (int) Math.floor(p.z())));
        LocalPlayer me = Minecraft.getInstance().player;
        if (me != null && me.level().dimension().location().toString().equals(p.dim())) {
            double dx = p.x() - me.getX(), dz = p.z() - me.getZ();
            b.row(Component.translatable("rysmap.place.panel.from_here"), Component.literal(Words.distance(Math.sqrt(dx * dx + dz * dz)) + " · " + Words.direction(dx, dz)));
        }
        b.row(Component.translatable("rysmap.point.dimension"), Component.literal(works.repka.rysmap.screen.MapScreen.dimensionName(p.dim())));
        int[] pair = works.repka.rysmap.point.Portal.toOther(p.dim(), (int) Math.floor(p.x()), (int) Math.floor(p.z()));
        if (pair != null) b.row(Component.translatable(p.dim().equals(works.repka.rysmap.point.Portal.NETHER) ? "rysmap.point.in_overworld" : "rysmap.point.in_nether"),
            Component.literal(works.repka.rysmap.hud.Minimap.group(pair[0]) + " · " + works.repka.rysmap.hud.Minimap.group(pair[1])));
        String why = blocked(p);
        int pause = d.rules.row(fn(p)).pause;
        String tp = why != null ? why : pause > 0 ? Component.translatable("rysmap.place.panel.pause", pause).getString()
            : Component.translatable("rysmap.place.panel.free").getString();
        b.row(Component.translatable("rysmap.place.panel.teleport"), Component.literal(tp));
        if (p.kind().equals("place") && d.manage)
            b.row(Component.translatable("rysmap.place.panel.vis"), Component.translatable("rysmap.place.vis." + p.vis()));
        ResourceLocation action = PlaceActions.actionId(p);
        if (action != null) {
            var bind = works.repka.ryscore.action.Actions.binding(action);
            b.row(Component.translatable("rysmap.place.panel.key"), Component.literal(bind == null ? Component.translatable("rysmap.place.panel.no_key").getString() : bind.label()));
        }
        if (p.lock() != null) b.note(Component.translatable("rysmap.place.panel.locked", p.lock()));
        if (why == null)
            b.action(MapApi.Action.builder(Component.literal(teleportLabel(p)), () -> teleport(p)).icon(Icons.Shape.TELEPORT).primary(true).build());
        b.action(MapApi.Action.builder(Component.translatable("rysmap.place.route"), () -> MapApi.get().track(
            MapApi.Target.builder(p.key(), p.x(), p.y(), p.z()).label(Component.literal(name(p))).build())).icon(Icons.Shape.TARGET).build());
        if (p.kind().equals("place") && d.manage && editor != null)
            b.action(MapApi.Action.builder(Component.translatable("rysmap.place.edit"), () -> editor.editPlace(p, p.key(), (int) p.x(), (int) p.y(), (int) p.z()))
                .icon(Icons.Shape.PENCIL).build());
        if (p.kind().equals("place") && d.manage) {
            // описание правится строкой чата, «кто видит» по кругу: все, модераторы, операторы
            b.action(MapApi.Action.builder(Component.translatable("rysmap.place.note"), () -> Minecraft.getInstance().setScreen(
                new net.minecraft.client.gui.screens.ChatScreen(PlacesClient.cmd("warpnote") + " " + works.repka.rysmap.places.PlaceNames.quote(p.name()) + " " + p.note())))
                .icon(Icons.Shape.NOTE).build());
            String next = switch (p.vis()) {
                case "all" -> "moderators";
                case "moderators" -> "operators";
                default -> "all";
            };
            b.action(MapApi.Action.builder(Component.translatable("rysmap.place.vis_button", Component.translatable("rysmap.place.vis." + p.vis())),
                () -> {
                    JsonObject e = new JsonObject();
                    e.addProperty("name", p.name());
                    e.addProperty("newName", p.name());
                    e.addProperty("icon", p.icon());
                    e.addProperty("color", p.color());
                    e.addProperty("note", p.note());
                    e.addProperty("vis", next);
                    PlacesClient.op("editwarp", e);
                }).icon(Icons.Shape.EYE).build());
        }
        if (canEdit(p)) {
            b.action(MapApi.Action.builder(Component.translatable("rysmap.place.move_here"), () -> moveHere(p)).icon(Icons.Shape.MOVE).build());
            b.action(MapApi.Action.builder(Component.translatable("rysmap.place.delete"), () -> delete(p)).icon(Icons.Shape.TRASH).build());
        }
        if (p.kind().equals("back")) {
            b.action(MapApi.Action.builder(Component.translatable("rysmap.place.forget"), () -> {
                JsonObject o = new JsonObject();
                o.addProperty("index", p.index());
                PlacesClient.op("delback", o);
            }).icon(Icons.Shape.TRASH).build());
        }
        return b.build();
    }

    /** «Сюда»: переставить место туда, где стоит игрок. */
    static void moveHere(PlacesClient.Place p) {
        switch (p.kind()) {
            case "home" -> PlacesClient.op("sethome", PlacesClient.obj("name", p.name()));
            case "team_home" -> PlacesClient.op("setteamhome", new JsonObject());
            default -> PlacesClient.op("setwarp", PlacesClient.obj("name", p.name()));
        }
    }

    static void delete(PlacesClient.Place p) {
        switch (p.kind()) {
            case "home" -> PlacesClient.op("delhome", PlacesClient.obj("name", p.name()));
            case "team_home" -> PlacesClient.op("delteamhome", new JsonObject());
            default -> PlacesClient.op("delwarp", PlacesClient.obj("name", p.name()));
        }
    }

    /** Слой и пункты меню: при запуске клиента. */
    public static void register() {
        MapApi api = MapApi.get();
        api.layer(new PlacesLayer());
        api.menu(MapApi.MenuItem.builder(HOME_HERE, Component.translatable("rysmap.menu.home_here")).icon(Icons.Shape.HOME).order(5)
            .visible(s -> PlacesClient.data().present && PlacesClient.data().allowed(PlaceRules.Fn.HOME))
            .run(s -> {
                PlacesClient.Data d = PlacesClient.data();
                JsonObject o = PlacesClient.at(s.dimension(), s.x() + 0.5, s.y(), s.z() + 0.5);
                o.remove("y"); // высоты с карты нет: сервер посадит на поверхность
                if (!d.homes.isEmpty() && d.rules.homes <= 1) o.addProperty("name", d.homes.get(0).name());
                PlacesClient.op("sethome", o);
            }).build());
        api.menu(MapApi.MenuItem.builder(PLACE_HERE, Component.translatable("rysmap.menu.place_here")).icon(Icons.Shape.SERVER).order(6)
            .visible(s -> PlacesClient.data().present && PlacesClient.data().manage)
            .run(s -> {
                if (editor != null) editor.editPlace(null, s.dimension(), s.x(), s.y(), s.z());
            }).build());
    }
}
