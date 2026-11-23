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
 * Настройки игрока: {@code config/rysmap/client.json}. Миникарту, угол, приближение, места гибели, подписи в мире и их дальность
 * правит страница настроек окна карты ({@code screen.SettingsPage}); память и сканер только файлом. Неизвестные поля файла сохраняются
 * как есть.
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

    /** Миникарта включена. */
    public boolean minimap = true;
    /** Размер миникарты в единицах: 0 «Авто» (M, на маленьком окне S), 72, 96, 128. */
    public int minimapSize = 0;
    /** Ступень приближения миникарты: 0 ×0,5, 1 ×1, 2 ×2 пикселя экрана на блок. */
    public int minimapZoom = 1;
    /** Угол миникарты ({@code TOP_RIGHT} и т. п.) или null: угол зоны карты ядра. */
    public @org.jetbrains.annotations.Nullable String minimapCorner;
    /** Круглая миникарта вместо квадрата (макет 03). */
    public boolean minimapCircle;
    /** Миникарта поворачивается по взгляду (север уходит с верха, буква севера ходит по кромке). */
    public boolean minimapRotate;
    /** Пещерный срез под крышей и этажи (М5). */
    public boolean caves = true;
    /** Старое (m9): мобы на всех слоях; при загрузке переходит в {@link #mobsRange} = 0. */
    public boolean entitiesAllLayers;
    /** Вид мобов (скептик s2): 0 нет, 1 головы, 2 точки. */
    public int mobsView = 1;
    /** Слой мобов и чужих игроков: ±8 или ±16 блоков от игрока (или от этажа на карте), 0 все слои. */
    public int mobsRange = 16;
    /** Кого показывать. */
    public boolean mobsHostile = true, mobsPassive = true, mobsPets;
    /** Строку «Клавиши: настройки карты» уже показали (при первом открытии карты). */
    public boolean keysHintSeen;
    /** Карта темнеет ночью (М6). */
    public boolean night = true;
    /** Сколько последних мест гибели хранить: 0 (не ставить), 1, 3, 5. Точки FTB не в счёт. */
    public int deathsKept = 3;
    /** Какие точки подписаны в мире: {@code goal} (цель и гибель), {@code nearby} (все рядом), {@code all} (все). */
    public String worldLabels = "nearby";
    /** Последняя открытая вкладка страницы настроек карты (0.2.10): minimap, world, server, places, keys. */
    public String settingsTab = "minimap";
    /** Дальность подписей в мире, блоки: дальше не рисуются (кроме цели пути и места гибели; в режиме «Все» без предела). */
    public int labelRange = 512;
    /** Метка раскрывается, когда она ближе к прицелу, чем столько единиц интерфейса; 0 не раскрывает у прицела. */
    public int labelFocus = 40;
    /** Сколько меток рисуется самое большее: ближайшие, цель и место гибели всегда. */
    public int labelLimit = 64;
    /** Глаз слоёв по id; слоя нет в списке: как решил мод. */
    public final java.util.Map<String, Boolean> layers = new java.util.LinkedHashMap<>();

    private JsonObject raw = new JsonObject();
    private static ClientConfig current = new ClientConfig();

    public static ClientConfig get() {
        return current;
    }

    public static Path file() {
        return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("rysmap").resolve("client.json");
    }

    public static ClientConfig load() {
        ClientConfig c = new ClientConfig();
        current = c;
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
                if (json.has("minimap")) c.minimap = json.get("minimap").getAsBoolean();
                c.minimapSize = clamp(json, "minimapSize", c.minimapSize, 0, 128);
                if (c.minimapSize != 0 && c.minimapSize != 72 && c.minimapSize != 96 && c.minimapSize != 128) c.minimapSize = 0;
                c.minimapZoom = clamp(json, "minimapZoom", c.minimapZoom, 0, 2);
                if (json.has("minimapCorner") && json.get("minimapCorner").isJsonPrimitive()) c.minimapCorner = json.get("minimapCorner").getAsString();
                if (json.has("settingsTab") && json.get("settingsTab").isJsonPrimitive()) c.settingsTab = json.get("settingsTab").getAsString();
                if (json.has("minimapCircle")) c.minimapCircle = json.get("minimapCircle").getAsBoolean();
                if (json.has("minimapRotate")) c.minimapRotate = json.get("minimapRotate").getAsBoolean();
                if (json.has("caves")) c.caves = json.get("caves").getAsBoolean();
                if (json.has("night")) c.night = json.get("night").getAsBoolean();
                if (json.has("keysHintSeen")) c.keysHintSeen = json.get("keysHintSeen").getAsBoolean();
                if (json.has("entitiesAllLayers")) c.entitiesAllLayers = json.get("entitiesAllLayers").getAsBoolean();
                if (c.entitiesAllLayers) c.mobsRange = 0;
                c.mobsView = clamp(json, "mobsView", c.mobsView, 0, 2);
                if (json.has("mobsRange")) {
                    int r = json.get("mobsRange").getAsInt();
                    c.mobsRange = r == 8 || r == 16 ? r : 0;
                }
                if (json.has("mobsHostile")) c.mobsHostile = json.get("mobsHostile").getAsBoolean();
                if (json.has("mobsPassive")) c.mobsPassive = json.get("mobsPassive").getAsBoolean();
                if (json.has("mobsPets")) c.mobsPets = json.get("mobsPets").getAsBoolean();
                c.deathsKept = clamp(json, "deathsKept", c.deathsKept, 0, 5);
                if (c.deathsKept == 2 || c.deathsKept == 4) c.deathsKept = 3;
                if (json.has("worldLabels") && json.get("worldLabels").isJsonPrimitive())
                    c.worldLabels = works.repka.rysmap.hud.LabelPlan.Mode.of(json.get("worldLabels").getAsString()).id;
                c.labelRange = clamp(json, "labelRange", c.labelRange, 32, 8192);
                c.labelFocus = clamp(json, "labelFocus", c.labelFocus, 0, 200);
                c.labelLimit = clamp(json, "labelLimit", c.labelLimit, 4, 256);
                if (json.has("layers") && json.get("layers").isJsonObject())
                    for (var e : json.getAsJsonObject("layers").entrySet()) c.layers.put(e.getKey(), e.getValue().getAsBoolean());
            } else {
                c.save();
            }
        } catch (Exception e) {
            RYSMap.LOG.warn("[rysmap] {} could not be read, defaults taken: {}", file, e.toString());
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
        json.addProperty("minimap", minimap);
        json.addProperty("minimapSize", minimapSize);
        json.addProperty("minimapZoom", minimapZoom);
        json.addProperty("minimapCircle", minimapCircle);
        json.addProperty("minimapRotate", minimapRotate);
        json.addProperty("caves", caves);
        json.addProperty("night", night);
        json.addProperty("keysHintSeen", keysHintSeen);
        json.remove("entitiesAllLayers");
        json.addProperty("mobsView", mobsView);
        json.addProperty("mobsRange", mobsRange);
        json.addProperty("mobsHostile", mobsHostile);
        json.addProperty("mobsPassive", mobsPassive);
        json.addProperty("mobsPets", mobsPets);
        if (minimapCorner != null) json.addProperty("minimapCorner", minimapCorner);
        else json.remove("minimapCorner");
        json.addProperty("deathsKept", deathsKept);
        json.addProperty("worldLabels", worldLabels);
        json.addProperty("settingsTab", settingsTab);
        json.addProperty("labelRange", labelRange);
        json.addProperty("labelFocus", labelFocus);
        json.addProperty("labelLimit", labelLimit);
        JsonObject l = new JsonObject();
        layers.forEach(l::addProperty);
        json.add("layers", l);
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(json), StandardCharsets.UTF_8);
        } catch (Exception e) {
            RYSMap.LOG.warn("[rysmap] settings were not saved: {}", e.toString());
        }
    }
}
