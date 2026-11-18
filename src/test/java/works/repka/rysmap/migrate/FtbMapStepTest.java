package works.repka.rysmap.migrate;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import works.repka.rysmap.client.ClientConfig;
import works.repka.rysmap.point.PointStore;
import works.repka.rysmap.tile.DimensionMap;
import works.repka.rysmap.tile.RegionData;
import works.repka.rysmap.tile.RegionFile;
import works.repka.rysmap.world.MapStore;
import works.repka.rysmap.world.WorldKey;

/**
 * Шаг {@code map} на настоящей карте FTB клиента автора (fixtures, в git их нет: тест пропускается без них). Ожидаемые
 * числа считаются из самих файлов: все точки, все регионы настоящей папки кроме измерений «без карты», чанки как в
 * {@code chunks.dat}; прерванный перенос продолжается без повторов; четыре чужие папки не нужны и не читаются.
 */
class FtbMapStepTest {
    @TempDir
    Path dir;

    /** Хозяин без игры: карта измерений на диске, точки в памяти; может «выйти из мира» после N регионов. */
    static final class TestHost implements FtbMapStep.Host {
        final MapStore store;
        final PointStore points = new PointStore();
        final Map<String, DimensionMap> dims = new HashMap<>();
        int regionsLeft = Integer.MAX_VALUE;
        int finishedPoints = -1;

        TestHost(MapStore store) {
            this.store = store;
        }

        @Override public boolean alive() { return regionsLeft > 0; }
        @Override public String playerDimension() { return "minecraft:overworld"; }
        @Override public int playerRegionX() { return 2; }
        @Override public int playerRegionZ() { return -1; }
        @Override public Set<String> noMap() { return Set.copyOf(ClientConfig.DEFAULT_NO_MAP); }
        @Override public boolean slow() { return false; }
        @Override public void finished(int p, int r) { finishedPoints = p; }

        @Override
        public int addPoints(List<PointStore.Point> list) {
            return FtbImport.mergePoints(points, list);
        }

        @Override
        public int importRegion(String dim, FtbRegionReader.Region region, FtbRegionReader.World world) throws IOException {
            regionsLeft--;
            DimensionMap d = dims.get(dim);
            if (d == null) {
                d = new DimensionMap(store, dim, works.repka.rysmap.tile.DimensionMapTestColors.GRAY, 9, new ConcurrentHashMap<>(), s -> {});
                dims.put(dim, d);
            }
            return d.importFtb(region, world);
        }

        void close() throws IOException {
            for (DimensionMap d : dims.values()) d.close();
            dims.clear();
        }
    }

    static Path realWorld() throws IOException {
        String p = System.getProperty("rysmap.ftbFixture");
        Path root = p == null ? null : Path.of(p).resolve("data");
        assumeTrue(root != null && Files.isDirectory(root), "нет папки fixtures карты FTB");
        Path real = null;
        try (Stream<Path> s = Files.list(root)) {
            for (Path d : s.filter(Files::isDirectory).toList()) if (FtbRegionReader.open(d).dimensions.size() > 1) real = d;
        }
        assumeTrue(real != null, "в fixtures нет мира с несколькими измерениями");
        return real;
    }

