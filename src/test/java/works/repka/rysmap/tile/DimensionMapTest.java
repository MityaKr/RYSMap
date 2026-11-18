package works.repka.rysmap.tile;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import works.repka.rysmap.world.MapStore;
import works.repka.rysmap.world.WorldKey;

class DimensionMapTest {
    @TempDir
    Path dir;

    static final MapColors GRAY = new MapColors() {
        public int block(String id) { return id.equals("minecraft:air") ? 0 : 0xFF808080; }
        public int tint(String id) { return TINT_NONE; }
        public int grass(String b) { return 0xFFFFFF; }
        public int foliage(String b) { return 0xFFFFFF; }
        public int water(String b) { return 0x3F76E4; }
        public int waterTexture() { return 0xFFFFFF; }
        public int unknown() { return 0xFF101010; }
        public long version() { return 1; }
    };

    @Test
    void writeFlushReopen() throws IOException {
        MapStore store = new MapStore(dir, WorldKey.singleplayer("Test World"));
        List<String> log = new ArrayList<>();
        ConcurrentHashMap<Long, int[]> index = new ConcurrentHashMap<>();
        DimensionMap map = new DimensionMap(store, "minecraft:overworld", GRAY, 9, index, log::add);
        RegionData r = map.write(-1, 2);
        int stone = map.palette().block("minecraft:stone");
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) r.set(x, z, 70, stone, 0, 0);
        r.mark(0, 0, 1_800_000_000);
        assertEquals(1_800_000_000, DimensionMap.scanned(index, -32, 64), "главный поток видит время скана");
        assertEquals(-1, DimensionMap.scanned(index, 0, 0), "чужой регион неизвестен");
        map.close();

        Path file = store.region("minecraft:overworld", -1, 2);
        assertTrue(Files.isRegularFile(file), "регион на диске: " + file);
        assertTrue(Files.isRegularFile(store.palette("minecraft:overworld")), "палитра рядом");
        assertEquals("overworld", file.getParent().getFileName().toString());
        assertEquals("minecraft", file.getParent().getParent().getFileName().toString());

        // повторный вход: регион читается без пересканирования, номера те же
        ConcurrentHashMap<Long, int[]> index2 = new ConcurrentHashMap<>();
        DimensionMap again = new DimensionMap(store, "minecraft:overworld", GRAY, 9, index2, log::add);
        again.index(-1, 2);
        assertEquals(1_800_000_000, DimensionMap.scanned(index2, -32, 64));
        assertEquals(stone, again.palette().block("minecraft:stone"));
        assertEquals(1, again.rawRegions(), "время скана читается вместе с регионом для записи");

        // окно карты: пиксели с диска, регион в кэш не кладётся
        DimensionMap viewer = new DimensionMap(store, "minecraft:overworld", GRAY, 9, new ConcurrentHashMap<>(), log::add);
        int[] px = viewer.bakeRegion(-1, 2);
        assertNotNull(px);
        assertEquals(0xFF808080, px[0], "серый непрозрачный ABGR");
        assertEquals(0, px[RegionData.index(100, 100)], "неразведанное прозрачно");
        assertEquals(0, viewer.rawRegions(), "пиксели для текстуры не кладут регион в кэш");
        assertNull(viewer.bakeRegion(50, 50), "региона нет");
        assertTrue(log.isEmpty(), log.toString());
    }

    @Test
    void brokenRegionGoesToBrokenAndIsRedrawn() throws IOException {
        MapStore store = new MapStore(dir, WorldKey.server("example.org"));
        Path file = store.region("minecraft:the_nether", 0, 0);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[]{'R', 'Y', 'S', 'M', 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        List<String> log = new ArrayList<>();
        DimensionMap map = new DimensionMap(store, "minecraft:the_nether", GRAY, 9, new ConcurrentHashMap<>(), log::add);
        map.index(0, 0);
        assertFalse(Files.exists(file), "плохой файл убран");
        assertTrue(Files.isDirectory(file.resolveSibling("_broken")));
        assertEquals(1, map.brokenFiles());
        assertEquals(1, log.size());
        // при проходе регион пишется заново
        RegionData r = map.write(0, 0);
        r.set(0, 0, 40, map.palette().block("minecraft:netherrack"), 0, 0);
        r.mark(0, 0, 5);
        map.flush();
        assertNotNull(RegionFile.readSurface(file, 0, 0));
    }

    @Test
    void lostPaletteMovesRegionsAside() throws IOException {
        MapStore store = new MapStore(dir, WorldKey.singleplayer("w"));
        DimensionMap map = new DimensionMap(store, "minecraft:overworld", GRAY, 9, new ConcurrentHashMap<>(), s -> {});
        RegionData r = map.write(0, 0);
        r.set(0, 0, 64, map.palette().block("a:b"), 0, 0);
        r.mark(0, 0, 1);
        map.close();
        Files.writeString(store.palette("minecraft:overworld"), "{ испорчено");
        List<String> log = new ArrayList<>();
        DimensionMap again = new DimensionMap(store, "minecraft:overworld", GRAY, 9, new ConcurrentHashMap<>(), log::add);
        assertFalse(Files.exists(store.region("minecraft:overworld", 0, 0)), "регионы без палитры отложены");
        assertEquals(1, again.palette().blockCount());
        assertEquals(1, log.size());
    }
}
