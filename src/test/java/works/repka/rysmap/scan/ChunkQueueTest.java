package works.repka.rysmap.scan;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Очередь сканера: отсев недавно снятых не трогает срочные. */
class ChunkQueueTest {
    @Test
    void dropNormalKeepsUrgent() {
        ChunkQueue q = new ChunkQueue();
        for (int i = 0; i < 10; i++) q.add(i, 0);
        q.addUrgent(3, 0);
        q.addUrgent(50, 50);
        int dropped = q.dropNormal(k -> ChunkQueue.x(k) % 2 == 0);
        assertEquals(5, dropped);
        assertTrue(q.isUrgent(ChunkQueue.key(50, 50)));
        assertEquals(ChunkQueue.key(50, 50), q.pollNearest(50, 50, k -> true), "срочный остался");
        long k = q.pollNearest(0, 0, key -> true);
        assertEquals(1, ChunkQueue.x(k) % 2, "в обычной очереди остались только нечётные");
    }
}
