package works.repka.rysmap.scan;

import java.io.IOException;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import works.repka.rysmap.tile.ColorTable;
import works.repka.rysmap.tile.DimensionMap;
import works.repka.rysmap.tile.Palette;
import works.repka.rysmap.tile.RegionData;

/**
 * Разбор этажа пещер в рабочем потоке (этап М5). На столбец: сверху этажа пропускается толща породы (свод), с первого
 * воздуха идёт спуск до пола; пол может лежать до 16 блоков ниже этажа. Столбец без воздуха внутри этажа (сплошная
 * порода) остаётся пустым: на карте он тёмный фон, а пещеры и шахты видны светлыми. Вода как у поверхности: до дна.
 */
public final class FloorScanner {
    private FloorScanner() {}

    public static void scan(ChunkSnapshot s, int floor, DimensionMap map, ColorTable colors) throws IOException {
        RegionData r = map.write(s.chunkX >> 5, s.chunkZ >> 5);
        Palette palette = map.palette();
        int x0 = (s.chunkX & 31) * 16, z0 = (s.chunkZ & 31) * 16;
        int top = Floors.top(floor), bottom = Floors.bottom(floor), lowest = Math.max(s.minY, bottom - 16);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int lx = x0 + x, lz = z0 + z;
                int y = top;
                while (y >= bottom && !s.state(x, y, z).isAir() && !SurfaceScanner.isWater(s.state(x, y, z))) y--;
                if (y < bottom) {
                    r.clear(lx, lz);
                    continue;
                }
                int waterTop = Integer.MIN_VALUE, depth = 0;
                BlockState found = null;
                ColorTable.Info info = null;
                for (; y >= lowest; y--) {
                    BlockState st = s.state(x, y, z);
                    if (st.isAir()) {
                        if (waterTop != Integer.MIN_VALUE) break;
                        continue;
                    }
                    if (SurfaceScanner.isWater(st)) {
                        if (waterTop == Integer.MIN_VALUE) waterTop = y;
                        depth++;
                        continue;
                    }
                    info = colors.info(st.getBlock());
                    if (info == null || !info.visible()) continue;
                    found = st;
                    break;
                }
                if (found == null && waterTop == Integer.MIN_VALUE) {
                    r.clear(lx, lz);
                    continue;
                }
                int height = waterTop != Integer.MIN_VALUE ? waterTop : y;
                int blockId;
                if (found != null) {
                    final ColorTable.Info named = info;
                    blockId = palette.block(found.getBlock(), b -> named.id());
                } else {
                    blockId = palette.block("minecraft:water");
                    depth = RegionData.WATER_MAX;
                }
                int water = waterTop == Integer.MIN_VALUE ? 0 : Math.max(1, Math.min(RegionData.WATER_MAX, depth));
                Holder<Biome> biome = s.biome(x, height, z);
                int biomeId = biome == null ? 0 : palette.biome(biome, h -> {
                    @SuppressWarnings("unchecked") Holder<Biome> hb = (Holder<Biome>) h;
                    return hb.unwrapKey().map(k -> k.location().toString()).orElse("");
                });
                r.set(lx, lz, height, blockId, biomeId, RegionData.pack(water, 0));
            }
        }
        r.mark(s.chunkX & 31, s.chunkZ & 31, s.time);
    }
}
