package works.repka.rysmap.scan;

import java.io.IOException;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import works.repka.rysmap.tile.ColorTable;
import works.repka.rysmap.tile.DimensionMap;
import works.repka.rysmap.tile.Palette;
import works.repka.rysmap.tile.RegionData;

/**
 * Разбор снимка в рабочем потоке: на столбец высота, видимый блок, биом и глубина воды. Сверху вниз сквозь воздух
 * и невидимое (стекло, барьер, свет); вода считается до дна, дно красится под водой. В измерении с потолком
 * сначала пропускается сам потолок, срез берётся под ним, как у FTB Chunks.
 */
public final class SurfaceScanner {
    private SurfaceScanner() {}

    /** Записывает чанк в регион и отмечает его разведанным. */
    public static void scan(ChunkSnapshot s, DimensionMap map, ColorTable colors) throws IOException {
        RegionData r = map.write(s.chunkX >> 5, s.chunkZ >> 5);
        Palette palette = map.palette();
        int x0 = (s.chunkX & 31) * 16, z0 = (s.chunkZ & 31) * 16;
        int waterId = -1;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int y = s.ceiling ? belowCeiling(s, x, z) : s.heights[z * 16 + x];
                int waterTop = Integer.MIN_VALUE, depth = 0;
                BlockState found = null;
                ColorTable.Info info = null;
                for (; y >= s.lowestCopied; y--) {
                    BlockState st = s.state(x, y, z);
                    if (st.isAir()) {
                        if (waterTop != Integer.MIN_VALUE) break; // воздушный карман под водой: дно не видно
                        continue;
                    }
                    if (isWater(st)) {
                        if (waterTop == Integer.MIN_VALUE) waterTop = y;
                        depth++;
                        continue;
                    }
                    info = colors.info(st.getBlock());
                    if (info == null || !info.visible()) continue;
                    found = st;
                    break;
                }
                int lx = x0 + x, lz = z0 + z;
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
                    // дно глубже снимка: красится самой водой
                    if (waterId < 0) waterId = palette.block("minecraft:water");
                    blockId = waterId;
                    depth = RegionData.WATER_MAX;
                }
                int water = waterTop == Integer.MIN_VALUE ? 0 : Math.max(1, Math.min(RegionData.WATER_MAX, depth));
                Holder<Biome> biome = s.biome(x, height, z);
                int biomeId = biome == null ? 0 : palette.biome(biome, SurfaceScanner::biomeName);
                r.set(lx, lz, height, blockId, biomeId, RegionData.pack(water, 0));
            }
        }
        r.mark(s.chunkX & 31, s.chunkZ & 31, s.time);
    }

    /** Первый блок под потолком: пропустить сплошной потолок сверху, потом воздух ниже ищет основной цикл. */
    private static int belowCeiling(ChunkSnapshot s, int x, int z) {
        int y = s.top;
        while (y >= s.lowestCopied && !s.state(x, y, z).isAir()) y--;
        return y;
    }

    static boolean isWater(BlockState st) {
        if (!st.getFluidState().getType().isSame(Fluids.WATER)) return false;
        Block b = st.getBlock();
        // водоросли и трава под водой видны как вода; ступени и плиты с водой видны как блок
        return b instanceof LiquidBlock || b instanceof BubbleColumnBlock || (b instanceof LiquidBlockContainer && !(b instanceof SimpleWaterloggedBlock));
    }

    private static String biomeName(Object holder) {
        @SuppressWarnings("unchecked")
        Holder<Biome> h = (Holder<Biome>) holder;
        return h.unwrapKey().map(k -> k.location().toString()).orElse("");
    }
}
