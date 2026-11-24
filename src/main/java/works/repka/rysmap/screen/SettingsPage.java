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
 * Страница настроек карты внутри окна (макет {@code 50}, урезано по плану 13.2). С 0.2.10 (отзыв автора 19.11: «настройки
 * поплыли») разделы стоят вкладками вверху ({@link Kit#tabs}): «Миникарта» с живым образцом справа, «В мире», «Сервер» и
 * «Перемещения» (только оператор), «Клавиши и данные». Последняя вкладка помнится в {@code config/rysmap/client.json}.
 * Строки раскладывает {@link RowLayout}: переключатель, который не помещается справа от подписи, уходит под неё, высота
 * строки по содержимому. Всё пишется в {@code config/rysmap/client.json} сразу.
 */
final class SettingsPage {
    private static final int SEG_H = 18;
    private static final String WORLD = SettingsTabs.WORLD, SERVER = SettingsTabs.SERVER, PLACES = SettingsTabs.PLACES, KEYS = SettingsTabs.KEYS;
    private final Kit.Dropdown cornerDrop = new Kit.Dropdown();
    private @Nullable CompletableFuture<String> mapInfo;
    private @Nullable String ftbInfo;

    boolean keyPressed(int key) {
        return cornerDrop.keyPressed(key);
    }

    boolean scrolled(double mx, double my, double dy) {
        if (cornerDrop.scrolled(mx, my, dy)) return true;
        if (maxScroll <= 0) return false;
        scroll = Math.max(0, Math.min(maxScroll, scroll - (float) dy * 24));
        return true;
    }

    /** Прокрутка вкладки: в сжатой раскладке вкладка может быть выше окна (макет 87). */
    private float scroll, maxScroll;

    /** Для автотеста: прокрутить вкладку. */
    void scrollTo(float value) {
        scroll = Math.max(0, value);
    }

    /** Открыть вкладку по id; выбор помнится. */
    void tab(String id) {
        ClientConfig c = ClientConfig.get();
        if (!c.settingsTab.equals(id)) {
            c.settingsTab = id;
            c.save();
        }
        scroll = 0;
        cornerDrop.close();
    }

    /** Кадр: сжатая раскладка, мышь, куда класть подсказку пояснения. */
    private boolean compactNow;
    private int mouseX, mouseY;
    private @Nullable MapScreen screen;

    void draw(GuiGraphics g, Theme t, Kit.Hits hits, MapScreen s, int mx, int my) {
        ClientConfig c = ClientConfig.get();
        compactNow = s.compact();
        mouseX = mx;
        mouseY = my;
        screen = s;
        int margin = compactNow ? 14 : 24;
        int viewTop = s.wy + MapScreen.HEADER, viewBottom = s.wy + s.wh - 2;
        int avail = s.ww - margin * 2;

        // «Назад» и вкладки стоят на месте, прокручивается только содержимое вкладки
        int ox = s.wx + margin;
        String back = tr("rysmap.settings.back");
        float bw = Draw.labelWidth(back, 12.5f, Weight.MEDIUM) + 8;
        boolean hotBack = Kit.over(mx, my, ox - 1, viewTop + 4, (int) bw + 2, 12);
        Icons.draw(g, Icons.Shape.CHEVRON_LEFT, ox, viewTop + 7, 6, t.c("link"));
        Draw.label(g, back, ox + 8, viewTop + 10, 12.5f, Weight.MEDIUM, hotBack ? t.c("text.primary") : t.c("link"));
        hits.add(ox - 1, viewTop + 4, (int) bw + 2, 12, b -> s.closeSettings());
        boolean op = works.repka.rysmap.client.ServerRules.op();
        var places = works.repka.rysmap.client.PlacesClient.data();
        List<String> visible = SettingsTabs.visible(op && works.repka.rysmap.client.ServerRules.rules() != null, places.present && places.op);
        String tab = SettingsTabs.current(c.settingsTab, visible);
        List<Kit.Tab> tabRow = new ArrayList<>();
        for (String id : visible) tabRow.add(new Kit.Tab(tr("rysmap.settings.tab." + id), -1, id.equals(tab), () -> tab(id)));
        int tabsY = viewTop + 17, tabsH = Kit.TABS_H + 2;
        Kit.tabs(g, t, hits, ox, tabsY, tabsH, avail, tabRow, mx, my);
        Kit.divider(g, t, s.wx + 1, tabsY + tabsH, s.ww - 2);

        int clipTop = tabsY + tabsH + 1;
        int top = clipTop + 8 - Math.round(scroll);
        Draw.scissor(g, s.wx + 1, clipTop, s.wx + s.ww - 1, viewBottom);
        hits.clip(clipTop, viewBottom);
        int bottom = switch (tab) {
            case WORLD -> world(g, t, hits, c, block(s, avail, 400), Math.min(avail, 400), top, mx, my);
            case SERVER -> server(g, t, hits, block(s, avail, 440), Math.min(avail, 440), top, mx, my);
            case PLACES -> PlacesSettings.draw(g, t, hits, block(s, avail, 420), Math.min(avail, 420), top, mx, my);
            case KEYS -> keysAndData(g, t, hits, s, block(s, avail, 340), Math.min(avail, 340), top, mx, my);
            default -> minimap(g, t, hits, c, s, avail, top, mx, my);
        };

        // прокрутка по высоте содержимого вкладки
        int contentBottom = bottom + 8;
        maxScroll = Math.max(0, contentBottom + Math.round(scroll) - viewBottom);
        scroll = Math.min(scroll, maxScroll);
        if (maxScroll > 0) {
            float viewH = viewBottom - clipTop, total = viewH + maxScroll;
            float bh = Math.max(16, viewH * viewH / total), by = clipTop + (viewH - bh) * (scroll / maxScroll);
            Draw.round(g, s.wx + s.ww - 5, by, 2.5f, bh, 1.25f, Draw.alpha(t.c("text.muted"), 0.6f));
        }
        hits.unclip();
        g.disableScissor();

        // раскрытый список угла поверх всего
        if (cornerDrop.open) {
            List<Kit.Option> options = new ArrayList<>();
            Anchors.Corner[] corners = Anchors.Corner.values();
            for (Anchors.Corner k : corners) options.add(new Kit.Option(tr("rysmap.settings.corner." + k.name().toLowerCase(java.util.Locale.ROOT))));
            Kit.dropdownList(g, t, hits, cornerDrop, options, Minimap.corner().ordinal(), 100, mx, my, i -> {
                c.minimapCorner = null;
                c.save();
                Anchors.setMapCorner(corners[i]);
            });
        }
    }

    /** Левый край колонки шириной не больше {@code max}, по середине окна. */
    private static int block(MapScreen s, int avail, int max) {
        return s.wx + (s.ww - Math.min(avail, max)) / 2;
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    /** Переключатель строки: рисуется правым краем в {@code right}, верх в {@code y}. */
    @FunctionalInterface
    private interface Control {
        void draw(int right, int y);
    }

    /**
     * Строка настроек: подпись, пояснение и переключатель по {@link RowLayout}. Возвращает верх следующей строки. В сжатой
     * раскладке пояснение уходит в подсказку при наведении на подпись (макет 87).
     */
    private int row(GuiGraphics g, Theme t, String key, @Nullable String noteKey, float controlW, int x, int w, int y, Control control) {
        String text = tr(key);
        String note = noteKey == null ? null : tr(noteKey);
        boolean shownNote = note != null && !compactNow;
        float labelW = Draw.labelWidth(text, 13, Weight.REGULAR);
        RowLayout.Place p = RowLayout.place(labelW, shownNote ? Draw.labelWidth(note, 11, Weight.REGULAR) : 0, controlW, w);
        if (p.noteLines() > 0) {
            Draw.label(g, text, x, y + 5.5f, 13, Weight.REGULAR, t.c("text.body"));
            List<String> lines = p.noteLines() == 1 ? List.of(note) : Draw.wrapLabel(note, w, 11, Weight.REGULAR, p.noteLines());
            float ly = y + 13;
            for (String line : lines) {
                Draw.label(g, line, x, ly, 11, Weight.REGULAR, t.c("text.muted"));
                ly += RowLayout.NOTE_LINE;
            }
        } else {
            Draw.label(g, text, x, p.below() ? y + 5.5f : y + 9, 13, Weight.REGULAR, t.c("text.body"));
            if (note != null && screen != null && Kit.over(mouseX, mouseY, x, y + 3, (int) labelW + 2, 12)) screen.tooltip(List.of(text, note));
        }
        control.draw(p.below() ? x + (int) Math.ceil(controlW) : x + w, y + p.controlDy());
        return y + p.height();
    }

    /** Строка с выключателем 18×10. */
    private int toggleRow(GuiGraphics g, Theme t, Kit.Hits hits, String key, @Nullable String note, int x, int w, int y, boolean on,
                          int mx, int my, Runnable flip) {
        return row(g, t, key, note, 18, x, w, y, (right, cy) -> {
            boolean hot = Kit.over(mx, my, right - 18, cy + 4, 18, 10);
            Kit.toggle(g, t, right - 18, cy + 4, on, hot);
            hits.add(right - 18, cy + 4, 18, 10, b -> flip.run());
        });
    }

    /** Строка с сегментным переключателем. */
    private int segRow(GuiGraphics g, Theme t, Kit.Hits hits, String key, @Nullable String note, int x, int w, int y, String[] options,
                       int selected, int mx, int my, java.util.function.IntConsumer pick) {
        return row(g, t, key, note, segWidth(options, selected), x, w, y,
            (right, cy) -> segmented(g, t, hits, right, cy, options, selected, mx, my, pick));
    }

    /** Строка с независимыми выключателями-плашками. */
    private int pillRow(GuiGraphics g, Theme t, Kit.Hits hits, String key, @Nullable String note, int x, int w, int y, String[] options,
                        boolean[] on, int mx, int my, java.util.function.IntConsumer toggle) {
        return row(g, t, key, note, pillsWidth(options, on), x, w, y, (right, cy) -> pills(g, t, hits, right, cy, options, on, mx, my, toggle));
    }

    /** Вкладка «Миникарта»: показ, угол, размер, приближение, форма, ночь и пещеры, мобы, камни пути; справа образец. */
    private int minimap(GuiGraphics g, Theme t, Kit.Hits hits, ClientConfig c, MapScreen s, int avail, int top, int mx, int my) {
        int pw = 166, gap = 16;
        boolean preview = !compactNow && avail >= 300 + gap + pw;
        int lw = preview ? Math.min(avail - gap - pw, 380) : Math.min(avail, 420);
        int blockW = preview ? lw + gap + pw : lw;
        int lx = s.wx + (s.ww - blockW) / 2;
        int y = top;

        y = toggleRow(g, t, hits, "rysmap.settings.show", null, lx, lw, y, c.minimap, mx, my, () -> {
            c.minimap = !c.minimap;
            c.save();
        });

        Anchors.Corner corner = Minimap.corner();
        String cornerName = tr("rysmap.settings.corner." + corner.name().toLowerCase(java.util.Locale.ROOT));
        int dw = Math.max(Kit.dropdownWidth(cornerName, 100), 85);
        y = row(g, t, "rysmap.settings.corner", null, dw, lx, lw, y,
            (right, cy) -> Kit.dropdown(g, t, hits, cornerDrop, right - dw, cy + 2, dw, cornerName, Anchors.mapCorner() != Anchors.Corner.TOP_RIGHT, mx, my));

        // где встают уведомления семьи (ядро 1.17): справа под миникартой, по центру под боссбарами, слева
        works.repka.ryscore.ui.Notices.Place[] places = works.repka.ryscore.ui.Notices.Place.values();
        String[] placeNames = new String[places.length];
        for (int i = 0; i < places.length; i++) placeNames[i] = tr("rysmap.settings.notices." + places[i].name().toLowerCase(java.util.Locale.ROOT));
        y = segRow(g, t, hits, "rysmap.settings.notices", "rysmap.settings.notices.note", lx, lw, y, placeNames,
            works.repka.ryscore.ui.Notices.place().ordinal(), mx, my, i -> works.repka.ryscore.ui.Notices.setPlace(places[i]));

        int[] sizes = {0, 72, 96, 128};
        String[] sizeNames = {tr("rysmap.settings.size.auto"), "S", "M", "L"};
        int sel = 0;
        for (int i = 0; i < sizes.length; i++) if (sizes[i] == c.minimapSize) sel = i;
        y = segRow(g, t, hits, "rysmap.settings.size", "rysmap.settings.size.note", lx, lw, y, sizeNames, sel, mx, my, i -> {
            c.minimapSize = sizes[i];
            c.save();
        });

        y = segRow(g, t, hits, "rysmap.settings.zoom", "rysmap.settings.zoom.note", lx, lw, y, new String[]{"×0,5", "×1", "×2"}, c.minimapZoom, mx, my, i -> {
            c.minimapZoom = i;
            c.save();
        });

        // форма и поворот (макет 50): два переключателя рядом
        String[] turns = {tr("rysmap.settings.shape.north"), tr("rysmap.settings.shape.view")};
        String[] shapes = {tr("rysmap.settings.shape.square"), tr("rysmap.settings.shape.circle")};
        int turnSel = c.minimapRotate ? 1 : 0, shapeSel = c.minimapCircle ? 1 : 0;
        float shapesW = segWidth(turns, turnSel) + 4 + segWidth(shapes, shapeSel);
        y = row(g, t, "rysmap.settings.shape", null, shapesW, lx, lw, y, (right, cy) -> {
            int tw = segmented(g, t, hits, right, cy, turns, turnSel, mx, my, i -> {
                c.minimapRotate = i == 1;
                c.save();
            });
            segmented(g, t, hits, right - tw - 4, cy, shapes, shapeSel, mx, my, i -> {
                c.minimapCircle = i == 1;
                c.save();
            });
        });

        // ночь и пещеры (макет 50): независимые выключатели
        y = pillRow(g, t, hits, "rysmap.settings.night_caves", null, lx, lw, y, new String[]{tr("rysmap.settings.night"), tr("rysmap.settings.caves")},
            new boolean[]{c.night, c.caves}, mx, my, i -> {
                if (i == 0) c.night = !c.night;
                else c.caves = !c.caves;
                c.save();
            });

        // мобы (скептик s2): вид, свой слой, кого показывать
        y = segRow(g, t, hits, "rysmap.settings.mobs", null, lx, lw, y,
            new String[]{tr("rysmap.settings.no"), tr("rysmap.settings.mobs.heads"), tr("rysmap.settings.mobs.dots")}, c.mobsView, mx, my, i -> {
                c.mobsView = i;
                c.save();
            });
        int[] mobRanges = {8, 16, 0};
        int rsel = c.mobsRange == 8 ? 0 : c.mobsRange == 16 ? 1 : 2;
        y = segRow(g, t, hits, "rysmap.settings.mobs.layer", "rysmap.settings.mobs.layer.note", lx, lw, y,
            new String[]{"±8", "±16", tr("rysmap.settings.mobs.all")}, rsel, mx, my, i -> {
                c.mobsRange = mobRanges[i];
                c.save();
            });
        y = pillRow(g, t, hits, "rysmap.settings.mobs.who", null, lx, lw, y,
            new String[]{tr("rysmap.settings.mobs.hostile"), tr("rysmap.settings.mobs.passive"), tr("rysmap.settings.mobs.pets")},
            new boolean[]{c.mobsHostile, c.mobsPassive, c.mobsPets}, mx, my, i -> {
                if (i == 0) c.mobsHostile = !c.mobsHostile;
                else if (i == 1) c.mobsPassive = !c.mobsPassive;
                else c.mobsPets = !c.mobsPets;
                c.save();
            });
        if (works.repka.rysmap.compat.Waystones.active()) {
            works.repka.ryscore.map.MapApi.Layer stones = works.repka.rysmap.layer.Layers.find(works.repka.rysmap.compat.Waystones.ID);
            boolean on = stones != null && works.repka.rysmap.layer.Layers.visible(stones);
            y = toggleRow(g, t, hits, "rysmap.settings.waystones", "rysmap.settings.waystones.note", lx, lw, y, on, mx, my, () -> {
                if (stones != null) works.repka.rysmap.layer.Layers.setVisible(stones, !on);
            });
        }

        // справа живой образец; в сжатой раскладке и в узком окне его нет: настоящая миникарта видна за окном
        if (preview) {
            int rx = lx + lw + gap, ry = top, ph = 134;
            Kit.caps(g, t, tr("rysmap.settings.preview"), rx, ry + 2, 11, 0.44f);
            ry += 8;
            if (t.beveled()) t.card(g, rx, ry, pw, ph);
            else Draw.roundBox(g, rx, ry, pw, ph, 5, t.c("card.bg"), t.c("panel.border"));
            int size = Math.min(Minimap.size(), 96);
            boolean left = corner == Anchors.Corner.TOP_LEFT || corner == Anchors.Corner.BOTTOM_LEFT;
            boolean topCorner = corner == Anchors.Corner.TOP_LEFT || corner == Anchors.Corner.TOP_RIGHT;
            int mxp = left ? rx + 6 : rx + pw - 6 - size;
            if (c.minimap) {
                int plate = 2 + 10 * 2 + 4;
                int myp = topCorner ? ry + 6 : ry + ph - 6 - size - plate;
                Minimap.preview(g, mxp, Math.max(ry + 3, myp), size, true);
            } else {
                String off = tr("rysmap.settings.preview.off");
                Draw.label(g, off, rx + (pw - Draw.labelWidth(off, 12, Weight.REGULAR)) / 2f, ry + ph / 2f, 12, Weight.REGULAR, t.c("text.muted"));
            }
            y = Math.max(y, ry + ph);
        }
        return y;
    }

    /** Вкладка «В мире»: места гибели, подписи точек в мире, их дальность. */
    private int world(GuiGraphics g, Theme t, Kit.Hits hits, ClientConfig c, int lx, int lw, int y, int mx, int my) {
        int[] kept = {0, 1, 3, 5};
        int ks = 2;
        for (int i = 0; i < kept.length; i++) if (kept[i] == c.deathsKept) ks = i;
        y = segRow(g, t, hits, "rysmap.settings.deaths", "rysmap.settings.deaths.note", lx, lw, y, new String[]{tr("rysmap.settings.off"), "1", "3", "5"},
            ks, mx, my, i -> {
                c.deathsKept = kept[i];
                c.save();
                MapSession session = ClientEntry.session();
                if (session != null && kept[i] > 0) {
                    session.points().deathsKept(kept[i]);
                    session.savePoints();
                }
            });
        // подписи точек в мире: режим и дальность (порог раскрытия у прицела и предел числа меток только в файле)
        works.repka.rysmap.hud.LabelPlan.Mode[] modes = works.repka.rysmap.hud.LabelPlan.Mode.values();
        String[] modeNames = new String[modes.length];
        int ms = 1;
        for (int i = 0; i < modes.length; i++) {
            modeNames[i] = tr("rysmap.settings.labels." + modes[i].id);
            if (modes[i].id.equals(c.worldLabels)) ms = i;
        }
        y = segRow(g, t, hits, "rysmap.settings.labels", "rysmap.settings.labels.note", lx, lw, y, modeNames, ms, mx, my, i -> {
            c.worldLabels = modes[i].id;
            c.save();
        });
        int[] ranges = {128, 256, 512, 1024};
        String[] rangeNames = new String[ranges.length];
        int rs = -1;
        for (int i = 0; i < ranges.length; i++) {
            rangeNames[i] = Minimap.group(ranges[i]);
            if (ranges[i] == c.labelRange) rs = i;
        }
        return segRow(g, t, hits, "rysmap.settings.range", "rysmap.settings.range.note", lx, lw, y, rangeNames, rs, mx, my, i -> {
            c.labelRange = ranges[i];
            c.save();
        });
    }

    /** Вкладка «Клавиши и данные»: клавиши карты, ссылки на настройки игры и окно «Клавиши» ядра, размер карты, переезд из FTB, экспорт. */
    private int keysAndData(GuiGraphics g, Theme t, Kit.Hits hits, MapScreen s, int rx, int rw, int ry, int mx, int my) {
        Kit.caps(g, t, tr("rysmap.settings.keys"), rx, ry + 2, 11, 0.44f);
        String change = tr("rysmap.settings.keys.change");
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
        ry += 12;
        key(g, t, keyText(ClientEntry.SHOW_POINTS), "rysmap.settings.keys.show_points", rx, ry);
        ry += 12;
        // колесо и свои клавиши на места (ядро 1.14): окно «Клавиши» ядра
        key(g, t, keyText(works.repka.ryscore.action.Actions.WHEEL), "rysmap.settings.keys.wheel", rx, ry);
        String wheelKeys = tr("rysmap.settings.keys.actions");
        float wkw = Draw.labelWidth(wheelKeys, 11.5f, Weight.REGULAR);
        boolean hotW = Kit.over(mx, my, (int) (rx + rw - wkw), ry, (int) wkw + 1, 9);
        Draw.label(g, wheelKeys, rx + rw - wkw, ry + 4, 11.5f, Weight.REGULAR, hotW ? t.c("text.primary") : t.c("link"));
        hits.add((int) (rx + rw - wkw), ry, (int) wkw + 1, 9, b -> works.repka.ryscore.action.Actions.openKeys(s));
        ry += 12 + 10;

        Kit.caps(g, t, tr("rysmap.settings.data"), rx, ry + 2, 11, 0.44f);
        String folder = tr("rysmap.settings.data.folder");
        Draw.label(g, folder, rx + rw - Draw.labelWidth(folder, 11.5f, Weight.REGULAR), ry + 2, 11.5f, Weight.REGULAR, t.c("text.muted"));
        ry += 9;
        MapSession session = ClientEntry.session();
        if (session != null && mapInfo == null) mapInfo = CompletableFuture.supplyAsync(() -> mapSize(session.store.root()));
        String size1 = mapInfo != null && mapInfo.isDone() ? mapInfo.getNow("") : "…";
        data(g, t, tr("rysmap.settings.data.map"), size1, rx, ry, rw);
        ry += 19;
        if (ftbInfo == null && session != null) ftbInfo = ftbInfo(session.store.root());
        data(g, t, tr("rysmap.settings.data.ftb"), ftbInfo == null ? "" : ftbInfo, rx, ry, rw);
        ry += 19;
        // экспорт всей разведанной карты измерения в PNG (0.2.4)
        String export = tr("rysmap.settings.data.export");
        float ew = Draw.labelWidth(export, 11.5f, Weight.REGULAR);
        boolean hotE = Kit.over(mx, my, rx, ry, (int) ew + 1, 10);
        Draw.label(g, export, rx, ry + 5, 11.5f, Weight.REGULAR, hotE ? t.c("text.primary") : t.c("link"));
        hits.add(rx, ry, (int) ew + 1, 10, b -> s.exportPng(true));
        return ry + 14;
    }

    /** Паузы между телепортами, секунды. */
    private static final int[] PAUSES = {0, 30, 60, 300, 900};

    /**
     * Вкладка «Сервер» (макеты {@code 50}, скептик {@code s3}): миникарта и пещеры у игроков, чужие игроки на карте (никогда,
     * только рядом, союз, все и везде; у последнего предупреждение), мобы, телепорт и пауза. Каждое нажатие сразу уходит на
     * сервер; 30 секунд после правки видна строка «Вернуть», она возвращает правила, какими они были до неё.
     */
    private int server(GuiGraphics g, Theme t, Kit.Hits hits, int lx, int lw, int y, int mx, int my) {
        works.repka.rysmap.net.MapNet.Rules r = works.repka.rysmap.client.ServerRules.rules();
        if (r == null) return y;
        Draw.label(g, tr("rysmap.settings.server.note"), lx, y + 4, 11, Weight.REGULAR, t.c("text.muted"));
        y += 12;
        y = pillRow(g, t, hits, "rysmap.settings.server.maps", null, lx, lw, y,
            new String[]{tr("rysmap.settings.server.minimap"), tr("rysmap.settings.server.caves"), tr("rysmap.settings.server.slime")},
            new boolean[]{r.minimap(), r.caves(), r.slime()}, mx, my, i -> {
                if (i == 0) send(r, r.teleport(), r.pause(), !r.minimap(), r.caves(), r.playersMode(), r.mobs());
                else if (i == 1) send(r, r.teleport(), r.pause(), r.minimap(), !r.caves(), r.playersMode(), r.mobs());
                else works.repka.rysmap.client.ServerRules.setSlime(!r.slime());
            });
        String[] pm = new String[4];
        for (int i = 0; i < 4; i++) pm[i] = tr("rysmap.settings.server.players." + i);
        y = segRow(g, t, hits, "rysmap.settings.server.players", "rysmap.settings.server.players.note", lx, lw, y, pm, r.playersMode(), mx, my,
            i -> send(r, r.teleport(), r.pause(), r.minimap(), r.caves(), i, r.mobs()));
        if (r.playersMode() == 3) {
            // «Все и везде»: места всех игроков раз в секунду, на сервере с боем это радар для засад
            List<String> warn = Draw.wrapLabel(tr("rysmap.settings.server.players.warn"), lw - 22, 11.5f, Weight.REGULAR, 3);
            int wh = warn.size() * 7 + 8;
            Draw.roundBox(g, lx, y, lw, wh, 4, Draw.alpha(t.c("state.warn"), 0.12f), Draw.alpha(t.c("state.warn"), 0.55f));
            Icons.draw(g, Icons.Shape.WARN, lx + 6, y + 4, 8, t.c("state.warn"));
            float ly = y + 4 + 3.5f;
            for (String line : warn) {
                Draw.label(g, line, lx + 18, ly, 11.5f, Weight.REGULAR, t.c("text.body"));
                ly += 7;
            }
            y += wh + 5;
        }
        String[] yn = {tr("rysmap.settings.no"), tr("rysmap.settings.yes")};
        y = segRow(g, t, hits, "rysmap.settings.server.mobs_row", "rysmap.settings.server.mobs_row.note", lx, lw, y, yn, r.mobs() ? 1 : 0, mx, my,
            i -> send(r, r.teleport(), r.pause(), r.minimap(), r.caves(), r.playersMode(), i == 1));
        String[] modes = {tr("rysmap.settings.server.teleport.off"), tr("rysmap.settings.server.teleport.ops"), tr("rysmap.settings.server.teleport.all")};
        y = segRow(g, t, hits, "rysmap.settings.server.teleport", "rysmap.settings.server.teleport.note", lx, lw, y, modes, r.teleport(), mx, my,
            i -> send(r, i, r.pause(), r.minimap(), r.caves(), r.playersMode(), r.mobs()));
        String[] pauses = new String[PAUSES.length];
        int ps = -1;
        for (int i = 0; i < PAUSES.length; i++) {
            pauses[i] = PAUSES[i] == 0 ? tr("rysmap.settings.server.pause.none")
                : PAUSES[i] < 60 ? Component.translatable("rysmap.settings.server.pause.s", PAUSES[i]).getString()
                : Component.translatable("rysmap.settings.server.pause.m", PAUSES[i] / 60).getString();
            if (PAUSES[i] == r.pause()) ps = i;
        }
        y = segRow(g, t, hits, "rysmap.settings.server.pause", "rysmap.settings.server.pause.note", lx, lw, y, pauses, ps, mx, my,
            i -> send(r, r.teleport(), PAUSES[i], r.minimap(), r.caves(), r.playersMode(), r.mobs()));
        // «Вернуть · 30 с»: как у прав RYSClaims
        long left = before == null ? 0 : 30_000 - (System.currentTimeMillis() - changedAt);
        if (before != null && left > 0) {
            works.repka.rysmap.net.MapNet.Rules b = before;
            String changed = tr("rysmap.settings.server.changed");
            String undo = Component.translatable("rysmap.settings.server.undo", (left + 999) / 1000).getString();
            Draw.label(g, changed, lx, y + 7, 11.5f, Weight.REGULAR, t.c("text.muted"));
            float ux = lx + Draw.labelWidth(changed, 11.5f, Weight.REGULAR) + 6, uw = Draw.labelWidth(undo, 11.5f, Weight.MEDIUM);
            boolean hot = Kit.over(mx, my, (int) ux - 1, y + 2, (int) uw + 2, 11);
            Draw.label(g, undo, ux, y + 7, 11.5f, Weight.MEDIUM, hot ? t.c("text.primary") : t.c("link"));
            hits.add((int) ux - 1, y + 2, (int) uw + 2, 11, btn -> {
                works.repka.rysmap.client.ServerRules.setRules(b.teleport(), b.pause(), b.minimap(), b.caves(), b.playersMode(), b.mobs());
                before = null;
            });
            y += 14;
        } else {
            before = null;
        }
        return y;
    }

    /** Правила до первой правки в окне 30 секунд: их возвращает «Вернуть». */
    private static works.repka.rysmap.net.MapNet.@Nullable Rules before;
    private static long changedAt;

    private static void send(works.repka.rysmap.net.MapNet.Rules r, int teleport, int pause, boolean minimap, boolean caves, int playersMode, boolean mobs) {
        if (before == null || System.currentTimeMillis() - changedAt > 30_000) before = r;
        changedAt = System.currentTimeMillis();
        works.repka.rysmap.client.ServerRules.setRules(teleport, pause, minimap, caves, playersMode, mobs);
    }

    /** Ширина ряда выключателей {@link #pills}. */
    private static float pillsWidth(String[] options, boolean[] on) {
        float w = 0;
        for (int i = 0; i < options.length; i++) w += Draw.labelWidth(options[i], 12.5f, on[i] ? Weight.MEDIUM : Weight.REGULAR) + 12 + (i > 0 ? 4 : 0);
        return w;
    }

    /** Ширина сегментного переключателя {@link #segmented}: не зависит от выбранного пункта. */
    private static float segWidth(String[] options, int selected) {
        return Kit.segmentedWidth(works.repka.ryscore.theme.ThemeManager.get(), segments(options));
    }

    private static java.util.List<Kit.Segment> segments(String[] options) {
        java.util.List<Kit.Segment> out = new java.util.ArrayList<>(options.length);
        for (String option : options) out.add(Kit.Segment.of(option));
        return out;
    }

    /** Ряд независимых выключателей справа до {@code right} (макет 50: «Миникарта», «Пещеры»). */
    private static void pills(GuiGraphics g, Theme t, Kit.Hits hits, int right, int y, String[] options, boolean[] on, int mx, int my,
                              java.util.function.IntConsumer toggle) {
        int px = (int) (right - Math.ceil(pillsWidth(options, on)));
        for (int i = 0; i < options.length; i++) {
            int index = i;
            px += Kit.pill(g, t, hits, px, y + 2, options[i], on[i], mx, my, () -> toggle.accept(index)) + 4;
        }
    }

    /** Сегментный переключатель справа до {@code right}: {@link Kit#segmented} ядра, равный отступ выбранного пункта со всех сторон. */
    private static int segmented(GuiGraphics g, Theme t, Kit.Hits hits, int right, int y, String[] options, int selected, int mx, int my,
                                 java.util.function.IntConsumer pick) {
        java.util.List<Kit.Segment> items = segments(options);
        int w = (int) Math.ceil(Kit.segmentedWidth(t, items));
        Kit.segmented(g, t, hits, right - w, y, SEG_H, items, selected, mx, my, pick);
        return w;
    }

    private static String keyText(net.minecraft.client.KeyMapping k) {
        return k.isUnbound() ? tr("rysmap.settings.keys.none") : k.getTranslatedKeyMessage().getString();
    }

    private static void key(GuiGraphics g, Theme t, String key, String what, int x, int y) {
        float w = 0;
        for (String part : key.split(" ")) w += MapScreen.kbd(g, t, part, x + w, y + 4) + 2;
        Draw.label(g, tr(what), x + Math.max(w, 26) + 4, y + 4, 12.5f, Weight.REGULAR, t.c("text.secondary"));
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
        return tr("rysmap.settings.data.ftb.none");
    }
}
