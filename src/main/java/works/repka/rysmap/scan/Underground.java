package works.repka.rysmap.scan;

/**
 * Под землёй ли игрок: миникарта показывает этаж пещер вместо поверхности (М5; разбор по отзыву автора 19.11). Чистая
 * часть без игры, проверяется модульным тестом.
 *
 * <p>Как было до 0.2.9: неба над головой не видно и верхний блок своего столбца выше ног больше чем на 8. Верхним блоком
 * считался любой, крыша тоже, поэтому в высоком цехе на уровне земли (VENIK, Y 63) карта уходила в пещеры и показывала
 * только то, что под крышей.
 *
 * <p>Теперь крыша над головой нужна, но мало: игрок должен быть ниже рельефа и вокруг себя. Высоты земли берутся в кольце
 * из {@value #RING_POINTS} точек на расстоянии {@value #RING_RADIUS} блоков (карта высот без листвы), и середина кольца
 * (медиана) выше ног больше чем на {@value #ENTER}. Постройка уже кольца или стоит на ровной земле: вокруг земля на уровне
 * ног, значит поверхность. Пещера под равниной, в горе, шахта под базой: вокруг земля выше, значит пещеры. Точки в
 * незагруженных чанках не считаются; если годных меньше половины, решает старое правило по своему столбцу.
 *
 * <p>Ещё свет неба у ног: входить в пещеры только при нуле (в цех с окнами и открытыми воротами небо светит), выходить,
 * когда он поднялся до {@value #SKY_EXIT} (у выхода из пещеры).
 *
 * <p>Чтобы на пороге пещеры карта не мигала: выход наверх при запасе меньше {@value #EXIT} (гистерезис), и новое решение
 * принимается, только если держится {@value #HOLD} проверки подряд (проверка раз в полсекунды).
 */
public final class Underground {
    public static final int RING_POINTS = 8, RING_RADIUS = 20;
    /** Входить в пещеры, когда земля выше ног больше чем на столько; выходить, когда меньше {@link #EXIT}. */
    public static final int ENTER = 6, EXIT = 3;
    /** Своему столбцу (крыша над головой) хватает этого запаса: как и раньше. */
    public static final int COLUMN_ENTER = 8, COLUMN_EXIT = 4;
    public static final int HOLD = 2;
    /** Свет неба у ног, при котором из пещер выходим. */
    public static final int SKY_EXIT = 8;
    /** Высота точки кольца в незагруженном чанке. */
    public static final int MISSING = Integer.MIN_VALUE;

    private boolean state;
    private int pending;

    /** Смещения точек кольца по X и Z. */
    public static int[][] ring() {
        int[][] out = new int[RING_POINTS][2];
        for (int i = 0; i < RING_POINTS; i++) {
            double a = Math.PI * 2 * i / RING_POINTS;
            out[i][0] = (int) Math.round(Math.cos(a) * RING_RADIUS);
            out[i][1] = (int) Math.round(Math.sin(a) * RING_RADIUS);
        }
        return out;
    }

    /**
     * Решение без задержки.
     *
     * @param current  сейчас под землёй
     * @param sky      над головой видно небо
     * @param skyLight свет неба у ног, 0..15
     * @param column   верхний блок своего столбца минус высота ног
     * @param ring     высоты земли в точках кольца ({@link #MISSING}: чанк не загружен)
     * @param feet     высота ног
     */
    public static boolean decide(boolean current, boolean sky, int skyLight, int column, int[] ring, int feet) {
        if (sky) return false;
        if (current ? skyLight >= SKY_EXIT : skyLight > 0) return false;
        if (column < (current ? COLUMN_EXIT : COLUMN_ENTER + 1)) return false;
        int[] got = new int[ring.length];
        int n = 0;
        for (int h : ring) if (h != MISSING) got[n++] = h;
        // мало данных вокруг: старое правило по своему столбцу
        if (n * 2 < ring.length) return true;
        java.util.Arrays.sort(got, 0, n);
        int median = n % 2 == 1 ? got[n / 2] : (got[n / 2 - 1] + got[n / 2]) / 2;
        int below = median - feet;
        return current ? below >= EXIT : below > ENTER;
    }

    /** Проверка с задержкой: состояние меняется, когда новое решение держится {@link #HOLD} проверки подряд. */
    public boolean update(boolean sky, int skyLight, int column, int[] ring, int feet) {
        boolean want = decide(state, sky, skyLight, column, ring, feet);
        if (want == state) {
            pending = 0;
        } else if (++pending >= HOLD) {
            state = want;
            pending = 0;
        }
        return state;
    }

    public boolean state() {
        return state;
    }

    public void reset() {
        state = false;
        pending = 0;
    }
}
