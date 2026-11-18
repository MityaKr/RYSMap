package works.repka.rysmap.scan;

import org.jetbrains.annotations.Nullable;

/**
 * Этажи пещер и Нижнего мира (этап М5, макеты {@code 21}, {@code 22}). Этаж это слой высотой 16 блоков, номер равен
 * номеру секции: этаж {@code n} лежит на высотах {@code 16n … 16n + 15}. Карта этажа хранится как отдельное измерение
 * с id {@code <измерение>/caves/<n>}: своя палитра и свои регионы в подпапке {@code caves/<n>} папки измерения. Так
 * пекарь, кэш регионов, текстуры и файлы работают для этажей без изменений.
 */
public final class Floors {
    private static final String MARK = "/caves/";

    private Floors() {}

    /** id карты этажа. */
    public static String id(String dimension, int floor) {
        return dimension + MARK + floor;
    }

    /** Этаж из id или null, если это поверхность. */
    public static @Nullable Integer floor(String id) {
        int i = id.lastIndexOf(MARK);
        if (i < 0) return null;
        try {
            return Integer.parseInt(id.substring(i + MARK.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Измерение без этажа. */
    public static String base(String id) {
        int i = id.lastIndexOf(MARK);
        return i < 0 ? id : id.substring(0, i);
    }

    /** Этаж, на котором стоят ноги на высоте {@code y}. */
    public static int of(int y) {
        return Math.floorDiv(y, 16);
    }

    public static int bottom(int floor) {
        return floor * 16;
    }

    public static int top(int floor) {
        return floor * 16 + 15;
    }
}