    @Test
    void realFolderMovesWholeAndResumes() throws Exception {
        Path ftb = realWorld();
        FtbRegionReader.World w = FtbRegionReader.open(ftb);
        // ожидаемое из файлов
        int expectedPoints = FtbFixtureTest.waypoints(w), expectedRegions = 0, expectedChunks = 0, skippedRegions = 0;
        for (Map.Entry<String, Path> e : w.dimensions.entrySet()) {
            List<Path> zips = FtbRegionReader.regions(e.getValue());
            if (ClientConfig.DEFAULT_NO_MAP.contains(e.getKey())) {
                skippedRegions += zips.size();
                continue;
            }
            expectedRegions += zips.size();
            for (Path z : zips) expectedChunks += FtbRegionReader.read(z).chunks.size();
        }

        MapStore store = new MapStore(dir.resolve("rysmap"), WorldKey.world(java.util.UUID.randomUUID(), "RepYS"));
        TestHost host = new TestHost(store);
        JsonObject data = new JsonObject();
        int[] saves = {0};
        // первый заход: «выход из мира» после 40 регионов
        host.regionsLeft = 40;
        IOException stop = assertThrows(IOException.class, () -> FtbMapStep.migrate(ftb, data, () -> saves[0]++, host, false));
        assertTrue(stop.getMessage().contains("продолжится"), stop.getMessage());
        assertEquals(40, data.getAsJsonArray("regions").size(), "пройденные регионы записаны в данные шага");
        assertEquals(expectedPoints, data.get("points").getAsInt());
        host.close();

        // второй заход продолжает с непройденных и точки не повторяет
        host.regionsLeft = Integer.MAX_VALUE;
        FtbMapStep.Result r = FtbMapStep.migrate(ftb, data, () -> saves[0]++, host, false);
        host.close();
        assertEquals(0, r.points(), "точки уже перенесены");
        assertEquals(40, r.regionsBefore());
        assertEquals(expectedRegions, 40 + r.regions(), "все регионы настоящей папки, кроме измерений без карты");
        assertEquals(expectedRegions, data.getAsJsonArray("regions").size());
        assertTrue(saves[0] >= expectedRegions, "отметка после каждого региона");

        // точки: все 13 мест гибели FTB лежат обычными точками с пометкой
        assertEquals(expectedPoints, host.points.points().size());
        for (PointStore.Point p : host.points.points()) {
            assertEquals("ftb", p.from);
            assertFalse(p.death(), "правило трёх последних не трогает точки FTB");
        }
        long deaths = host.points.points().stream().filter(p -> FtbPoints.NOTE_DEATH.equals(p.note)).count();
        System.out.println("[ftb-step] точек " + expectedPoints + " (бывших мест гибели " + deaths + "), регионов " + expectedRegions
            + ", чанков " + expectedChunks + ", пропущено регионов без карты " + skippedRegions);

        // на диске: регионы и чанки как в chunks.dat
        int chunks = 0, files = 0;
        for (String dim : w.dimensions.keySet()) {
            Path d = store.dimension(dim);
            if (!Files.isDirectory(d)) continue;
            try (Stream<Path> s = Files.list(d)) {
                for (Path f : s.filter(x -> x.toString().endsWith(".rmap")).toList()) {
                    String[] parts = f.getFileName().toString().split("\\.");
                    RegionData rd = RegionFile.readSurface(f, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                    chunks += rd.chunkCount();
                    files++;
                }
            }
        }
        assertEquals(expectedRegions, files);
        assertEquals(expectedChunks, chunks);
        assertFalse(Files.exists(store.dimension("neovitae:dungeon")), "измерение без карты не переносится");

        // третий заход ничего не делает
        FtbMapStep.Result again = FtbMapStep.migrate(ftb, data, () -> {}, host, false);
        host.close();
        assertEquals(0, again.regions());
        assertEquals(0, again.points());
    }

    @Test
    void brokenZipIsSkippedOnce() throws Exception {
        Path ftb = dir.resolve("ftb");
        Files.createDirectories(ftb.resolve("minecraft_overworld"));
        Files.writeString(ftb.resolve("dimensions.txt"), "minecraft:overworld\n");
        Files.writeString(ftb.resolve("block_map.txt"), "");
        Files.writeString(ftb.resolve("biome_map.txt"), "");
        Files.write(ftb.resolve("minecraft_overworld").resolve("0EA60-0EA60.zip"), new byte[]{1, 2, 3});
        MapStore store = new MapStore(dir.resolve("rysmap"), WorldKey.singleplayer("t"));
        TestHost host = new TestHost(store);
        JsonObject data = new JsonObject();
        FtbMapStep.Result r = FtbMapStep.migrate(ftb, data, () -> {}, host, false);
        assertEquals(0, r.regions());
        assertEquals(1, r.skipped().size(), "испорченный zip пропущен со строкой: " + r.skipped());
        assertEquals(1, data.getAsJsonArray("regions").size(), "и больше не пробуется");
    }

    @Test
    void findOnlyExactFolder() throws IOException {
        Path game = dir.resolve("game");
        Path data = game.resolve("local/ftbchunks/data/abc");
        Files.createDirectories(data);
        assertNull(FtbImport.find(game, "abc"), "без dimensions.txt это не карта");
        Files.writeString(data.resolve("dimensions.txt"), "minecraft:overworld\n", StandardCharsets.UTF_8);
        assertEquals(data, FtbImport.find(game, "abc"));
        assertNull(FtbImport.find(game, ""));
        assertNull(FtbImport.find(game, "../abc"));
        assertNull(FtbImport.find(game, "other"));
    }

    @Test
    void folderMovesToWorldUuid() throws IOException {
        Path maps = dir.resolve("rysmap");
        WorldKey old = WorldKey.server("play.example.org"), uuid = WorldKey.world(java.util.UUID.randomUUID(), "RepYS");
        Files.createDirectories(maps.resolve(old.id()).resolve("minecraft/overworld"));
        Files.writeString(maps.resolve(old.id()).resolve("points.json"), "{\"v\":1,\"points\":[]}");
        String what = MapStore.adopt(maps, old, uuid);
        assertTrue(what.contains("переехала"), what);
        assertFalse(Files.exists(maps.resolve(old.id())));
        assertTrue(Files.isRegularFile(maps.resolve(uuid.id()).resolve("points.json")));
        assertTrue(Files.readString(maps.resolve(uuid.id()).resolve("world.json")).contains(old.id()), "старый ключ записан");
        // обе папки есть: старая остаётся как есть
        Files.createDirectories(maps.resolve(old.id()));
        assertTrue(MapStore.adopt(maps, old, uuid).contains("уже есть"));
        assertTrue(Files.isDirectory(maps.resolve(old.id())));
        Set<String> names = new HashSet<>();
        try (Stream<Path> s = Files.list(maps)) {
            s.forEach(p -> names.add(p.getFileName().toString()));
        }
        assertEquals(Set.of(old.id(), uuid.id()), names);
    }
}
