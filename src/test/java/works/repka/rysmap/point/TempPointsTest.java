package works.repka.rysmap.point;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Точки на время: срок переживает файл, истёкшие уходят, постоянные остаются. */
class TempPointsTest {
    @TempDir
    Path tmp;

    @Test
    void expireAndRoundTrip() throws Exception {
        PointStore s = new PointStore();
        PointStore.Point keep = new PointStore.Point();
        keep.name = "Дом";
        PointStore.Point temp = new PointStore.Point();
        temp.name = "Метка";
        temp.until = 1000;
        s.add(keep);
        s.add(temp);
        Path f = tmp.resolve("points.json");
        s.save(f);
        PointStore back = PointStore.load(f);
        assertEquals(1000, back.find(temp.id).until, "срок пишется в файл");
        assertFalse(back.find(keep.id).temporary());
        long rev = back.revision();
        assertEquals(0, back.expire(999), "срок ещё не прошёл");
        assertEquals(rev, back.revision());
        assertEquals(1, back.expire(1000));
        assertNull(back.find(temp.id));
        assertNotNull(back.find(keep.id), "постоянная точка остаётся");
        assertTrue(back.revision() > rev, "слой точек пересоберётся");
    }
}
