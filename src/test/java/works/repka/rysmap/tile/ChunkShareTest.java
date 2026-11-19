package works.repka.rysmap.tile;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Обмен картой: чанки переезжают со своим словарём, своё у получателя не затирается. */
class ChunkShareTest {
    @Test
    void packMergeKeepsOwn() throws Exception {
        Palette a = new Palette();
        RegionData src = new RegionData(3, -2);
        int stone = a.block("minecraft:stone"), plains = a.biome("minecraft:plains");
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) src.set(x, z, 64 + x, stone, plains, 0);
        src.clear(5, 5);
        src.mark(0, 0, 100);
        for (int x = 16; x < 32; x++) for (int z = 0; z < 16; z++) src.set(x, z, 70, stone, plains, 0);
        src.mark(1, 0, 200);
        byte[] data = ChunkShare.pack(src, a);
        assertArrayEquals(new int[]{3, -2}, ChunkShare.region(data));

        Palette b = new Palette();
        b.block("minecraft:dirt");
        RegionData dst = new RegionData(3, -2);
        int dirt = b.block("minecraft:dirt");
        for (int x = 16; x < 32; x++) for (int z = 0; z < 16; z++) dst.set(x, z, 50, dirt, 0, 0);
        dst.mark(1, 0, 300);
        assertEquals(1, ChunkShare.merge(data, dst, b), "чужой чанк дописан, свой не тронут");
        assertEquals(67, dst.height(3, 0));
        assertEquals("minecraft:stone", b.blockName(dst.block(3, 0)));
        assertEquals("minecraft:plains", b.biomeName(dst.biome(3, 0)));
        assertEquals(RegionData.NO_HEIGHT, dst.height(5, 5));
        assertEquals(50, dst.height(20, 3), "своё не затирается");
        assertEquals(100, dst.scanned(0, 0));
        assertEquals(0, ChunkShare.merge(data, dst, b), "повтор ничего не добавляет");
        assertThrows(java.io.IOException.class, () -> ChunkShare.merge(data, new RegionData(0, 0), b));
        assertNull(ChunkShare.pack(new RegionData(0, 0), a));
    }
}
