package works.repka.rysmap.tile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;
import works.repka.rysmap.world.MapStore;

/**
 * Карта одного измерения у рабочего потока: палитра, кэш сырых регионов, пекарь. Наружу, главному потоку, отдаёт
 * только время скана чанков ({@link #scanIndex()}): по нему сканер решает, снимать ли чанк заново.
 *
 * <p>Чистая Java. Все методы, кроме {@link #scanIndex()}, зовёт только рабочий поток.
 */
public final class DimensionMap {
    private static final int[] NEVER = new int[RegionData.CHUNKS * RegionData.CHUNKS];

    public final String dimension;
    private final MapStore store;
    private final Palette palette;
    private final RegionCache cache;
    private final Baker baker;
    private final ConcurrentHashMap<Long, int[]> scanIndex;
    private final Consumer<String> log;
    private int broken;

    /**
     * @param scanIndex общая с главным потоком таблица «регион → время скана чанков»; массивы пишет только этот объект
     * @param log       строки для лога: нечитаемый файл, ошибки записи
     */
    public DimensionMap(MapStore store, String dimension, MapColors colors, int rawCap, ConcurrentHashMap<Long, int[]> scanIndex,
                        Consumer<String> log) throws IOException {
        this.store = store;
        this.dimension = dimension;
        this.scanIndex = scanIndex;
        this.log = log;
        Palette p;
        try {
            p = Palette.load(store.palette(dimension));
        } catch (IOException | RuntimeException e) {
            // палитра потеряна: регионы с её номерами нечитаемы по смыслу, поэтому в сторону уходит вся папка измерения
            log.accept("palette.json " + dimension + " could not be read (" + e.getMessage() + "), the dimension map starts anew");
            Path file = store.palette(dimension);
            if (java.nio.file.Files.exists(file)) RegionFile.quarantine(file);
            for (Path region : regionFiles()) RegionFile.quarantine(region);
            p = new Palette();
        }
        this.palette = p;
        this.baker = new Baker(colors);
        this.cache = new RegionCache(new RegionCache.Store() {
            @Override
            public @Nullable RegionData load(int regionX, int regionZ) throws IOException {
                return DimensionMap.this.load(regionX, regionZ);
            }

            @Override
            public void save(RegionData region) throws IOException {
                DimensionMap.this.save(region);
            }
        }, rawCap);
    }

    private List<Path> regionFiles() throws IOException {
        Path dir = store.dimension(dimension);
        if (!java.nio.file.Files.isDirectory(dir)) return List.of();
        try (var s = java.nio.file.Files.list(dir)) {
            return s.filter(f -> f.getFileName().toString().endsWith(".rmap")).toList();
        }
    }

    private @Nullable RegionData load(int regionX, int regionZ) throws IOException {
        Path file = store.region(dimension, regionX, regionZ);
        RegionData r;
        try {
            r = RegionFile.readSurface(file, regionX, regionZ);
        } catch (RegionFile.BrokenException e) {
            broken++;
            Path moved = RegionFile.quarantine(file);
            log.accept("region " + file.getFileName() + " could not be read (" + e.getMessage() + "), moved to " + moved.getParent().getFileName()
                + "; the piece is redrawn on the next pass");
            r = null;
        }
        publish(regionX, regionZ, r);
        return r;
    }

    private void save(RegionData region) throws IOException {
        // номера в регионе должны уже лежать в палитре на диске
        if (palette.dirty()) palette.save(store.palette(dimension));
        RegionFile.write(store.region(dimension, region.x, region.z), List.of(region));
    }

    private void publish(int regionX, int regionZ, @Nullable RegionData r) {
        scanIndex.put(RegionData.key(regionX, regionZ), r == null ? NEVER : r.scanned);
    }

    /** Время скана чанков региона для главного потока: null, пока регион ни разу не спрашивали. */
    public static int scanned(ConcurrentHashMap<Long, int[]> index, int chunkX, int chunkZ) {
        int[] times = index.get(RegionData.key(chunkX >> 5, chunkZ >> 5));
        if (times == null) return -1;
        return times[RegionData.chunkIndex(chunkX & 31, chunkZ & 31)];
    }

    public ConcurrentHashMap<Long, int[]> scanIndex() {
        return scanIndex;
    }

    /** Убедиться, что время скана региона известно главному потоку (читает файл, если надо). */
    public void index(int regionX, int regionZ) throws IOException {
        if (!scanIndex.containsKey(RegionData.key(regionX, regionZ))) cache.get(regionX, regionZ);
    }

    /** Регион для записи скана: из памяти, с диска или новый. */
    public RegionData write(int regionX, int regionZ) throws IOException {
        RegionData r = cache.getOrCreate(regionX, regionZ);
        publish(regionX, regionZ, r);
        return r;
    }

    public @Nullable RegionData peek(int regionX, int regionZ) {
        return cache.peek(regionX, regionZ);
    }

    public Palette palette() {
        return palette;
    }

    public void pinAround(int regionX, int regionZ) {
        cache.pinAround(regionX, regionZ);
    }

    /** Соседи для теней: только регионы, уже лежащие в памяти. */
    public Baker.Neighbors neighbors() {
        return (wx, wz) -> {
            RegionData r = cache.peek(Math.floorDiv(wx, RegionData.SIZE), Math.floorDiv(wz, RegionData.SIZE));
            if (r == null) return RegionData.NO_HEIGHT;
            int lx = Math.floorMod(wx, RegionData.SIZE), lz = Math.floorMod(wz, RegionData.SIZE);
            return r.has(lx >> 4, lz >> 4) ? r.height(lx, lz) : RegionData.NO_HEIGHT;
        };
    }

