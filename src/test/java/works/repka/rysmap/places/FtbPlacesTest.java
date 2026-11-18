package works.repka.rysmap.places;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import works.repka.ryscore.migrate.Snbt;

/**
 * Шаг {@code places} на копии настоящих данных FTB Essentials RepYS (fixtures 18.11, в git их нет: без папки тест
 * пропускается). Три прогона подряд: первый переносит, второй и третий ничего не добавляют; файлы FTB не меняются.
 */
class FtbPlacesTest {
    @TempDir
    Path tmp;

    static Path fixture() {
        String p = System.getProperty("rysmap.ftbEssentials", "");
        return p.isEmpty() ? null : Path.of(p);
    }

    static void copy(Path from, Path to) throws Exception {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path f : s.toList()) {
                Path t = to.resolve(from.relativize(f).toString());
                if (Files.isDirectory(f)) Files.createDirectories(t);
                else Files.copy(f, t);
            }
        }
    }

    static String hash(Path dir) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path f : s.filter(Files::isRegularFile).sorted().toList()) {
                md.update(dir.relativize(f).toString().getBytes());
                md.update(Files.readAllBytes(f));
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    @Test
    void realDataThreeRuns() throws Exception {
        Path src = fixture();
        assumeTrue(src != null && Files.isDirectory(src), "нет fixtures FTB Essentials");
        Path dir = tmp.resolve("ftbessentials");
        copy(src, dir);
        String before = hash(dir);
        int warps = Snbt.readObject(dir.resolve("data.snbt")).getAsJsonObject("warps").size();

        PlaceBook book = new PlaceBook();
        StringBuilder report = new StringBuilder();
        for (int run = 1; run <= 3; run++) {
            assertTrue(FtbPlaces.exists(dir));
            FtbPlaces.Source s = FtbPlaces.read(dir);
            assertTrue(s.unreadable().isEmpty(), s.unreadable().toString());
            FtbPlaces.Counts c = FtbPlaces.apply(book, s.data(), s.players(), 10, 1000L * run);
            report.append("прогон ").append(run).append(": мест ").append(c.places).append(" (уже ").append(c.placesSkipped).append("), домов ").append(c.homes)
                .append(" (уже ").append(c.homesSkipped).append("), назад ").append(c.back).append(" (пропущено ").append(c.backSkipped).append("), игроков ")
                .append(c.players).append(", нечитаемых ").append(c.bad).append('\n');
            if (run == 1) {
                assertEquals(warps, c.places, "все места сервера");
                assertEquals(2, c.homes, "два дома");
                assertTrue(c.back > 0);
                assertEquals(0, c.bad);
            } else {
                assertEquals(0, c.places, "повтор не добавляет мест");
                assertEquals(0, c.homes, "повтор не добавляет домов");
                assertEquals(0, c.back, "повтор не добавляет историю");
                assertEquals(warps, c.placesSkipped);
            }
        }
        assertEquals(warps, book.places().size());
        PlaceBook.Place boos = book.place("boos apoth");
        assertNotNull(boos, "имя с пробелом из кавычек");
        assertEquals("minecraft:the_nether", boos.dim);
        assertEquals(-1809.5, boos.x, 0.001, "середина блока");
        assertTrue(boos.facing);
        assertNotNull(book.place("farmilka"), "место в измерении мода переносится");
        assertEquals("neovitae:dungeon", book.place("farmilka").dim);
        UUID a = UUID.fromString("216ea99a-d6eb-45be-8a5b-3da7852588a5");
        assertTrue(book.back(a).size() <= 10);
        // запись истории без взгляда (у FTB он есть не у всех)
        assertTrue(book.back(a).stream().anyMatch(b -> !b.facing) || book.back(UUID.fromString("633d6f3f-f318-4004-a3fb-b5131b22c28b")).stream().anyMatch(b -> !b.facing));
        assertEquals(before, hash(dir), "файлы FTB не меняются");
        Files.writeString(Path.of("build", "ftb-essentials-report.txt"), report.toString());
    }

    @Test
    void historyOrderNewestFirst() {
        JsonObject p = new JsonObject();
        com.google.gson.JsonArray h = new com.google.gson.JsonArray();
        for (int i = 1; i <= 12; i++) {
            JsonObject e = new JsonObject();
            e.addProperty("dim", "minecraft:overworld");
            e.addProperty("x", i);
            e.addProperty("y", 64);
            e.addProperty("z", 0);
            h.add(e);
        }
        p.add("teleport_history", h);
        Map<UUID, JsonObject> players = new LinkedHashMap<>();
        UUID id = UUID.randomUUID();
        players.put(id, p);
        PlaceBook b = new PlaceBook();
        FtbPlaces.Counts c = FtbPlaces.apply(b, null, players, 10, 0);
        assertEquals(10, c.back);
        assertEquals(2, c.backSkipped);
        assertEquals(12.5, b.back(id).get(0).x, 0.001, "последняя запись FTB становится первой");
        assertFalse(b.back(id).get(0).facing);
    }

    @Test
    void badEntriesSkipped() {
        JsonObject data = new JsonObject();
        JsonObject warps = new JsonObject();
        JsonObject ok = new JsonObject();
        ok.addProperty("dim", "minecraft:overworld");
        ok.addProperty("x", 1);
        ok.addProperty("y", 2);
        ok.addProperty("z", 3);
        warps.add("ok", ok);
        JsonObject bad = new JsonObject();
        bad.addProperty("dim", "");
        warps.add("bad", bad);
        data.add("warps", warps);
        PlaceBook b = new PlaceBook();
        FtbPlaces.Counts c = FtbPlaces.apply(b, data, Map.of(), 10, 0);
        assertEquals(1, c.places);
        assertEquals(1, c.bad);
    }
}
