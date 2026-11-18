package works.repka.rysmap.screen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.ui.Anchors;
import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.ui.Icons;
import works.repka.ryscore.ui.Kit;
import works.repka.ryscore.ui.Type.Weight;
import works.repka.rysmap.client.ClientConfig;
import works.repka.rysmap.client.ClientEntry;
import works.repka.rysmap.client.MapSession;
import works.repka.rysmap.hud.Minimap;

/**
 * Страница настроек карты внутри окна (макет {@code 50}, урезано по плану 13.2): слева «Миникарта» (показывать, угол,
 * размер, приближение) и «Места гибели»; справа живой образец миникарты, клавиши со ссылкой на настройки игры и
 * сведения о данных (размер карты мира, переезд из FTB). Форма, поворот, строки под картой, подписи в мире и раздел
 * «Сервер» идут во втором выпуске. Всё пишется в {@code config/rysmap/client.json} сразу.
 */
final class SettingsPage {
    private static final int ROW = 19, SEG_H = 18;
    private final Kit.Dropdown cornerDrop = new Kit.Dropdown();
    private @Nullable CompletableFuture<String> mapInfo;
    private @Nullable String ftbInfo;

    boolean keyPressed(int key) {
        return cornerDrop.keyPressed(key);
    }

    boolean scrolled(double mx, double my, double dy) {
        return cornerDrop.scrolled(mx, my, dy);
    }

