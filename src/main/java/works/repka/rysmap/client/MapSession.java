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
    /** Дальний план (М6): регион уменьшен до 64×64, потолок 256 текстур (4 МБ видеопамяти). */
    public final TileTextures overview;
    public static final int OVERVIEW_SIDE = 64;
    /** Подсветка биомов и высот (М7): маска 128×128 на регион, по клетке 4×4 блока. */
    public final TileTextures highlight;
    private volatile works.repka.ryscore.map.MapApi.@Nullable Highlight highlightShown;
    private final works.repka.rysmap.point.PointStore points;

    private @Nullable String dimension;
    private boolean ceiling, noMap;
    /** Этажи пещер (М5): какие снимаются сейчас, какой показывает миникарта (MIN: поверхность), какой листает окно карты. */
    private int[] floors = new int[0];
    private int caveFloor = Integer.MIN_VALUE;
    private @Nullable Integer viewFloor;
    private boolean underground;
    private int minFloor, maxFloor;
    private int ticks;
    private int pinnedX = Integer.MIN_VALUE, pinnedZ = Integer.MIN_VALUE;
    private long colorsSeen = -1;
    private volatile boolean closed;

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
        this.overview = new TileTextures(this::requestOverview, 256, 30, OVERVIEW_SIDE);
        this.highlight = new TileTextures(this::requestHighlight, 128, 20, 128);
        try {
            store.touch();
        } catch (IOException e) {
            RYSMap.LOG.warn("[rysmap] world.json не записался: {}", e.toString());
        }
        works.repka.rysmap.point.PointStore p;
        try {
            p = works.repka.rysmap.point.PointStore.load(store.root().resolve("points.json"));
        } catch (IOException | RuntimeException e) {
            RYSMap.LOG.warn("[rysmap] points.json не прочитался ({}), файл отложен, точки начнутся заново", e.toString());
            try {
                java.nio.file.Path f = store.root().resolve("points.json");
                if (java.nio.file.Files.exists(f)) works.repka.rysmap.tile.RegionFile.quarantine(f);
            } catch (IOException ignored) {
                // не удалось отложить: файл останется на месте и перезапишется при первой правке
            }
            p = new works.repka.rysmap.point.PointStore();
        }
        this.points = p;
        RYSMap.LOG.info("[rysmap] карта мира «{}» в {}, точек {}", store.key().name(), store.root(), points.points().size());
    }

    public works.repka.rysmap.point.PointStore points() {
        return points;
    }

    /** Точки пишутся сразу: файл маленький, правка редкая. */
    public void savePoints() {
        try {
            points.save(store.root().resolve("points.json"));
        } catch (IOException e) {
            RYSMap.LOG.warn("[rysmap] points.json не записался: {}", e.toString());
        }
    }

    /** Тиков с начала сессии. */
    public int ticksAlive() {
        return ticks;
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
        minFloor = level.getMinSection();
        maxFloor = Math.min(level.getMaxSection() - 1, works.repka.rysmap.scan.Floors.of(level.getMinBuildHeight() + level.dimensionType().logicalHeight() - 1));
        floors = new int[0];
        caveFloor = Integer.MIN_VALUE;
        viewFloor = null;
        underground = false;
        queue.clear();
        dirty.clear();
        pinnedX = pinnedZ = Integer.MIN_VALUE;
        tiles.keepOnly(dim);
        overview.keepOnly(dim);
        highlight.keepOnly(dim);
        if (old != null) worker.submit(() -> closeOthers(dim));
        RYSMap.LOG.info("[rysmap] измерение {}{}{}", dim, ceiling ? " (потолок: срез под потолком)" : "", noMap ? " (без карты)" : "");
    }

    public void chunkLoaded(int chunkX, int chunkZ) {
        if (noMap || dimension == null || SablePlots.isPlotChunk(chunkX, chunkZ)) return;
        queue.add(chunkX, chunkZ);
        requestIndex(dimension, chunkX >> 5, chunkZ >> 5);
        for (int f : floors) requestIndex(works.repka.rysmap.scan.Floors.id(dimension, f), chunkX >> 5, chunkZ >> 5);
    }

    public void chunkUnloaded(int chunkX, int chunkZ) {
        queue.remove(chunkX, chunkZ);
    }

    /** Из перехвата {@code ClientLevel.sendBlockUpdated}: состояние уже сменилось. */
    public void blockChanged(ClientLevel level, BlockPos pos) {
        if (noMap || dimension == null || level.dimension() == null) return;
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        if (SablePlots.isPlotChunk(cx, cz)) return;
        boolean inFloor = false;
        for (int f : floors) inFloor |= works.repka.rysmap.scan.Floors.of(pos.getY()) >= f - 1 && works.repka.rysmap.scan.Floors.of(pos.getY()) <= f;
        if (!ceiling && !inFloor) {
            // глубоко под поверхностью правка карты не меняет: шахта под базой не перерисовывает чанк (кроме снимаемых этажей)
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
        // точки на время: раз в секунду убрать истёкшие
        if (ticks % 20 == 0 && points.expire(System.currentTimeMillis() / 1000) > 0) savePoints();
        ColorTable colors = ColorTable.INSTANCE;
        if (!colors.step(2_000_000L)) return;
        if (colorsSeen != colors.version()) {
            if (colorsSeen >= 0) {
                tiles.invalidate();
                overview.invalidate();
            }
            colorsSeen = colors.version();
        }
        if (ticks % 20 == 0) {
            tiles.tick();
            overview.tick();
            highlight.tick();
        }
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
        if (ticks % 10 == 0) updateFloors(level, player);

        long budget = config.scanBudgetMicros * 1000L;
        if (mc.getFps() < 40) budget /= 2;
        long start = System.nanoTime();
        ConcurrentHashMap<Long, int[]> index = index(dimension);
        int[] fl = floors;
        @SuppressWarnings("unchecked") ConcurrentHashMap<Long, int[]>[] floorIndex = new ConcurrentHashMap[fl.length];
        for (int i = 0; i < fl.length; i++) floorIndex[i] = index(works.repka.rysmap.scan.Floors.id(dimension, fl[i]));
        int now = (int) (System.currentTimeMillis() / 1000);
        int rescan = config.rescanMinutes * 60;
        while (pending.get() < MAX_PENDING_SNAPSHOTS && System.nanoTime() - start < budget) {
            long key = queue.pollNearest(pcx, pcz, k -> queue.isUrgent(k) || indexReady(index, floorIndex, ChunkQueue.x(k), ChunkQueue.z(k)));
            if (key == Long.MIN_VALUE) break;
            int cx = ChunkQueue.x(key), cz = ChunkQueue.z(key);
            boolean urgent = queue.isUrgent(key);
            // этажи, которым нужен снимок: при правке все снимаемые, иначе только давно не снятые
            int[] want = new int[fl.length];
            int nw = 0;
            for (int i = 0; i < fl.length; i++) {
                int sc = DimensionMap.scanned(floorIndex[i], cx, cz);
                if (urgent || sc <= 0 || now - sc >= rescan) want[nw++] = fl[i];
            }
            if (!urgent) {
                int scanned = DimensionMap.scanned(index, cx, cz);
                if (scanned > 0 && now - scanned < rescan && nw == 0) continue;
            }
            ChunkAccess chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
            if (!(chunk instanceof LevelChunk lc) || lc.isEmpty()) continue;
            long t0 = System.nanoTime();
            ChunkSnapshot snap = ChunkSnapshot.capture(dimension, lc, level, ceiling, now, java.util.Arrays.copyOf(want, nw));
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
            } else if (r instanceof Overview o) {
                results.poll();
                overview.accept(o.pixels());
            } else if (r instanceof HighlightMask h) {
                results.poll();
                if (h.of() == highlightShown) highlight.accept(h.pixels());
            } else if (r instanceof Invalidate inv) {
                results.poll();
                tiles.invalidate(inv.dimension, inv.regionX, inv.regionZ);
            } else if (r instanceof TileTextures.Patch p) {
                if (patches >= PATCHES_PER_FRAME) break;
                results.poll();
                tiles.accept(p);
                overview.markStale(p.dimension(), p.chunkX() >> 5, p.chunkZ() >> 5);
                patches++;
            } else {
                results.poll();
            }
        }
    }

    private static boolean indexReady(ConcurrentHashMap<Long, int[]> surface, ConcurrentHashMap<Long, int[]>[] floors, int cx, int cz) {
        if (DimensionMap.scanned(surface, cx, cz) < 0) return false;
        for (ConcurrentHashMap<Long, int[]> f : floors) if (DimensionMap.scanned(f, cx, cz) < 0) return false;
        return true;
    }

    // ---- этажи пещер (М5) ----

    /**
     * Какие этажи снимать: в измерении с потолком всегда этаж игрока и соседние, в остальных только под землёй (неба
     * не видно и до поверхности больше 8 блоков; выход наверх при запасе меньше 4, чтобы не мигало). Этаж, который
     * листает окно карты, снимается тоже. Сервер может выключить пещеры правилом, игрок настройкой.
     */
    private void updateFloors(ClientLevel level, LocalPlayer player) {
        int[] next;
        int show = Integer.MIN_VALUE;
        boolean allowed = ServerRules.cavesAllowed() && config.caves;
        if (allowed) {
            int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, player.getBlockX(), player.getBlockZ());
            int below = surface - player.getBlockY();
            boolean sky = level.canSeeSky(player.blockPosition().above());
            underground = !sky && (underground ? below >= 4 : below > 8);
        } else {
            underground = false;
        }
        java.util.TreeSet<Integer> set = new java.util.TreeSet<>();
        if (allowed && (ceiling || underground)) {
            int f = works.repka.rysmap.scan.Floors.of(player.getBlockY());
            show = Math.max(minFloor, Math.min(maxFloor, f));
            for (int d = -1; d <= 1; d++) if (f + d >= minFloor && f + d <= maxFloor) set.add(f + d);
        }
        if (allowed && viewFloor != null && viewFloor >= minFloor && viewFloor <= maxFloor) set.add(viewFloor);
        next = set.stream().mapToInt(Integer::intValue).toArray();
        caveFloor = show;
        if (!java.util.Arrays.equals(next, floors)) {
            floors = next;
            // новые этажи: чанки рядом снова в очередь (недавно снятая поверхность не мешает, решает индекс этажа)
            requeueLoaded(level, player.getBlockX() >> 4, player.getBlockZ() >> 4, Minecraft.getInstance().options.getEffectiveRenderDistance() + 1);
        }
    }

    /** Этаж, который сейчас показывает миникарта, или null (поверхность). */
    public @Nullable Integer caveFloor() {
        return caveFloor == Integer.MIN_VALUE ? null : caveFloor;
    }

    /** Окно карты листает этаж (null: не листает); этот этаж тоже снимается вокруг игрока. */
    public void viewFloor(@Nullable Integer floor) {
        viewFloor = floor;
    }

    /** Пределы этажей текущего измерения игрока. */
    public int minFloor() {
        return minFloor;
    }

    public int maxFloor() {
        return maxFloor;
    }

    /** Измерение с потолком (Нижний мир): поверхность это срез под потолком, этажи всегда. */
    public boolean ceiling() {
        return ceiling;
    }

    private ConcurrentHashMap<Long, int[]> index(String dim) {
        return scanIndex.computeIfAbsent(dim, d -> new ConcurrentHashMap<>());
    }

    private void requestIndex(String dim, int regionX, int regionZ) {
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

    private record HighlightMask(works.repka.ryscore.map.MapApi.Highlight of, TileTextures.Pixels pixels) {}

    /** Подсветка сменилась (или снята): маски заказываются заново. Главный поток, перед рисованием. */
    public works.repka.ryscore.map.MapApi.@Nullable Highlight syncHighlight() {
        works.repka.ryscore.map.MapApi.Highlight now = works.repka.ryscore.map.MapApi.highlighted();
        if (now != highlightShown) {
            highlightShown = now;
            highlight.invalidate();
        }
        return now;
    }

    private void requestHighlight(String dim, int regionX, int regionZ) {
        works.repka.ryscore.map.MapApi.Highlight h = highlightShown;
        if (h == null) {
            results.add(new HighlightMask(null, new TileTextures.Pixels(dim, regionX, regionZ, null)));
            return;
        }
        java.util.Set<String> biomes = new java.util.HashSet<>();
        for (var k : h.biomes()) biomes.add(k.location().toString());
        worker.submit(() -> {
            int[] px = null;
            try {
                px = dim(dim).highlightRegion(regionX, regionZ, biomes, h.minY(), h.maxY(), (h.color() & 0xFFFFFF) | 0x99000000, 128);
            } catch (IOException e) {
                RYSMap.LOG.warn("[rysmap] регион {},{} {} не прочитался: {}", regionX, regionZ, dim, e.toString());
            }
            results.add(new HighlightMask(h, new TileTextures.Pixels(dim, regionX, regionZ, px)));
        });
    }

    /** Уменьшенный регион для дальнего плана. */
    private record Overview(TileTextures.Pixels pixels) {}

    private void requestOverview(String dim, int regionX, int regionZ) {
        worker.submit(() -> {
            int[] px = null;
            try {
                int[] full = dim(dim).bakeRegion(regionX, regionZ);
                if (full != null) px = TileTextures.downsample(full, OVERVIEW_SIDE);
            } catch (IOException e) {
                RYSMap.LOG.warn("[rysmap] регион {},{} {} не прочитался: {}", regionX, regionZ, dim, e.toString());
            }
            results.add(new Overview(new TileTextures.Pixels(dim, regionX, regionZ, px)));
        });
    }

    /**
     * Множитель ночи (М6): 1 днём, к ночи до 0,45 по небесной темноте мира. В измерениях без неба и на этажах пещер
     * карта не темнеет; игрок выключает это настройкой «Темнеть ночью».
     */
    public static float nightBrightness(@Nullable ClientLevel level, float partial) {
        if (level == null || !ClientConfig.get().night || !level.dimensionType().hasSkyLight()) return 1f;
        float sky = level.getSkyDarken(partial); // 0,2 ночью … 1 днём
        float day = Math.max(0f, Math.min(1f, (sky - 0.2f) / 0.8f));
        return 0.45f + 0.55f * day;
    }

    /** Регион переписан не сканером (переезд из FTB): текстуру заказать заново. */
    private record Invalidate(String dimension, int regionX, int regionZ) {}

    /**
     * Переезд из FTB: регион в карту в рабочем потоке. Будущее завершается числом перенесённых чанков; поток переезда
     * ждёт его, поэтому в куче одновременно лежит один регион FTB.
     */
    public java.util.concurrent.CompletableFuture<Integer> importFtb(String dimensionId, works.repka.rysmap.migrate.FtbRegionReader.Region region,
                                                                    works.repka.rysmap.migrate.FtbRegionReader.World world) {
        java.util.concurrent.CompletableFuture<Integer> out = new java.util.concurrent.CompletableFuture<>();
        if (closed) {
            out.completeExceptionally(new IOException("карта закрыта"));
            return out;
        }
        worker.submit(() -> {
            try {
                int n = dim(dimensionId).importFtb(region, world);
                if (n > 0) results.add(new Invalidate(dimensionId, region.x, region.z));
                out.complete(n);
            } catch (Throwable t) {
                out.completeExceptionally(t);
            }
        });
        return out;
    }

    /** Карта закрыта (выход из мира или смена ключа). */
    public boolean closed() {
        return closed;
    }

    /** Чанки, уже лежащие у клиента, снова в очередь: после смены ключа мира новая сессия их ещё не видела. */
    public void requeueLoaded(ClientLevel level, int centerChunkX, int centerChunkZ, int radius) {
        for (int z = centerChunkZ - radius; z <= centerChunkZ + radius; z++)
            for (int x = centerChunkX - radius; x <= centerChunkX + radius; x++)
                if (level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) != null) chunkLoaded(x, z);
    }

    /**
     * Точки из FTB в хранилище (главный поток). Перед первой правкой {@code points.json} копируется рядом
     * ({@code points.before-ftb-<время>.json}). Возвращает добавленные.
     */
    public int importPoints(java.util.List<works.repka.rysmap.point.PointStore.Point> ftb) {
        java.nio.file.Path file = store.root().resolve("points.json");
        try {
            if (java.nio.file.Files.isRegularFile(file)) {
                String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
                java.nio.file.Files.copy(file, file.resolveSibling("points.before-ftb-" + stamp + ".json"));
            }
        } catch (IOException e) {
            RYSMap.LOG.warn("[rysmap] копия points.json не сделана: {}", e.toString());
        }
        int added = works.repka.rysmap.migrate.FtbImport.mergePoints(points, ftb);
        if (added > 0) savePoints();
        return added;
    }

    // ---- рабочий поток ----

    private DimensionMap dim(String id) {
        DimensionMap d = dims.get(id);
        if (d == null) {
            try {
                // у этажа свой маленький кэш: этажей снимается несколько сразу
                int cap = works.repka.rysmap.scan.Floors.floor(id) == null ? config.rawRegions : Math.max(3, config.rawRegions / 3);
                d = new DimensionMap(store, id, ColorTable.INSTANCE, cap, index(id), s -> RYSMap.LOG.warn("[rysmap] {}", s));
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
            patches(d, snap.dimension, snap.chunkX, snap.chunkZ);
            for (int f : snap.floors) {
                String id = works.repka.rysmap.scan.Floors.id(snap.dimension, f);
                DimensionMap fd = dim(id);
                works.repka.rysmap.scan.FloorScanner.scan(snap, f, fd, ColorTable.INSTANCE);
                patches(fd, id, snap.chunkX, snap.chunkZ);
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

    /** Сам чанк и соседи с востока и юга: их тень смотрит на этот чанк. */
    private void patches(DimensionMap d, String id, int chunkX, int chunkZ) {
        for (int dz = 0; dz <= 1; dz++) {
            for (int dx = 0; dx <= 1; dx++) {
                int[] px = d.bakeChunk(chunkX + dx, chunkZ + dz);
                if (px != null && (dx == 0 && dz == 0 || hasChunk(d, chunkX + dx, chunkZ + dz)))
                    results.add(new TileTextures.Patch(id, chunkX + dx, chunkZ + dz, px));
            }
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

    /** Переход в другое измерение: карты прежнего измерения и его этажей закрываются. */
    private void closeOthers(String keep) {
        for (String id : new java.util.ArrayList<>(dims.keySet()))
            if (!works.repka.rysmap.scan.Floors.base(id).equals(keep)) closeDimension(id);
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
        closed = true;
        worker.stop(() -> {
            for (String id : new HashSet<>(dims.keySet())) closeDimension(id);
        }, 10_000);
        tiles.close();
        overview.close();
        highlight.close();
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

    /**
     * Ключ мира: UUID от сервера с RYSMap ({@code MapHello}), иначе имя сохранения или адрес сервера. Имя для шапки
     * окна: название мира или сервера из списка.
     */
    public static WorldKey keyFor(Minecraft mc, java.util.@Nullable UUID world) {
        String name;
        WorldKey fallback;
        if (mc.getSingleplayerServer() != null) {
            String folder = mc.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .toAbsolutePath().normalize().getFileName().toString();
            fallback = WorldKey.singleplayer(folder);
            String level = mc.getSingleplayerServer().getWorldData().getLevelName();
            name = level == null || level.isBlank() ? folder : level;
        } else {
            var server = mc.getCurrentServer();
            fallback = WorldKey.server(server != null ? server.ip : "unknown");
            name = server == null ? "?" : server.name == null || server.name.isBlank() ? server.ip : server.name;
        }
        if (world != null) return WorldKey.world(world, name);
        // UUID этого мира уже приходил раньше: та же папка и до MapHello
        String known = works.repka.rysmap.world.MapStore.known(mc.gameDirectory.toPath().resolve("rysmap"), fallback.id());
        if (known != null && known.startsWith("w-")) return new WorldKey(known, name);
        return new WorldKey(fallback.id(), name);
    }

    /** Ключ без серверной части (по имени сохранения или адресу). */
    public static WorldKey fallbackKey(Minecraft mc) {
        if (mc.getSingleplayerServer() != null) {
            String folder = mc.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .toAbsolutePath().normalize().getFileName().toString();
            return WorldKey.singleplayer(folder);
        }
        var server = mc.getCurrentServer();
        return WorldKey.server(server != null ? server.ip : "unknown");
    }
}
