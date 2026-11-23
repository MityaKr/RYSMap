package works.repka.rysmap.migrate;

import java.nio.file.Path;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import works.repka.ryscore.migrate.Migrations;
import works.repka.rysmap.places.FtbPlaces;
import works.repka.rysmap.places.PlaceBook;
import works.repka.rysmap.server.MapServer;
import works.repka.rysmap.server.PlaceStore;
import works.repka.rysmap.server.PlacesServer;

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
        if (server == null) throw new IllegalStateException("the places step runs only on the server");
        FtbPlaces.Source src = FtbPlaces.read(dir(c));
        PlaceStore store = PlaceStore.get(server);
        PlaceBook book = store.book();
        FtbPlaces.Counts n = FtbPlaces.apply(book, src.data(), src.players(), MapServer.rules().places.backSize, System.currentTimeMillis());
        store.touch();
        // игрокам в сети новые места (шаг мог идти на работающем сервере, `/rys migrate run`): колесо и список без перезахода
        PlacesServer.changedAll(server);
        // база одним сохранением до отметки «сделано»
        server.overworld().getDataStorage().save();
        Migrations.Report r = Migrations.Report.create().count("places", n.places).count("homes", n.homes).count("back", n.back).count("players", n.players);
        r.line(Say.t("places_done", "migrated: %s server places, %s homes, %s \"back\" entries (players: %s)", n.places, n.homes, n.back, n.players));
        r.line(Say.t("now_in_base", "now in the base: %s", PlaceStore.summary(book)));
        if (n.placesSkipped + n.homesSkipped > 0) r.line(Say.t("untouched", "already there and untouched: %s places, %s homes", n.placesSkipped, n.homesSkipped));
        for (String s : n.skipped) r.skipped(s, "");
        r.skipped("nick, kits, kit_use_times, muted, fly, god, last_seen", Say.t("not_places", "not places: RYSHelm migrates them (admin step) or they are not migrated"));
        for (String bad : src.unreadable()) r.partial(Say.t("player_unread", "player file could not be read: %s", bad));
        c.log("FTB Essentials places: " + n.places + " server places, " + n.homes + " homes, " + n.back + " 'back' entries");
        return r;
    }
}
