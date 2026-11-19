package works.repka.rysmap.tile;

import java.util.ArrayList;
import java.util.List;

/**
 * Экспорт карты в PNG (план карты, раздел 5 «Потом»; 0.2.4), чистая часть: какие регионы нужны, сколько блоков в одной
 * точке картинки и как пиксели региона ложатся в картинку. Картинка не больше {@link #MAX} точек по стороне: большая
 * область берётся каждым N-м блоком. Пиксели пекаря ABGR, картинка ARGB; неразведанное прозрачно.
 */
public final class MapExport {
    /** Сторона картинки не больше: 4096 × 4096 это 64 МБ в памяти на время записи. */
    public static final int MAX = 4096;

    /** План: область в блоках (включительно), шаг, размер картинки. */
    public record Plan(int minX, int minZ, int maxX, int maxZ, int step, int width, int height) {
        /** Регионы {x, z}, которые задевает область. */
        public List<int[]> regions() {
            List<int[]> out = new ArrayList<>();
            for (int rz = Math.floorDiv(minZ, RegionData.SIZE); rz <= Math.floorDiv(maxZ, RegionData.SIZE); rz++)
                for (int rx = Math.floorDiv(minX, RegionData.SIZE); rx <= Math.floorDiv(maxX, RegionData.SIZE); rx++)
                    out.add(new int[]{rx, rz});
            return out;
        }
    }

    private MapExport() {}

    /** План для области: шаг — сколько блоков на точку, чтобы сторона была не больше {@code max}. */
    public static Plan plan(int minX, int minZ, int maxX, int maxZ, int max) {
        int x0 = Math.min(minX, maxX), x1 = Math.max(minX, maxX), z0 = Math.min(minZ, maxZ), z1 = Math.max(minZ, maxZ);
        int w = x1 - x0 + 1, h = z1 - z0 + 1;
        int step = Math.max(1, (int) Math.ceil(Math.max(w, h) / (double) max));
        // начало на кратном шагу, чтобы соседние выгрузки совпадали точка в точку
        x0 = Math.floorDiv(x0, step) * step;
        z0 = Math.floorDiv(z0, step) * step;
        int width = (x1 - x0) / step + 1, height = (z1 - z0) / step + 1;
        return new Plan(x0, z0, x1, z1, step, width, height);
    }

    /** Регион {@code (rx, rz)} в картинку {@code out} (ARGB, ширина {@code plan.width}). */
    public static void compose(Plan plan, int[] region, int rx, int rz, int[] out) {
        int bx0 = rx * RegionData.SIZE, bz0 = rz * RegionData.SIZE;
        for (int lz = 0; lz < RegionData.SIZE; lz++) {
            int wz = bz0 + lz;
            if (wz < plan.minZ || wz > plan.maxZ || Math.floorMod(wz - plan.minZ, plan.step) != 0) continue;
            int py = (wz - plan.minZ) / plan.step;
            if (py >= plan.height) continue;
            for (int lx = 0; lx < RegionData.SIZE; lx++) {
                int wx = bx0 + lx;
                if (wx < plan.minX || wx > plan.maxX || Math.floorMod(wx - plan.minX, plan.step) != 0) continue;
                int px = (wx - plan.minX) / plan.step;
                if (px >= plan.width) continue;
                out[py * plan.width + px] = argb(region[lz * RegionData.SIZE + lx]);
            }
        }
    }

    /** ABGR пекаря в ARGB картинки. */
    static int argb(int abgr) {
        return (abgr & 0xFF00FF00) | ((abgr & 0xFF) << 16) | ((abgr >> 16) & 0xFF);
    }
}
