package works.repka.rysmap.point;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Личные точки и места гибели одного мира: {@code points.json} в папке карты (план 9.1). Чистая Java.
 *
 * <pre>
 * { "v": 1, "points": [ { "id": "…", "name": "Дом", "dim": "minecraft:overworld", "x": 1192, "y": 68, "z": -409,
 *   "icon": "home", "color": 2, "minimap": true, "world": false, "kind": "point", "created": 1794267098, "from": "ftb" } ] }
 * </pre>
 *
 * Мест гибели хранится три последних. {@link #revision()} растёт при любой правке: слой точек пересобирает метки по нему.
 */
public final class PointStore {
    public static final int VERSION = 1, DEATHS_KEPT = 3, COLORS = 12;
    /** Цвета точек: те же двенадцать, что {@code cat.1}–{@code cat.12} ядра (запасные значения темы). */
    public static final int[] PALETTE = {0xE4B23C, 0x6FA8FF, 0x6FCF8F, 0xE58A6A, 0xB78CF0, 0x5FD0D0, 0xD7D36A, 0xF08FB8, 0x9AA3B5, 0x8FD18A, 0xC9A37A, 0x7F8FF0};
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Точка. Поля открыты: владелец один, главный поток. */
    public static final class Point {
        public UUID id = UUID.randomUUID();
        public String name = "";
        public String dimension = "minecraft:overworld";
        public int x, y, z;
        public String icon = "flag";
        public int color;
        public boolean minimap = true, world;
        public String kind = "point";
        public long created = System.currentTimeMillis() / 1000;
        public @Nullable String from;

        public boolean death() {
            return "death".equals(kind);
        }
    }

    private final List<Point> points = new ArrayList<>();
    private long revision;

    public List<Point> points() {
        return Collections.unmodifiableList(points);
    }

    public long revision() {
        return revision;
    }

    public Point add(Point p) {
        points.add(p);
        if (p.death()) trimDeaths();
        revision++;
        return p;
    }

    public boolean remove(UUID id) {
        boolean removed = points.removeIf(p -> p.id.equals(id));
        if (removed) revision++;
        return removed;
    }

    public @Nullable Point find(UUID id) {
        for (Point p : points) if (p.id.equals(id)) return p;
        return null;
    }

    /** После правки полей точки снаружи. */
    public void changed() {
        revision++;
    }

    private void trimDeaths() {
        List<Point> deaths = new ArrayList<>();
        for (Point p : points) if (p.death()) deaths.add(p);
        if (deaths.size() <= DEATHS_KEPT) return;
        deaths.sort(Comparator.comparingLong(p -> p.created));
        for (int i = 0; i < deaths.size() - DEATHS_KEPT; i++) points.remove(deaths.get(i));
    }

    /** Ближайший из двенадцати цветов к {@code rgb}. */
    public static int nearestColor(int rgb) {
        int best = 0;
        long bestD = Long.MAX_VALUE;
        for (int i = 0; i < PALETTE.length; i++) {
            int c = PALETTE[i];
            long dr = ((c >> 16) & 0xFF) - ((rgb >> 16) & 0xFF), dg = ((c >> 8) & 0xFF) - ((rgb >> 8) & 0xFF), db = (c & 0xFF) - (rgb & 0xFF);
            long d = 3 * dr * dr + 4 * dg * dg + 2 * db * db;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    // ---- файл ----

    public static PointStore load(Path file) throws IOException {
        PointStore s = new PointStore();
        if (!Files.isRegularFile(file)) return s;
        JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        int v = json.has("v") ? json.get("v").getAsInt() : 0;
        if (v != VERSION) throw new IOException("points.json: версия " + v + ", читается " + VERSION);
        JsonArray array = json.getAsJsonArray("points");
        if (array == null) return s;
        for (JsonElement e : array) {
            JsonObject o = e.getAsJsonObject();
            Point p = new Point();
            if (o.has("id")) p.id = UUID.fromString(o.get("id").getAsString());
            p.name = str(o, "name", "");
            p.dimension = str(o, "dim", p.dimension);
            p.x = num(o, "x");
            p.y = num(o, "y");
            p.z = num(o, "z");
            p.icon = str(o, "icon", p.icon);
            p.color = Math.floorMod(num(o, "color"), COLORS);
            p.minimap = !o.has("minimap") || o.get("minimap").getAsBoolean();
            p.world = o.has("world") && o.get("world").getAsBoolean();
            p.kind = str(o, "kind", "point");
            if (o.has("created")) p.created = o.get("created").getAsLong();
            p.from = o.has("from") ? o.get("from").getAsString() : null;
            s.points.add(p);
        }
        return s;
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    private static int num(JsonObject o, String key) {
        return o.has(key) ? o.get(key).getAsInt() : 0;
    }

    public void save(Path file) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("v", VERSION);
        JsonArray array = new JsonArray();
        for (Point p : points) {
            JsonObject o = new JsonObject();
            o.addProperty("id", p.id.toString());
            o.addProperty("name", p.name);
            o.addProperty("dim", p.dimension);
            o.addProperty("x", p.x);
            o.addProperty("y", p.y);
            o.addProperty("z", p.z);
            o.addProperty("icon", p.icon);
            o.addProperty("color", p.color);
            o.addProperty("minimap", p.minimap);
            o.addProperty("world", p.world);
            o.addProperty("kind", p.kind);
            o.addProperty("created", p.created);
            if (p.from != null) o.addProperty("from", p.from);
            array.add(o);
        }
        json.add("points", array);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(json), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