    void draw(GuiGraphics g, Theme t, Kit.Hits hits, MapScreen s, int mx, int my) {
        ClientConfig c = ClientConfig.get();
        int top = s.wy + MapScreen.HEADER;
        int lx = s.wx + 70, lw = 270, rx = s.wx + 364, rw = 166;
        if (s.ww < 560) {
            lx = s.wx + 14;
            rx = s.wx + 14 + lw + 16;
        }

        // назад
        String back = Component.translatable("rysmap.settings.back").getString();
        float bw = Draw.labelWidth(back, 12.5f, Weight.MEDIUM) + 8;
        boolean hotBack = Kit.over(mx, my, lx - 1, top + 7, (int) bw + 2, 12);
        Icons.draw(g, Icons.Shape.CHEVRON_LEFT, lx, top + 10, 6, t.c("link"));
        Draw.label(g, back, lx + 8, top + 13, 12.5f, Weight.MEDIUM, hotBack ? t.c("text.primary") : t.c("link"));
        hits.add(lx - 1, top + 7, (int) bw + 2, 12, b -> s.closeSettings());
        Draw.label(g, Component.translatable("rysmap.settings.title").getString(), lx, top + 26.5f, 19, Weight.SEMIBOLD, t.c("text.primary"));

        int y = top + 38;
        Kit.caps(g, t, Component.translatable("rysmap.settings.minimap").getString(), lx, y + 4, 11, 0.44f);
        y += 10;

        // показывать
        label(g, t, "rysmap.settings.show", null, lx, y);
        boolean hotT = Kit.over(mx, my, lx + lw - 18, y + 4, 18, 10);
        Kit.toggle(g, t, lx + lw - 18, y + 4, c.minimap, hotT);
        hits.add(lx + lw - 18, y + 4, 18, 10, b -> {
            c.minimap = !c.minimap;
            c.save();
        });
        y += ROW;

        // угол
        label(g, t, "rysmap.settings.corner", null, lx, y);
        Anchors.Corner corner = Minimap.corner();
        String cornerName = Component.translatable("rysmap.settings.corner." + corner.name().toLowerCase(java.util.Locale.ROOT)).getString();
        int dw = Kit.dropdownWidth(cornerName, 100);
        int cornerX = lx + lw - Math.max(dw, 85);
        Kit.dropdown(g, t, hits, cornerDrop, cornerX, y + 2, Math.max(dw, 85), cornerName, c.minimapCorner != null, mx, my);
        y += ROW;

        // размер
        label(g, t, "rysmap.settings.size", "rysmap.settings.size.note", lx, y);
        int[] sizes = {0, 72, 96, 128};
        String[] sizeNames = {Component.translatable("rysmap.settings.size.auto").getString(), "S", "M", "L"};
        int sel = 0;
        for (int i = 0; i < sizes.length; i++) if (sizes[i] == c.minimapSize) sel = i;
        segmented(g, t, hits, lx + lw, y, sizeNames, sel, mx, my, i -> {
            c.minimapSize = sizes[i];
            c.save();
        });
        y += ROW + 2;

        // приближение
        label(g, t, "rysmap.settings.zoom", "rysmap.settings.zoom.note", lx, y);
        String[] zooms = {"×0,5", "×1", "×2"};
        segmented(g, t, hits, lx + lw, y, zooms, c.minimapZoom, mx, my, i -> {
            c.minimapZoom = i;
            c.save();
        });
        y += ROW + 8;

        // места гибели
        Kit.caps(g, t, Component.translatable("rysmap.settings.world").getString(), lx, y + 4, 11, 0.44f);
        y += 10;
        label(g, t, "rysmap.settings.deaths", "rysmap.settings.deaths.note", lx, y);
        int[] kept = {0, 1, 3, 5};
        String[] keptNames = {Component.translatable("rysmap.settings.off").getString(), "1", "3", "5"};
        int ks = 2;
        for (int i = 0; i < kept.length; i++) if (kept[i] == c.deathsKept) ks = i;
        segmented(g, t, hits, lx + lw, y, keptNames, ks, mx, my, i -> {
            c.deathsKept = kept[i];
            c.save();
            MapSession session = ClientEntry.session();
            if (session != null && kept[i] > 0) {
                session.points().deathsKept(kept[i]);
                session.savePoints();
            }
        });

        // справа: образец
        int ry = top + 12;
        Kit.caps(g, t, Component.translatable("rysmap.settings.preview").getString(), rx, ry + 2, 11, 0.44f);
        ry += 8;
        int ph = 134;
        if (t.beveled()) t.card(g, rx, ry, rw, ph);
        else Draw.roundBox(g, rx, ry, rw, ph, 5, t.c("card.bg"), t.c("panel.border"));
        int size = Math.min(Minimap.size(), 96);
        boolean left = corner == Anchors.Corner.TOP_LEFT || corner == Anchors.Corner.BOTTOM_LEFT;
        boolean topCorner = corner == Anchors.Corner.TOP_LEFT || corner == Anchors.Corner.TOP_RIGHT;
        int mxp = left ? rx + 6 : rx + rw - 6 - size;
        if (c.minimap) {
            int plate = 2 + 10 * 2 + 4;
            int myp = topCorner ? ry + 6 : ry + ph - 6 - size - plate;
            Minimap.preview(g, mxp, Math.max(ry + 3, myp), size, true);
        } else {
            String off = Component.translatable("rysmap.settings.preview.off").getString();
            Draw.label(g, off, rx + (rw - Draw.labelWidth(off, 12, Weight.REGULAR)) / 2f, ry + ph / 2f, 12, Weight.REGULAR, t.c("text.muted"));
        }
        ry += ph + 10;

        // клавиши
        Kit.caps(g, t, Component.translatable("rysmap.settings.keys").getString(), rx, ry + 2, 11, 0.44f);
        String change = Component.translatable("rysmap.settings.keys.change").getString();
        float chw = Draw.labelWidth(change, 11.5f, Weight.REGULAR);
        boolean hotK = Kit.over(mx, my, (int) (rx + rw - chw), ry - 3, (int) chw + 1, 10);
        Draw.label(g, change, rx + rw - chw, ry + 2, 11.5f, Weight.REGULAR, hotK ? t.c("text.primary") : t.c("link"));
        hits.add((int) (rx + rw - chw), ry - 3, (int) chw + 1, 10, b -> Minecraft.getInstance().setScreen(
            new net.minecraft.client.gui.screens.options.controls.KeyBindsScreen(s, Minecraft.getInstance().options)));
        ry += 9;
        key(g, t, keyText(ClientEntry.OPEN), "rysmap.settings.keys.open", rx, ry);
        ry += 12;
        key(g, t, keyText(ClientEntry.NEW_POINT), "rysmap.settings.keys.new_point", rx, ry);
        ry += 12;
        key(g, t, keyText(ClientEntry.ZOOM_IN) + " " + keyText(ClientEntry.ZOOM_OUT), "rysmap.settings.keys.zoom", rx, ry);
        ry += 12 + 8;

        // данные
        Kit.caps(g, t, Component.translatable("rysmap.settings.data").getString(), rx, ry + 2, 11, 0.44f);
        String folder = Component.translatable("rysmap.settings.data.folder").getString();
        Draw.label(g, folder, rx + rw - Draw.labelWidth(folder, 11.5f, Weight.REGULAR), ry + 2, 11.5f, Weight.REGULAR, t.c("text.muted"));
        ry += 9;
        MapSession session = ClientEntry.session();
        if (session != null && mapInfo == null) mapInfo = CompletableFuture.supplyAsync(() -> mapSize(session.store.root()));
        String size1 = mapInfo != null && mapInfo.isDone() ? mapInfo.getNow("") : "…";
        data(g, t, Component.translatable("rysmap.settings.data.map").getString(), size1, rx, ry, rw);
        ry += 19;
        if (ftbInfo == null && session != null) ftbInfo = ftbInfo(session.store.root());
        data(g, t, Component.translatable("rysmap.settings.data.ftb").getString(), ftbInfo == null ? "" : ftbInfo, rx, ry, rw);

        // раскрытый список угла поверх всего
        if (cornerDrop.open) {
            List<Kit.Option> options = new ArrayList<>();
            Anchors.Corner[] corners = Anchors.Corner.values();
            for (Anchors.Corner k : corners)
                options.add(new Kit.Option(Component.translatable("rysmap.settings.corner." + k.name().toLowerCase(java.util.Locale.ROOT)).getString()));
            Kit.dropdownList(g, t, hits, cornerDrop, options, corner.ordinal(), 100, mx, my, i -> {
                c.minimapCorner = corners[i] == Anchors.mapCorner() ? null : corners[i].name();
                c.save();
            });
        }
    }

