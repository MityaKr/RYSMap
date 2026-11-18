package works.repka.rysmap.client;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.ui.Icons;
import works.repka.ryscore.ui.Notice;
import works.repka.rysmap.migrate.FtbMapStep;
import works.repka.rysmap.migrate.FtbRegionReader;
import works.repka.rysmap.point.PointStore;

/**
 * Шаг {@code map} в игре: точки пишутся в главном потоке, регионы в рабочем потоке карты, поток переезда ядра ждёт
 * каждое действие. Место игрока снимается при запуске: от него идёт порядок регионов.
 */
final class FtbHost implements FtbMapStep.Host {
    private final MapSession session;
    private final @Nullable String dimension;
    private final int regionX, regionZ;
    private final Set<String> noMap;

    FtbHost(MapSession session, @Nullable String dimension, int blockX, int blockZ, Set<String> noMap) {
        this.session = session;
        this.dimension = dimension;
        this.regionX = blockX >> 9;
        this.regionZ = blockZ >> 9;
        this.noMap = Set.copyOf(noMap);
    }

    @Override
    public boolean alive() {
        return ClientEntry.session() == session && !session.closed();
    }

    @Override
    public @Nullable String playerDimension() {
        return dimension;
    }

    @Override
    public int playerRegionX() {
        return regionX;
    }

    @Override
    public int playerRegionZ() {
        return regionZ;
    }

    @Override
    public Set<String> noMap() {
        return noMap;
    }

    @Override
    public int addPoints(List<PointStore.Point> points) throws Exception {
        return CompletableFuture.supplyAsync(() -> session.importPoints(points), Minecraft.getInstance()).get(30, TimeUnit.SECONDS);
    }

    @Override
    public int importRegion(String dim, FtbRegionReader.Region region, FtbRegionReader.World world) throws Exception {
        return session.importFtb(dim, region, world).get(120, TimeUnit.SECONDS);
    }

    @Override
    public boolean slow() {
        return Minecraft.getInstance().getFps() < 30;
    }

    @Override
    public void finished(int points, int regions) {
        if (points == 0 && regions == 0) return;
        Minecraft.getInstance().execute(() -> Notice.row(Notice.MAP, "ftb_done").icon(Icons.Shape.MAP)
            .label(Component.translatable("rysmap.migrate.done", points, regions).getString()).show());
    }
}
