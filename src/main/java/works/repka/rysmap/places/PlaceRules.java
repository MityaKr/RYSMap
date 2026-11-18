package works.repka.rysmap.places;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Правила «Мест» (план Essentials 4.6, макет {@code 07}): раздел {@code places} файла правил карты
 * {@code world/serverconfig/rysmap-server.json} (версия файла 2). Чистый класс, без игры: его читает сервер, правит оператор
 * в блоке «Перемещения» страницы «Сервер», его же шлют клиенту для паузы в колесе и на кнопке.
 *
 * <p>Умолчания как в FTB Essentials RepYS сейчас: дом, места сервера, спавн и запросы по 10 с, назад 30 с, случайное место
 * 600 с, разогрев 0, один дом, история «назад» 10 мест с гибелью, случайное место 500–25000 блоков от спавна, не в Крае,
 * срок запроса 60 с.
 */
public final class PlaceRules {
    /** Функция блока «Перемещения»: строка таблицы. Порядок строк как в макете. */
    public enum Fn {
        HOME("home", 10), TEAM_HOME("team_home", 10), PLACE("place", 10), BACK("back", 30), SPAWN("spawn", 10), RANDOM("random", 600),
        REQUEST("request", 10);

        public final String id;
        public final int defaultPause;

        Fn(String id, int defaultPause) {
            this.id = id;
            this.defaultPause = defaultPause;
        }

        public static Fn byId(String id) {
            for (Fn f : values()) if (f.id.equals(id)) return f;
            return null;
        }
    }

    /** Кто пользуется функцией. */
    public enum Who { OFF, ALL, MODERATORS, OPERATORS }

    /** Строка таблицы: кто, пауза, разогрев (секунды). */
    public static final class Row {
        public Who who = Who.ALL;
        public int pause;
        public int warmup;

        Row copy() {
            Row r = new Row();
            r.who = who;
            r.pause = pause;
            r.warmup = warmup;
            return r;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Row r && r.who == who && r.pause == pause && r.warmup == warmup;
        }

        @Override
        public int hashCode() {
            return who.hashCode() * 31 * 31 + pause * 31 + warmup;
        }
    }

    private final Map<Fn, Row> rows = new EnumMap<>(Fn.class);
    /** Домов на игрока. */
    public int homes = 1;
    /** Сколько мест помнит «Назад». */
    public int backSize = 10;
    /** Пишет ли гибель в историю «назад». */
    public boolean backDeath = true;
    /** Случайное место: от и до, блоков от спавна. */
    public int randomMin = 500, randomMax = 25000;
    /** Измерения без случайного места. */
    public List<String> randomExcluded = new ArrayList<>(List.of("minecraft:the_end"));
    /** Срок запроса к игроку, секунды. */
    public int requestTimeout = 60;
    /** Секунд без телепорта после урона от игрока; 0 выключено. */
    public int pvpDelay = 0;
    /** Движение срывает разогрев (урон срывает всегда). */
    public boolean moveCancels = false;

    public PlaceRules() {
        for (Fn f : Fn.values()) {
            Row r = new Row();
            r.pause = f.defaultPause;
            rows.put(f, r);
        }
    }

    public Row row(Fn fn) {
        return rows.get(fn);
    }

    public PlaceRules copy() {
        PlaceRules p = new PlaceRules();
        for (Fn f : Fn.values()) p.rows.put(f, rows.get(f).copy());
        p.homes = homes;
        p.backSize = backSize;
        p.backDeath = backDeath;
        p.randomMin = randomMin;
        p.randomMax = randomMax;
        p.randomExcluded = new ArrayList<>(randomExcluded);
        p.requestTimeout = requestTimeout;
        p.pvpDelay = pvpDelay;
        p.moveCancels = moveCancels;
        return p;
    }

    /** Приводит числа к допустимым границам. */
    public PlaceRules clamp() {
        for (Row r : rows.values()) {
            r.pause = clamp(r.pause, 0, 86400);
            r.warmup = clamp(r.warmup, 0, 60);
        }
        homes = clamp(homes, 0, 64);
        backSize = clamp(backSize, 0, 50);
        randomMin = clamp(randomMin, 0, 1_000_000);
        randomMax = clamp(randomMax, randomMin + 16, 2_000_000);
        requestTimeout = clamp(requestTimeout, 10, 600);
        pvpDelay = clamp(pvpDelay, 0, 600);
        return this;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        JsonObject fns = new JsonObject();
        for (Fn f : Fn.values()) {
            Row r = rows.get(f);
            JsonObject row = new JsonObject();
            row.addProperty("who", r.who.name().toLowerCase(Locale.ROOT));
            row.addProperty("pause", r.pause);
            row.addProperty("warmup", r.warmup);
            fns.add(f.id, row);
        }
        o.add("functions", fns);
        o.addProperty("homes", homes);
        o.addProperty("backSize", backSize);
        o.addProperty("backDeath", backDeath);
        o.addProperty("randomMin", randomMin);
        o.addProperty("randomMax", randomMax);
        JsonArray ex = new JsonArray();
        for (String d : randomExcluded) ex.add(d);
        o.add("randomExcluded", ex);
        o.addProperty("requestTimeout", requestTimeout);
        o.addProperty("pvpDelay", pvpDelay);
        o.addProperty("moveCancels", moveCancels);
        return o;
    }

    /** Из раздела {@code places}; чего нет или что не читается, остаётся умолчанием. */
    public static PlaceRules fromJson(JsonObject o) {
        PlaceRules p = new PlaceRules();
        if (o == null) return p;
        if (o.has("functions") && o.get("functions").isJsonObject()) {
            JsonObject fns = o.getAsJsonObject("functions");
            for (Fn f : Fn.values()) {
                if (!fns.has(f.id) || !fns.get(f.id).isJsonObject()) continue;
                JsonObject row = fns.getAsJsonObject(f.id);
                Row r = p.rows.get(f);
                if (row.has("who")) r.who = who(row.get("who").getAsString());
                r.pause = intOr(row, "pause", r.pause);
                r.warmup = intOr(row, "warmup", r.warmup);
            }
        }
        p.homes = intOr(o, "homes", p.homes);
        p.backSize = intOr(o, "backSize", p.backSize);
        if (o.has("backDeath")) p.backDeath = bool(o, "backDeath", p.backDeath);
        p.randomMin = intOr(o, "randomMin", p.randomMin);
        p.randomMax = intOr(o, "randomMax", p.randomMax);
        if (o.has("randomExcluded") && o.get("randomExcluded").isJsonArray()) {
            p.randomExcluded = new ArrayList<>();
            for (var e : o.getAsJsonArray("randomExcluded")) {
                try {
                    p.randomExcluded.add(e.getAsString());
                } catch (RuntimeException ignored) {
                    // не строка
                }
            }
        }
        p.requestTimeout = intOr(o, "requestTimeout", p.requestTimeout);
        p.pvpDelay = intOr(o, "pvpDelay", p.pvpDelay);
        if (o.has("moveCancels")) p.moveCancels = bool(o, "moveCancels", p.moveCancels);
        return p.clamp();
    }

    private static int intOr(JsonObject o, String key, int fallback) {
        try {
            return o.has(key) ? o.get(key).getAsInt() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject o, String key, boolean fallback) {
        try {
            return o.get(key).getAsBoolean();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public static Who who(String s) {
        try {
            return Who.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return Who.ALL;
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PlaceRules p && p.toJson().equals(toJson());
    }

    @Override
    public int hashCode() {
        return toJson().hashCode();
    }
}
