package works.repka.rysmap.scan;

import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

/**
 * Снимок чанка для рабочего потока. Главный поток только копирует секции, в которых может лежать поверхность
 * ({@code PalettedContainer.copy()}), и карту высот {@code WORLD_SURFACE}; разбор идёт в рабочем потоке.
 * В измерении с потолком копируются все секции: срез ищется под потолком.
 */
public final class ChunkSnapshot {
    /** Сколько блоков ниже самой низкой поверхности чанка ещё копируется: вода и прозрачное над дном. */
    public static final int BELOW = 32;

    public final String dimension;
    public final int chunkX, chunkZ;
    public final int minSection;
    public final int minY;
    /** Верх логической высоты: с него идёт поиск среза под потолком. */
    public final int top;
    public final boolean ceiling;
    /** Верхний занятый блок столбца (по {@code WORLD_SURFACE}), индекс {@code z * 16 + x}. */
    public final int[] heights = new int[256];
    /** Самый нижний скопированный блок: ниже сканер не спускается. */
    public final int lowestCopied;
    final @Nullable PalettedContainer<BlockState>[] states;
    final @Nullable PalettedContainer<Holder<Biome>>[] biomes;
    public final int time;

    @SuppressWarnings("unchecked")
    private ChunkSnapshot(String dimension, LevelChunk chunk, Level level, boolean ceiling, int time) {
        this.dimension = dimension;
        this.chunkX = chunk.getPos().x;
        this.chunkZ = chunk.getPos().z;
        this.minSection = chunk.getMinSection();
        this.minY = level.getMinBuildHeight();
        this.ceiling = ceiling;
        this.time = time;
        this.top = Math.min(level.getMaxBuildHeight() - 1, minY + level.dimensionType().logicalHeight() - 1);
        LevelChunkSection[] sections = chunk.getSections();
        this.states = new PalettedContainer[sections.length];
        this.biomes = new PalettedContainer[sections.length];
        int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int h = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                heights[z * 16 + x] = h;
                low = Math.min(low, h);
                high = Math.max(high, h);
            }
        }
        int from, to;
        if (ceiling) {
            from = 0;
            to = sections.length - 1;
        } else {
            from = Math.max(0, ((low - BELOW) >> 4) - minSection);
            to = Math.min(sections.length - 1, (high >> 4) - minSection);
        }
        int lowest = Integer.MAX_VALUE;
        for (int i = from; i <= to; i++) {
            lowest = Math.min(lowest, (i + minSection) << 4);
            LevelChunkSection s = sections[i];
            if (s == null || s.hasOnlyAir()) continue;
            states[i] = s.getStates().copy();
            if (s.getBiomes() instanceof PalettedContainer<Holder<Biome>> b) biomes[i] = b.copy();
        }
        this.lowestCopied = lowest == Integer.MAX_VALUE ? minY : lowest;
    }

    /** Снимает чанк. Только главный поток. */
    public static ChunkSnapshot capture(String dimension, LevelChunk chunk, Level level, boolean ceiling, int timeSeconds) {
        return new ChunkSnapshot(dimension, chunk, level, ceiling, timeSeconds);
    }

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    BlockState state(int x, int y, int z) {
        int i = (y >> 4) - minSection;
        if (i < 0 || i >= states.length) return AIR;
        PalettedContainer<BlockState> s = states[i];
        return s == null ? AIR : s.get(x, y & 15, z);
    }

    @Nullable Holder<Biome> biome(int x, int y, int z) {
        int i = (y >> 4) - minSection;
        if (i < 0 || i >= biomes.length) return null;
        PalettedContainer<Holder<Biome>> b = biomes[i];
        if (b == null) {
            // секция воздуха: ближайшая скопированная ниже
            for (int j = Math.min(i, biomes.length - 1); j >= 0; j--) if (biomes[j] != null) return biomes[j].get(x >> 2, 3, z >> 2);
            return null;
        }
        return b.get(x >> 2, (y & 15) >> 2, z >> 2);
    }
}
