package works.repka.rysmap.screen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import works.repka.ryscore.map.MapApi;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.theme.ThemeManager;
import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.ui.Icons;
import works.repka.ryscore.ui.Kit;
import works.repka.ryscore.ui.ScaledScreen;
import works.repka.ryscore.ui.Type.Weight;
import works.repka.rysmap.RYSMap;
import works.repka.rysmap.client.ClientEntry;
import works.repka.rysmap.client.MapSession;

/**
 * Карточка «что нового» один раз при первом входе после переезда с FTB (скептик s4): клавиша карты, что перенесено, что
 * нового. Показывается, когда шаг переезда карты {@code map} записал итог в {@code migrate.json} папки карты мира, и
 * запоминается файлом {@code whatsnew.json} там же. Число чанков земли команды карта не знает: у ядра нет клиентского
 * контракта земли, поэтому строка земли говорит только, что земля перенесена и где её смотреть (вкладка RYSClaims).
 */
public final class WhatsNewScreen extends ScaledScreen {
    private static final int W = 300;
    private final Kit.Hits hits = new Kit.Hits();
    private final long points, regions;
    private final MapApi.@Nullable Tab land;

    private WhatsNewScreen(long points, long regions) {
        super(Component.translatable("rysmap.whatsnew.title"));
        this.points = points;
        this.regions = regions;
        MapApi.Tab found = null;
        for (MapApi.Tab t : MapApi.tabs()) if (t.id().getNamespace().equals("rysclaims")) found = t;
        this.land = found;
    }

    /** Тик клиента: показать карточку, если переезд прошёл и её ещё не видели в этом мире. */
    public static void maybeShow(Minecraft mc) {
        if (System.getProperty("rysmap.autotest") != null) return; // автотесты открывают её сами
        MapSession s = ClientEntry.session();
        if (s == null || mc.screen != null || mc.player == null || mc.player.tickCount < 100) return;
        Path root = s.store.root();
        Path seen = root.resolve("whatsnew.json");
        if (Files.exists(seen)) return;
        long[] counts = ftbCounts(root);
        if (counts == null) return;
        try {
            Files.writeString(seen, "{\"v\":1,\"shown\":" + System.currentTimeMillis() / 1000 + "}", StandardCharsets.UTF_8);
        } catch (Exception e) {
            RYSMap.LOG.warn("[rysmap] whatsnew.json was not saved: {}", e.toString());
            return; // не записалось: не показывать, чтобы не всплывала каждый вход
        }
        mc.setScreen(new WhatsNewScreen(counts[0], counts[1]));
    }

    /** Для автотеста: открыть карточку с этими числами. */
    public static void openForTest(long points, long regions) {
        Minecraft.getInstance().setScreen(new WhatsNewScreen(points, regions));
    }

    /** {точки, регионы} из отметки шага map или null, если переезда карты не было. */
    static long @Nullable [] ftbCounts(Path root) {
        Path file = root.resolve("migrate.json");
        try {
            if (!Files.isRegularFile(file)) return null;
            JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject steps = json.has("steps") && json.get("steps").isJsonObject() ? json.getAsJsonObject("steps") : json;
            JsonObject map = steps.has("map") && steps.get("map").isJsonObject() ? steps.getAsJsonObject("map") : null;
            if (map == null || !map.has("counts") || !map.get("counts").isJsonObject()) return null;
            JsonObject c = map.getAsJsonObject("counts");
            return new long[]{c.has("points") ? c.get("points").getAsLong() : 0, c.has("regions") ? c.get("regions").getAsLong() : 0};
        } catch (Exception e) {
            return null;
        }
    }

    private record Row(@Nullable String key, @Nullable Icons.Shape mark, int markColor, String title, String note) {}

