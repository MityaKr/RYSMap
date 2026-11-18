package works.repka.rysmap.tile;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PaletteTest {
    @TempDir
    Path dir;

    @Test
    void idsAreStableAndZeroReserved() {
        Palette p = new Palette();
        assertEquals(0, p.block(Palette.NO_BLOCK));
        assertEquals(0, p.biome(Palette.NO_BIOME));
        int stone = p.block("minecraft:stone");
        int grass = p.block("minecraft:grass_block");
        assertEquals(1, stone);
        assertEquals(2, grass);
        assertEquals(stone, p.block("minecraft:stone"));
        assertEquals("minecraft:grass_block", p.blockName(grass));
        assertEquals(Palette.NO_BLOCK, p.blockName(999));
        assertTrue(p.dirty());
    }

    @Test
    void fastPathUsesNameOnce() {
        Palette p = new Palette();
        Object key = new Object();
        int[] calls = {0};
        int a = p.block(key, k -> {
            calls[0]++;
            return "mod:machine";
        });
        int b = p.block(key, k -> {
            calls[0]++;
            return "other";
        });
        assertEquals(a, b);
        assertEquals(1, calls[0]);
        assertEquals(a, p.block("mod:machine"));
    }

    @Test
    void saveAndLoad() throws IOException {
        Palette p = new Palette();
        p.block("a:b");
        p.block("c:d");
        p.biome("minecraft:plains");
        Path file = dir.resolve("x/palette.json");
        p.save(file);
        assertFalse(p.dirty());
        Palette back = Palette.load(file);
        assertEquals(p.blockCount(), back.blockCount());
        assertEquals(p.block("c:d"), back.block("c:d"));
        assertEquals(p.biome("minecraft:plains"), back.biome("minecraft:plains"));
        assertFalse(back.dirty());
        assertTrue(Files.readString(file).contains("\"v\": 1"));
    }

    @Test
    void biomeOverflowGivesZero() {
        Palette p = new Palette();
        for (int i = 1; i <= 255; i++) assertEquals(i, p.biome("b:" + i));
        assertEquals(0, p.biome("b:too_many"));
        assertEquals(1, p.overflow());
    }

    @Test
    void wrongVersionRejected() throws IOException {
        Path file = dir.resolve("palette.json");
        Files.writeString(file, "{\"v\": 7, \"blocks\": [\"minecraft:air\"], \"biomes\": [\"\"]}");
        assertThrows(IOException.class, () -> Palette.load(file));
        Files.writeString(file, "{\"v\": 1, \"blocks\": [\"minecraft:stone\"], \"biomes\": [\"\"]}");
        assertThrows(IOException.class, () -> Palette.load(file), "номер 0 должен быть воздухом");
    }

    @Test
    void missingFileGivesFreshPalette() throws IOException {
        Palette p = Palette.load(dir.resolve("none.json"));
        assertEquals(1, p.blockCount());
    }
}
