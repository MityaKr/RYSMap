package works.repka.rysmap.migrate;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import works.repka.rysmap.tile.Palette;
import works.repka.rysmap.tile.RegionData;
import works.repka.rysmap.tile.RegionFile;

/**
 * Настоящая карта FTB Chunks клиента автора на 18.11 ({@code _shared/_saved/fixtures/ftb-2026-11-18/client/ftbchunks}).
 * В git её нет (ники и UUID): тест пропускается, если папки нет. Ожидаемые числа считаются из самих файлов.
 */
class FtbFixtureTest {
    static Path root() {
        String p = System.getProperty("rysmap.ftbFixture");
        return p == null ? null : Path.of(p).resolve("data");
    }

    static List<Path> worlds() throws IOException {
        Path root = root();
        assumeTrue(root != null && Files.isDirectory(root), "нет папки fixtures карты FTB");
        try (Stream<Path> s = Files.list(root)) {
            return s.filter(Files::isDirectory).sorted().toList();
        }
    }

    @Test
    void everyRegionOfEveryWorldReads() throws IOException {
        List<Path> worlds = worlds();
        assertFalse(worlds.isEmpty());
        long start = System.nanoTime();
        int regions = 0, chunks = 0, columns = 0;
        long writtenBytes = 0;
        List<String> report = new ArrayList<>();
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) if (pool.getType() == MemoryType.HEAP) pool.resetPeakUsage();
        for (Path dir : worlds) {
            FtbRegionReader.World w = FtbRegionReader.open(dir);
            int worldRegions = 0;
            for (Map.Entry<String, Path> dim : w.dimensions.entrySet()) {
                // обратное имя из dimensions.txt: папка существует под заменой «:» на «_»
                List<Path> files = FtbRegionReader.regions(dim.getValue());
                Palette palette = new Palette();
                for (Path zip : files) {
                    FtbRegionReader.Region r = FtbRegionReader.read(zip);
                    Set<Long> listed = new HashSet<>();
                    for (FtbRegionReader.Chunk c : r.chunks) listed.add(RegionData.key(c.x(), c.z()));
                    // разведанные по chunks.dat и чанки с высотами в data.png совпадают
                    for (int cz = 0; cz < 32; cz++) {
                        for (int cx = 0; cx < 32; cx++) {
                            boolean data = false;
                            for (int i = 0; i < 256 && !data; i++) data = r.height(cx * 16 + (i & 15), cz * 16 + (i >> 4)) != FtbRegionReader.NO_HEIGHT;
                            if (data) assertTrue(listed.contains(RegionData.key(cx, cz)), zip + ": у чанка " + cx + "," + cz + " есть высоты, но его нет в chunks.dat");
                        }
                    }
                    for (FtbRegionReader.Chunk c : r.chunks) {
                        for (int i = 0; i < 256; i++) {
                            int lx = c.x() * 16 + (i & 15), lz = c.z() * 16 + (i >> 4);
                            int h = r.height(lx, lz);
                            if (h == FtbRegionReader.NO_HEIGHT) continue;
                            columns++;
                            assertTrue(h >= -64 && h < 320, zip + ": высота " + h + " вне мира");
                            assertTrue(w.blocks.containsKey(r.block(lx, lz)), zip + ": номер блока " + Integer.toHexString(r.block(lx, lz)) + " не найден в block_map.txt");
                            assertTrue(w.biomes.containsKey(r.biome(lx, lz)), zip + ": номер биома " + r.biome(lx, lz) + " не найден в biome_map.txt");
                        }
                    }
                    RegionData dst = new RegionData(r.x, r.z);
                    int written = FtbRegionReader.write(r, w, dst, palette);
                    assertEquals(r.chunks.size(), written);
                    assertEquals(r.chunks.size(), dst.chunkCount());
                    byte[] bytes = RegionFile.encode(List.of(dst));
                    writtenBytes += bytes.length;
                    RegionData back = RegionFile.decode(bytes, r.x, r.z).get(0);
                    assertEquals(dst.chunkCount(), back.chunkCount());
                    regions++;
                    worldRegions++;
                    chunks += r.chunks.size();
                }
            }
            report.add(dir.getFileName() + ": регионов " + worldRegions + ", точек " + waypoints(w));
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        long peak = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) if (pool.getType() == MemoryType.HEAP) peak += pool.getPeakUsage().getUsed();
        String summary = String.format("[ftb-fixture] миров %d, регионов %d, чанков %d, столбцов %d, %d мс, пик кучи %d МБ, наш формат %d КБ%n  %s",
            worlds.size(), regions, chunks, columns, ms, peak / (1024 * 1024), writtenBytes / 1024, String.join("\n  ", report));
        System.out.println(summary);
        Files.writeString(Path.of("build", "ftb-fixture-report.txt"), summary, StandardCharsets.UTF_8);
        assertTrue(regions > 0);
    }

    @Test
    void realWorldHasFourDimensionsAndCountsFromFiles() throws IOException {
        Path real = null;
        for (Path dir : worlds()) {
            FtbRegionReader.World w = FtbRegionReader.open(dir);
            if (w.dimensions.size() > 1) real = dir;
        }
        assumeTrue(real != null, "в fixtures нет мира с несколькими измерениями");
        FtbRegionReader.World w = FtbRegionReader.open(real);
        assertTrue(w.dimensions.containsKey("minecraft:the_nether"));
        assertTrue(w.dimensions.containsKey("neovitae:dungeon"));
        assertEquals("neovitae_dungeon", w.dimensions.get("neovitae:dungeon").getFileName().toString());
        int zips = 0, listed = 0;
        for (Path dim : w.dimensions.values()) {
            try (Stream<Path> s = Files.list(dim)) {
                zips += (int) s.filter(p -> p.getFileName().toString().endsWith(".zip")).count();
            }
            listed += FtbRegionReader.regions(dim).size();
        }
        assertEquals(zips, listed, "каждый zip с именем региона находится");
        assertTrue(waypoints(w) > 0, "в настоящем мире есть точки");
    }

    /** Точек во всех waypoints.json мира (для отчёта; перенос точек это этап М4). */
    static int waypoints(FtbRegionReader.World w) throws IOException {
        int n = 0;
        for (Path dim : w.dimensions.values()) {
            Path f = dim.resolve("waypoints.json");
            if (!Files.isRegularFile(f)) continue;
            var json = com.google.gson.JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            n += json.getAsJsonArray("waypoints").size();
        }
        return n;
    }
}
