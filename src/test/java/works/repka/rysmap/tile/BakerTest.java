package works.repka.rysmap.tile;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BakerTest {
    static final MapColors COLORS = new MapColors() {
        public int block(String id) {
            return switch (id) {
                case "minecraft:grass_block" -> 0xFFFFFFFF; // белая текстура, цвет даёт оттенок
                case "minecraft:stone" -> 0xFF808080;
                case "minecraft:sand" -> 0xFFE0D0A0;
                default -> 0;
            };
        }
        public int tint(String id) { return id.equals("minecraft:grass_block") ? TINT_GRASS : TINT_NONE; }
        public int grass(String b) { return b.equals("minecraft:swamp") ? 0x6A7039 : 0x91BD59; }
        public int foliage(String b) { return 0x77AB2F; }
        public int water(String b) { return 0x3F76E4; }
        public int waterTexture() { return 0xFFFFFF; }
        public int unknown() { return 0xFF222222; }
        public long version() { return 1; }
    };

    static int rgb(int abgr) {
        return ((abgr & 0xFF) << 16) | (abgr & 0xFF00) | ((abgr >> 16) & 0xFF);
    }

    @Test
    void colorsTintsShadowsWater() {
        Palette p = new Palette();
        int grass = p.block("minecraft:grass_block"), stone = p.block("minecraft:stone"), gone = p.block("removedmod:block");
        int sand = p.block("minecraft:sand");
        int plains = p.biome("minecraft:plains"), swamp = p.biome("minecraft:swamp");
        RegionData r = new RegionData(0, 0);
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) r.set(x, z, 64, grass, plains, 0);
        r.set(5, 5, 64, grass, swamp, 0);
        r.set(6, 6, 64, stone, plains, 0);
        r.set(8, 8, 70, stone, plains, 0);   // выше северо-западного соседа: светлее
        r.set(10, 10, 60, stone, plains, 0); // ниже: темнее
        r.set(12, 12, 64, gone, plains, 0);
        r.set(13, 12, 64, gone, plains, 0);
        r.set(3, 3, 62, sand, plains, RegionData.pack(3, 0));
        r.set(3, 4, 62, sand, plains, RegionData.pack(RegionData.WATER_UNKNOWN, 7));
        r.clear(1, 14);
        r.mark(0, 0, 1);
        Baker baker = new Baker(COLORS);
        int[] px = baker.bakeRegion(r, p, Baker.Neighbors.NONE);

        assertEquals(0x91BD59, rgb(px[RegionData.index(2, 2)]), "трава равнин");
        assertEquals(0xFF, px[RegionData.index(2, 2)] >>> 24, "непрозрачно");
        assertEquals(0x6A7039, rgb(px[RegionData.index(5, 5)]), "трава болота");
        assertEquals(0x808080, rgb(px[RegionData.index(6, 6)]), "камень на ровном");
        assertTrue((rgb(px[RegionData.index(8, 8)]) & 0xFF) > 0x80, "выше соседа светлее");
        assertTrue((rgb(px[RegionData.index(10, 10)]) & 0xFF) < 0x80, "ниже соседа темнее");
        assertEquals(0x222222, rgb(px[RegionData.index(13, 12)]), "блока нет в игре: цвет неизвестного");
        assertEquals(Baker.hatch(0x222222) & 0xFFFFFF, rgb(px[RegionData.index(12, 12)]), "каждый четвёртый столбец по диагонали светлее: штриховка");
        assertNotEquals(rgb(px[RegionData.index(12, 12)]), rgb(px[RegionData.index(13, 12)]));
        int shallow = rgb(px[RegionData.index(3, 3)]), deep = rgb(px[RegionData.index(3, 4)]);
        assertTrue((shallow & 0xFF) > 0x80 && (deep & 0xFF) > 0x80, "вода синяя");
        assertTrue(((deep >> 16) & 0xFF) < ((shallow >> 16) & 0xFF), "вода без глубины темнее мелкой");
        assertEquals(0, px[RegionData.index(1, 14)], "столбец без данных прозрачный");
        assertEquals(0, px[RegionData.index(20, 20)], "неразведанный чанк прозрачный");
        // кусок 16×16 совпадает с регионом
        int[] chunk = baker.bakeChunk(r, p, Baker.Neighbors.NONE, 0, 0);
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) assertEquals(px[RegionData.index(x, z)], chunk[z * 16 + x]);
    }

    @Test
    void neighborRegionShadow() {
        Palette p = new Palette();
        int stone = p.block("minecraft:stone");
        RegionData r = new RegionData(1, 1);
        r.set(0, 0, 80, stone, 0, 0);
        r.mark(0, 0, 1);
        int[] lower = new Baker(COLORS).bakeChunk(r, p, (x, z) -> {
            assertEquals(511, x);
            assertEquals(511, z);
            return 60;
        }, 0, 0);
        int[] alone = new Baker(COLORS).bakeChunk(r, p, Baker.Neighbors.NONE, 0, 0);
        assertTrue((rgb(lower[0]) & 0xFF) > (rgb(alone[0]) & 0xFF), "сосед в другом регионе ниже: светлее");
    }

    @Test
    void paletteGrowthAndColorReload() {
        Palette p = new Palette();
        int stone = p.block("minecraft:stone");
        RegionData r = new RegionData(0, 0);
        r.set(0, 0, 64, stone, 0, 0);
        r.mark(0, 0, 1);
        Baker baker = new Baker(COLORS);
        baker.bakeChunk(r, p, Baker.Neighbors.NONE, 0, 0);
        int sand = p.block("minecraft:sand");
        r.set(1, 0, 64, sand, 0, 0);
        int[] px = baker.bakeChunk(r, p, Baker.Neighbors.NONE, 0, 0);
        assertEquals(0xE0D0A0, rgb(px[1]), "новый номер палитры подхвачен");
    }
}
