package works.repka.rysmap.screen;

/**
 * Раскладка строки настроек (0.2.10, отзыв автора 19.11: «настройки поплыли»): подпись с пояснением слева, переключатель
 * справа. Чистая часть без игры, проверяется модульным тестом.
 *
 * <p>Переключатель стоит справа в той же строке, если подпись и пояснение помещаются слева от него с зазором
 * {@value #GAP}. Иначе он уходит под подпись, к левому краю, а пояснение переносится по ширине строки (до двух строк).
 * Высота строки считается по содержимому, поэтому строки не налезают друг на друга.
 */
final class RowLayout {
    static final int GAP = 10, CONTROL_H = 18;
    /** Шаг строк пояснения и высота подписи с пояснением в одну строку. */
    static final int NOTE_LINE = 7, LABEL_BLOCK = 17, LABEL_ONLY = 11;

    /**
     * @param below      переключатель под подписью
     * @param height     высота строки вместе с отступом до следующей
     * @param controlDy  верх переключателя от верха строки
     * @param noteLines  строк пояснения (0: пояснения нет или оно в подсказке)
     */
    record Place(boolean below, int height, int controlDy, int noteLines) {}

    private RowLayout() {}

    /**
     * @param labelW    ширина подписи
     * @param noteW     ширина пояснения в одну строку (0: нет или в подсказке)
     * @param controlW  ширина переключателя
     * @param width     ширина строки
     */
    static Place place(float labelW, float noteW, float controlW, int width) {
        boolean note = noteW > 0;
        if (Math.max(labelW, noteW) + GAP + controlW <= width) {
            int h = note ? Math.max(LABEL_BLOCK, CONTROL_H) + 4 : CONTROL_H + 3;
            return new Place(false, h, 0, note ? 1 : 0);
        }
        int lines = note ? Math.min(2, (int) Math.ceil(noteW / Math.max(1, width))) : 0;
        int block = note ? LABEL_BLOCK + (lines - 1) * NOTE_LINE : LABEL_ONLY;
        int dy = block + 3;
        return new Place(true, dy + CONTROL_H + 5, dy, lines);
    }
}
