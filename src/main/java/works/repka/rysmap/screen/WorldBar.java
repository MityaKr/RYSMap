package works.repka.rysmap.screen;

import org.jetbrains.annotations.Nullable;

/**
 * Полоса «Мира» сверху полотна (0.2.12, вариант 5 автора 20.11): рядом с «Обзор | Приват» выбор измерения и
 * «Поверхность | Пещеры», под ними строка этажа, когда включены пещеры. Чистая часть без игры: что куда встаёт.
 *
 * <p>Порядок уступок, когда места мало: сначала список измерения сужается до {@code dropMin} (имя с многоточием и
 * подсказкой), потом «Поверхность | Пещеры» остаются значками (подписи в подсказке), потом группа уходит строкой ниже
 * переключателя режимов, где справа уже нет кнопок масштаба. Проверяется модульным тестом.
 */
final class WorldBar {
    /** Высота плашки, промежуток между плашками и строками, поля внутри плашки, промежуток между списком и сегментами. */
    static final int H = 18, GAP = 4, PAD = 2, INNER = 3;

    private WorldBar() {}

    /** Плашка: место и ширина. */
    record Box(int x, int y, int w) {}

    /**
     * Итог: плашка режимов (null без режимов), плашка мира, ширина списка измерения, подписи у сегментов или только
     * значки, строка этажа (null без пещер) и низ последней строки.
     */
    record Plan(@Nullable Box modes, Box world, int dropW, boolean segLabels, @Nullable Box floor, int bottom) {}

    static int worldWidth(int dropW, int segW) {
        return PAD + dropW + INNER + segW + PAD;
    }

    /**
     * @param left      левый край полосы
     * @param top       верх первой строки
     * @param rightTop  правый край первой строки (слева от кнопок справки и масштаба)
     * @param right     правый край строк ниже
     * @param modesW    ширина плашки режимов, 0 без режимов
     * @param dropPref  ширина списка измерения по имени
     * @param dropMin   самая узкая ширина списка
     * @param segFull   ширина сегментов с подписями
     * @param segIcons  ширина сегментов значками
     * @param floorW    ширина строки этажа, 0 без пещер
     */
    static Plan plan(int left, int top, int rightTop, int right, int modesW, int dropPref, int dropMin, int segFull, int segIcons, int floorW) {
        Box modes = modesW > 0 ? new Box(left, top, modesW) : null;
        int x1 = modes == null ? left : left + modesW + GAP;
        int[] fit = fit(rightTop - x1, dropPref, dropMin, segFull, segIcons);
        Box world;
        int dropW;
        boolean labels;
        int rowBottom;
        if (fit != null) {
            dropW = fit[0];
            labels = fit[1] == 1;
            world = new Box(x1, top, worldWidth(dropW, labels ? segFull : segIcons));
            rowBottom = top + H;
        } else {
            // строкой ниже: справа больше нет кнопок; не влезло и там — самое узкое, обрезается краем
            int y = top + H + GAP;
            fit = fit(right - left, dropPref, dropMin, segFull, segIcons);
            dropW = fit != null ? fit[0] : Math.max(1, Math.min(dropMin, right - left - worldWidth(0, segIcons)));
            labels = fit != null && fit[1] == 1;
            world = new Box(left, y, worldWidth(dropW, labels ? segFull : segIcons));
            rowBottom = y + H;
        }
        Box floor = null;
        if (floorW > 0) {
            floor = new Box(left, rowBottom + GAP, Math.min(floorW, Math.max(1, right - left)));
            rowBottom = floor.y() + H;
        }
        return new Plan(modes, world, dropW, labels, floor, rowBottom);
    }

    /** Ширина списка и подписи (1) или значки (0), которые помещаются в {@code room}, или null. */
    private static int @Nullable [] fit(int room, int dropPref, int dropMin, int segFull, int segIcons) {
        int min = Math.min(dropPref, dropMin);
        for (int seg : new int[]{segFull, segIcons}) {
            int drop = Math.min(dropPref, room - worldWidth(0, seg));
            if (drop >= min) return new int[]{drop, seg == segFull ? 1 : 0};
        }
        return null;
    }
}
