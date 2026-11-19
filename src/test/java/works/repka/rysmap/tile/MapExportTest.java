package works.repka.rysmap.tile;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Экспорт в PNG: план, шаг для большой области, пиксели региона в картинку. */
class MapExportTest {
    @Test
    void smallAreaOneToOne() {
        MapExport.Plan p = MapExport.plan(-10, -5, 9, 4, MapExport.MAX);
        assertEquals(1, p.step());
        assertEquals(20, p.width());
        assertEquals(10, p.height());
        assertEquals(4, p.regions().size(), "область вокруг нуля задевает четыре региона");
    }

    @Test
    void bigAreaSteps() {
        MapExport.Plan p = MapExport.plan(0, 0, 15359, 10239, 4096);
        assertEquals(4, p.step());
        assertTrue(p.width() <= 4096 && p.height() <= 4096);
        assertEquals(30 * 20, p.regions().size());
    }

    @Test
    void composeCopiesAndConverts() {
        MapExport.Plan p = MapExport.plan(0, 0, 511, 511, MapExport.MAX);
        int[] region = new int[RegionData.AREA];
        region[0] = 0xFF0000FF; // ABGR: красный
        region[5 * RegionData.SIZE + 7] = 0xFFFF0000; // ABGR: синий
        int[] out = new int[p.width() * p.height()];
        MapExport.compose(p, region, 0, 0, out);
        assertEquals(0xFFFF0000, out[0], "красный в ARGB");
        assertEquals(0xFF0000FF, out[5 * p.width() + 7], "синий в ARGB");
        assertEquals(0, out[1], "неразведанное прозрачно");
        // регион вне области ничего не пишет
        int[] other = new int[RegionData.AREA];
        java.util.Arrays.fill(other, 0xFFFFFFFF);
        MapExport.compose(p, other, 1, 0, out);
        assertEquals(0, out[1]);
    }

    @Test
    void stepSamplesEveryNth() {
        MapExport.Plan p = MapExport.plan(0, 0, 8191, 511, 4096);
        assertEquals(2, p.step());
        int[] region = new int[RegionData.AREA];
        region[2] = 0xFF00FF00;
        region[1] = 0xFF0000FF;
        int[] out = new int[p.width() * p.height()];
        MapExport.compose(p, region, 0, 0, out);
        assertEquals(0xFF00FF00, out[1], "блок 2 в точке 1, блок 1 пропущен");
    }
}