    private static void label(GuiGraphics g, Theme t, String key, @Nullable String note, int x, int y) {
        String text = Component.translatable(key).getString();
        if (note == null) {
            Draw.label(g, text, x, y + 9, 13, Weight.REGULAR, t.c("text.body"));
            return;
        }
        Draw.label(g, text, x, y + 5.5f, 13, Weight.REGULAR, t.c("text.body"));
        Draw.label(g, Component.translatable(note).getString(), x, y + 13, 11, Weight.REGULAR, t.c("text.muted"));
    }

    /** Сегментный переключатель справа до {@code right}: ряд {@link Kit#pill} на общей подложке. */
    private static void segmented(GuiGraphics g, Theme t, Kit.Hits hits, int right, int y, String[] options, int selected, int mx, int my,
                                  java.util.function.IntConsumer pick) {
        float w = 4;
        for (int i = 0; i < options.length; i++) w += Draw.labelWidth(options[i], 12.5f, i == selected ? Weight.MEDIUM : Weight.REGULAR) + 12 + (i > 0 ? 1 : 0);
        int x = (int) (right - Math.ceil(w));
        if (t.beveled()) t.input(g, x, y, (int) Math.ceil(w), SEG_H, false);
        else Draw.roundBox(g, x, y, (int) Math.ceil(w), SEG_H, 4.5f, t.c("input.bg"), t.c("panel.border"));
        int px = x + 2;
        for (int i = 0; i < options.length; i++) {
            int index = i;
            px += Kit.pill(g, t, hits, px, y + 2, options[i], i == selected, mx, my, () -> pick.accept(index)) + 1;
        }
    }

    private static String keyText(net.minecraft.client.KeyMapping k) {
        return k.isUnbound() ? Component.translatable("rysmap.settings.keys.none").getString() : k.getTranslatedKeyMessage().getString();
    }

    private static void key(GuiGraphics g, Theme t, String key, String what, int x, int y) {
        float w = 0;
        for (String part : key.split(" ")) w += MapScreen.kbd(g, t, part, x + w, y + 4) + 2;
        Draw.label(g, Component.translatable(what).getString(), x + Math.max(w, 26) + 4, y + 4, 12.5f, Weight.REGULAR, t.c("text.secondary"));
    }

    private static void data(GuiGraphics g, Theme t, String title, String note, int x, int y, int w) {
        Draw.label(g, title, x, y + 5, 13, Weight.REGULAR, t.c("text.body"));
        Draw.label(g, Draw.ellipsize(note, w, 11, Weight.REGULAR), x, y + 12.5f, 11, Weight.REGULAR, t.c("text.muted"));
    }

    /** «84 МБ · 148 регионов» по файлам папки мира; считается в стороннем потоке один раз за открытие. */
    private static String mapSize(Path root) {
        long bytes = 0;
        int regions = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path f : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(f)) continue;
                bytes += Files.size(f);
                if (f.getFileName().toString().endsWith(".rmap")) regions++;
            }
        } catch (Exception e) {
            return "";
        }
        String mb = bytes < 1024 * 1024 ? "<1" : Long.toString(bytes / (1024 * 1024));
        return Component.translatable("rysmap.settings.data.map.note", mb, Words.plural(regions, "rysmap.settings.regions")).getString();
    }

    /** Строка о переезде из FTB по отметке шага в папке мира. */
    private static String ftbInfo(Path root) {
        Path file = root.resolve("migrate.json");
        try {
            if (Files.isRegularFile(file)) {
                JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject steps = json.has("steps") && json.get("steps").isJsonObject() ? json.getAsJsonObject("steps") : json;
                JsonObject map = steps.has("map") && steps.get("map").isJsonObject() ? steps.getAsJsonObject("map") : null;
                if (map != null && map.has("counts") && map.get("counts").isJsonObject()) {
                    JsonObject counts = map.getAsJsonObject("counts");
                    long points = counts.has("points") ? counts.get("points").getAsLong() : 0;
                    long regions = counts.has("regions") ? counts.get("regions").getAsLong() : 0;
                    String when = map.has("time") ? map.get("time").getAsString() : "";
                    if (when.length() >= 10) when = when.substring(8, 10) + "." + when.substring(5, 7) + "." + when.substring(0, 4);
                    return Component.translatable("rysmap.settings.data.ftb.done", Words.plural(points, "rysmap.settings.points"),
                        Words.plural(regions, "rysmap.settings.regions"), when).getString();
                }
            }
        } catch (Exception ignored) {
            // отметка не читается: переезда как будто не было
        }
        return Component.translatable("rysmap.settings.data.ftb.none").getString();
    }
}
