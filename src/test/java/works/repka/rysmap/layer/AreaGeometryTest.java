package works.repka.rysmap.layer;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import works.repka.ryscore.map.MapApi;

/** Геометрия областей: полосы строк, края групп, сосед через границу региона. */
class AreaGeometryTest {
    static Layers.AreaChunk c(int x, int z, int group, int color) {
        return new Layers.AreaChunk(x, z, group, color, MapApi.AreaStyle.FILL, false);
    }

    @Test
    void runsAndEdges() {
        List<Layers.AreaChunk> own = List.of(c(0, 0, 1, 0xFF0000), c(1, 0, 1, 0xFF0000), c(2, 0, 2, 0x00FF00), c(0, 1, 1, 0xFF0000));
        Map<Long, Layers.AreaChunk> near = new HashMap<>();
        for (Layers.AreaChunk a : own) near.put(AreaGeometry.key(a.chunkX(), a.chunkZ()), a);
        AreaGeometry g = AreaGeometry.build(own, near::get);
        assertEquals(3, g.runCount(), "строка 0: красная полоса из двух и зелёный чанк; строка 1: один чанк");
        // группа 1 из трёх чанков буквой «Г»: 8 краёв; зелёный одиночный: 4
        assertEquals(12, g.edgeCount());
    }

    @Test
    void neighborAcrossRegion() {
        List<Layers.AreaChunk> own = List.of(c(31, 0, 1, 0xFF0000));
        Map<Long, Layers.AreaChunk> near = new HashMap<>();
        near.put(AreaGeometry.key(31, 0), own.get(0));
        AreaGeometry alone = AreaGeometry.build(own, near::get);
        near.put(AreaGeometry.key(32, 0), c(32, 0, 1, 0xFF0000));
        AreaGeometry joined = AreaGeometry.build(own, near::get);
        assertEquals(4, alone.edgeCount());
        assertEquals(3, joined.edgeCount(), "сосед той же группы в соседнем регионе: края между ними нет");
        assertSame(AreaGeometry.EMPTY, AreaGeometry.build(List.of(), k -> null));
    }
}
