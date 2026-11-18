package works.repka.rysmap.screen;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import works.repka.ryscore.map.MapApi;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.ui.Icons;
import works.repka.ryscore.ui.Kit;
import works.repka.ryscore.ui.Type.Weight;
import works.repka.rysmap.hud.Minimap;
import works.repka.rysmap.migrate.FtbPoints;
import works.repka.rysmap.point.PointStore;
import works.repka.rysmap.point.PointsLayer;

/**
 * Панель точки справа (макеты {@code 15}, {@code 18}), 180 единиц: значок, имя, кто это; карточка «Координаты» с
 * копированием, «Отсюда» с расстоянием и стороной света, «Измерение», место портала в другом измерении; «На миникарте»;
 * внизу «В путь», «Изменить», «Удалить» (с вопросом). У места гибели строка «Прошло» с подсказкой, почему срок вещей
 * не показан. «Телепорт», «В чат» и «В мире всегда» идут во втором выпуске.
 */
final class PointPanel {
    static final int W = 180;
    private static final int PAD_X = 9, PAD_Y = 8, ROW = 15;

    private PointPanel() {}

    static void draw(GuiGraphics g, Theme t, Kit.Hits hits, MapScreen s, PointStore.Point p, int mx, int my, List<String>[] tip) {
        int x = s.wx + s.ww - W, y = s.cy0, h = s.ch;
        float r = t.beveled() ? 0 : t.r("panel.radius");
        // подложка окна: панель лежит поверх полотна, правый нижний угол повторяет скругление окна
        if (t.beveled()) {
            Draw.rect(g, x, y, W, h, t.c("panel.bg"));
        } else {
            Draw.rect(g, x, y, W - 1, (int) (h - r - 1), t.c("panel.bg"));
            Draw.round(g, x, y + h - 2 * r - 1, W - 1, 2 * r, r - 1, t.c("panel.bg"));
            Draw.rect(g, x, (int) (y + h - 2 * r - 1), (int) r, (int) (2 * r), t.c("panel.bg"));
        }
        Draw.rect(g, x, y, 1, h - 1, t.c("panel.border"));
        hits.add(x, y, W, h, b -> {});
        int ix = x + PAD_X, iw = W - PAD_X * 2;
        int cy = y + PAD_Y;

        // шапка: значок 22, имя, кто это, крестик
        Draw.disc(g, ix + 11, cy + 11, 11, PointsLayer.color(p));
        Icons.draw(g, PointsLayer.icon(p.icon), ix + 5, cy + 5, 12, 0xFF15181F);
        String name = PointsLayer.name(p);
        Draw.label(g, Draw.ellipsize(name, iw - 40, 18, Weight.SEMIBOLD), ix + 28, cy + 6.5f, 18, Weight.SEMIBOLD, t.c("text.primary"));
        String sub = Component.translatable(p.death() ? "rysmap.point.kind.death.note" : FtbPoints.NOTE_DEATH.equals(p.note) ? "rysmap.point.note.ftb_death"
            : "ftb".equals(p.from) ? "rysmap.point.kind.ftb" : "rysmap.point.kind.mine").getString();
        Draw.label(g, Draw.ellipsize(sub, iw - 40, 12, Weight.REGULAR), ix + 28, cy + 16.5f, 12, Weight.REGULAR, t.c("text.muted"));
        int closeX = x + W - PAD_X - 9;
        boolean hotClose = Kit.over(mx, my, closeX - 3, cy + 3, 14, 14);
        Draw.cross(g, closeX, cy + 6, hotClose ? t.c("text.primary") : t.c("text.muted"));
        hits.add(closeX - 3, cy + 3, 14, 14, b -> s.select(null));
        cy += 22 + 6;

        // карточка
        LocalPlayer player = Minecraft.getInstance().player;
        List<String[]> rows = new ArrayList<>();
        String coords = Minimap.group(p.x) + " · " + Minimap.group(p.y) + " · " + Minimap.group(p.z);
        rows.add(new String[]{Component.translatable("rysmap.point.coords").getString(), coords, "copy"});
        if (player != null && player.level().dimension().location().toString().equals(p.dimension)) {
            double dx = p.x + 0.5 - player.getX(), dz = p.z + 0.5 - player.getZ(), d = Math.sqrt(dx * dx + dz * dz);
            String where = d < 3 ? Component.translatable("rysmap.point.here").getString() : Words.distance(d) + " · " + Words.direction(dx, dz);
            rows.add(new String[]{Component.translatable("rysmap.point.from_here").getString(), where, null});
        }
        rows.add(new String[]{Component.translatable("rysmap.point.dimension").getString(), MapScreen.dimensionName(p.dimension), null});
        if (p.dimension.equals("minecraft:overworld")) {
            rows.add(new String[]{Component.translatable("rysmap.point.in_nether").getString(), Minimap.group(Math.floorDiv(p.x, 8)) + " · " + Minimap.group(Math.floorDiv(p.z, 8)), "info.nether"});
        } else if (p.dimension.equals("minecraft:the_nether")) {
            rows.add(new String[]{Component.translatable("rysmap.point.in_overworld").getString(), Minimap.group(p.x * 8) + " · " + Minimap.group(p.z * 8), "info.overworld"});
        }
        if (p.death()) {
            long ago = Math.max(0, System.currentTimeMillis() / 1000 - p.created);
            rows.add(new String[]{Component.translatable("rysmap.point.ago").getString(), ago(ago), "info.death"});
        }
        int cardH = rows.size() * ROW + 2;
        if (t.beveled()) t.card(g, ix, cy, iw, cardH);
        else Draw.roundBox(g, ix, cy, iw, cardH, 5, t.c("card.bg"), t.c("panel.border"));
        float ry = cy + 1;
        for (int i = 0; i < rows.size(); i++) {
            String[] row = rows.get(i);
            if (i > 0) Draw.rect(g, ix + 1, (int) ry, iw - 2, 1, t.c("panel.border"));
            float mid = ry + ROW / 2f;
            Draw.label(g, row[0], ix + 6, mid, 12.5f, Weight.REGULAR, t.c("text.muted"));
            float right = ix + iw - 6;
            if (row[2] != null) {
                float bx = right - 9;
                boolean hot = Kit.over(mx, my, (int) bx - 1, (int) ry + 2, 11, 11);
                if (row[2].equals("copy")) {
                    Icons.draw(g, Icons.Shape.COPY, bx, mid - 3.5f, 7, hot ? t.c("text.primary") : t.c("text.muted"));
                    String clip = p.x + " " + p.y + " " + p.z;
                    hits.add((int) bx - 1, (int) ry + 2, 11, 11, b -> Minecraft.getInstance().keyboardHandler.setClipboard(clip));
                    if (hot) tip[0] = List.of(Component.translatable("rysmap.point.copy").getString());
                } else {
                    Draw.info(g, bx, mid - 3.5f, 7, hot ? t.c("text.primary") : t.c("text.muted"));
                    if (hot) tip[0] = List.of(Component.translatable("rysmap.point." + row[2]).getString());
                }
                right = bx - 4;
            }
            float vw = Draw.labelWidth(row[1], 12.5f, Weight.MEDIUM);
            Draw.label(g, row[1], right - vw, mid, 12.5f, Weight.MEDIUM, t.c("text.primary"));
            ry += ROW;
        }
        cy += cardH + 6;

        // где показывать
        Kit.caps(g, t, Component.translatable("rysmap.point.where").getString(), ix, cy + 3, 11, 0.44f);
        cy += 6 + 4;
        Draw.label(g, Component.translatable("rysmap.point.on_minimap").getString(), ix, cy + 9, 13, Weight.REGULAR, t.c("text.body"));
        boolean hotT = Kit.over(mx, my, ix + iw - 18, cy + 4, 18, 10);
        Kit.toggle(g, t, ix + iw - 18, cy + 4, p.minimap, hotT);
        hits.add(ix + iw - 18, cy + 4, 18, 10, b -> {
            p.minimap = !p.minimap;
            s.pointsChanged();
        });

        // действия
        int actTop = y + h - 6 - 14 - 4 - 21 - 6;
        Draw.rect(g, x + 1, actTop, W - 2, 1, t.c("panel.border"));
        int ay = actTop + 6;
        boolean tracked = MapApi.get() instanceof works.repka.rysmap.api.MapApiImpl api && api.tracking(p.id);
        String go = Component.translatable(tracked ? "rysmap.point.stop" : "rysmap.point.go").getString();
        PointWidgets.button(g, t, hits, ix, ay, iw, 21, go, null, tracked ? 0 : 1, mx, my, () -> s.track(p));
        ay += 21 + 4;
        int half = (iw - 4) / 2;
        PointWidgets.button(g, t, hits, ix, ay, half, 14, Component.translatable("rysmap.point.edit_short").getString(), null, 0, mx, my, () -> s.edit(p));
        PointWidgets.button(g, t, hits, ix + half + 4, ay, iw - half - 4, 14, Component.translatable("rysmap.point.delete").getString(), null, 0, mx, my, () -> s.askDelete(p));
    }

    /** «5 минут», «2 часа», «3 дня». */
    static String ago(long seconds) {
        if (seconds < 60) return Words.plural(seconds, "rysmap.time.seconds");
        if (seconds < 3600) return Words.plural(seconds / 60, "rysmap.time.minutes");
        if (seconds < 86400) return Words.plural(seconds / 3600, "rysmap.time.hours");
        return Words.plural(seconds / 86400, "rysmap.time.days");
    }
}
