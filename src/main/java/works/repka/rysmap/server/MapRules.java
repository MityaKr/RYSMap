package works.repka.rysmap.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import works.repka.rysmap.RYSMap;

/**
 * Правила карты на сервере (план 9.1, макет {@code 50}, раздел «Сервер»): миникарта и пещеры у игроков, телепорт с карты
 * (выключен, операторы, все) и пауза между телепортами, показ чужих игроков и мобов. Лежат в
 * {@code world/serverconfig/rysmap-server.json}; правит оператор в настройках карты или руками в файле (читается при
 * запуске сервера). Значения по умолчанию: всё видно, телепорт только у операторов, пауза 5 минут.
 *
 * <p>Пауза касается только тех, кто не оператор: оператору телепорт нужен для работы.
 */
public final class MapRules {
    public static final int V = 1;
    /** Кто телепортируется с карты. */
    public enum Teleport { OFF, OPS, ALL }

    public boolean minimap = true;
    public boolean caves = true;
    public Teleport teleport = Teleport.OPS;
    /** Пауза между телепортами, секунды. */
    public int pause = 300;
    /** Чужие игроки на карте (участники своей команды видны всегда). */
    public boolean players = true;
    /** Мобы на карте. */
    public boolean mobs = true;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public MapRules copy() {
        MapRules r = new MapRules();
        r.minimap = minimap;
        r.caves = caves;
        r.teleport = teleport;
        r.pause = pause;
        r.players = players;
        r.mobs = mobs;
        return r;
    }

    public static MapRules load(Path file) {
        MapRules r = new MapRules();
        if (!Files.isRegularFile(file)) return r;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (o.has("minimap")) r.minimap = o.get("minimap").getAsBoolean();
            if (o.has("caves")) r.caves = o.get("caves").getAsBoolean();
            if (o.has("teleport")) r.teleport = teleport(o.get("teleport").getAsString());
            if (o.has("pause")) r.pause = clampPause(o.get("pause").getAsInt());
            if (o.has("players")) r.players = o.get("players").getAsBoolean();
            if (o.has("mobs")) r.mobs = o.get("mobs").getAsBoolean();
        } catch (Exception e) {
            RYSMap.LOG.warn("[rysmap] {} не прочитался, правила по умолчанию: {}", file, e.toString());
        }
        return r;
    }

    public void save(Path file) {
        JsonObject o = new JsonObject();
        o.addProperty("v", V);
        o.addProperty("minimap", minimap);
        o.addProperty("caves", caves);
        o.addProperty("teleport", teleport.name().toLowerCase(java.util.Locale.ROOT));
        o.addProperty("pause", pause);
        o.addProperty("players", players);
        o.addProperty("mobs", mobs);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(o), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            RYSMap.LOG.warn("[rysmap] {} не записался: {}", file, e.toString());
        }
    }

    public static Teleport teleport(String s) {
        try {
            return Teleport.valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            return Teleport.OPS;
        }
    }

    /** Пауза от 0 до суток. */
    public static int clampPause(int seconds) {
        return Math.max(0, Math.min(86400, seconds));
    }
}
