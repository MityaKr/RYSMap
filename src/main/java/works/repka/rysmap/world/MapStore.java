package works.repka.rysmap.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import works.repka.rysmap.tile.RegionFile;

/**
 * Папка карты одного мира: {@code <папка игры>/rysmap/<ключ мира>/}. Внутри {@code world.json} и по папке на
 * измерение {@code <пространство>/<путь>/} с {@code palette.json} и регионами. Двоеточие id измерения становится
 * разделителем папок, поэтому обратное имя не угадывается по подчёркиванию.
 */
public final class MapStore {
    public static final int VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path root;
    private final WorldKey key;

    public MapStore(Path mapsRoot, WorldKey key) {
        this.root = mapsRoot.resolve(key.id());
        this.key = key;
    }

    public Path root() {
        return root;
    }

    public WorldKey key() {
        return key;
    }

    /** Папка измерения по его id ({@code minecraft:overworld} → {@code minecraft/overworld}). */
    public Path dimension(String dimensionId) {
        int colon = dimensionId.indexOf(':');
        String ns = colon < 0 ? "minecraft" : dimensionId.substring(0, colon);
        String path = colon < 0 ? dimensionId : dimensionId.substring(colon + 1);
        Path dir = root.resolve(WorldKey.safe(ns));
        for (String part : path.split("/")) dir = dir.resolve(WorldKey.safe(part));
        return dir;
    }

    public Path region(String dimensionId, int regionX, int regionZ) {
        return dimension(dimensionId).resolve(RegionFile.name(regionX, regionZ));
    }

    public Path palette(String dimensionId) {
        return dimension(dimensionId).resolve("palette.json");
    }

    /**
     * Переезд папки карты на новый ключ (UUID мира от сервера): папка старого ключа переименовывается, если папки
     * нового ещё нет. Если есть обе, старая остаётся как есть (в лог), карта берётся из новой. Старый ключ дописывается
     * в {@code world.json} нового ({@code "was"}).
     *
     * @return что случилось, строкой для лога
     */
    public static String adopt(Path mapsRoot, WorldKey from, WorldKey to) throws IOException {
        if (from.id().equals(to.id())) return "same key";
        Path src = mapsRoot.resolve(from.id()), dst = mapsRoot.resolve(to.id());
        if (!Files.isDirectory(src)) return "no old folder";
        if (Files.exists(dst)) return "folder " + to.id() + " already exists, " + from.id() + " left as is";
        Files.move(src, dst);
        Path file = dst.resolve("world.json");
        JsonObject json = new JsonObject();
        if (Files.isRegularFile(file)) {
            try {
                json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (RuntimeException ignored) {
                json = new JsonObject();
            }
        }
        com.google.gson.JsonArray was = json.has("was") && json.get("was").isJsonArray() ? json.getAsJsonArray("was") : new com.google.gson.JsonArray();
        was.add(from.id());
        json.add("was", was);
        Files.writeString(file, GSON.toJson(json), StandardCharsets.UTF_8);
        return "folder " + from.id() + " moved to " + to.id();
    }

    /**
     * Ключ по UUID мира, который уже приходил для этого адреса или сохранения ({@code rysmap/keys.json}): следующий
     * вход сразу открывает папку {@code w-<uuid>}, не дожидаясь {@code MapHello}, и не плодит папку по адресу.
     */
    public static @org.jetbrains.annotations.Nullable String known(Path mapsRoot, String fallbackId) {
        Path file = mapsRoot.resolve("keys.json");
        if (!Files.isRegularFile(file)) return null;
        try {
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            return json.has(fallbackId) ? json.get(fallbackId).getAsString() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Запомнить, что адрес или сохранение {@code fallbackId} это мир {@code worldId}. */
    public static void remember(Path mapsRoot, String fallbackId, String worldId) throws IOException {
        Path file = mapsRoot.resolve("keys.json");
        JsonObject json = new JsonObject();
        if (Files.isRegularFile(file)) {
            try {
                json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (RuntimeException ignored) {
                json = new JsonObject();
            }
        }
        if (json.has(fallbackId) && json.get(fallbackId).getAsString().equals(worldId)) return;
        json.addProperty("v", VERSION);
        json.addProperty(fallbackId, worldId);
        Files.createDirectories(mapsRoot);
        Files.writeString(file, GSON.toJson(json), StandardCharsets.UTF_8);
    }

    /** Отмечает вход: имя, когда заходил. Ошибка записи не мешает карте. */
    public void touch() throws IOException {
        Files.createDirectories(root);
        Path file = root.resolve("world.json");
        JsonObject json = new JsonObject();
        if (Files.isRegularFile(file)) {
            try {
                json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (RuntimeException ignored) {
                json = new JsonObject();
            }
        }
        json.addProperty("v", VERSION);
        json.addProperty("name", key.name());
        if (key.id().startsWith("w-")) json.addProperty("world", key.id().substring(2));
        long now = System.currentTimeMillis() / 1000;
        if (!json.has("created")) json.addProperty("created", now);
        json.addProperty("seen", now);
        Files.writeString(file, GSON.toJson(json), StandardCharsets.UTF_8);
    }
}
