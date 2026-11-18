package works.repka.rysmap.scan;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.function.LongConsumer;

/**
 * Чанки с правками блоков. Перехват {@code ClientLevel.sendBlockUpdated} кладёт сюда чанк, только если состояние блока
 * сменилось (ленты, воронки и машины шлют обновления постоянно, с тем же состоянием). Раз в 10 тиков набранное уходит в
 * очередь сканера срочными. Размер ограничен: лишнее отбрасывается и перерисуется при следующей загрузке чанка.
 */
public final class DirtyColumns {
    public static final int PERIOD_TICKS = 10;
    private final LongOpenHashSet chunks = new LongOpenHashSet();
    private final int cap;
    private int dropped;

    public DirtyColumns(int cap) {
        this.cap = cap;
    }

    public void add(int chunkX, int chunkZ) {
        if (chunks.size() >= cap) {
            dropped++;
            return;
        }
        chunks.add(ChunkQueue.key(chunkX, chunkZ));
    }

    /** Отдаёт набранное и очищает. */
    public void drain(LongConsumer sink) {
        for (LongIterator it = chunks.iterator(); it.hasNext(); ) sink.accept(it.nextLong());
        chunks.clear();
    }

    public int size() {
        return chunks.size();
    }

    public int dropped() {
        return dropped;
    }

    public void clear() {
        chunks.clear();
    }
}
