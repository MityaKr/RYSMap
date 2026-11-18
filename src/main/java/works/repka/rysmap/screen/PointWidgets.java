package works.repka.rysmap.screen;

import java.util.function.IntConsumer;
import net.minecraft.client.gui.GuiGraphics;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.ui.Field;
import works.repka.ryscore.ui.Kit;
import works.repka.ryscore.ui.Type.Weight;
import works.repka.rysmap.point.PointStore;
import works.repka.rysmap.point.PointsLayer;

/** Общие части окон точки (макеты {@code 09}, {@code 16}): поле ввода, сетка значков, ряд цветов, рамка всплывающего окна. */
final class PointWidgets {
    static final int INPUT_H = 17, ICON = 15, ICON_GAP = 2, SWATCH = 11, SWATCH_GAP = 3;

    private PointWidgets() {}

    /** Рамка всплывающего окна (макет {@code .pop}): подложка боковой панели, рамка кнопки, тень. */
    static void frame(GuiGraphics g, Theme t, int x, int y, int w, int h) {
        if (t.beveled()) {
            t.panel(g, x, y, w, h);
            return;
        }
        Draw.shadow(g, x, y, w, h, 6, 1f);
        Draw.roundBox(g, x, y, w, h, 6, t.c("sidebar.bg"), t.c("button.border"));
    }

    /** Подпись поля (макет {@code .fld label}): кегль 11,5. */
    static void label(GuiGraphics g, Theme t, String text, float x, float cy) {
        Draw.label(g, text, x, cy, 11.5f, Weight.REGULAR, t.c("text.muted"));
    }

    /** Поле ввода (макет {@code .inp}) с текстом {@link Field} внутри. */
    static void input(GuiGraphics g, Theme t, Field f, int x, int y, int w, int mx, int my) {
        if (t.beveled()) t.input(g, x, y, w, INPUT_H, f.isFocused());
        else Draw.roundBox(g, x, y, w, INPUT_H, 4, t.c("input.bg"), f.isFocused() ? t.c("text.secondary") : t.c("input.border"));
        f.setX(x + 5);
        f.setY(y + (INPUT_H - 10) / 2);
        f.setWidth(w - 10);
        f.setVisible(true);
        f.setTextColor(t.c("input.fg") & 0xFFFFFF);
        f.render(g, mx, my, 0);
    }

    /** Сетка значков по {@code perRow} в ряд; возвращает высоту. */
    static int icons(GuiGraphics g, Theme t, Kit.Hits hits, int x, int y, int count, int perRow, String selected, int mx, int my, IntConsumer pick) {
        int rows = (count + perRow - 1) / perRow;
        for (int i = 0; i < count; i++) {
            String name = PointsLayer.ICONS.get(i);
            int cx = x + (i % perRow) * (ICON + ICON_GAP), cy = y + (i / perRow) * (ICON + ICON_GAP);
            boolean on = name.equals(selected), hot = Kit.over(mx, my, cx, cy, ICON, ICON);
            int fill = on ? t.c("tab.active.bg") : hot ? t.c("button.bg") : t.c("card.bg");
            if (t.beveled()) {
                if (on) t.pressed(g, cx, cy, ICON, ICON, 0);
                else t.button(g, cx, cy, ICON, ICON, hot, false);
            } else {
                Draw.roundBox(g, cx, cy, ICON, ICON, 4, fill, on ? t.c("text.secondary") : t.c("panel.border"));
            }
            works.repka.ryscore.ui.Icons.draw(g, PointsLayer.icon(name), cx + 3.5f, cy + 3.5f, 8, on || hot ? t.c("text.primary") : t.c("text.secondary"));
            int index = i;
            hits.add(cx, cy, ICON, ICON, b -> pick.accept(index));
        }
        return rows * ICON + (rows - 1) * ICON_GAP;
    }

    /** Ряд цветов по {@code perRow}; возвращает высоту. */
    static int colors(GuiGraphics g, Theme t, Kit.Hits hits, int x, int y, int count, int perRow, int selected, int mx, int my, IntConsumer pick) {
        int rows = (count + perRow - 1) / perRow;
        for (int i = 0; i < count; i++) {
            int cx = x + (i % perRow) * (SWATCH + SWATCH_GAP), cy = y + (i / perRow) * (SWATCH + SWATCH_GAP);
            float r = SWATCH / 2f;
            if (i == selected) {
                Draw.disc(g, cx + r, cy + r, r + 2, t.c("text.primary"));
                Draw.disc(g, cx + r, cy + r, r + 1, t.c("sidebar.bg"));
            } else if (Kit.over(mx, my, cx, cy, SWATCH, SWATCH)) {
                Draw.disc(g, cx + r, cy + r, r + 1, Draw.alpha(t.c("text.primary"), 0.4f));
            }
            Draw.disc(g, cx + r, cy + r, r, 0xFF000000 | PointStore.PALETTE[i]);
            int index = i;
            hits.add(cx, cy, SWATCH, SWATCH, b -> pick.accept(index));
        }
        return rows * SWATCH + (rows - 1) * SWATCH_GAP;
    }

    /** Кнопка (макет {@code .btn}): главная янтарная с клавишей в рамке, обычная серая, призрачная без рамки. */
    static int button(GuiGraphics g, Theme t, Kit.Hits hits, int x, int y, int w, int h, String label, String key, int kind, int mx, int my, Runnable action) {
        float px = h >= 20 ? 13.5f : 12f;
        Weight weight = kind == 1 ? Weight.SEMIBOLD : Weight.MEDIUM;
        float tw = Draw.labelWidth(label, px, weight);
        float kw = key == null ? 0 : Draw.labelWidth(key, 11, Weight.REGULAR) + 5 + 3.5f;
        if (w <= 0) w = (int) Math.ceil(tw + kw + 10);
        boolean hot = Kit.over(mx, my, x, y, w, h);
        if (kind == 2) {
            if (hot) Draw.round(g, x, y, w, h, 4, Draw.alpha(t.c("button.bg"), 0.8f));
        } else {
            t.button(g, x, y, w, h, hot, kind == 1);
        }
        int fg = kind == 1 ? t.c("accent.text") : kind == 2 ? (hot ? t.c("text.primary") : t.c("text.muted")) : (hot ? t.c("text.primary") : t.c("button.fg"));
        float cx = x + (w - tw - kw) / 2f;
        Draw.label(g, label, cx, y + h / 2f, px, weight, fg);
        if (key != null) {
            float kx = cx + tw + 3.5f, kwid = kw - 3.5f;
            Draw.round(g, kx, y + h / 2f - 4, kwid, 8, 2.5f, kind == 1 ? Draw.alpha(0xFF000000, 0.18f) : t.c("button.bg"));
            Draw.label(g, key, kx + 2.5f, y + h / 2f, 11, Weight.REGULAR, kind == 1 ? t.c("accent.text") : t.c("text.muted"));
        }
        hits.add(x, y, w, h, b -> {
            if (b == 0) action.run();
        });
        return w;
    }
}