    /**
     * Пиксели региона для текстуры. Регион не из памяти читается с диска только на время пекаря и в кэш не кладётся:
     * открытая карта не вытесняет регионы вокруг игрока.
     *
     * @return null, если региона нет ни в памяти, ни на диске
     */
    public int @Nullable [] bakeRegion(int regionX, int regionZ) throws IOException {
        RegionData r = cache.peek(regionX, regionZ);
        if (r == null) {
            Path file = store.region(dimension, regionX, regionZ);
            try {
                r = RegionFile.readSurface(file, regionX, regionZ);
            } catch (RegionFile.BrokenException e) {
                r = cache.get(regionX, regionZ); // кэш сам уберёт файл в _broken и запишет в лог
            }
            if (r == null) {
                if (!scanIndex.containsKey(RegionData.key(regionX, regionZ))) publish(regionX, regionZ, null);
                return null;
            }
            if (!scanIndex.containsKey(RegionData.key(regionX, regionZ))) publish(regionX, regionZ, r);
        }
        return r.empty() ? null : baker.bakeRegion(r, palette, neighbors());
    }

    /**
     * Маска подсветки (М7, макет {@code 20}) региона стороной {@code side}: пиксель цвета {@code argb}, где в клетке есть
     * столбец подходящего биома на подходящей высоте, иначе прозрачный. Биомы по id палитры, пусто: любые.
     *
     * @return null, если региона нет
     */
    public int @Nullable [] highlightRegion(int regionX, int regionZ, java.util.Set<String> biomes, int minY, int maxY, int argb, int side) throws IOException {
        RegionData r = cache.peek(regionX, regionZ);
        if (r == null) {
            try {
                r = RegionFile.readSurface(store.region(dimension, regionX, regionZ), regionX, regionZ);
            } catch (RegionFile.BrokenException e) {
                return null;
            }
            if (r == null) return null;
        }
        // номера палитры подходящих биомов
        boolean[] ok = new boolean[256];
        for (int i = 0; i < 256; i++) ok[i] = biomes.isEmpty() || biomes.contains(palette.biomeName(i));
        int f = RegionData.SIZE / side;
        int abgr = (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb & 0xFF) << 16);
        int[] out = new int[side * side];
        boolean any = false;
        for (int oy = 0; oy < side; oy++) {
            for (int ox = 0; ox < side; ox++) {
                search:
                for (int y = 0; y < f; y++) {
                    for (int x = 0; x < f; x++) {
                        int lx = ox * f + x, lz = oy * f + y;
                        int h = r.height(lx, lz);
                        if (h == RegionData.NO_HEIGHT || h < minY || h > maxY || !ok[r.biome(lx, lz)]) continue;
                        out[oy * side + ox] = abgr;
                        any = true;
                        break search;
                    }
                }
            }
        }
        return any ? out : null;
    }

    /** Пиксели одного чанка 16×16 (мировые координаты чанка) или null, если регион не в памяти. */
    public int @Nullable [] bakeChunk(int chunkX, int chunkZ) {
        RegionData r = cache.peek(chunkX >> 5, chunkZ >> 5);
        if (r == null) return null;
        return baker.bakeChunk(r, palette, neighbors(), chunkX & 31, chunkZ & 31);
    }

    /**
     * Переезд из FTB: разведанные чанки региона FTB дописываются в наш слой поверхности; чанк, который сканер уже снял,
     * не трогается. Регион в памяти правится на месте, регион не из памяти читается с диска, правится и сразу пишется,
     * в кэш не кладётся: переезд не вытесняет регионы вокруг игрока.
     *
     * @return сколько чанков перенесено
     */
    public int importFtb(works.repka.rysmap.migrate.FtbRegionReader.Region src, works.repka.rysmap.migrate.FtbRegionReader.World world) throws IOException {
        RegionData r = cache.peek(src.x, src.z);
        boolean cached = r != null;
        if (r == null) {
            Path file = store.region(dimension, src.x, src.z);
            try {
                r = RegionFile.readSurface(file, src.x, src.z);
            } catch (RegionFile.BrokenException e) {
                broken++;
                Path moved = RegionFile.quarantine(file);
                log.accept("region " + file.getFileName() + " could not be read (" + e.getMessage() + "), moved to " + moved.getParent().getFileName());
                r = null;
            }
            if (r == null) r = new RegionData(src.x, src.z);
        }
        int n = works.repka.rysmap.migrate.FtbRegionReader.write(src, world, r, palette);
        if (n == 0) return 0;
        if (cached) r.dirty = true;
        else save(r);
        if (cached || scanIndex.containsKey(RegionData.key(src.x, src.z))) publish(src.x, src.z, r);
        return n;
    }

    public void flush() throws IOException {
        if (palette.dirty()) palette.save(store.palette(dimension));
        cache.flush();
        cache.trim();
    }

    public void close() throws IOException {
        if (palette.dirty()) palette.save(store.palette(dimension));
        cache.close();
    }

    public int rawRegions() {
        return cache.size();
    }

    public long rawBytes() {
        return cache.bytes();
    }

    public int brokenFiles() {
        return broken;
    }

    public RegionCache cache() {
        return cache;
    }
}
