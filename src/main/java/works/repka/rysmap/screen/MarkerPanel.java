package works.repka.rysmap.screen;

import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import works.repka.ryscore.map.MapApi;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.ui.Icons;
import works.repka.ryscore.ui.Kit;
import works.repka.ryscore.ui.Type.Weight;

/**
 * Панель чужой метки справа (макет {@code 19}, ядро 1.12 {@link MapApi.Marker#panel()}), 180 единиц: значок цвета
 * метки, заголовок, подзаголовок, текст, карточка строк, врезка, внизу до трёх кнопок (главная широкая). Вид как у
 * панели своей точки ({@link PointPanel}); данные мода берутся каждый кадр.
 */
final class MarkerPanel {
    private static final int PAD_X = 9, PAD_Y = 8, ROW = 15;

    private MarkerPanel() {}

    static void draw(GuiGraphics g, Theme t, Kit.Hits hits, MapScreen s, MapApi.Marker m, MapApi.Panel p, int mx, int my) {
        int w = PointPanel.W, x = s.wx + s.ww - w, y = s.cy0, h = s.ch;
        Draw.rect(g, x, y, w - 1, h - 1, t.c("panel.bg"));
        Draw.rect(g, x, y, 1, h - 1, t.c("panel.border"));
        hits.add(x, y, w, h, b -> {});
        int ix = x + PAD_X, iw = w - PAD_X * 2, cy = y + PAD_Y;

        Draw.disc(g, ix + 11, cy + 11, 11, m.color());
        if (m.icon() != null) Icons.draw(g, m.icon(), ix + 5, cy + 5, 12, 0xFF15181F);
        Draw.label(g, Draw.ellipsize(p.title().getString(), iw - 40, 18, Weight.SEMIBOLD), ix + 28, cy + 6.5f, 18, Weight.SEMIBOLD, t.c("text.primary"));
        Draw.label(g, Draw.ellipsize(p.subtitle().getString(), iw - 40, 12, Weight.REGULAR), ix + 28, cy + 16.5f, 12, Weight.REGULAR, t.c("text.muted"));
        int closeX = x + w - PAD_X - 9;
        boolean hotClose = Kit.over(mx, my, closeX - 3, cy + 3, 14, 14);
        Draw.cross(g, closeX, cy + 6, hotClose ? t.c("text.primary") : t.c("text.muted"));
        hits.add(closeX - 3, cy + 3, 14, 14, b -> s.closeMarkerPanel());
        cy += 22 + 6;

        if (p.text() != null) {
            List<String> lines = Draw.wrapLabel(p.text().getString(), iw, 12.5f, Weight.REGULAR, 6);
            for (String line : lines) {
                Draw.label(g, line, ix, cy + 4, 12.5f, Weight.REGULAR, t.c("text.body"));
                cy += 7;
            }
            cy += 4;
        }
        if (!p.rows().isEmpty()) {
            int cardH = p.rows().size() * ROW + 2;
            if (t.beveled()) t.card(g, ix, cy, iw, cardH);
            else Draw.roundBox(g, ix, cy, iw, cardH, 5, t.c("card.bg"), t.c("panel.border"));
            float ry = cy + 1;
            for (int i = 0; i < p.rows().size(); i++) {
                MapApi.Row row = p.rows().get(i);
                if (i > 0) Draw.rect(g, ix + 1, (int) ry, iw - 2, 1, t.c("panel.border"));
                float mid = ry + ROW / 2f;
                String key = row.key().getString(), value = row.value().getString();
                Draw.label(g, Draw.ellipsize(key, iw / 2f - 8, 12.5f, Weight.REGULAR), ix + 6, mid, 12.5f, Weight.REGULAR, t.c("text.muted"));
                String v = Draw.ellipsize(value, iw / 2f, 12.5f, Weight.MEDIUM);
                Draw.label(g, v, ix + iw - 6 - Draw.labelWidth(v, 12.5f, Weight.MEDIUM), mid, 12.5f, Weight.MEDIUM, t.c("text.primary"));
                ry += ROW;
            }
            cy += cardH + 6;
        }
        if (p.note() != null) {
            List<String> lines = Draw.wrapLabel(p.note().getString(), iw - 12, 11.5f, Weight.REGULAR, 4);
            int nh = lines.size() * 7 + 8;
            Draw.roundBox(g, ix, cy, iw, nh, 4, Draw.alpha(t.c("state.warn"), 0.12f), Draw.alpha(t.c("state.warn"), 0.5f));
            float ly = cy + 4 + 3.5f;
            for (String line : lines) {
                Draw.label(g, line, ix + 6, ly, 11.5f, Weight.REGULAR, t.c("text.body"));
                ly += 7;
            }
        }

        // кнопки: главная широкая, остальные под ней рядами до трёх; если их больше трёх, первая (обычно «В путь»)
        // идёт своим широким рядом (макет Essentials 04: «Телепорт», «В путь», «Изменить · Сюда · Удалить»)
        List<MapApi.Action> actions = p.actions().size() > 6 ? p.actions().subList(0, 6) : p.actions();
        if (actions.isEmpty()) return;
        MapApi.Action primary = null;
        for (MapApi.Action a : actions) if (a.primary()) primary = a;
        List<MapApi.Action> rest = new java.util.ArrayList<>(actions);
        if (primary != null) rest.remove(primary);
        List<List<MapApi.Action>> rows = new java.util.ArrayList<>();
        if (rest.size() > 3) {
            rows.add(List.of(rest.get(0)));
            rows.add(rest.subList(1, rest.size()));
        } else if (!rest.isEmpty()) {
            rows.add(rest);
        }
        int bottom = y + h - 6;
        int ay = bottom - rows.size() * (14 + 4) - (primary != null ? 21 : 0);
        Draw.rect(g, x + 1, ay - 6, w - 2, 1, t.c("panel.border"));
        if (primary != null) {
            MapApi.Action a = primary;
            PointWidgets.button(g, t, hits, ix, ay, iw, 21, a.label().getString(), null, 1, mx, my, () -> run(a));
            ay += 21 + 4;
        }
        for (List<MapApi.Action> row : rows) {
            int bw = (iw - 4 * (row.size() - 1)) / row.size();
            for (int i = 0; i < row.size(); i++) {
                MapApi.Action a = row.get(i);
                PointWidgets.button(g, t, hits, ix + i * (bw + 4), ay, i == row.size() - 1 ? iw - i * (bw + 4) : bw, 14, a.label().getString(), null, 0, mx, my, () -> run(a));
            }
            ay += 14 + 4;
        }
    }

    private static void run(MapApi.Action a) {
        try {
            a.run();
        } catch (Throwable e) {
            works.repka.rysmap.RYSMap.LOG.error("[rysmap] кнопка панели метки упала", e);
        }
    }
}
