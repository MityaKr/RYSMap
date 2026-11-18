package works.repka.rysmap.migrate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
import works.repka.rysmap.point.PointStore;

/**
 * Чистая часть шага {@code map} (план 10.6): где лежит карта FTB, в каком порядке идут регионы, как точки FTB
 * встают в наши без повторов. Без игры, проверяется модульными тестами на fixtures.
 */
public final class FtbImport {
    private FtbImport() {}

    /** Папка карты FTB Chunks этого мира: {@code <папка игры>/local/ftbchunks/data/<id сервера>/} или null. */
    public static @Nullable Path find(Path gameDir, String ftbId) {
        if (ftbId == null || ftbId.isBlank() || ftbId.contains("/") || ftbId.contains("\\") || ftbId.contains("..")) return null;
        Path dir = gameDir.resolve("local").resolve("ftbchunks").resolve("data").resolve(ftbId);
        return Files.isRegularFile(dir.resolve("dimensions.txt")) ? dir : null;
    }

    /** Регион к переносу; {@link #key()} пишется в отметку шага после переноса. */
    public record Job(String dimension, Path zip, int regionX, int regionZ) {
        public String key() {
            return dimension + "|" + zip.getFileName();
        }
    }

    /**
     * Порядок регионов: сначала измерение игрока от ближних к дальним, потом остальные измерения по
     * {@code dimensions.txt}, в каждом от начала координат. Измерения «без карты» пропускаются и попадают в {@code skipped}.
     */
    public static List<Job> plan(FtbRegionReader.World world, @Nullable String playerDim, int playerRegionX, int playerRegionZ,
                                 Set<String> noMap, List<String> skipped) throws IOException {
        List<Job> out = new ArrayList<>();
        List<String> order = new ArrayList<>(world.dimensions.keySet());
        if (playerDim != null && order.remove(playerDim)) order.add(0, playerDim);
        for (String dim : order) {
            if (noMap.contains(dim)) {
                skipped.add(dim);
                continue;
            }
            Path dir = world.dimensions.get(dim);
            List<Job> jobs = new ArrayList<>();
            for (Path zip : FtbRegionReader.regions(dir)) {
                int[] xz = FtbRegionReader.coords(zip.getFileName().toString());
                if (xz != null) jobs.add(new Job(dim, zip, xz[0], xz[1]));
            }
            int cx = dim.equals(playerDim) ? playerRegionX : 0, cz = dim.equals(playerDim) ? playerRegionZ : 0;
            jobs.sort(Comparator.comparingLong((Job j) -> sq(j.regionX - cx) + sq(j.regionZ - cz)).thenComparing(j -> j.zip.getFileName().toString()));
            out.addAll(jobs);
        }
        return out;
    }

    private static long sq(long v) {
        return v * v;
    }

    /**
     * Точки FTB в наше хранилище: точка, которая уже пришла из FTB (то же измерение, место и имя), не повторяется.
     *
     * @return сколько добавлено
     */
    public static int mergePoints(PointStore store, List<PointStore.Point> ftb) {
        int added = 0;
        for (PointStore.Point p : ftb) {
            boolean known = false;
            for (PointStore.Point q : store.points()) {
                if ("ftb".equals(q.from) && q.dimension.equals(p.dimension) && q.x == p.x && q.y == p.y && q.z == p.z && q.name.equals(p.name)) {
                    known = true;
                    break;
                }
            }
            if (known) continue;
            store.add(p);
            added++;
        }
        return added;
    }

    /** Сколько регионов и точек в папке FTB по измерениям: для отчёта. */
    public static Map<String, Integer> regionCounts(FtbRegionReader.World world) throws IOException {
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Path> e : world.dimensions.entrySet()) out.put(e.getKey(), FtbRegionReader.regions(e.getValue()).size());
        return out;
    }
}
