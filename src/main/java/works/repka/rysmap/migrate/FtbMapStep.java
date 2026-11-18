package works.repka.rysmap.migrate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.migrate.Migrations;
import works.repka.rysmap.point.PointStore;

/**
 * Шаг {@code map} переезда с FTB (порядок 40, клиент; план 10.6). Идёт, когда сервер прислал в {@code MapHello} старый
 * id FTB, во флаге сборки есть {@code map}, мод {@code ftbchunks} не загружен и у клиента есть папка
 * {@code local/ftbchunks/data/<id>/}. Чужие папки (другие миры) не трогаются. Отметку, отчёт и повтор после сбоя
 * ведёт ядро; исходники FTB только читаются (они и есть копия), а наш {@code points.json} перед дописыванием
 * копируется рядом.
 *
 * <p>Сначала точки (миллисекунды), потом регионы по одному: чтение FTB в потоке переезда ядра, запись в рабочем потоке
 * карты, между регионами пауза 100 мс, при FPS ниже 30 перенос ждёт. После каждого региона его имя ложится в данные
 * шага ({@link Migrations.Context#data()}): выход из игры посреди переезда ничего не ломает, при следующем входе шаг
 * продолжит с непройденных.
 */
public final class FtbMapStep implements Migrations.Step {
    public static final String ID = "map", VALUE_FTB_ID = "ftbId";
    private static final long PAUSE_MS = 100, SLOW_WAIT_MS = 250;

    /** Что шагу нужно от игры. Главный поток и рабочий поток карты прячутся за ним; тесты дают свой. */
    public interface Host {
        /** Сессия карты того же мира ещё открыта. */
        boolean alive();

        @Nullable
        String playerDimension();

        int playerRegionX();

        int playerRegionZ();

        Set<String> noMap();

        /** Точки в хранилище мира (в главном потоке), перед этим копия {@code points.json}. Возвращает добавленные. */
        int addPoints(List<PointStore.Point> points) throws Exception;

        /** Регион в карту (в рабочем потоке карты), ждёт окончания. Возвращает перенесённые чанки. */
        int importRegion(String dimension, FtbRegionReader.Region region, FtbRegionReader.World world) throws Exception;

        /** FPS ниже 30: перенос ждёт. */
        boolean slow();

        /** Переезд закончен: тихое уведомление. */
        void finished(int points, int regions);
    }

    /** Итог переноса для отчёта. */
    public record Result(int points, int pointsKnown, int regions, int chunks, int regionsBefore, List<String> skipped) {}

    private static volatile @Nullable Host host;

    public static void host(@Nullable Host h) {
        host = h;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int order() {
        return 40;
    }

    @Override
    public Migrations.Side side() {
        return Migrations.Side.CLIENT;
    }

    @Override
    public String modId() {
        return "rysmap";
    }

    @Override
    public boolean needed(Migrations.Context c) {
        if (c.isLoaded("ftbchunks")) return false;
        return host != null && FtbImport.find(c.gameDir(), c.value(VALUE_FTB_ID)) != null;
    }

    @Override
    public Migrations.Report run(Migrations.Context c) throws Exception {
        Host h = host;
        Path dir = FtbImport.find(c.gameDir(), c.value(VALUE_FTB_ID));
        if (h == null || dir == null) throw new IOException("карты FTB нет или мир закрыт");
        Result r = migrate(dir, c.data(), () -> {
            try {
                c.save();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }, h, true);
        Migrations.Report report = Migrations.Report.create()
            .count("points", r.points).count("regions", r.regions).count("chunks", r.chunks);
        if (r.pointsKnown > 0) report.skipped("точки: " + r.pointsKnown, "уже есть");
        if (r.regionsBefore > 0) report.line("регионов перенесено раньше: " + r.regionsBefore);
        for (String s : r.skipped) report.skipped(s, s.startsWith("измерение") ? "без карты" : "формат");
        c.log("точек " + r.points + ", регионов " + r.regions + ", чанков " + r.chunks + ", пропущено " + r.skipped.size());
        h.finished(r.points, r.regions);
        return report;
    }

    /**
     * Сам перенос.
     *
     * @param data  данные шага: {@code points} (сколько перенесено), {@code regions} (пройденные регионы)
     * @param save  записать данные шага сейчас
     * @param pace  пауза между регионами и ожидание при низком FPS (в тестах выключено)
     */
    public static Result migrate(Path dir, JsonObject data, Runnable save, Host h, boolean pace) throws Exception {
        FtbRegionReader.World world = FtbRegionReader.open(dir);
        int points = 0, known = 0;
        if (!data.has("points")) {
            List<PointStore.Point> ftb = FtbPoints.read(world);
            points = h.addPoints(ftb);
            known = ftb.size() - points;
            data.addProperty("points", points);
            save.run();
        }
        JsonArray done = data.has("regions") && data.get("regions").isJsonArray() ? data.getAsJsonArray("regions") : new JsonArray();
        data.add("regions", done);
        Set<String> passed = new HashSet<>();
        for (JsonElement e : done) passed.add(e.getAsString());
        int before = passed.size();
        List<String> skipped = new ArrayList<>();
        List<String> noMapDims = new ArrayList<>();
        List<FtbImport.Job> jobs = FtbImport.plan(world, h.playerDimension(), h.playerRegionX(), h.playerRegionZ(), h.noMap(), noMapDims);
        for (String d : noMapDims) skipped.add("измерение " + d);
        int regions = 0, chunks = 0;
        for (FtbImport.Job job : jobs) {
            if (passed.contains(job.key())) continue;
            while (pace && h.slow() && h.alive()) Thread.sleep(SLOW_WAIT_MS);
            if (!h.alive()) throw new IOException("выход из мира: перенос продолжится при следующем входе (пройдено " + passed.size() + " из " + jobs.size() + ")");
            FtbRegionReader.Region region;
            try {
                region = FtbRegionReader.read(job.zip());
            } catch (FtbRegionReader.FormatException e) {
                skipped.add(job.key() + ": " + e.getMessage());
                done.add(job.key());
                passed.add(job.key());
                save.run();
                continue;
            }
            chunks += h.importRegion(job.dimension(), region, world);
            regions++;
            done.add(job.key());
            passed.add(job.key());
            save.run();
            if (pace) Thread.sleep(PAUSE_MS);
        }
        return new Result(points, known, regions, chunks, before, skipped);
    }
}
