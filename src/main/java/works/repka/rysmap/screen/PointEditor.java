package works.repka.rysmap.screen;

import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.ui.Field;
import works.repka.ryscore.ui.Icons;
import works.repka.ryscore.ui.Kit;
import works.repka.ryscore.ui.Type.Weight;
import works.repka.rysmap.point.PointStore;
import works.repka.rysmap.point.PointsLayer;

/**
 * Окно новой точки и правки точки поверх полотна (макет {@code 16}): название, X Y Z, шестнадцать значков, двенадцать
 * цветов, «Отмена» и «Поставить · Enter» (у правки «Сохранить»). Окно встаёт рядом с местом на полотне и не выходит
 * за полотно. «Кто видит» в 0.1 нет: точки личные, общие метки команды даёт слой RYSTeams.
 */
final class PointEditor {
    static final int W = 158, PAD_X = 7, PAD_Y = 6;
    private static final int H = 187, LIFE_H = 24;
    /** Срок точки: навсегда, 10 минут, час, сутки (точки на время). */
    static final int[] LIFETIMES = {0, 600, 3600, 86400};

    final @Nullable PointStore.Point editing;
    final String dimension;
    final Field name, x, y, z;
    String icon;
    int color;
    /** Выбранный срок: номер в {@link #LIFETIMES}, -1 — не менять (у правимой точки на время). */
    int lifetime;
    /** Строка «Срок»: у своих точек есть, у места сервера нет. */
    boolean lifetimeRow = true;
    private final int anchorX, anchorY;
    int left, top;

    PointEditor(@Nullable PointStore.Point editing, String dimension, int bx, int by, int bz, String defaultName, int defaultColor, int anchorX, int anchorY) {
        this.editing = editing;
        this.dimension = dimension;
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        name = field(32, null);
        x = field(9, "-?\\d{0,8}");
        y = field(5, "-?\\d{0,4}");
        z = field(9, "-?\\d{0,8}");
        if (editing != null) {
            name.setValue(editing.name);
            x.setValue(Integer.toString(editing.x));
            y.setValue(Integer.toString(editing.y));
            z.setValue(Integer.toString(editing.z));
            icon = editing.icon;
            color = Math.floorMod(editing.color, PointStore.COLORS);
            lifetime = editing.temporary() ? -1 : 0;
        } else {
            name.setValue(defaultName);
            x.setValue(Integer.toString(bx));
            y.setValue(Integer.toString(by));
            z.setValue(Integer.toString(bz));
            icon = "flag";
            color = defaultColor;
        }
        // имя выделено: можно сразу печатать или сразу жать Enter
        name.setFocused(true);
        name.moveCursorToEnd(false);
        name.setHighlightPos(0);
    }

    private static Field field(int max, @Nullable String pattern) {
        Field f = new Field(100, 10);
        f.setMaxLength(max);
        f.setSpans(List.of(), new int[0]);
        if (pattern != null) f.setFilter(s -> s.matches(pattern));
        return f;
    }

    List<Field> fields() {
        return List.of(name, x, y, z);
    }

    int height() {
        return H + (lifetimeRow ? LIFE_H : 0);
    }

    boolean over(double mx, double my) {
        return Kit.over(mx, my, left, top, W, height());
    }

    /** Место окна: справа от якоря, иначе слева; по высоте у якоря, в пределах полотна. */
    private void place(MapScreen s) {
        int gap = 12;
        int right = s.visibleRight();
        left = anchorX + gap + W <= right - 6 ? anchorX + gap : anchorX - gap - W;
        left = Math.max(s.cx0 + 6, Math.min(left, right - 6 - W));
        top = Math.max(s.cy0 + 6, Math.min(anchorY - 40, s.cy0 + s.ch - 6 - height()));
    }

