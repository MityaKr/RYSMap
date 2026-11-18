package works.repka.rysmap.migrate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import works.repka.rysmap.point.PointStore;

/**
 * Точки FTB Chunks ({@code <папка измерения>/waypoints.json}) в наши (план 10.6, шаг 2): {@code default} становится
 * личной точкой со значком флага и ближайшим из двенадцати цветов, {@code death} местом гибели, {@code hidden}
 * выключает «на миникарте». У точки пометка {@code "from": "ftb"}. Чистая Java; шаг {@code map} это этап М4.
 */
public final class FtbPoints {
    private static final java.util.regex.Pattern NUMBER = java.util.regex.Pattern.compile("#([0-9]{1,6})");

    private FtbPoints() {}

    /** Все точки мира FTB по измерениям из {@code dimensions.txt}. Измерение без файла точек пропускается. */
    public static List<PointStore.Point> read(FtbRegionReader.World world) throws IOException {
        List<PointStore.Point> out = new ArrayList<>();
        for (Map.Entry<String, Path> dim : world.dimensions.entrySet()) {
            Path file = dim.getValue().resolve("waypoints.json");
            if (!Files.isRegularFile(file)) continue;
            JsonObject json;
            try {
                json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (RuntimeException e) {
                throw new FtbRegionReader.FormatException(dim.getKey() + ": waypoints.json не JSON (" + e.getMessage() + ")");
            }
            if (!json.has("waypoints")) continue;
            for (JsonElement e : json.getAsJsonArray("waypoints")) {
                JsonObject w = e.getAsJsonObject();
                PointStore.Point p = new PointStore.Point();
                p.name = w.has("name") ? w.get("name").getAsString() : "";
                p.dimension = dim.getKey();
                p.x = w.has("x") ? w.get("x").getAsInt() : 0;
                p.y = w.has("y") ? w.get("y").getAsInt() : 64;
                p.z = w.has("z") ? w.get("z").getAsInt() : 0;
                p.minimap = !(w.has("hidden") && w.get("hidden").getAsBoolean());
                boolean death = w.has("type") && "death".equals(w.get("type").getAsString());
                p.kind = death ? "death" : "point";
                p.icon = death ? "skull" : "flag";
                p.color = PointStore.nearestColor(color(w));
                p.from = "ftb";
                if (death) {
                    // у FTB времени гибели нет, есть номер в имени «Death #28»: по нему остаются три последних
                    java.util.regex.Matcher m = NUMBER.matcher(p.name);
                    if (m.find()) p.created = p.created - 1_000_000 + Long.parseLong(m.group(1));
                }
                out.add(p);
            }
        }
        return out;
    }

    private static int color(JsonObject w) {
        if (!w.has("color")) return 0xFFFFFF;
        String c = w.get("color").getAsString();
        if (c.startsWith("#")) c = c.substring(1);
        try {
            return Integer.parseInt(c, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return 0xFFFFFF;
        }
    }
}
