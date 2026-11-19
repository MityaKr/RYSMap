package works.repka.rysmap.hud;

import java.util.ArrayList;
import java.util.List;

/**
 * Кучи значков мобов (отзыв автора 19.11, макет {@code _shared/_audit/map/mobs}): значки, которые на экране легли бы друг
 * на друга, схлопываются в один значок с числом. Чистая часть без классов игры, проверяется модульным тестом.
 *
 * <p>Порядок важности: босс, враждебный, питомец, мирный. Куча начинается с самого важного моба и стоит на его месте,
 * поэтому значок не плавает, когда соседи ходят. Цвет кучи по самому важному виду в ней, голова по самому частому типу
 * этого вида: в толпе из пяти зомби и коровы видна голова зомби в красном кольце и число 6.
 */
public final class MobPiles {
    public static final int PASSIVE = 0, PET = 1, HOSTILE = 2, BOSS = 3;

    /** Одна куча: место первого моба, его вид, моб с головой и все мобы кучи (номера во входных массивах). */
    public static final class Pile {
        public final float x, y;
        public final int rank;
        int lead;
        float alpha;
        int count;
        int[] members = new int[4];

        Pile(int first, float x, float y, int rank, float alpha) {
            this.x = x;
            this.y = y;
            this.rank = rank;
            this.lead = first;
            this.alpha = alpha;
            add(first, alpha);
        }

        void add(int i, float a) {
            if (count == members.length) members = java.util.Arrays.copyOf(members, count * 2);
            members[count++] = i;
            if (a > alpha) alpha = a;
        }

        /** Моб, чья голова рисуется. */
        public int lead() { return lead; }
        public int count() { return count; }
        /** Самый яркий моб кучи: куча с мобом своего слоя не бледнеет. */
        public float alpha() { return alpha; }
        public int member(int k) { return members[k]; }
    }

    private MobPiles() {}

    /**
     * Сгруппировать значки. {@code xy} места на экране парами, {@code rank} вид ({@link #BOSS} и т. д.), {@code type} номер
     * типа моба (одинаковый у одинаковых мобов), {@code gap} расстояние между серединами, ближе которого значки сливаются.
     */
    public static List<Pile> group(float[] xy, int[] rank, int[] type, float[] alpha, int n, float gap) {
        List<Pile> piles = new ArrayList<>();
        if (n <= 0) return piles;
        // самые важные первыми, при равенстве по порядку во входе
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> rank[b] != rank[a] ? Integer.compare(rank[b], rank[a]) : Integer.compare(a, b));
        float gap2 = gap * gap;
        for (int k = 0; k < n; k++) {
            int i = order[k];
            float x = xy[i * 2], y = xy[i * 2 + 1];
            Pile into = null;
            float best = gap2;
            for (Pile p : piles) {
                float dx = p.x - x, dy = p.y - y, d = dx * dx + dy * dy;
                if (d < best) {
                    best = d;
                    into = p;
                }
            }
            if (into == null) piles.add(new Pile(i, x, y, rank[i], alpha[i]));
            else into.add(i, alpha[i]);
        }
        // голова кучи: самый частый тип среди мобов её вида
        for (Pile p : piles) {
            if (p.count < 2) continue;
            int bestType = -1, bestCount = 0;
            for (int a = 0; a < p.count; a++) {
                int i = p.members[a];
                if (rank[i] != p.rank) continue;
                int c = 0;
                for (int b = 0; b < p.count; b++) {
                    int j = p.members[b];
                    if (rank[j] == p.rank && type[j] == type[i]) c++;
                }
                if (c > bestCount) {
                    bestCount = c;
                    bestType = i;
                }
            }
            if (bestType >= 0) p.lead = bestType;
        }
        return piles;
    }

    /** Подпись числа на значке кучи: больше 99 пишется «99+». */
    public static String countText(int count) {
        return count > 99 ? "99+" : Integer.toString(count);
    }
}
