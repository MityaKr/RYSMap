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
        long now = System.currentTimeMillis() / 1000;
        if (!json.has("created")) json.addProperty("created", now);
        json.addProperty("seen", now);
        Files.writeString(file, GSON.toJson(json), StandardCharsets.UTF_8);
    }
}
