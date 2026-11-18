package works.repka.rysmap.client;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;
import works.repka.rysmap.RYSMap;
import works.repka.rysmap.compat.SablePlots;
import works.repka.rysmap.scan.ChunkQueue;
import works.repka.rysmap.scan.ChunkSnapshot;
import works.repka.rysmap.scan.DirtyColumns;
import works.repka.rysmap.scan.MapWorker;
import works.repka.rysmap.scan.SurfaceScanner;
import works.repka.rysmap.tile.ColorTable;
import works.repka.rysmap.tile.DimensionMap;
import works.repka.rysmap.tile.RegionData;
import works.repka.rysmap.tile.TileTextures;
import works.repka.rysmap.world.MapStore;
import works.repka.rysmap.world.WorldKey;

/**
 * Карта одного входа в мир. Главный поток держит очередь чанков, правки блоков, снимки и текстуры; рабочий поток
 * держит регионы, палитры и пекаря ({@link DimensionMap} на измерение). Обмен только очередями задач и результатов.
 *
 * <p>Бюджеты плана (раздел 12): снимки до 1,5 мс за тик (половина при FPS ниже 40), до 64 снимков в очереди рабочего
 * потока, заливка текстур до 16 кусков и одного региона за кадр, запись на диск раз в 30 секунд.
 */
public final class MapSession {
    private static final int MAX_PENDING_SNAPSHOTS = 64;
    private static final int FLUSH_TICKS = 20 * 30;
    private static final int PATCHES_PER_FRAME = 16, REGIONS_PER_FRAME = 1;

    public final MapStore store;
    private final ClientConfig config;
    private final MapWorker worker;
    private final Map<String, DimensionMap> dims = new HashMap<>(); // только рабочий поток
    private final Map<String, ConcurrentHashMap<Long, int[]>> scanIndex = new ConcurrentHashMap<>();
    private final Set<Long> indexRequested = new HashSet<>();
    private final ConcurrentLinkedQueue<Object> results = new ConcurrentLinkedQueue<>();
    private final ChunkQueue queue = new ChunkQueue();
    private final DirtyColumns dirty = new DirtyColumns(2048);
    private final AtomicInteger pending = new AtomicInteger();
    public final TileTextures tiles;

    private @Nullable String dimension;
    private boolean ceiling, noMap;
    private int ticks;
    private int pinnedX = Integer.MIN_VALUE, pinnedZ = Integer.MIN_VALUE;
    private long colorsSeen = -1;

    // замеры для отчёта и строки F3
    private long snapNanos, snapCount, snapMaxNanos;
    private final AtomicLong scanNanos = new AtomicLong(), scanCount = new AtomicLong();
    private volatile int rawRegions;
    private volatile long rawBytes;

    public MapSession(MapStore store, ClientConfig config) {
        this.store = store;
        this.config = config;
        this.worker = new MapWorker("RYSMap worker", t -> RYSMap.LOG.error("[rysmap] сбой задачи рабочего потока", t));
        this.tiles = new TileTextures(this::requestRegion, config.textures, config.textureIdleSeconds);
        try {
            store.touch();
        } catch (IOException e) {
            RYSMap.LOG.warn("[rysmap] world.json не записался: {}", e.toString());
        }
        RYSMap.LOG.info("[rysmap] карта мира «{}» в {}", store.key().name(), store.root());
    }

    public @Nullable String dimension() {
        return dimension;
    }

    // ---- события ----

    /** Игрок оказался в измерении (вход или переход). */
    public void enterDimension(ClientLevel level) {
        String dim = level.dimension().location().toString();
        if (dim.equals(dimension)) return;
        String old = dimension;
        dimension = dim;
        ceiling = level.dimensionType().hasCeiling();
        noMap = config.noMapDimensions.contains(dim);
        queue.clear();
        dirty.clear();
        pinnedX = pinnedZ = Integer.MIN_VALUE;
        tiles.keepOnly(dim);
        if (old != null) worker.submit(() -> closeDimension(old));
        RYSMap.LOG.info("[rysmap] измерение {}{}{}", dim, ceiling ? " (потолок: срез под потолком)" : "", noMap ? " (без карты)" : "");
    }

    public void chunkLoaded(int chunkX, int chunkZ) {
        if (noMap || dimension == null || SablePlots.isPlotChunk(chunkX, chunkZ)) return;
        queue.add(chunkX, chunkZ);
        requestIndex(chunkX >> 5, chunkZ >> 5);
    }

    public void chunkUnloaded(int chunkX, int chunkZ) {
        queue.remove(chunkX, chunkZ);
    }

