package works.repka.rysmap;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import works.repka.rysmap.compat.SablePlots;
import works.repka.rysmap.scan.ChunkQueue;
import works.repka.rysmap.scan.DirtyColumns;
import works.repka.rysmap.world.MapStore;
import works.repka.rysmap.world.WorldKey;

class MiscTest {
    @Test
    void sablePlotGrid() {
        // участки: чанки 1 280 000 … 1 296 383 по обеим осям (разбор R0)
        assertTrue(SablePlots.inPlotGrid(1_280_000, 1_280_000));
        assertTrue(SablePlots.inPlotGrid(1_296_383, 1_296_383));
        assertFalse(SablePlots.inPlotGrid(1_296_384, 1_290_000));
        assertFalse(SablePlots.inPlotGrid(1_279_999, 1_290_000));
        assertFalse(SablePlots.inPlotGrid(1_290_000, 0), "одна ось в сетке, другая нет");
        assertFalse(SablePlots.inPlotGrid(0, 0));
        assertFalse(SablePlots.inPlotGrid(-1_290_000, -1_290_000));
        SablePlots.setActive(false);
        assertFalse(SablePlots.isPlotChunk(1_290_000, 1_290_000), "без Sable проверка выключена");
        SablePlots.setActive(true);
        assertTrue(SablePlots.isPlotChunk(1_290_000, 1_290_000));
        SablePlots.setActive(false);
    }

    @Test
    void worldKeysAreSafeFolders() {
        assertEquals("sp-new_world", WorldKey.singleplayer("New World").id());
        assertEquals("mp-play.example.org", WorldKey.server("Play.Example.org:25565").id());
        assertEquals("mp-10.0.0.5_25566", WorldKey.server("10.0.0.5:25566").id());
        assertEquals("sp-____1", WorldKey.singleplayer("Мир 1").id());
        String longName = "x".repeat(200);
        String a = WorldKey.safe(longName), b = WorldKey.safe(longName + "y");
        assertTrue(a.length() <= 64);
        assertNotEquals(a, b, "длинные имена не сливаются");
        assertEquals("_.hidden", WorldKey.safe("..hidden"));
        assertTrue(WorldKey.world(new UUID(1, 2), "srv").id().startsWith("w-"));
    }

    @Test
    void dimensionFolders() {
        MapStore store = new MapStore(Path.of("maps"), WorldKey.singleplayer("w"));
        assertEquals(Path.of("maps", "sp-w", "minecraft", "the_nether"), store.dimension("minecraft:the_nether"));
        assertEquals(Path.of("maps", "sp-w", "neovitae", "dungeon"), store.dimension("neovitae:dungeon"));
        assertEquals(Path.of("maps", "sp-w", "mod", "deep", "dim"), store.dimension("mod:deep/dim"));
        assertEquals("r.-1.2.rmap", store.region("minecraft:overworld", -1, 2).getFileName().toString());
    }

    @Test
    void queueNearestAndUrgentFirst() {
        ChunkQueue q = new ChunkQueue();
        q.add(10, 10);
        q.add(1, 1);
        q.add(-5, 0);
        q.addUrgent(50, 50);
        assertEquals(4, q.size());
        long k = q.pollNearest(0, 0, x -> true);
        assertEquals(50, ChunkQueue.x(k), "срочный раньше");
        k = q.pollNearest(0, 0, x -> true);
        assertEquals(1, ChunkQueue.x(k));
        // не готовый (регион ещё не прочитан) пропускается и остаётся в очереди
        k = q.pollNearest(0, 0, x -> ChunkQueue.x(x) != -5);
        assertEquals(10, ChunkQueue.x(k));
        assertEquals(1, q.size());
        assertEquals(Long.MIN_VALUE, q.pollNearest(0, 0, x -> false));
        q.remove(-5, 0);
        assertEquals(0, q.size());
        assertEquals(-7, ChunkQueue.z(ChunkQueue.key(3, -7)));
    }

    @Test
    void dirtyColumnsBounded() {
        DirtyColumns d = new DirtyColumns(3);
        d.add(1, 1);
        d.add(1, 1);
        d.add(2, 2);
        d.add(3, 3);
        d.add(4, 4);
        assertEquals(3, d.size());
        assertEquals(1, d.dropped());
        int[] n = {0};
        d.drain(k -> n[0]++);
        assertEquals(3, n[0]);
        assertEquals(0, d.size());
    }
}
