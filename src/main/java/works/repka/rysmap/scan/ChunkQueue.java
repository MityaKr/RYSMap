package works.repka.rysmap.scan;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.function.LongPredicate;

/**
 * Чанки, ждущие снимка, по расстоянию до игрока: ближний первым. Обычные чанки снимаются, только если давно не
 * сканировались; «срочные» (правка блока) снимаются всегда. Хранятся только координаты, восемь байт на чанк.
 */
public final class ChunkQueue {
    private final LongOpenHashSet normal = new LongOpenHashSet(), urgent = new LongOpenHashSet();

    public static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public static int x(long key) {
        return (int) (key >> 32);
    }

    public static int z(long key) {
        return (int) key;
    }

    public void add(int chunkX, int chunkZ) {
        long k = key(chunkX, chunkZ);
        if (!urgent.contains(k)) normal.add(k);
    }

    public void addUrgent(int chunkX, int chunkZ) {
        long k = key(chunkX, chunkZ);
        normal.remove(k);
        urgent.add(k);
    }

    public void remove(int chunkX, int chunkZ) {
        long k = key(chunkX, chunkZ);
        normal.remove(k);
        urgent.remove(k);
    }

    public boolean isUrgent(long key) {
        return urgent.contains(key);
    }

    /**
     * Ближайший к точке чанк, для которого {@code ready} истина, вынимается из очереди. Срочные идут раньше обычных.
     *
     * @return ключ или {@link Long#MIN_VALUE}, если готовых нет
     */
    public long pollNearest(int centerX, int centerZ, LongPredicate ready) {
        long best = nearest(urgent, centerX, centerZ, ready);
        if (best != Long.MIN_VALUE) {
            urgent.remove(best);
            return best;
        }
        best = nearest(normal, centerX, centerZ, ready);
        if (best != Long.MIN_VALUE) normal.remove(best);
        return best;
    }

    private static long nearest(LongOpenHashSet set, int cx, int cz, LongPredicate ready) {
        long best = Long.MIN_VALUE;
        long bestD = Long.MAX_VALUE;
        for (LongIterator it = set.iterator(); it.hasNext(); ) {
            long k = it.nextLong();
            long dx = x(k) - cx, dz = z(k) - cz;
            long d = dx * dx + dz * dz;
            if (d < bestD && ready.test(k)) {
                bestD = d;
                best = k;
            }
        }
        return best;
    }

    public int size() {
        return normal.size() + urgent.size();
    }

    public void clear() {
        normal.clear();
        urgent.clear();
    }
}
