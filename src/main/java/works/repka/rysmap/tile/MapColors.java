package works.repka.rysmap.tile;

/**
 * Цвета для пекаря по строковым id: реализация в игре читает атлас и биомы, тесты дают свои. Зовётся из рабочего потока,
 * поэтому реализация отвечает из готовых неизменяемых таблиц.
 */
public interface MapColors {
    int TINT_NONE = 0, TINT_GRASS = 1, TINT_FOLIAGE = 2, TINT_WATER = 3;

    /** Средний цвет верхней грани без оттенка, ARGB; альфа это доля непрозрачных пикселей. 0: блока в игре нет. */
    int block(String blockId);

    /** Оттенок: {@link #TINT_NONE}, {@link #TINT_GRASS}, {@link #TINT_FOLIAGE}, {@link #TINT_WATER} или готовый цвет {@code 0xFF rrggbb}. */
    int tint(String blockId);

    /** Цвета биома RGB; неизвестный биом даёт цвета равнин. */
    int grass(String biomeId);

    int foliage(String biomeId);

    int water(String biomeId);

    /** Средний цвет текстуры воды без оттенка, RGB. */
    int waterTexture();

    /** Цвет неизвестного блока (мод убран), ARGB: токен {@code map.unknown}. */
    int unknown();

    /** Растёт, когда таблицы перечитаны (смена ресурспака): пекарь сбрасывает свой кэш. */
    long version();

    /** Свет, который блок даёт сам (0–15): для слоя «Свет» (0.2.3). */
    default int light(String blockId) {
        return 0;
    }
}
