package works.repka.rysmap.tile;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Слои «Высоты» и «Свет» в пекаре: горизонтали, цвет высоты, свет. */
class OverlayTest {
    static int sum(int rgb) {
        return ((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF);
    }

    @Test
    void contourOnBandBoundary() {
        int plain = Baker.heights(0x808080, 70, 70, 70);
        int line = Baker.heights(0x808080, 72, 71, 72);
        assertTrue(sum(line) < sum(plain), "горизонталь между 71 и 72 (шаг 8) темнее");
        assertEquals(plain, Baker.heights(0x808080, 70, RegionData.NO_HEIGHT, RegionData.NO_HEIGHT), "без соседей горизонтали нет");
        assertEquals(Baker.heights(0x808080, 66, 65, 66), Baker.heights(0x808080, 66, 66, 66), "внутри пояса без линии");
    }

    @Test
    void heightColorRamps() {
        int low = Baker.heightColor(20), mid = Baker.heightColor(70), top = Baker.heightColor(250);
        assertTrue((low & 0xFF) > ((low >> 16) & 0xFF), "низина синяя");
        assertTrue(((mid >> 8) & 0xFF) > ((mid >> 16) & 0xFF), "равнина зелёная");
        assertEquals(0xF2F2F2, top, "вершины белые");
        assertEquals(Baker.heightColor(-200), Baker.heightColor(-64));
    }

    @Test
    void lightSourcesStandOut() {
        int dark = Baker.light(0x808080, 0), torch = Baker.light(0x808080, 14);
        assertTrue(sum(torch) > sum(0x808080), "светящий блок ярче");
        assertTrue(sum(dark) < sum(0x808080), "остальное приглушено");
        assertTrue(sum(Baker.light(0x808080, 15)) >= sum(Baker.light(0x808080, 5)), "сильный свет не тусклее слабого");
    }
}
