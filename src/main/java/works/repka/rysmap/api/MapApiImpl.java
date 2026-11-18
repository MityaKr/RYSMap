package works.repka.rysmap.api;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.map.MapApi;
import works.repka.rysmap.client.ClientEntry;
import works.repka.rysmap.client.MapSession;

/**
 * Реализация ядрового {@link MapApi}: окно карты на цели ({@link #open}), путь к цели на миникарте ({@link #track}),
 * фон тайлов в чужом окне ({@link #drawTiles}). Слои, пункты меню и строки под миникартой регистрирует ядро,
 * миникарта и окно карты читают их сами.
 */
public final class MapApiImpl implements MapApi {
    private @Nullable Target tracked;
    private java.util.@Nullable UUID trackedPoint;

    @Override
    public boolean present() {
        return true;
    }

    @Override
    public void open(Target target) {
        works.repka.rysmap.screen.MapScreen.open(target);
    }

    @Override
    public void openTab(net.minecraft.resources.ResourceLocation tab) {
        works.repka.rysmap.screen.MapScreen.openTab(tab);
    }

    @Override
    public void openMode(net.minecraft.resources.ResourceLocation mode, @Nullable Target at) {
        works.repka.rysmap.screen.MapScreen.openMode(mode, at);
    }

    @Override
    public void track(@Nullable Target target) {
        tracked = target;
        trackedPoint = null;
    }

    /** «В путь» к своей точке: цель запоминает id точки, чтобы панель показала «Снять путь». */
    public void trackPoint(java.util.UUID point, Target target) {
        tracked = target;
        trackedPoint = point;
    }

    public boolean tracking(java.util.UUID point) {
        return tracked != null && point.equals(trackedPoint);
    }

    public @Nullable Target tracked() {
        return tracked;
    }

    /** Id своей точки, к которой ведёт путь, или null (путь к месту с карты или от другого мода). */
    public java.util.@Nullable UUID trackedPoint() {
        return tracked == null ? null : trackedPoint;
    }

    @Override
    public boolean drawTiles(GuiGraphics g, ResourceKey<Level> dimension, double centerX, double centerZ, double pixelsPerBlock,
                             int x, int y, int w, int h) {
        MapSession s = ClientEntry.session();
        if (s == null) return false;
        return s.tiles.draw(g, dimension.location().toString(), centerX, centerZ, pixelsPerBlock, x, y, w, h);
    }
}
