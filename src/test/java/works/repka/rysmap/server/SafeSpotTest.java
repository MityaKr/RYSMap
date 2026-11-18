package works.repka.rysmap.server;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import works.repka.rysmap.server.SafeSpot.Kind;

class SafeSpotTest {
    /** Мир из словаря: по умолчанию воздух, пол задаётся столбцами. */
    static final class World implements SafeSpot.Probe {
        final Map<Long, Kind> blocks = new HashMap<>();
        final int minY;

        World(int minY) {
            this.minY = minY;
        }

        static long key(int x, int y, int z) {
            return ((long) x & 0xFFFFF) | (((long) z & 0xFFFFF) << 20) | (((long) (y + 2048) & 0xFFF) << 40);
        }

        World set(int x, int y, int z, Kind k) {
            blocks.put(key(x, y, z), k);
            return this;
        }

        /** Столбец пола от minY до y включительно. */
        World ground(int x, int z, int y) {
            for (int i = minY; i <= y; i++) set(x, i, z, Kind.FLOOR);
            return this;
        }

        World groundAll(int r, int y) {
            for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) ground(x, z, y);
            return this;
        }

        @Override
        public Kind at(int x, int y, int z) {
            return blocks.getOrDefault(key(x, y, z), Kind.FREE);
        }

        @Override
        public int top(int x, int z) {
            for (int y = 320; y >= minY; y--) if (at(x, y, z) != Kind.FREE) return y;
            return minY - 1;
        }
    }

    @Test
    void surface() {
        World w = new World(-64).groundAll(8, 70);
        assertArrayEquals(new int[]{3, 71, -2}, SafeSpot.find(w, 3, -2, -64, 319, false, SafeSpot.NO_HINT));
    }

    @Test
    void lavaUnderCursorMovesToNeighbour() {
        World w = new World(-64).groundAll(8, 70);
        w.set(0, 70, 0, Kind.DANGER);
        int[] s = SafeSpot.find(w, 0, 0, -64, 319, false, SafeSpot.NO_HINT);
        assertNotNull(s);
        assertEquals(71, s[1]);
        assertEquals(1, Math.abs(s[0]) + Math.abs(s[2]), "ближайший соседний столбец");
    }

    @Test
    void waterSurfaceIsNotFloor() {
        World w = new World(-64);
        // океан вокруг, остров в 4 блоках
        for (int x = -8; x <= 8; x++)
            for (int z = -8; z <= 8; z++) {
                w.ground(x, z, 50);
                for (int y = 51; y <= 62; y++) w.set(x, y, z, Kind.FLUID);
            }
        w.ground(4, 0, 64);
        assertArrayEquals(new int[]{4, 65, 0}, SafeSpot.find(w, 0, 0, -64, 319, false, SafeSpot.NO_HINT));
    }

    @Test
    void voidHasNoSpot() {
        World w = new World(0);
        assertNull(SafeSpot.find(w, 100, 100, 0, 255, false, SafeSpot.NO_HINT));
    }

    /** Мир, где верх столбца по WORLD_SURFACE учитывает траву и снег слоем (как у игры), а не только пол. */
    static final class Grassy implements SafeSpot.Probe {
        final World base;
        final java.util.Set<Long> plants = new java.util.HashSet<>();

        Grassy(World base) {
            this.base = base;
        }

        @Override
        public Kind at(int x, int y, int z) {
            return base.at(x, y, z);
        }

        @Override
        public int top(int x, int z) {
            int t = base.top(x, z);
            return plants.contains(World.key(x, t + 1, z)) ? t + 1 : t;
        }
    }

    @Test
    void grassAndSnowLayerOnTopAreSteppedThrough() {
        World w = new World(-64).groundAll(8, 70);
        Grassy g = new Grassy(w);
        for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++) g.plants.add(World.key(x, 71, z)); // трава везде
        assertArrayEquals(new int[]{0, 71, 0}, SafeSpot.find(g, 0, 0, -64, 319, false, SafeSpot.NO_HINT));
    }

    @Test
    void fenceOnTopMovesToNeighbour() {
        World w = new World(-64).groundAll(8, 70);
        w.set(0, 71, 0, Kind.BLOCKED); // забор: сверху не встать
        int[] s = SafeSpot.find(w, 0, 0, -64, 319, false, SafeSpot.NO_HINT);
        assertNotNull(s);
        assertEquals(71, s[1]);
        assertNotEquals(0, Math.abs(s[0]) + Math.abs(s[2]));
    }

    @Test
    void netherPicksFloorNearHint() {
        World w = new World(0);
        for (int x = -8; x <= 8; x++)
            for (int z = -8; z <= 8; z++) {
                w.ground(x, z, 31); // нижний пол
                for (int y = 60; y <= 64; y++) w.set(x, y, z, Kind.FLOOR); // средний пол 64
                for (int y = 100; y <= 127; y++) w.set(x, y, z, Kind.FLOOR); // потолок
            }
        assertEquals(65, SafeSpot.find(w, 0, 0, 0, 127, true, 70)[1]);
        assertEquals(32, SafeSpot.find(w, 0, 0, 0, 127, true, 20)[1]);
        // без подсказки середина измерения: ближе средний пол
        assertEquals(65, SafeSpot.find(w, 0, 0, 0, 127, true, SafeSpot.NO_HINT)[1]);
    }

    @Test
    void hintInOverworldFindsCave() {
        World w = new World(-64).groundAll(8, 70);
        // пещера на 20..22 под центром
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) for (int y = 20; y <= 22; y++) w.set(x, y, z, Kind.FREE);
        assertArrayEquals(new int[]{0, 20, 0}, SafeSpot.find(w, 0, 0, -64, 319, false, 21));
    }

    @Test
    void ringStartsAtCenterAndGrows() {
        var ring = SafeSpot.ring();
        assertArrayEquals(new int[]{0, 0}, ring.get(0));
        int prev = 0;
        for (int[] d : ring) {
            int r = d[0] * d[0] + d[1] * d[1];
            assertTrue(r >= prev);
            prev = r;
        }
    }
}
