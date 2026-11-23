package works.repka.rysmap.places;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Перенос мест FTB Essentials в {@link PlaceBook} (план Essentials 6, шаг {@code places}). Чистая часть: на входе уже
 * разобранные SNBT ({@code data.snbt} и {@code playerdata/<uuid>.snbt}), на выходе счётчики. Повтор безопасен:
 * существующее имя не затирается, история добавляется только тому, у кого своей ещё нет.
 *
 * <p>Найдено в данных RepYS: имя в кавычках и с пробелом ({@code "boos apoth"}); {@code xRot} и {@code yRot} есть не у всех
 * записей истории; измерение мода ({@code neovitae:dungeon}) переносится как есть, телепорт проверит его в момент прыжка.
 * FTB хранит место блоком: ноги встают в середину блока.
 */
public final class FtbPlaces {
    /** Что перенесено и что пропущено. */
    public static final class Counts {
        public int places, placesSkipped, homes, homesSkipped, back, backSkipped, players, bad;
        public final List<String> skipped = new ArrayList<>();
    }

    private FtbPlaces() {}

    /** Прочитанная папка {@code world/ftbessentials}. */
    public record Source(@Nullable JsonObject data, Map<UUID, JsonObject> players, List<String> unreadable) {
        public boolean empty() {
            return data == null && players.isEmpty();
        }
    }

    /** Есть ли что переносить: {@code data.snbt} или {@code playerdata}. */
    public static boolean exists(java.nio.file.Path dir) {
        return java.nio.file.Files.isRegularFile(dir.resolve("data.snbt")) || java.nio.file.Files.isDirectory(dir.resolve("playerdata"));
    }

    /** Читает папку FTB Essentials, ничего в ней не меняя. Нечитаемый файл игрока пропускается и попадает в список. */
    public static Source read(java.nio.file.Path dir) throws java.io.IOException {
        JsonObject data = null;
        List<String> bad = new ArrayList<>();
        java.nio.file.Path main = dir.resolve("data.snbt");
        if (java.nio.file.Files.isRegularFile(main)) data = works.repka.ryscore.migrate.Snbt.readObject(main);
        Map<UUID, JsonObject> players = new java.util.LinkedHashMap<>();
        java.nio.file.Path pd = dir.resolve("playerdata");
        if (java.nio.file.Files.isDirectory(pd)) {
            try (var files = java.nio.file.Files.list(pd)) {
                for (java.nio.file.Path f : files.sorted().toList()) {
                    String n = f.getFileName().toString();
                    if (!n.endsWith(".snbt")) continue;
                    UUID id;
                    try {
                        id = UUID.fromString(n.substring(0, n.length() - 5));
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    try {
                        players.put(id, works.repka.ryscore.migrate.Snbt.readObject(f));
                    } catch (java.io.IOException | RuntimeException e) {
                        bad.add(n + ": " + e.getMessage());
                    }
                }
            }
        }
        return new Source(data, players, bad);
    }

    /**
     * @param data    {@code data.snbt} или null
     * @param players {@code playerdata} по UUID игрока
     */
    public static Counts apply(PlaceBook book, @Nullable JsonObject data, Map<UUID, JsonObject> players, int backLimit, long now) {
        Counts c = new Counts();
        if (data != null && data.has("warps") && data.get("warps").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : data.getAsJsonObject("warps").entrySet()) {
                PlaceBook.Place p = place(e.getKey(), e.getValue());
                if (p == null) {
                    c.bad++;
                    c.skipped.add("place " + e.getKey() + ": unreadable");
                    continue;
                }
                p.at = now;
                p.note = "";
                if (book.addPlaceIfAbsent(p)) c.places++;
                else {
                    c.placesSkipped++;
                    c.skipped.add("place " + e.getKey() + ": already exists");
                }
            }
        }
        for (Map.Entry<UUID, JsonObject> e : players.entrySet()) {
            UUID id = e.getKey();
            JsonObject o = e.getValue();
            if (o == null) continue;
            c.players++;
            if (o.has("homes") && o.get("homes").isJsonObject()) {
                for (Map.Entry<String, JsonElement> h : o.getAsJsonObject("homes").entrySet()) {
                    PlaceBook.Place p = place(h.getKey(), h.getValue());
                    if (p == null) {
                        c.bad++;
                        c.skipped.add("home " + id + " " + h.getKey() + ": unreadable");
                        continue;
                    }
                    p.icon = "home";
                    p.at = now;
                    if (book.addHomeIfAbsent(id, p)) c.homes++;
                    else {
                        c.homesSkipped++;
                        c.skipped.add("home " + id + " " + h.getKey() + ": already exists");
                    }
                }
            }
            if (o.has("teleport_history") && o.get("teleport_history").isJsonArray()) {
                var arr = o.getAsJsonArray("teleport_history");
                if (!book.back(id).isEmpty()) {
                    c.backSkipped += arr.size();
                    if (!arr.isEmpty()) c.skipped.add("history " + id + ": own one already exists");
                    continue;
                }
                // у FTB новые записи в конце списка, у нас первыми: читаем с конца
                for (int i = arr.size() - 1; i >= 0; i--) {
                    PlaceBook.Place p = place("", arr.get(i));
                    if (p == null) {
                        c.bad++;
                        continue;
                    }
                    if (book.back(id).size() >= backLimit) {
                        c.backSkipped++;
                        continue;
                    }
                    book.appendBack(id, PlaceBook.Back.of(p.dim, p.x, p.y, p.z, p.yRot, p.xRot, p.facing, "ftb", now), backLimit);
                    c.back++;
                }
            }
        }
        return c;
    }

    /** Место FTB: {@code { dim, x, y, z, xRot?, yRot? }}; null, если не читается. */
    static @Nullable PlaceBook.Place place(String name, JsonElement e) {
        if (e == null || !e.isJsonObject()) return null;
        JsonObject o = e.getAsJsonObject();
        try {
            if (!o.has("dim") || !o.has("x") || !o.has("y") || !o.has("z")) return null;
            PlaceBook.Place p = new PlaceBook.Place();
            p.name = name;
            p.dim = o.get("dim").getAsString();
            if (p.dim.isBlank() || !p.dim.contains(":")) return null;
            p.x = o.get("x").getAsInt() + 0.5;
            p.y = o.get("y").getAsInt();
            p.z = o.get("z").getAsInt() + 0.5;
            if (o.has("xRot") && o.has("yRot")) {
                p.xRot = o.get("xRot").getAsFloat();
                p.yRot = o.get("yRot").getAsFloat();
                p.facing = true;
            }
            return p;
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
