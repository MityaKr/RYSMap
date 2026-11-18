package works.repka.rysmap.migrate;

import java.nio.file.Path;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import works.repka.ryscore.migrate.Migrations;
import works.repka.rysmap.places.FtbPlaces;
import works.repka.rysmap.places.PlaceBook;
import works.repka.rysmap.server.MapServer;
import works.repka.rysmap.server.PlaceStore;

/**
 * Шаг {@code places} переезда с FTB Essentials (порядок 50, сервер; план Essentials 6): места сервера (варпы), дома
 * игроков и история «назад» из {@code world/ftbessentials} в {@code world/data/rysmap_places.dat}. Идёт, пока FTB
 * Essentials не загружен (пока он стоит, дома и места ведёт он). Файлы FTB только читаются; копию делает ядро
 * ({@link #backup()}). Повтор безопасен: существующие имена не затираются, историю получает только тот, у кого её нет.
 * Нечитаемый файл игрока делает шаг частичным: отметки нет, шаг повторится.
 */
public final class PlacesStep implements Migrations.Step {
    public static final String ID = "places";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public Migrations.Side side() {
        return Migrations.Side.SERVER;
    }

    @Override
    public String modId() {
        return "rysmap";
    }

    @Override
    public List<String> backup() {
        return List.of("ftbessentials");
    }

    static Path dir(Migrations.Context c) {
        Path world = c.worldDir();
        return world == null ? null : world.resolve("ftbessentials");
    }

    @Override
    public boolean needed(Migrations.Context c) {
        Path dir = dir(c);
        return dir != null && !c.isLoaded("ftbessentials") && FtbPlaces.exists(dir);
    }

    @Override
    public Migrations.Report run(Migrations.Context c) throws Exception {
        MinecraftServer server = c.server();
        if (server == null) throw new IllegalStateException("шаг places идёт только на сервере");
        FtbPlaces.Source src = FtbPlaces.read(dir(c));
        PlaceStore store = PlaceStore.get(server);
        PlaceBook book = store.book();
        FtbPlaces.Counts n = FtbPlaces.apply(book, src.data(), src.players(), MapServer.rules().places.backSize, System.currentTimeMillis());
        store.touch();
        // база одним сохранением до отметки «сделано»
        server.overworld().getDataStorage().save();
        Migrations.Report r = Migrations.Report.create().count("places", n.places).count("homes", n.homes).count("back", n.back).count("players", n.players);
        r.line("перенесено: мест сервера " + n.places + ", домов " + n.homes + ", записей «назад» " + n.back + " (игроков " + n.players + ")");
        r.line("теперь в базе: " + PlaceStore.summary(book));
        if (n.placesSkipped + n.homesSkipped > 0) r.line("уже были и не тронуты: мест " + n.placesSkipped + ", домов " + n.homesSkipped);
        for (String s : n.skipped) r.skipped(s, "");
        r.skipped("nick, kits, kit_use_times, muted, fly, god, last_seen", "не места: их переносит RYSHelm (шаг admin) или не переносим");
        for (String bad : src.unreadable()) r.partial("файл игрока не прочитался: " + bad);
        c.log("места FTB Essentials: " + n.places + " мест сервера, " + n.homes + " домов, " + n.back + " записей «назад»");
        return r;
    }
}
