package works.repka.rysmap.client;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.action.Action;
import works.repka.ryscore.action.Actions;
import works.repka.ryscore.team.TeamCards;
import works.repka.ryscore.ui.Icons;
import works.repka.rysmap.RYSMap;
import works.repka.rysmap.places.PlaceRules;
import works.repka.rysmap.screen.MapScreen;
import works.repka.rysmap.screen.Words;

/**
 * Места в колесе ядра и на своих клавишах (макеты {@code 01}, {@code 02}, план Essentials 3 и 4.2). Ядро ждёт в кольце
 * «Моё» {@code rysmap:home}, {@code back}, {@code spawn}, {@code places}, {@code team_home}, {@code players}; ещё есть
 * {@code bed} и {@code rtp} для своих клавиш. Каждое место сервера и каждый дом отдельным действием
 * ({@code rysmap:place/<имя>}, {@code rysmap:home/<имя>}): на него вешают клавишу. Состояние считается из последнего пакета
 * мест, быстро. Карточка «Дом команды» на экране команды.
 */
public final class PlaceActions {
    private PlaceActions() {}

    static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(RYSMap.MODID, path);
    }

    /** Часть id из имени места: как есть, если годится, иначе байты UTF-8 шестнадцатерично (стабильно для клавиш). */
    static String slug(String name) {
        String k = name.toLowerCase(java.util.Locale.ROOT);
        if (!k.isEmpty() && k.matches("[a-z0-9._-]+")) return k;
        StringBuilder b = new StringBuilder("x");
        for (byte x : k.getBytes(java.nio.charset.StandardCharsets.UTF_8)) b.append(String.format("%02x", x & 0xFF));
        return b.toString();
    }

    /** Действие места для клавиши (место сервера, дом) или null. */
    static @Nullable ResourceLocation actionId(PlacesClient.Place p) {
        return switch (p.kind()) {
            case "place" -> id("place/" + slug(p.name()));
            case "home" -> PlacesClient.data().homes.size() > 1 ? id("home/" + slug(p.name())) : id("home");
            case "team_home" -> id("team_home");
            default -> null;
        };
    }

    static Action.State state(PlaceRules.Fn fn, @Nullable PlacesClient.Place p, String missing) {
        PlacesClient.Data d = PlacesClient.data();
        if (!d.present) return Action.State.off(Component.translatable("rysmap.place.no_server_short").getString());
        if (!d.allowed(fn)) return Action.State.off(Component.translatable("rysmap.place.off_short").getString());
        if (p == null) return Action.State.off(missing);
        if (p.lock() != null && !p.death()) return Action.State.off(Component.translatable("rysmap.place.locked_short", p.lock()).getString());
        // бой: правило «после урона от игрока»
        int pvp = d.pvpLeft();
        if (pvp > 0) return Action.State.pause(pvp);
        int left = d.left(fn);
        return left > 0 ? Action.State.pause(left) : Action.State.ready();
    }

    static String distance(@Nullable PlacesClient.Place p) {
        LocalPlayer me = Minecraft.getInstance().player;
        if (p == null || me == null) return "";
        if (!me.level().dimension().location().toString().equals(p.dim())) return MapScreen.dimensionName(p.dim());
        double dx = p.x() - me.getX(), dz = p.z() - me.getZ();
        return Words.distance(Math.sqrt(dx * dx + dz * dz));
    }

    static @Nullable PlacesClient.Place firstHome() {
        List<PlacesClient.Place> h = PlacesClient.data().homes;
        return h.isEmpty() ? null : h.get(0);
    }

    static @Nullable PlacesClient.Place lastBack() {
        List<PlacesClient.Place> b = PlacesClient.data().back;
        return b.isEmpty() ? null : b.get(0);
    }

    public static void register() {
        Action.builder(id("home")).label(Component.translatable("rysmap.action.home")).icon(Icons.Shape.HOME).group(Action.Group.PLACES).order(0)
            .detail(() -> {
                PlacesClient.Place h = firstHome();
                return h == null ? "" : PlacesLayer.name(h) + " · " + distance(h);
            })
            .state(() -> state(PlaceRules.Fn.HOME, firstHome(), Component.translatable("rysmap.action.no_home").getString()))
            .run(() -> {
                PlacesClient.Place h = firstHome();
                if (h != null) PlacesLayer.teleport(h);
            }).register();
        Action.builder(id("back")).label(Component.translatable("rysmap.action.back")).icon(Icons.Shape.HISTORY).group(Action.Group.PLACES).order(1)
            .detail(() -> {
                PlacesClient.Place b = lastBack();
                return b == null ? "" : PlacesLayer.name(b) + " · " + distance(b);
            })
            .state(() -> state(PlaceRules.Fn.BACK, lastBack(), Component.translatable("rysmap.action.no_back").getString()))
            .run(() -> PlacesClient.op("back", new JsonObject())).register();
        Action.builder(id("spawn")).label(Component.translatable("rysmap.action.spawn")).icon(Icons.Shape.FLAG).group(Action.Group.PLACES).order(2)
            .detail(() -> distance(PlacesClient.data().spawn))
            .state(() -> state(PlaceRules.Fn.SPAWN, PlacesClient.data().spawn, ""))
            .run(() -> PlacesClient.op("spawn", new JsonObject())).register();
        Action.builder(id("places")).label(Component.translatable("rysmap.action.places")).icon(Icons.Shape.SERVER).group(Action.Group.PLACES).order(3)
            .detail(() -> Words.plural(PlacesClient.data().places.size(), "rysmap.action.places_n"))
            .badge(() -> PlacesClient.data().places.size())
            .state(() -> {
                PlacesClient.Data d = PlacesClient.data();
                if (!d.present) return Action.State.off(Component.translatable("rysmap.place.no_server_short").getString());
                if (d.places.isEmpty()) return Action.State.off(Component.translatable("rysmap.action.no_places").getString());
                return Action.State.ready();
            })
            .list(PlaceActions::placeActions).notBindable().register();
        Action.builder(id("team_home")).label(Component.translatable("rysmap.action.team_home")).icon(Icons.Shape.USERS).group(Action.Group.PLACES).order(4)
            .detail(() -> distance(PlacesClient.data().teamHome))
            .state(() -> {
                PlacesClient.Data d = PlacesClient.data();
                if (d.present && !d.teams) return Action.State.off(Component.translatable("rysmap.action.no_teams").getString());
                return state(PlaceRules.Fn.TEAM_HOME, d.teamHome, Component.translatable("rysmap.action.no_team_home").getString());
            })
            .run(() -> PlacesClient.op("teamhome", new JsonObject())).register();
        Action.builder(id("players")).label(Component.translatable("rysmap.action.players")).icon(Icons.Shape.USERS).group(Action.Group.PLACES).order(5)
            .badge(() -> players().size())
            .state(() -> {
                PlacesClient.Data d = PlacesClient.data();
                if (!d.present) return Action.State.off(Component.translatable("rysmap.place.no_server_short").getString());
                if (!d.allowed(PlaceRules.Fn.REQUEST)) return Action.State.off(Component.translatable("rysmap.place.off_short").getString());
                if (players().isEmpty()) return Action.State.off(Component.translatable("rysmap.action.no_players").getString());
                return Action.State.pause(d.left(PlaceRules.Fn.REQUEST));
            })
            .list(PlaceActions::playerActions).notBindable().register();
        Action.builder(id("bed")).label(Component.translatable("rysmap.action.bed")).icon(Icons.Shape.BED).group(Action.Group.PLACES).order(6)
            .detail(() -> distance(PlacesClient.data().bed))
            .state(() -> state(PlaceRules.Fn.SPAWN, PlacesClient.data().bed, Component.translatable("rysmap.action.no_bed").getString()))
            .run(() -> PlacesClient.op("bed", new JsonObject())).register();
        Action.builder(id("rtp")).label(Component.translatable("rysmap.action.rtp")).icon(Icons.Shape.DICE).group(Action.Group.PLACES).order(7)
            .state(() -> {
                PlacesClient.Data d = PlacesClient.data();
                LocalPlayer me = Minecraft.getInstance().player;
                if (me != null && d.rules.randomExcluded.contains(me.level().dimension().location().toString()))
                    return Action.State.off(Component.translatable("rysmap.action.no_rtp_here").getString());
                return state(PlaceRules.Fn.RANDOM, d.present ? d.spawn : null, "");
            })
            .run(() -> PlacesClient.op("rtp", new JsonObject())).register();
        // каждое место сервера и каждый дом (если их больше одного): на них вешают свои клавиши
        Actions.provider(id("place_list"), () -> {
            List<Action> out = new ArrayList<>(placeActions());
            if (PlacesClient.data().homes.size() > 1) for (PlacesClient.Place h : PlacesClient.data().homes) out.add(placeAction(h));
            return out;
        });
        TeamCards.register(PlaceActions::teamCard);
    }

    /** Места сервера для списка рядом с колесом: сначала измерение игрока, ближние выше; раздел по измерению. */
    static List<Action> placeActions() {
        LocalPlayer me = Minecraft.getInstance().player;
        String here = me == null ? "" : me.level().dimension().location().toString();
        List<PlacesClient.Place> list = new ArrayList<>(PlacesClient.data().places);
        list.sort(Comparator.comparing((PlacesClient.Place p) -> !p.dim().equals(here)).thenComparing(PlacesClient.Place::dim)
            .thenComparingDouble(p -> me == null ? 0 : (p.x() - me.getX()) * (p.x() - me.getX()) + (p.z() - me.getZ()) * (p.z() - me.getZ())));
        List<Action> out = new ArrayList<>(list.size());
        int order = 0;
        for (PlacesClient.Place p : list) out.add(placeAction(p, order++));
        return out;
    }

    static Action placeAction(PlacesClient.Place p) {
        return placeAction(p, 0);
    }

    static Action placeAction(PlacesClient.Place p, int order) {
        LocalPlayer me = Minecraft.getInstance().player;
        boolean same = me != null && me.level().dimension().location().toString().equals(p.dim());
        String right = same ? distance(p) : (int) Math.floor(p.x()) + " · " + (int) Math.floor(p.z());
        ResourceLocation id = actionId(p);
        return Action.builder(id == null ? id("place/" + slug(p.name())) : id).label(Component.literal(PlacesLayer.name(p))).icon(PlacesLayer.icon(p))
            .color(PlacesLayer.color(p)).section(MapScreen.dimensionName(p.dim())).right(right).group(Action.Group.PLACES).order(100 + order)
            .detail(p.note().isEmpty() ? "" : p.note())
            .state(() -> state(PlacesLayer.fn(p), p, ""))
            .run(() -> PlacesLayer.teleport(p)).build();
    }

    /** Игроки в сети для списка «Игроки…»: выбор шлёт запрос «Попроситься к нему». */
    static List<PlayerInfo> players() {
        var conn = Minecraft.getInstance().getConnection();
        LocalPlayer me = Minecraft.getInstance().player;
        if (conn == null || me == null) return List.of();
        List<PlayerInfo> out = new ArrayList<>();
        for (PlayerInfo i : conn.getOnlinePlayers()) if (!i.getProfile().getId().equals(me.getUUID())) out.add(i);
        out.sort(Comparator.comparing(i -> i.getProfile().getName().toLowerCase(java.util.Locale.ROOT)));
        return out;
    }

    static List<Action> playerActions() {
        List<Action> out = new ArrayList<>();
        int order = 0;
        for (PlayerInfo i : players()) {
            UUID uid = i.getProfile().getId();
            String name = i.getProfile().getName();
            var spot = ServerRules.players().get(uid);
            String right = spot == null ? "" : spot.dimension().equals(currentDim()) ? Words.distance(dist(spot.x(), spot.z())) : MapScreen.dimensionName(spot.dimension());
            out.add(Action.builder(id("player/" + uid)).label(Component.literal(name)).icon(Icons.Shape.USER).right(right).group(Action.Group.PLACES)
                .order(order++).notBindable()
                .detail(Component.translatable(PlacesClient.data().teamTp && spot != null && spot.team() ? "rysmap.player.to_team" : "rysmap.player.to").getString())
                .state(() -> Action.State.pause(PlacesClient.data().left(PlaceRules.Fn.REQUEST)))
                .run(() -> PlacesClient.op("tpa", PlacesClient.obj("id", uid.toString()))).build());
        }
        return out;
    }

    static String currentDim() {
        LocalPlayer me = Minecraft.getInstance().player;
        return me == null ? "" : me.level().dimension().location().toString();
    }

    static double dist(double x, double z) {
        LocalPlayer me = Minecraft.getInstance().player;
        if (me == null) return 0;
        double dx = x - me.getX(), dz = z - me.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Карточка «Дом команды · Телепорт» на экране команды. */
    static TeamCards.@Nullable Card teamCard() {
        PlacesClient.Data d = PlacesClient.data();
        if (!d.present || !d.teams) return null;
        PlacesClient.Place h = d.teamHome;
        ResourceLocation src = ResourceLocation.fromNamespaceAndPath(RYSMap.MODID, "team_home");
        if (h == null) {
            if (!d.teamHomePerm) return null;
            return new TeamCards.Card(src, Component.translatable("rysmap.team_card.none"), List.of(Component.translatable("rysmap.team_card.how",
                d.ftb ? "/rys place setteamhome" : "/setteamhome")), Component.translatable("rysmap.team_card.set_here"),
                () -> PlacesClient.op("setteamhome", new JsonObject()), false);
        }
        String where = MapScreen.dimensionName(h.dim()) + " · " + (int) Math.floor(h.x()) + " " + (int) Math.floor(h.y()) + " " + (int) Math.floor(h.z());
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(where));
        String dist = distance(h);
        if (!dist.isEmpty() && !dist.equals(MapScreen.dimensionName(h.dim()))) lines.add(Component.translatable("rysmap.team_card.distance", dist));
        String why = PlacesLayer.blocked(h);
        if (why != null) lines.add(Component.literal(why));
        return new TeamCards.Card(src, Component.translatable("rysmap.team_card.title"), lines,
            why == null ? Component.literal(PlacesLayer.teleportLabel(h)) : null, why == null ? () -> PlacesLayer.teleport(h) : null, false);
    }
}
