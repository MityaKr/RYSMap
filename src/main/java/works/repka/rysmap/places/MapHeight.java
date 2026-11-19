package works.repka.rysmap.places;

import org.jetbrains.annotations.Nullable;
import works.repka.rysmap.scan.Floors;

/**
 * Высота места, поставленного с карты (просьба автора 19.11: «Дом здесь» над базой встал на крышу). Чистая часть без игры,
 * проверяется модульным тестом.
 *
 * <p>С высотой место садится на ближайший к ней пол своего столбца (сервер, {@code SafeSpot}), без высоты на верх столбца,
 * то есть на крышу. Поэтому высота даётся, когда она известна:
 * <ul>
 *   <li>окно в режиме «Пещеры»: середина показанного этажа, посадка на пол этого этажа;</li>
 *   <li>поверхность рядом с игроком (до {@value #NEAR} блоков по горизонтали, то же измерение): высота ног игрока, дом
 *       встаёт внутрь постройки, где игрок стоит;</li>
 *   <li>иначе высоты нет (null): верх столбца, как раньше, и ответ сервера об этом предупреждает.</li>
 * </ul>
 */
public final class MapHeight {
    /** Ближе этого по горизонтали точка с карты считается местом игрока. */
    public static final double NEAR = 6;

    private MapHeight() {}

    /**
     * @param floor     показанный этаж пещер или null (поверхность)
     * @param sameDim   игрок в измерении карты
     * @param bx, bz    середина блока, куда ставят
     * @return высота ног или null (верх столбца)
     */
    public static @Nullable Double pick(@Nullable Integer floor, boolean sameDim, double px, double py, double pz, double bx, double bz) {
        if (floor != null) return (double) (Floors.bottom(floor) + 8);
        if (!sameDim) return null;
        double dx = bx - px, dz = bz - pz;
        return dx * dx + dz * dz <= NEAR * NEAR ? py : null;
    }
}