    @Override
    protected void draw(GuiGraphics g, int mx, int my, float partial) {
        hits.clear();
        Theme t = ThemeManager.get();
        g.fill(0, 0, width, height, t.c("overlay"));
        List<Row> rows = new ArrayList<>();
        String key = ClientEntry.OPEN.isUnbound() ? "—" : ClientEntry.OPEN.getTranslatedKeyMessage().getString();
        rows.add(new Row(key, null, 0, Component.translatable("rysmap.whatsnew.map").getString(),
            Component.translatable(land != null ? "rysmap.whatsnew.map.note_land" : "rysmap.whatsnew.map.note").getString()));
        if (land != null) rows.add(new Row(null, Icons.Shape.CHECK, t.c("state.ok"), Component.translatable("rysmap.whatsnew.land").getString(),
            Component.translatable("rysmap.whatsnew.land.note").getString()));
        rows.add(new Row(null, Icons.Shape.CHECK, t.c("state.ok"),
            Component.translatable("rysmap.whatsnew.points", Words.plural(points, "rysmap.settings.points"), Words.plural(regions, "rysmap.settings.regions")).getString(),
            Component.translatable("rysmap.whatsnew.points.note").getString()));
        rows.add(new Row(null, Icons.Shape.WARN, t.c("state.warn"), Component.translatable("rysmap.whatsnew.new").getString(),
            Component.translatable("rysmap.whatsnew.new.note").getString()));
        int rowH = 22, cardH = rows.size() * rowH + 4;
        int h = 16 + 22 + 10 + cardH + 12 + 18 + 14;
        int x = (width - W) / 2, y = (height - h) / 2;
        t.panel(g, x, y, W, h);
        int ix = x + 14, iw = W - 28, cy = y + 14;
        Draw.round(g, ix, cy, 18, 18, 5, 0xFF4F8F6A);
        Icons.draw(g, Icons.Shape.MAP, ix + 4, cy + 4, 10, 0xFFFFFFFF);
        Draw.label(g, Component.translatable("rysmap.whatsnew.title").getString(), ix + 24, cy + 5, 16, Weight.SEMIBOLD, t.c("text.primary"));
        Draw.label(g, Component.translatable("rysmap.whatsnew.sub").getString(), ix + 24, cy + 14, 11.5f, Weight.REGULAR, t.c("text.muted"));
        cy += 22 + 10;
        if (t.beveled()) t.card(g, ix, cy, iw, cardH);
        else Draw.roundBox(g, ix, cy, iw, cardH, 5, t.c("card.bg"), t.c("panel.border"));
        float ry = cy + 2;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (i > 0) Draw.rect(g, ix + 6, (int) ry, iw - 12, 1, t.c("panel.border"));
            float tx = ix + 8;
            if (r.key() != null) tx += MapScreen.kbd(g, t, r.key(), tx, ry + rowH / 2f) + 5;
            else if (r.mark() != null) {
                Icons.draw(g, r.mark(), tx, ry + rowH / 2f - 3.5f, 7, r.markColor());
                tx += 11;
            }
            Draw.label(g, Draw.ellipsize(r.title(), ix + iw - 8 - tx, 12.5f, Weight.MEDIUM), tx, ry + 7.5f, 12.5f, Weight.MEDIUM, t.c("text.primary"));
            Draw.label(g, Draw.ellipsize(r.note(), ix + iw - 8 - tx, 11, Weight.REGULAR), tx, ry + 15.5f, 11, Weight.REGULAR, t.c("text.muted"));
            ry += rowH;
        }
        cy += cardH + 12;
        int bx = ix;
        bx += PointWidgets.button(g, t, hits, bx, cy, 0, 18, Component.translatable("rysmap.whatsnew.open").getString(), null, 1, mx, my, () -> {
            onClose();
            MapScreen.open();
        }) + 4;
        if (land != null) {
            MapApi.Tab tab = land;
            PointWidgets.button(g, t, hits, bx, cy, 0, 18, Component.translatable("rysmap.whatsnew.where").getString(), null, 0, mx, my, () -> {
                onClose();
                MapApi.get().openTab(tab.id());
            });
        }
        String ok = Component.translatable("rysmap.whatsnew.ok").getString();
        int ow = (int) Draw.labelWidth(ok, 12, Weight.MEDIUM) + 12;
        PointWidgets.button(g, t, hits, ix + iw - ow, cy, ow, 18, ok, null, 2, mx, my, this::onClose);
    }

    @Override
    protected boolean onClick(double x, double y, int button) {
        return hits.click(x, y, button) || true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            onClose();
            MapScreen.open();
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
