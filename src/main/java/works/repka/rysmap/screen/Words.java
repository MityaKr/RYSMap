package works.repka.rysmap.screen;

import net.minecraft.network.chat.Component;

/** Числа и слова для подписей карты: формы слова по числу, расстояние, сторона света. */
public final class Words {
    private Words() {}

    /**
     * Форма слова по числу: ключ {@code base.one}, {@code base.few} или {@code base.many} по правилу русского языка
     * (1 блок, 2 блока, 5 блоков). В английском файле {@code few} и {@code many} совпадают.
     */
    public static String plural(long n, String base) {
        long a = Math.abs(n) % 100, b = a % 10;
        String form = a >= 11 && a <= 14 ? "many" : b == 1 ? "one" : b >= 2 && b <= 4 ? "few" : "many";
        return Component.translatable(base + "." + form, works.repka.rysmap.hud.Minimap.group((int) n)).getString();
    }

    /** «190 м» или «1,4 км». */
    public static String distance(double blocks) {
        if (blocks < 10_000) return Component.translatable("rysmap.meters", works.repka.rysmap.hud.Minimap.group((int) Math.round(blocks))).getString();
        return Component.translatable("rysmap.kilometers", String.format(java.util.Locale.ROOT, "%.1f", blocks / 1000).replace('.', ',')).getString();
    }

    /** Сторона света от {@code dx, dz} (север это −Z): «на восток». */
    public static String direction(double dx, double dz) {
        String[] keys = {"east", "southeast", "south", "southwest", "west", "northwest", "north", "northeast"};
        double angle = Math.toDegrees(Math.atan2(dz, dx));
        int i = (int) Math.floorMod(Math.round(angle / 45.0), 8);
        return Component.translatable("rysmap.direction." + keys[i]).getString();
    }
}
