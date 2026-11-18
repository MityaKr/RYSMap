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

    /**
     * Чужие игроки на карте (макет скептика {@code s3}): никогда; только рядом (кого клиент и так видит, сервер мест не
     * шлёт); союз (места союзников везде, остальные рядом); все и везде (места всех игроков сервера раз в секунду).
     * Своя команда видна всегда.
     */
    public enum Players { NEVER, NEAR, ALLIES, ALL }

    public boolean minimap = true;
    public boolean caves = true;
    public Teleport teleport = Teleport.OPS;
    /** Пауза между телепортами, секунды. */
    public int pause = 300;
    /** Чужие игроки на карте; по умолчанию только рядом. */
    public Players players = Players.NEAR;
    /**
     * Куда можно телепортироваться не оператору, кроме своего текущего измерения (перепроверка 18.11): по умолчанию Верхний
     * и Нижний мир. Энд, пространственные ячейки AE2, шахтёрские измерения модов не открыты. Правится в файле.
     */
    public java.util.List<String> teleportDimensions = new java.util.ArrayList<>(java.util.List.of("minecraft:overworld", "minecraft:the_nether"));
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
        r.teleportDimensions = new java.util.ArrayList<>(teleportDimensions);
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
            // до 0.1.0-m10 был флажок players (по умолчанию true, «все везде»): умолчание стало приватнее, true даёт «только рядом»
            if (o.has("playersMode")) r.players = players(o.get("playersMode").getAsString());
            else if (o.has("players")) r.players = o.get("players").getAsBoolean() ? Players.NEAR : Players.NEVER;
            if (o.has("mobs")) r.mobs = o.get("mobs").getAsBoolean();
            if (o.has("teleportDimensions") && o.get("teleportDimensions").isJsonArray()) {
                r.teleportDimensions = new java.util.ArrayList<>();
                for (var e : o.getAsJsonArray("teleportDimensions")) r.teleportDimensions.add(e.getAsString());
            }
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
        o.addProperty("playersMode", players.name().toLowerCase(java.util.Locale.ROOT));
        o.addProperty("mobs", mobs);
        com.google.gson.JsonArray dims = new com.google.gson.JsonArray();
        for (String d : teleportDimensions) dims.add(d);
        o.add("teleportDimensions", dims);
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

    public static Players players(String s) {
        try {
            return Players.valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            return Players.NEAR;
        }
    }

    /** Пауза от 0 до суток. */
    public static int clampPause(int seconds) {
        return Math.max(0, Math.min(86400, seconds));
    }
}
