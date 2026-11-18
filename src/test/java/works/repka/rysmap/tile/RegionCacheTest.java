package works.repka.rysmap.tile;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

class RegionCacheTest {
    /** Диск в памяти: считает чтения и записи. */
    static final class FakeStore implements RegionCache.Store {
        final Map<Long, RegionData> disk = new HashMap<>();
        final List<Long> loads = new ArrayList<>(), saves = new ArrayList<>();

        @Override
        public @Nullable RegionData load(int x, int z) {
            loads.add(RegionData.key(x, z));
            return disk.get(RegionData.key(x, z));
        }

        @Override
        public void save(RegionData r) {
            saves.add(RegionData.key(r.x, r.z));
            disk.put(RegionData.key(r.x, r.z), r);
        }
    }

    @Test
    void capIsHardAndDirtyIsSavedBeforeEviction() throws IOException {
        FakeStore store = new FakeStore();
        RegionCache cache = new RegionCache(store, 9);
        for (int i = 0; i < 20; i++) {
            RegionData r = cache.getOrCreate(i, 0);
            r.set(0, 0, 64, 1, 1, 0);
            r.mark(0, 0, 1);
            assertTrue(cache.size() <= 9, "потолок держится: " + cache.size());
        }
        assertEquals(9, cache.size());
        assertEquals(11, store.saves.size(), "вытесненные записаны");
        // вытеснялись самые старые
        assertFalse(cache.contains(0, 0));
        assertTrue(cache.contains(19, 0));
        assertTrue(store.disk.containsKey(RegionData.key(0, 0)));
        assertEquals(cache.size() * RegionData.BYTES, cache.bytes());
    }

    @Test
    void pinnedRegionsStay() throws IOException {
        FakeStore store = new FakeStore();
        RegionCache cache = new RegionCache(store, 10);
        cache.pinAround(0, 0);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) cache.getOrCreate(dx, dz);
        for (int i = 0; i < 30; i++) cache.getOrCreate(100 + i, 100);
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) assertTrue(cache.contains(dx, dz), "закреплённый " + dx + "," + dz + " на месте");
        assertEquals(10, cache.size());
    }

    @Test
    void absenceIsRemembered() throws IOException {
        FakeStore store = new FakeStore();
        RegionCache cache = new RegionCache(store, 9);
        assertNull(cache.get(5, 5));
        assertNull(cache.get(5, 5));
        assertNull(cache.get(5, 5));
        assertEquals(1, store.loads.size(), "диск спрошен один раз");
        RegionData created = cache.getOrCreate(5, 5);
        assertSame(created, cache.get(5, 5));
    }

    @Test
    void cleanRegionsAreNotRewritten() throws IOException {
        FakeStore store = new FakeStore();
        RegionData onDisk = new RegionData(1, 1);
        onDisk.dirty = false;
        store.disk.put(RegionData.key(1, 1), onDisk);
        RegionCache cache = new RegionCache(store, 9);
        assertSame(onDisk, cache.get(1, 1));
        cache.flush();
        assertTrue(store.saves.isEmpty(), "чистый регион не пишется");
        onDisk.mark(0, 0, 5);
        cache.flush();
        assertEquals(1, store.saves.size());
        assertFalse(onDisk.dirty);
        cache.close();
        assertEquals(0, cache.size());
        assertEquals(1, store.saves.size(), "после записи повторно не пишется");
    }

    @Test
    void peekDoesNotTouchDisk() throws IOException {
        FakeStore store = new FakeStore();
        store.disk.put(RegionData.key(2, 2), new RegionData(2, 2));
        RegionCache cache = new RegionCache(store, 9);
        assertNull(cache.peek(2, 2));
        assertTrue(store.loads.isEmpty());
    }
}
