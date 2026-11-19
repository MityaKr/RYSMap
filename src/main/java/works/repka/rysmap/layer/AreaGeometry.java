package works.repka.rysmap.layer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.LongFunction;

/**
 * Геометрия областей одного региона 32×32 чанка в координатах чанков (разбор логики 19.11, п. 3): полосы заливки
 * (подряд идущие чанки строки одного цвета и вида) и края групп (сосед другой группы или пустой, в том числе через
 * границу региона). Строится один раз на ревизию региона и его четырёх соседей; кадр только переводит числа в экран.
 * Чистый класс, без игры.
 */
public final class AreaGeometry {
    /** Стороны края: сверху, снизу, слева, справа. */
    static final int[][] DIRS = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
    public static final AreaGeometry EMPTY = new AreaGeometry(new int[0], 0, new int[0], 0);

    /** По пять чисел на полосу: z чанка, x первого, x последнего, цвет, вид. */
    final int[] runs;
    final int runCount;
    /** По пять чисел на край: x и z чанка, сторона (номер в {@link #DIRS}), цвет, вид. */
    final int[] edges;
    final int edgeCount;

    private AreaGeometry(int[] runs, int runCount, int[] edges, int edgeCount) {
        this.runs = runs;
        this.runCount = runCount;
        this.edges = edges;
        this.edgeCount = edgeCount;
    }

    public int runCount() {
        return runCount;
    }

    public int edgeCount() {
        return edgeCount;
    }

    /**
     * @param chunks   чанки региона
     * @param neighbor чанк по ключу {@code RegionData.key(x, z)} в этом регионе или соседних, null — пусто
     */
    public static AreaGeometry build(List<Layers.AreaChunk> chunks, LongFunction<Layers.AreaChunk> neighbor) {
        if (chunks.isEmpty()) return EMPTY;
        List<Layers.AreaChunk> sorted = new ArrayList<>(chunks);
        sorted.sort(Comparator.comparingInt(Layers.AreaChunk::chunkZ).thenComparingInt(Layers.AreaChunk::chunkX));
        int[] runs = new int[sorted.size() * 5];
        int rc = 0;
        for (int i = 0; i < sorted.size(); ) {
            Layers.AreaChunk first = sorted.get(i);
            int j = i + 1;
            while (j < sorted.size()) {
                Layers.AreaChunk nx = sorted.get(j), pv = sorted.get(j - 1);
                if (nx.chunkZ() != first.chunkZ() || nx.chunkX() != pv.chunkX() + 1 || nx.color() != first.color() || nx.style() != first.style()) break;
                j++;
            }
            int o = rc * 5;
            runs[o] = first.chunkZ();
            runs[o + 1] = first.chunkX();
            runs[o + 2] = sorted.get(j - 1).chunkX();
            runs[o + 3] = first.color();
            runs[o + 4] = first.style().ordinal();
            rc++;
            i = j;
        }
        int[] edges = new int[sorted.size() * 4 * 5];
        int ec = 0;
        for (Layers.AreaChunk c : sorted) {
            for (int d = 0; d < 4; d++) {
                Layers.AreaChunk nb = neighbor.apply(key(c.chunkX() + DIRS[d][0], c.chunkZ() + DIRS[d][1]));
                if (nb != null && nb.group() == c.group()) continue;
                int o = ec * 5;
                edges[o] = c.chunkX();
                edges[o + 1] = c.chunkZ();
                edges[o + 2] = d;
                edges[o + 3] = c.color();
                edges[o + 4] = c.style().ordinal();
                ec++;
            }
        }
        return new AreaGeometry(runs, rc, edges, ec);
    }

    /** Тот же ключ, что {@code RegionData.key}. */
    static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