    void draw(GuiGraphics g, Theme t, Kit.Hits hits, MapScreen s, int mx, int my) {
        place(s);
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        // окно ловит нажатия мимо своих частей: они не уходят на полотно
        hits.add(left, top, W, height(), b -> {});
        PointWidgets.frame(g, t, left, top, W, height());
        int ix = left + PAD_X, iw = W - PAD_X * 2;
        int cy = top + PAD_Y;
        String title = Component.translatable(editing == null ? "rysmap.point.new" : "rysmap.point.edit").getString();
        Draw.label(g, title, ix, cy + 5, 14, Weight.SEMIBOLD, t.c("text.primary"));
        int closeX = left + W - PAD_X - 10;
        boolean hot = Kit.over(mx, my, closeX - 2, cy - 2, 14, 14);
        Draw.cross(g, closeX, cy + 1, hot ? t.c("text.primary") : t.c("text.muted"));
        hits.add(closeX - 2, cy - 2, 14, 14, b -> s.closeEditor());
        cy += 10 + 5;

        PointWidgets.label(g, t, Component.translatable("rysmap.point.name").getString(), ix, cy + 3);
        cy += 6 + 2;
        PointWidgets.input(g, t, name, ix, cy, iw, mx, my);
        cy += PointWidgets.INPUT_H + 5;

        int colW = (iw - 8) / 3;
        Field[] xyz = {x, y, z};
        String[] axes = {"X", "Y", "Z"};
        for (int i = 0; i < 3; i++) {
            int fx = ix + i * (colW + 4);
            PointWidgets.label(g, t, axes[i], fx, cy + 3);
            PointWidgets.input(g, t, xyz[i], fx, cy + 8, colW, mx, my);
        }
        cy += 8 + PointWidgets.INPUT_H + 5;

        PointWidgets.label(g, t, Component.translatable("rysmap.point.icon").getString(), ix, cy + 3);
        cy += 8;
        cy += PointWidgets.icons(g, t, hits, ix, cy, PointsLayer.ICONS.size(), 8, icon, mx, my, i -> icon = PointsLayer.ICONS.get(i)) + 5;

        PointWidgets.label(g, t, Component.translatable("rysmap.point.color").getString(), ix, cy + 3);
        cy += 8;
        cy += PointWidgets.colors(g, t, hits, ix + 1, cy + 1, PointStore.COLORS, 10, color, mx, my, i -> color = i) + 7;
        if (lifetimeRow) {
            String head = Component.translatable("rysmap.point.lifetime").getString();
            if (lifetime < 0 && editing != null) {
                long left = Math.max(0, editing.until - System.currentTimeMillis() / 1000);
                head += " · " + Component.translatable("rysmap.point.lifetime.left", PointPanel.ago(left)).getString();
            }
            PointWidgets.label(g, t, head, ix, cy + 3);
            cy += 8;
            int cw = (iw - 3 * 3) / 4;
            for (int i = 0; i < LIFETIMES.length; i++) {
                int bx = ix + i * (cw + 3);
                boolean on = i == lifetime, hotL = Kit.over(mx, my, bx, cy, cw, 11);
                Draw.roundBox(g, bx, cy, cw, 11, 3, on ? t.c("tab.active.bg") : t.c("input.bg"), on || hotL ? t.c("text.muted") : t.c("panel.border"));
                String l = Component.translatable("rysmap.point.lifetime." + i).getString();
                Draw.label(g, l, bx + (cw - Draw.labelWidth(l, 11, Weight.REGULAR)) / 2f, cy + 5.5f, 11, Weight.REGULAR, on ? t.c("text.primary") : t.c("text.secondary"));
                int index = i;
                hits.add(bx, cy, cw, 11, b -> lifetime = index);
            }
            cy += 11 + 5;
        }

        String ok = Component.translatable(editing == null ? "rysmap.point.place" : "rysmap.point.save").getString();
        int bw = (int) Math.ceil(Draw.labelWidth(ok, 12, Weight.SEMIBOLD) + Draw.labelWidth("Enter", 11, Weight.REGULAR) + 5 + 3.5f + 10);
        PointWidgets.button(g, t, hits, left + W - PAD_X - bw, cy, bw, 14, ok, "Enter", 1, mx, my, () -> s.submitEditor());
        PointWidgets.button(g, t, hits, ix, cy, 0, 14, Component.translatable("rysmap.point.cancel").getString(), null, 2, mx, my, s::closeEditor);
        g.pose().popPose();
    }

    /** Значения окна в точку: новую или правимую. Пустое имя и неразобранные координаты не дают сохранить. */
    @Nullable
    PointStore.Point result() {
        String n = name.getValue().trim();
        Integer px = parse(x.getValue()), py = parse(y.getValue()), pz = parse(z.getValue());
        if (n.isEmpty() || px == null || py == null || pz == null) return null;
        PointStore.Point p = editing != null ? editing : new PointStore.Point();
        p.name = n;
        p.dimension = dimension;
        p.x = px;
        p.y = py;
        p.z = pz;
        p.icon = icon;
        p.color = color;
        if (lifetimeRow && lifetime >= 0) p.until = LIFETIMES[lifetime] == 0 ? 0 : System.currentTimeMillis() / 1000 + LIFETIMES[lifetime];
        return p;
    }

    private static @Nullable Integer parse(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Tab переходит к следующему полю. */
    void tab(MapScreen s, boolean back) {
        List<Field> f = fields();
        int i = 0;
        for (int k = 0; k < f.size(); k++) if (f.get(k).isFocused()) i = k;
        int next = Math.floorMod(i + (back ? -1 : 1), f.size());
        for (Field field : f) field.setFocused(false);
        f.get(next).setFocused(true);
        f.get(next).moveCursorToEnd(false);
        f.get(next).setHighlightPos(0);
        s.setFocused(f.get(next));
    }

    static Icons.Shape shape(String icon) {
        return PointsLayer.icon(icon);
    }
}
