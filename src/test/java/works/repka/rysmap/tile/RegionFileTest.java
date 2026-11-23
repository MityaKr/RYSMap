package works.repka.rysmap.tile;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegionFileTest {
    @TempDir
    Path dir;

    static RegionData sample(int rx, int rz, long seed) {
        RegionData r = new RegionData(rx, rz);
        Random rnd = new Random(seed);
        for (int i = 0; i < 40; i++) {
            int cx = rnd.nextInt(32), cz = rnd.nextInt(32);
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++)
                    r.set(cx * 16 + x, cz * 16 + z, 60 + rnd.nextInt(40) - 64, rnd.nextInt(300), rnd.nextInt(60), RegionData.pack(rnd.nextInt(15), rnd.nextInt(16)));
            r.mark(cx, cz, 1_790_000_000 + i);
        }
        return r;
    }

    static void assertSame(RegionData a, RegionData b) {
        assertEquals(a.x, b.x);
        assertEquals(a.z, b.z);
        assertEquals(a.floor, b.floor);
        assertArrayEquals(a.present, b.present);
        assertArrayEquals(a.scanned, b.scanned);
        assertArrayEquals(a.height, b.height);
        assertArrayEquals(a.block, b.block);
        assertArrayEquals(a.biome, b.biome);
        assertArrayEquals(a.flags, b.flags);
    }

    @Test
    void roundTrip() throws IOException {
        RegionData r = sample(-3, 7, 1);
        Path file = dir.resolve(RegionFile.name(-3, 7));
        RegionFile.write(file, List.of(r));
        assertFalse(Files.exists(file.resolveSibling(file.getFileName() + ".tmp")), "временный файл убран");
        RegionData back = RegionFile.readSurface(file, -3, 7);
        assertNotNull(back);
        assertSame(r, back);
        assertFalse(back.dirty, "прочитанный регион чистый");
        // размер на диске в пределах плана (150–600 КБ с этажами; у одного слоя с 40 чанками меньше)
        assertTrue(Files.size(file) < 600 * 1024, "размер " + Files.size(file));
    }

    @Test
    void severalLayersAndEmpty() throws IOException {
        RegionData surface = sample(0, 0, 2);
        RegionData floor = new RegionData(0, 0, 4);
        floor.set(1, 1, 70, 5, 3, 0);
        floor.mark(0, 0, 123);
        byte[] bytes = RegionFile.encode(List.of(surface, floor));
        List<RegionData> layers = RegionFile.decode(bytes, 0, 0);
        assertEquals(2, layers.size());
        assertSame(surface, layers.get(0));
        assertSame(floor, layers.get(1));

        RegionData empty = new RegionData(5, 5);
        RegionData back = RegionFile.decode(RegionFile.encode(List.of(empty)), 5, 5).get(0);
        assertTrue(back.empty());
        assertEquals(RegionData.NO_HEIGHT, back.height(10, 10));
    }

    @Test
    void headerVersionChecked() throws IOException {
        byte[] bytes = RegionFile.encode(List.of(sample(1, 1, 3)));
        assertEquals('R', bytes[0]);
        assertEquals(RegionFile.VERSION, bytes[4]);
        bytes[4] = 2;
        var e = assertThrows(RegionFile.BrokenException.class, () -> RegionFile.decode(bytes, 1, 1));
        assertTrue(e.getMessage().contains("version"), e.getMessage());
    }

    @Test
    void brokenFilesRejected() throws IOException {
        byte[] good = RegionFile.encode(List.of(sample(2, 2, 4)));
        byte[] flipped = good.clone();
        flipped[good.length / 2] ^= 0x55;
        assertThrows(RegionFile.BrokenException.class, () -> RegionFile.decode(flipped, 2, 2), "сумма");
        byte[] cut = java.util.Arrays.copyOf(good, good.length / 3);
        assertThrows(RegionFile.BrokenException.class, () -> RegionFile.decode(cut, 2, 2), "обрезан");
        byte[] magic = good.clone();
        magic[0] = 'X';
        assertThrows(RegionFile.BrokenException.class, () -> RegionFile.decode(magic, 2, 2), "магия");
        var e = assertThrows(RegionFile.BrokenException.class, () -> RegionFile.decode(good, 3, 2), "чужие координаты");
        assertTrue(e.getMessage().contains("expected"));
        assertThrows(RegionFile.BrokenException.class, () -> RegionFile.decode(new byte[3], 0, 0));
    }

    @Test
    void quarantineMovesToBroken() throws IOException {
        Path file = dir.resolve("r.0.0.rmap");
        Files.write(file, new byte[]{1, 2, 3});
        Path moved = RegionFile.quarantine(file);
        assertFalse(Files.exists(file));
        assertTrue(Files.exists(moved));
        assertEquals("_broken", moved.getParent().getFileName().toString());
        // второй файл с тем же именем в ту же секунду не затирает первый
        Files.write(file, new byte[]{4});
        Path second = RegionFile.quarantine(file);
        assertNotEquals(moved, second);
        assertTrue(Files.exists(moved) && Files.exists(second));
    }

    @Test
    void missingFileIsEmpty() throws IOException {
        assertTrue(RegionFile.read(dir.resolve("r.9.9.rmap"), 9, 9).isEmpty());
        assertNull(RegionFile.readSurface(dir.resolve("r.9.9.rmap"), 9, 9));
    }
}
