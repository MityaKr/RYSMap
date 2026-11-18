package works.repka.rysmap.tile;

/**
 * Один слой региона 512×512 блоков (32×32 чанка) в памяти: высота, номер блока, номер биома, вода и свет на столбец,
 * карта разведанных чанков и время скана каждого чанка. Чистая Java, без игры: её читают тесты и переезд с FTB.
 *
 * <p>Владелец один: рабочий поток карты. Главный поток регион не трогает, он получает готовые пиксели.
 */
public final class RegionData {
    public static final int SIZE = 512, CHUNKS = 32, AREA = SIZE * SIZE;
    /** Номер слоя «поверхность». Этажи пещер (0.2) получат номера секций. */
    public static final int SURFACE = 127;
    /** Нижние 4 бита флагов: глубина воды 1–14, 0 без воды, 15 вода без известной глубины (перенос из FTB). */
    public static final int WATER_MASK = 0x0F, WATER_UNKNOWN = 15, WATER_MAX = 14;
    /** Верхние 4 бита флагов: свет блока 0–15 (для ночи шейдером во втором выпуске). */
    public static final int LIGHT_SHIFT = 4;
    /** Высота столбца без данных. */
    public static final short NO_HEIGHT = Short.MIN_VALUE;
    /** Примерный размер в куче: для потолка кэша и строки F3. */
    public static final long BYTES = (long) AREA * 6 + 16 * 8 + 1024 * 4 + 64;

    public final int x, z, floor;
    final short[] height = new short[AREA];
    final char[] block = new char[AREA];
    final byte[] biome = new byte[AREA];
    final byte[] flags = new byte[AREA];
    final long[] present = new long[16];
    final int[] scanned = new int[CHUNKS * CHUNKS];
    /** Есть несохранённые правки. Трогает только владелец. */
    public boolean dirty;
    /** Для вытеснения: когда регион брали последний раз (счётчик кэша). */
    long lastUse;

    public RegionData(int x, int z) {
        this(x, z, SURFACE);
    }

    public RegionData(int x, int z, int floor) {
        this.x = x;
        this.z = z;
        this.floor = floor;
        java.util.Arrays.fill(height, NO_HEIGHT);
    }

    public static int index(int localX, int localZ) {
        return localZ * SIZE + localX;
    }

    public static int chunkIndex(int localChunkX, int localChunkZ) {
        return localChunkZ * CHUNKS + localChunkX;
    }

    public static long key(int regionX, int regionZ) {
        return ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
    }

    public static int keyX(long key) {
        return (int) (key >> 32);
    }

    public static int keyZ(long key) {
        return (int) key;
    }

    /** Разведан ли чанк (координаты внутри региона 0–31). */
    public boolean has(int localChunkX, int localChunkZ) {
        int i = chunkIndex(localChunkX, localChunkZ);
        return (present[i >> 6] & (1L << (i & 63))) != 0;
    }

    /** Время скана чанка в секундах эпохи, 0 если не разведан. */
    public int scanned(int localChunkX, int localChunkZ) {
        return scanned[chunkIndex(localChunkX, localChunkZ)];
    }

    /** Отмечает чанк разведанным в это время. */
    public void mark(int localChunkX, int localChunkZ, int timeSeconds) {
        int i = chunkIndex(localChunkX, localChunkZ);
        present[i >> 6] |= 1L << (i & 63);
        scanned[i] = timeSeconds;
        dirty = true;
    }

    public int chunkCount() {
        int n = 0;
        for (long bits : present) n += Long.bitCount(bits);
        return n;
    }

    public boolean empty() {
        for (long bits : present) if (bits != 0) return false;
        return true;
    }

    /** Пишет столбец (координаты внутри региона 0–511). Флаг «разведан» ставит {@link #mark}. */
    public void set(int localX, int localZ, int height, int blockId, int biomeId, int flags) {
        int i = index(localX, localZ);
        this.height[i] = (short) Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, height));
        this.block[i] = (char) blockId;
        this.biome[i] = (byte) biomeId;
        this.flags[i] = (byte) flags;
        dirty = true;
    }

    /** Столбец без данных: внутри разведанного чанка так помечается столбец, где не нашлось ни одного видимого блока. */
    public void clear(int localX, int localZ) {
        int i = index(localX, localZ);
        height[i] = NO_HEIGHT;
        block[i] = 0;
        biome[i] = 0;
        flags[i] = 0;
        dirty = true;
    }

    public int height(int localX, int localZ) {
        return height[index(localX, localZ)];
    }

    public int block(int localX, int localZ) {
        return block[index(localX, localZ)];
    }

    public int biome(int localX, int localZ) {
        return biome[index(localX, localZ)] & 0xFF;
    }

    public int flags(int localX, int localZ) {
        return flags[index(localX, localZ)] & 0xFF;
    }

    public static int water(int flags) {
        return flags & WATER_MASK;
    }

    public static int light(int flags) {
        return (flags >> LIGHT_SHIFT) & 15;
    }

    public static int pack(int waterDepth, int blockLight) {
        return (waterDepth & WATER_MASK) | ((blockLight & 15) << LIGHT_SHIFT);
    }
}
