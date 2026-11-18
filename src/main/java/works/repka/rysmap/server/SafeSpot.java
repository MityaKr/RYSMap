package works.repka.rysmap.server;

import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * Безопасное место для телепорта (чистая часть, без игры): твёрдый блок под ногами и два свободных блока над ним, без
 * лавы, огня и прочего, что ранит, не в пустоте. Ищется в столбце под курсором, затем по кругу соседних столбцов до
 * {@link #RADIUS} блоков, ближние первыми.
 *
 * <p>В измерении без потолка берётся верхняя поверхность столбца: первый непустой блок сверху, если на нём можно стоять.
 * Если задана высота-подсказка (этаж пещер), выбирается подходящее место, ближайшее к ней. В измерении с потолком
 * (Нижний мир) перебирается весь столбец ниже потолка и берётся место, ближайшее к подсказке.
 */
public final class SafeSpot {
    /** Насколько далеко от столбца под курсором искать, блоки. */
    public static final int RADIUS = 6;
    /** Подсказки высоты нет. */
    public static final int NO_HINT = Integer.MIN_VALUE;

    /** Что за блок для стоящего игрока. */
    public enum Kind {
        /** Можно стоять внутри: воздух, трава, цветы, снег слоем. */
        FREE,
        /** Можно стоять сверху. */
        FLOOR,
        /** Вода и другие жидкости: не встать и не стоять сверху. */
        FLUID,
        /** Ранит: лава, огонь, магма, кактус, ягодный куст, паутина, рыхлый снег. */
        DANGER,
        /** Мешает, но стоять сверху нельзя: забор, стена, высокие блоки. */
        BLOCKED
    }

    /** Мир для поиска. */
    public interface Probe {
        Kind at(int x, int y, int z);

        /** Высота первого непустого блока сверху в столбце (как карта высот {@code WORLD_SURFACE} минус один). */
        int top(int x, int z);
    }

    private SafeSpot() {}

    /**
     * @param minY      нижний блок мира
     * @param maxY      верхний блок, где ещё можно стоять (у измерения с потолком: под потолком)
     * @param ceiling   измерение с потолком
     * @param hint      высота ног-подсказка или {@link #NO_HINT}
     * @return {x, y, z} блока, где окажутся ноги, или null
     */
    public static int @Nullable [] find(Probe probe, int x, int z, int minY, int maxY, boolean ceiling, int hint) {
        for (int[] d : ring()) {
            int cx = x + d[0], cz = z + d[1];
            Integer y = column(probe, cx, cz, minY, maxY, ceiling, hint);
            if (y != null) return new int[]{cx, y, cz};
        }
        return null;
    }

    /** Высота ног в столбце или null. */
    static @Nullable Integer column(Probe probe, int x, int z, int minY, int maxY, boolean ceiling, int hint) {
        if (!ceiling && hint == NO_HINT) {
            int top = Math.min(probe.top(x, z), maxY);
            // верхний блок столбца по WORLD_SURFACE может быть травой, цветком, снегом слоем или факелом: у них нет
            // формы столкновения, стоять надо на опоре под ними (перепроверка 18.11, пункт 4)
            while (top >= minY && probe.at(x, top, z) == Kind.FREE) top--;
            if (top < minY) return null; // пустота
            return stand(probe, x, top, z) ? top + 1 : null;
        }
        Integer best = null;
        int target = hint == NO_HINT ? (minY + maxY) / 2 : hint;
        for (int y = minY; y + 2 <= maxY; y++) {
            if (!stand(probe, x, y, z)) continue;
            int feet = y + 1;
            if (best == null || Math.abs(feet - target) < Math.abs(best - target)) best = feet;
        }
        return best;
    }

    /** На блоке {@code y} можно стоять: он пол, а два над ним свободны. */
    static boolean stand(Probe probe, int x, int y, int z) {
        return probe.at(x, y, z) == Kind.FLOOR && probe.at(x, y + 1, z) == Kind.FREE && probe.at(x, y + 2, z) == Kind.FREE;
    }

    private static final List<int[]> RING = build();

    /** Смещения столбцов по возрастанию расстояния, центр первым. */
    static List<int[]> ring() {
        return RING;
    }

    private static List<int[]> build() {
        List<int[]> out = new ArrayList<>();
        for (int dz = -RADIUS; dz <= RADIUS; dz++)
            for (int dx = -RADIUS; dx <= RADIUS; dx++)
                if (dx * dx + dz * dz <= RADIUS * RADIUS) out.add(new int[]{dx, dz});
        out.sort((a, b) -> Integer.compare(a[0] * a[0] + a[1] * a[1], b[0] * b[0] + b[1] * b[1]));
        return List.copyOf(out);
    }
}
