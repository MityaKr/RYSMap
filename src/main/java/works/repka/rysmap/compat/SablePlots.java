package works.repka.rysmap.compat;

/**
 * Участки Sable: блоки летающих построек лежат в далёких чанках того же измерения. Карта такие чанки не сканирует,
 * иначе в районе 20 миллионов блоков от нуля пеклись бы регионы с мусором.
 *
 * <p>Sable 2.0.6 держит на уровень один контейнер {@code SubLevelContainer(level, 7, 7, 10000, 10000)}: участок 2^7 = 128
 * чанков, сетка 128 × 128 участков со сдвигом 10000. Чанк принадлежит участку, если {@code (chunk >> 7) − 10000} лежит
 * в {@code [0, 128)} по обеим осям (разбор R0, {@code SubLevelContainer.inBounds}). Проверка арифметикой, без классов
 * Sable: зависимости нет, а без Sable она выключена.
 */
public final class SablePlots {
    public static final int LOG_PLOT = 7, OFFSET = 10000, SIDE = 128;
    private static volatile boolean active;

    private SablePlots() {}

    /** Включается, если Sable загружен. */
    public static void setActive(boolean on) {
        active = on;
    }

    public static boolean active() {
        return active;
    }

    /** Чанк участка Sable: не сканировать и не рисовать. */
    public static boolean isPlotChunk(int chunkX, int chunkZ) {
        return active && inPlotGrid(chunkX, chunkZ);
    }

    public static boolean inPlotGrid(int chunkX, int chunkZ) {
        int px = (chunkX >> LOG_PLOT) - OFFSET, pz = (chunkZ >> LOG_PLOT) - OFFSET;
        return px >= 0 && px < SIDE && pz >= 0 && pz < SIDE;
    }
}
