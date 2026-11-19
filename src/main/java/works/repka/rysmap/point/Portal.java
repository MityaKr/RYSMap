package works.repka.rysmap.point;

import org.jetbrains.annotations.Nullable;

/**
 * Координаты Верхнего мира в Нижнем и обратно (план карты, раздел 14): Нижний мир в 8 раз меньше по X и Z, высота не
 * меняется. Нужны, чтобы строить портал в пару: курсор карты, панель точки и места показывают место в другом мире.
 * Чистый класс, без игры.
 */
public final class Portal {
    public static final String OVERWORLD = "minecraft:overworld", NETHER = "minecraft:the_nether";

    private Portal() {}

    /** Парное измерение или null, если у измерения пары нет (Край, измерения модов). */
    public static @Nullable String other(String dimension) {
        return switch (dimension) {
            case OVERWORLD -> NETHER;
            case NETHER -> OVERWORLD;
            default -> null;
        };
    }

    /** Место в парном измерении: из Верхнего делится на 8 вниз (как у игры), из Нижнего умножается на 8. */
    public static int @Nullable [] toOther(String dimension, int x, int z) {
        return switch (dimension) {
            case OVERWORLD -> new int[]{Math.floorDiv(x, 8), Math.floorDiv(z, 8)};
            case NETHER -> new int[]{x * 8, z * 8};
            default -> null;
        };
    }
}
