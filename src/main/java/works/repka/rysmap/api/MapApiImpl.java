package works.repka.rysmap.api;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.map.MapApi;
import works.repka.rysmap.RYSMap;
import works.repka.rysmap.client.ClientEntry;
import works.repka.rysmap.client.MapSession;

/**
 * Реализация ядрового {@link MapApi}. На этапе М1 работает фон тайлов в чужом окне ({@link #drawTiles}); окно карты,
 * путь к цели и миникарта появятся на этапе М2, до тех пор {@link #open} только пишет в лог, а {@link #track}
 * запоминает цель. Слои, пункты меню и строки под миникартой регистрирует ядро, окно карты прочитает их само.
 */
public final class MapApiImpl implements MapApi {
    private @Nullable Target tracked;

    @Override
    public boolean present() {
        return true;
    }

    @Override
    public void open(Target target) {
        RYSMap.LOG.info("[rysmap] open {} {} {}: окно карты появится на этапе М2", target.dimension().location(), (int) target.x(), (int) target.z());
    }

    @Override
    public void track(@Nullable Target target) {
        tracked = target;
    }

    public @Nullable Target tracked() {
        return tracked;
    }

    @Override
    public boolean drawTiles(GuiGraphics g, ResourceKey<Level> dimension, double centerX, double centerZ, double pixelsPerBlock,
                             int x, int y, int w, int h) {
        MapSession s = ClientEntry.session();
        if (s == null) return false;
        return s.tiles.draw(g, dimension.location().toString(), centerX, centerZ, pixelsPerBlock, x, y, w, h);
    }
}
