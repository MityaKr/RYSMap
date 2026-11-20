package works.repka.rysmap.screen;

import org.jetbrains.annotations.Nullable;

/**
 * Плитка слоёв в сайдбаре окна карты (0.2.12, вариант 1 автора 20.11): значки-переключатели сеткой вместо строк.
 * Чистая часть без игры: сколько столбцов и строк, где плитка, какая вкладка настроек у слоя. Слоёв больше, чем
 * помещается в строку, сетка растёт строками, без прокрутки. Проверяется модульным тестом.
 */
final class LayerGrid {
    /** Самая узкая плитка, промежуток между плитками и между строками (место под бейдж числа). */
    static final int MIN_TILE = 16, MAX_TILE = 20, GAP = 2, ROW_GAP = 3;

    private LayerGrid() {}

    /** Сетка {@code n} плиток в полосе от {@code x0} шириной {@code width}. */
    record Grid(int n, int cols, int rows, int tile, int x0) {
        int x(int i) {
            return x0 + (i % cols) * (tile + GAP);
        }

        int y(int i, int top) {
            return top + (i / cols) * (tile + ROW_GAP);
        }

        int height() {
            return rows == 0 ? 0 : rows * tile + (rows - 1) * ROW_GAP;
        }

        /** Номер плитки под точкой или -1. */
        int at(double mx, double my, int top) {
            for (int i = 0; i < n; i++) {
                int x = x(i), y = y(i, top);
                if (mx >= x && mx < x + tile && my >= y && my < y + tile) return i;
            }
            return -1;
        }
    }

    static Grid of(int n, int x0, int width) {
        int cols = Math.max(1, (width + GAP) / (MIN_TILE + GAP));
        int tile = Math.max(1, Math.min(MAX_TILE, (width - GAP * (cols - 1)) / cols));
        // ширина, которую не съели плитки, делится поровну по краям
        int used = cols * tile + (cols - 1) * GAP;
        int rows = n <= 0 ? 0 : (n + cols - 1) / cols;
        return new Grid(Math.max(0, n), cols, rows, tile, x0 + Math.max(0, (width - used) / 2));
    }

    /**
     * Вкладка страницы настроек, где лежат настройки своего слоя (ПКМ по плитке), или null. «Перемещения» видит только
     * оператор: без неё у слоя «Места» настроек нет.
     */
    static @Nullable String settingsTab(String namespace, String path, boolean placesTab) {
        if (!"rysmap".equals(namespace)) return null;
        return switch (path) {
            case "mobs" -> SettingsTabs.MINIMAP;
            case "points" -> SettingsTabs.WORLD;
            case "places" -> placesTab ? SettingsTabs.PLACES : null;
            default -> null;
        };
    }
}
