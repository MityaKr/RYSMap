package works.repka.rysmap.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.ui.Icons;
import works.repka.ryscore.ui.Notice;
import works.repka.rysmap.RYSMap;
import works.repka.rysmap.net.PlaceNet;
import works.repka.rysmap.places.PlaceRules;

/**
 * «Места» на клиенте (0.2.0, план Essentials 4.2–4.3): что прислал сервер (свои дома, дом команды, места сервера, «назад»,
 * правила и паузы), строки уведомлений (отказ, разогрев), запросы к игроку с ответом кнопками и клавишами Y и N ядра,
 * действия на сервер. Только главный поток. Без «Мест» на сервере всё пусто, действия отвечают строкой «нет сервера».
 */
public final class PlacesClient {
    /** Место: дом, дом команды, место сервера, запись «назад», спавн, кровать. */
    public record Place(String kind, String name, String dim, double x, double y, double z, String icon, int color, String note, String vis,
                        String by, @Nullable String lock, String reason, long at, int index) {
        public ResourceKey<Level> key() {
            return ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, net.minecraft.resources.ResourceLocation.parse(dim));
        }

        public boolean death() {
            return "death".equals(reason);
        }
    }

    /** Что сейчас известно с сервера. */
    public static final class Data {
        public boolean present, ftb, manage, op, bypass, teams, teamHomePerm, teamTp;
        /** Секунд боя («после урона от игрока») на момент пакета. */
        int pvp;
        public PlaceRules rules = new PlaceRules();
        public final Map<String, Boolean> allowed = new HashMap<>();
        public final Map<String, Integer> left = new HashMap<>();
        public List<Place> homes = List.of(), places = List.of(), back = List.of();
        public @Nullable Place teamHome, spawn, bed;
        public String teamName = "";
        long at;
        long revision;

        /** Пауза функции сейчас, секунды (отсчёт от прихода пакета). */
        public int left(PlaceRules.Fn fn) {
            Integer l = left.get(fn.id);
            if (l == null || l <= 0) return 0;
            long ms = at + l * 1000L - System.currentTimeMillis();
            return ms <= 0 ? 0 : (int) ((ms + 999) / 1000);
        }

        /** Сколько секунд ещё нельзя телепортироваться после урона от игрока. */
        public int pvpLeft() {
            if (pvp <= 0) return 0;
            long ms = at + pvp * 1000L - System.currentTimeMillis();
            return ms <= 0 ? 0 : (int) ((ms + 999) / 1000);
        }

        public boolean allowed(PlaceRules.Fn fn) {
            return present && allowed.getOrDefault(fn.id, false);
        }
    }

    /** Запрос к игроку: входящий или свой. */
    public record Req(UUID other, String name, boolean mine, boolean here, String where, long until) {
        public int left() {
            return (int) Math.max(0, (until - System.currentTimeMillis() + 999) / 1000);
        }
    }

    private static Data data = new Data();
    private static final Map<String, Req> REQUESTS = new LinkedHashMap<>();
    /** Разогрев: до какого времени и сколько всего. */
    private static long warmUntil, warmTotal;
    private static long revision;

    private PlacesClient() {}

    public static Data data() {
        return data;
    }

    /** Растёт с каждым пакетом мест: по нему слой карты и колесо видят правки. */
    public static long revision() {
        return revision;
    }

    public static void reset() {
        data = new Data();
        REQUESTS.clear();
        warmUntil = 0;
        revision++;
    }

    // ---- приём ----

    public static void sync(String json) {
        Data d = new Data();
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            d.present = true;
            d.ftb = bool(o, "ftb");
            d.manage = bool(o, "manage");
            d.op = bool(o, "op");
            d.bypass = bool(o, "bypass");
            d.teams = bool(o, "teams");
            d.teamHomePerm = bool(o, "teamHomePerm");
            d.teamTp = bool(o, "teamTp");
            d.pvp = o.has("pvp") ? o.get("pvp").getAsInt() : 0;
            if (o.has("rules") && o.get("rules").isJsonObject()) d.rules = PlaceRules.fromJson(o.getAsJsonObject("rules"));
            if (o.has("allowed")) for (var e : o.getAsJsonObject("allowed").entrySet()) d.allowed.put(e.getKey(), e.getValue().getAsBoolean());
            if (o.has("left")) for (var e : o.getAsJsonObject("left").entrySet()) d.left.put(e.getKey(), e.getValue().getAsInt());
            d.homes = list(o, "homes", "home");
            d.places = list(o, "places", "place");
            d.back = list(o, "back", "back");
            if (o.has("teamHome")) {
                d.teamHome = place(o.getAsJsonObject("teamHome"), "team_home", 0);
                d.teamName = str(o.getAsJsonObject("teamHome"), "team");
            }
            if (o.has("spawn")) d.spawn = place(o.getAsJsonObject("spawn"), "spawn", 0);
            if (o.has("bed")) d.bed = place(o.getAsJsonObject("bed"), "bed", 0);
        } catch (RuntimeException e) {
            RYSMap.LOG.warn("[rysmap] места с сервера не прочитались: {}", e.toString());
            return;
        }
        d.at = System.currentTimeMillis();
        data = d;
        revision++;
        d.revision = revision;
        works.repka.ryscore.action.Actions.changed();
    }

    private static List<Place> list(JsonObject o, String key, String kind) {
        if (!o.has(key) || !o.get(key).isJsonArray()) return List.of();
        JsonArray a = o.getAsJsonArray(key);
        List<Place> out = new ArrayList<>(a.size());
        for (int i = 0; i < a.size(); i++) out.add(place(a.get(i).getAsJsonObject(), kind, i));
        return List.copyOf(out);
    }

    private static Place place(JsonObject j, String kind, int index) {
        return new Place(kind, str(j, "name"), str(j, "dim"), num(j, "x"), num(j, "y"), num(j, "z"), j.has("icon") ? str(j, "icon") : "flag",
            j.has("color") ? j.get("color").getAsInt() : -1, str(j, "note"), j.has("vis") ? str(j, "vis") : "all", str(j, "by"),
            j.has("lock") ? str(j, "lock") : null, str(j, "reason"), j.has("at") ? j.get("at").getAsLong() : 0, index);
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static double num(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null ? 0 : e.getAsDouble();
    }

    private static boolean bool(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.getAsBoolean();
    }

    /** Строка уведомления с сервера: отказ, разогрев, итог. */
    public static void note(PlaceNet.Note n) {
        String text = n.text().getString();
        switch (n.kind()) {
            case 0 -> denied(text);
            case 1 -> warmup(text, n.seconds(), n.reason());
            case 2 -> {
                warmUntil = 0;
                if (!text.isEmpty()) denied(text);
            }
            default -> {
                if (!text.isEmpty()) Notice.row(Notice.MAP, "place_done").icon(Icons.Shape.CHECK).title(text).millis(3500).show();
            }
        }
    }

    /** «Телепорт не прошёл» и причина (макет {@code 05}). */
    public static void denied(String why) {
        Notice.row(Notice.MAP, "place_denied").icon(Icons.Shape.LOCK).title(Component.translatable("rysmap.tp.fail_title").getString())
            .lines(why).bad().millis(6000).show();
    }

    private static void warmup(String label, int seconds, String reason) {
        long now = System.currentTimeMillis();
        warmTotal = seconds * 1000L;
        warmUntil = now + warmTotal;
        String title = label.isEmpty() ? Component.translatable("rysmap.travel.warmup_title", seconds).getString()
            : Component.translatable("rysmap.travel.warmup_to", label, seconds).getString();
        Notice.card(Notice.MAP, "warmup").icon(icon(reason)).title(title)
            .lines(Component.translatable(data.rules.moveCancels ? "rysmap.travel.warmup_move" : "rysmap.travel.warmup_hurt").getString())
            .progress(() -> warmTotal <= 0 ? 1 : Math.min(1, 1 - (warmUntil - System.currentTimeMillis()) / (double) warmTotal))
            .alive(() -> System.currentTimeMillis() < warmUntil).millis(seconds * 1000L + 500).show();
    }

    /** Значок по причине перемещения. */
    public static Icons.Shape icon(String reason) {
        return switch (reason) {
            case "home" -> Icons.Shape.HOME;
            case "team_home" -> Icons.Shape.USERS;
            case "place" -> Icons.Shape.SERVER;
            case "back", "death" -> Icons.Shape.HISTORY;
            case "spawn" -> Icons.Shape.FLAG;
            case "bed" -> Icons.Shape.BED;
            case "random" -> Icons.Shape.DICE;
            case "request" -> Icons.Shape.USER;
            default -> Icons.Shape.COMPASS;
        };
    }

    /** Запрос пришёл, ждёт ответа или погас (макет {@code 05}). */
    public static void request(PlaceNet.Request r) {
        String id = (r.mine() ? "out:" : "in:") + r.other();
        if (r.seconds() <= 0) {
            REQUESTS.remove(id);
            return;
        }
        Req q = new Req(r.other(), r.name(), r.mine(), r.here(), r.where(), System.currentTimeMillis() + r.seconds() * 1000L);
        REQUESTS.put(id, q);
        String where = where(r.where());
        if (r.mine()) {
            Notice.buttons(Notice.MAP, "wait:" + r.other()).head(r.other())
                .title(Component.translatable("rysmap.request.wait", r.name()).getString())
                .lines(Component.translatable(r.here() ? "rysmap.request.wait_here" : "rysmap.request.wait_to").getString())
                .timer(() -> clock(q.left())).alive(() -> REQUESTS.get(id) == q && q.left() > 0)
                .button(Component.translatable("rysmap.request.cancel").getString(), false, () -> op("cancel", obj("id", r.other().toString())))
                .millis(r.seconds() * 1000L).show();
        } else {
            Notice.buttons(Notice.MAP, "req:" + r.other()).head(r.other())
                .title(Component.translatable(r.here() ? "rysmap.request.calls" : "rysmap.request.asks", r.name()).getString())
                .lines(where).timer(() -> clock(q.left())).alive(() -> REQUESTS.get(id) == q && q.left() > 0)
                .accept(Component.translatable("rysmap.request.accept").getString(), () -> answer(r.other(), true))
                .decline(Component.translatable("rysmap.request.decline").getString(), () -> answer(r.other(), false))
                .sound(Notice.Sound.INVITE).millis(r.seconds() * 1000L).show();
        }
    }

    private static void answer(UUID other, boolean yes) {
        REQUESTS.remove("in:" + other);
        op(yes ? "accept" : "decline", obj("id", other.toString()));
    }

    /** «Нижний мир · земля Репка». */
    static String where(String where) {
        if (where.isEmpty()) return "";
        String[] parts = where.split("\\|", 2);
        String dim = works.repka.rysmap.screen.MapScreen.dimensionName(parts[0]);
        return parts.length > 1 ? Component.translatable("rysmap.request.where_land", dim, parts[1]).getString() : dim;
    }

    public static List<Req> requests() {
        List<Req> out = new ArrayList<>();
        for (Req r : REQUESTS.values()) if (r.left() > 0) out.add(r);
        return out;
    }

    public static boolean warming() {
        return System.currentTimeMillis() < warmUntil;
    }

    public static String clock(int seconds) {
        return seconds / 60 + ":" + String.format(java.util.Locale.ROOT, "%02d", seconds % 60);
    }

    // ---- действия ----

    public static JsonObject obj(String... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i + 1 < kv.length; i += 2) o.addProperty(kv[i], kv[i + 1]);
        return o;
    }

    /** Действие на сервер; без «Мест» на сервере строка «нет сервера». */
    public static boolean op(String op, JsonObject args) {
        var conn = Minecraft.getInstance().getConnection();
        if (conn == null || !conn.hasChannel(PlaceNet.Op.TYPE) || !data.present) {
            denied(Component.translatable("rysmap.place.no_server").getString());
            return false;
        }
        PacketDistributor.sendToServer(new PlaceNet.Op(op, args.toString()));
        return true;
    }

    /** Команда мест для чата с учётом FTB Essentials: {@code /warpnote} или {@code /rys place warpnote}. */
    public static String cmd(String sub) {
        return (data.ftb ? "/rys place " : "/") + sub;
    }

    /** Место с координатами для действия «поставить здесь». */
    public static JsonObject at(ResourceKey<Level> dim, double x, double y, double z) {
        JsonObject o = new JsonObject();
        o.addProperty("dim", dim.location().toString());
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        return o;
    }
}
