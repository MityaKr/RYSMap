package works.repka.rysmap.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import works.repka.rysmap.RYSMap;

/**
 * Настройки игрока: {@code config/rysmap/client.json}. В М1 только то, что нужно сканеру и памяти; страница настроек
 * придёт вместе с окном карты. Неизвестные поля файла сохраняются как есть.
 */
public final class ClientConfig {
    public static final int VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Измерения без карты: не сканируются и не переносятся из FTB (план 10.6). */
    public static final List<String> DEFAULT_NO_MAP = List.of("ae2:spatial_storage", "neovitae:dungeon");

    public final Set<String> noMapDimensions = new LinkedHashSet<>(DEFAULT_NO_MAP);
    /** Сырые регионы в куче на измерение (по 1,5 МБ). */
    public int rawRegions = 12;
    /** Текстуры регионов 512×512 в видеопамяти (по 1 МБ), всего. */
    public int textures = 32;
    /** Текстура, которую не рисовали столько секунд, отпускается. */
    public int textureIdleSeconds = 60;
    /** Чанк, снятый недавно, при повторной загрузке не снимается заново, минуты. */
    public int rescanMinutes = 10;
    /** Бюджет главного потока на снимки чанков за тик, микросекунды; при FPS ниже 40 берётся половина. */
    public int scanBudgetMicros = 1500;

    private JsonObject raw = new JsonObject();

    public static Path file() {
        return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("rysmap").resolve("client.json");
    }

    public static ClientConfig load() {
        ClientConfig c = new ClientConfig();
        Path file = file();
        try {
            if (Files.isRegularFile(file)) {
                JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                c.raw = json;
                if (json.has("noMapDimensions")) {
                    c.noMapDimensions.clear();
                    for (JsonElement e : json.getAsJsonArray("noMapDimensions")) c.noMapDimensions.add(e.getAsString());
                }
                c.rawRegions = clamp(json, "rawRegions", c.rawRegions, 9, 64);
                c.textures = clamp(json, "textures", c.textures, 8, 128);
                c.textureIdleSeconds = clamp(json, "textureIdleSeconds", c.textureIdleSeconds, 5, 3600);
                c.rescanMinutes = clamp(json, "rescanMinutes", c.rescanMinutes, 0, 24 * 60);
                c.scanBudgetMicros = clamp(json, "scanBudgetMicros", c.scanBudgetMicros, 200, 10_000);
            } else {
                c.save();
            }
        } catch (Exception e) {
            RYSMap.LOG.warn("[rysmap] {} не прочитался, взяты значения по умолчанию: {}", file, e.toString());
        }
        return c;
    }

    private static int clamp(JsonObject json, String key, int def, int min, int max) {
        if (!json.has(key)) return def;
        try {
            return Math.max(min, Math.min(max, json.get(key).getAsInt()));
        } catch (RuntimeException e) {
            return def;
        }
    }

    public void save() {
        JsonObject json = raw.deepCopy();
        json.addProperty("v", VERSION);
        JsonArray dims = new JsonArray();
        noMapDimensions.forEach(dims::add);
        json.add("noMapDimensions", dims);
        json.addProperty("rawRegions", rawRegions);
        json.addProperty("textures", textures);
        json.addProperty("textureIdleSeconds", textureIdleSeconds);
        json.addProperty("rescanMinutes", rescanMinutes);
        json.addProperty("scanBudgetMicros", scanBudgetMicros);
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(json), StandardCharsets.UTF_8);
        } catch (Exception e) {
            RYSMap.LOG.warn("[rysmap] настройки не записались: {}", e.toString());
        }
    }
}