    /** Из перехвата {@code ClientLevel.sendBlockUpdated}: состояние уже сменилось. */
    public void blockChanged(ClientLevel level, BlockPos pos) {
        if (noMap || dimension == null || level.dimension() == null) return;
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        if (SablePlots.isPlotChunk(cx, cz)) return;
        if (!ceiling) {
            // глубоко под поверхностью правка карты не меняет: шахта под базой не перерисовывает чанк
            int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ());
            if (pos.getY() < surface - ChunkSnapshot.BELOW) return;
        }
        dirty.add(cx, cz);
    }

    // ---- тик главного потока ----

    public void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) return;
        ticks++;
        ColorTable colors = ColorTable.INSTANCE;
        if (!colors.step(2_000_000L)) return;
        if (colorsSeen != colors.version()) {
            if (colorsSeen >= 0) tiles.invalidate();
            colorsSeen = colors.version();
        }
        if (ticks % 20 == 0) tiles.tick();
        if (ticks % FLUSH_TICKS == 0) worker.submit(this::flushAll);
        if (noMap || dimension == null) return;

        int pcx = player.getBlockX() >> 4, pcz = player.getBlockZ() >> 4;
        int prx = pcx >> 5, prz = pcz >> 5;
        if (prx != pinnedX || prz != pinnedZ) {
            pinnedX = prx;
            pinnedZ = prz;
            String dim = dimension;
            worker.submit(() -> dim(dim).pinAround(prx, prz));
        }
        if (ticks % DirtyColumns.PERIOD_TICKS == 0) dirty.drain(k -> queue.addUrgent(ChunkQueue.x(k), ChunkQueue.z(k)));

        long budget = config.scanBudgetMicros * 1000L;
        if (mc.getFps() < 40) budget /= 2;
        long start = System.nanoTime();
        ConcurrentHashMap<Long, int[]> index = index(dimension);
        int now = (int) (System.currentTimeMillis() / 1000);
        int rescan = config.rescanMinutes * 60;
        while (pending.get() < MAX_PENDING_SNAPSHOTS && System.nanoTime() - start < budget) {
            long key = queue.pollNearest(pcx, pcz, k -> queue.isUrgent(k) || DimensionMap.scanned(index, ChunkQueue.x(k), ChunkQueue.z(k)) >= 0);
            if (key == Long.MIN_VALUE) break;
            int cx = ChunkQueue.x(key), cz = ChunkQueue.z(key);
            if (!queue.isUrgent(key)) {
                int scanned = DimensionMap.scanned(index, cx, cz);
                if (scanned > 0 && now - scanned < rescan) continue;
            }
            ChunkAccess chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
            if (!(chunk instanceof LevelChunk lc) || lc.isEmpty()) continue;
            long t0 = System.nanoTime();
            ChunkSnapshot snap = ChunkSnapshot.capture(dimension, lc, level, ceiling, now);
            long dt = System.nanoTime() - t0;
            snapNanos += dt;
            snapCount++;
            snapMaxNanos = Math.max(snapMaxNanos, dt);
            pending.incrementAndGet();
            worker.submit(() -> scan(snap));
        }
    }

    /** Кадр: заливка готового в текстуры по бюджету. */
    public void frame() {
        int patches = 0, regions = 0;
        while (patches < PATCHES_PER_FRAME || regions < REGIONS_PER_FRAME) {
            Object r = results.peek();
            if (r == null) break;
            if (r instanceof TileTextures.Pixels p) {
                if (regions >= REGIONS_PER_FRAME) break;
                results.poll();
                tiles.accept(p);
                regions++;
            } else if (r instanceof TileTextures.Patch p) {
                if (patches >= PATCHES_PER_FRAME) break;
                results.poll();
                tiles.accept(p);
                patches++;
            } else {
                results.poll();
            }
        }
    }

    private ConcurrentHashMap<Long, int[]> index(String dim) {
        return scanIndex.computeIfAbsent(dim, d -> new ConcurrentHashMap<>());
    }

    private void requestIndex(int regionX, int regionZ) {
        String dim = dimension;
        long key = RegionData.key(regionX, regionZ) ^ ((long) dim.hashCode() << 17);
        if (index(dim).containsKey(RegionData.key(regionX, regionZ)) || !indexRequested.add(key)) return;
        worker.submit(() -> {
            try {
                dim(dim).index(regionX, regionZ);
            } catch (IOException e) {
                RYSMap.LOG.warn("[rysmap] регион {},{} {} не прочитался: {}", regionX, regionZ, dim, e.toString());
                index(dim).putIfAbsent(RegionData.key(regionX, regionZ), new int[RegionData.CHUNKS * RegionData.CHUNKS]);
            }
        });
    }

    private void requestRegion(String dim, int regionX, int regionZ) {
        worker.submitFirst(() -> {
            int[] px = null;
            try {
                px = dim(dim).bakeRegion(regionX, regionZ);
            } catch (IOException e) {
                RYSMap.LOG.warn("[rysmap] регион {},{} {} не прочитался: {}", regionX, regionZ, dim, e.toString());
            }
            results.add(new TileTextures.Pixels(dim, regionX, regionZ, px));
        });
    }

    // ---- рабочий поток ----

    private DimensionMap dim(String id) {
        DimensionMap d = dims.get(id);
        if (d == null) {
            try {
                d = new DimensionMap(store, id, ColorTable.INSTANCE, config.rawRegions, index(id), s -> RYSMap.LOG.warn("[rysmap] {}", s));
            } catch (IOException e) {
                throw new RuntimeException("папка карты " + id + " недоступна", e);
            }
            dims.put(id, d);
        }
        return d;
    }

    private void scan(ChunkSnapshot snap) {
        try {
            long t0 = System.nanoTime();
            DimensionMap d = dim(snap.dimension);
            SurfaceScanner.scan(snap, d, ColorTable.INSTANCE);
            // сам чанк и соседи с востока и юга: их тень смотрит на этот чанк
            for (int dz = 0; dz <= 1; dz++) {
                for (int dx = 0; dx <= 1; dx++) {
                    int[] px = d.bakeChunk(snap.chunkX + dx, snap.chunkZ + dz);
                    if (px != null && (dx == 0 && dz == 0 || hasChunk(d, snap.chunkX + dx, snap.chunkZ + dz)))
                        results.add(new TileTextures.Patch(snap.dimension, snap.chunkX + dx, snap.chunkZ + dz, px));
                }
            }
            scanNanos.addAndGet(System.nanoTime() - t0);
            scanCount.incrementAndGet();
            stats(d);
        } catch (IOException e) {
            RYSMap.LOG.warn("[rysmap] чанк {},{} не записался: {}", snap.chunkX, snap.chunkZ, e.toString());
        } finally {
            pending.decrementAndGet();
        }
    }

    private static boolean hasChunk(DimensionMap d, int chunkX, int chunkZ) {
        RegionData r = d.peek(chunkX >> 5, chunkZ >> 5);
        return r != null && r.has(chunkX & 31, chunkZ & 31);
    }

    private void stats(DimensionMap current) {
        int n = 0;
        long b = 0;
        for (DimensionMap d : dims.values()) {
            n += d.rawRegions();
            b += d.rawBytes();
        }
        rawRegions = n;
        rawBytes = b;
    }

    private void flushAll() {
        for (DimensionMap d : dims.values()) {
            try {
                d.flush();
            } catch (IOException e) {
                RYSMap.LOG.warn("[rysmap] карта {} не записалась: {}", d.dimension, e.toString());
            }
        }
        if (!dims.isEmpty()) stats(null);
    }

    private void closeDimension(String id) {
        DimensionMap d = dims.remove(id);
        if (d == null) return;
        try {
            d.close();
        } catch (IOException e) {
            RYSMap.LOG.warn("[rysmap] карта {} не записалась: {}", id, e.toString());
        }
        stats(null);
    }

    /** Выход из мира: всё записать, поток остановить, текстуры отпустить. Главный поток ждёт до 10 секунд. */
    public void close() {
        worker.stop(() -> {
            for (String id : new HashSet<>(dims.keySet())) closeDimension(id);
        }, 10_000);
        tiles.close();
        results.clear();
        RYSMap.LOG.info("[rysmap] карта закрыта: снимков {}, среднее {} мкс, худший {} мкс; разбор в рабочем потоке {} мкс на чанк",
            snapCount, snapCount == 0 ? 0 : snapNanos / snapCount / 1000, snapMaxNanos / 1000, scanCount.get() == 0 ? 0 : scanNanos.get() / scanCount.get() / 1000);
    }

    // ---- замеры ----

    /** Строка F3: «RYSMap: N МБ, M регионов». */
    public String debugLine() {
        long mb = (rawBytes + tiles.bytes()) / (1024 * 1024);
        return "RYSMap: " + mb + " МБ, " + rawRegions + " рег. в памяти, " + tiles.textures() + " текстур, очередь " + queue.size();
    }

    public long snapshotCount() {
        return snapCount;
    }

    public long snapshotMicrosAverage() {
        return snapCount == 0 ? 0 : snapNanos / snapCount / 1000;
    }

    public long snapshotMicrosMax() {
        return snapMaxNanos / 1000;
    }

    public long scanMicrosAverage() {
        long n = scanCount.get();
        return n == 0 ? 0 : scanNanos.get() / n / 1000;
    }

    public long scannedChunks() {
        return scanCount.get();
    }

    public int queued() {
        return queue.size() + pending.get();
    }

    public int rawRegions() {
        return rawRegions;
    }

    public long rawBytes() {
        return rawBytes;
    }

    /** Для автотеста: записать всё на диск сейчас и дождаться. */
    public void flushNow(Runnable then) {
        worker.submit(() -> {
            flushAll();
            then.run();
        });
    }

    /** Есть ли регион на диске (для автотеста). */
    public boolean regionOnDisk(String dim, int regionX, int regionZ) {
        return java.nio.file.Files.isRegularFile(store.region(dim, regionX, regionZ));
    }

    public static WorldKey keyFor(Minecraft mc) {
        if (mc.getSingleplayerServer() != null) {
            String folder = mc.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .toAbsolutePath().normalize().getFileName().toString();
            return WorldKey.singleplayer(folder);
        }
        var server = mc.getCurrentServer();
        if (server != null) return WorldKey.server(server.ip);
        return WorldKey.server("unknown");
    }
}
