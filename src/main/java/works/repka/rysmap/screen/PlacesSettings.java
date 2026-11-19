package works.repka.rysmap.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.ui.Icons;
import works.repka.ryscore.ui.Kit;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.ui.Type.Weight;
import works.repka.rysmap.client.PlacesClient;
import works.repka.rysmap.places.PlaceRules;

/**
 * Блок «Перемещения» на странице «Сервер» настроек карты (макет Essentials {@code 07}), видит только оператор: для каждой
 * функции кто ею пользуется, пауза и разогрев; ниже числа (домов на игрока, история «назад», случайное место, срок
 * запроса, после урона от игрока, движение срывает разогрев). Значения меняются кликом: левая кнопка следующее, правая
 * предыдущее; каждое нажатие сразу уходит на сервер, как остальные правила сервера. Пока стоит FTB Essentials, под
 * блоком строка о переносе.
 */
final class PlacesSettings {
    private static final int ROW = 20;
    private static final int[] PAUSES = {0, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600};
    private static final int[] WARMUPS = {0, 1, 2, 3, 5, 10};
    private static final int[] HOMES = {0, 1, 2, 3, 5, 10};
    private static final int[] BACK = {0, 3, 5, 10, 20, 30};
    private static final int[] RANDOM_MIN = {0, 100, 250, 500, 1000, 2000, 5000};
    private static final int[] RANDOM_MAX = {1000, 2500, 5000, 10000, 25000, 50000, 100000};
    private static final int[] TIMEOUTS = {30, 60, 90, 120, 300};
    private static final int[] PVP = {0, 5, 10, 15, 30, 60};
    private static final Icons.Shape[] ICONS = {Icons.Shape.HOME, Icons.Shape.USERS, Icons.Shape.SERVER, Icons.Shape.HISTORY, Icons.Shape.FLAG,
        Icons.Shape.DICE, Icons.Shape.USER};

    /** Правила с последней правкой, пока сервер не прислал новые (иначе клик мигал бы старым значением). */
    private static @Nullable PlaceRules pending;
    private static long pendingRevision;

    private PlacesSettings() {}

    static PlaceRules rules() {
        PlacesClient.Data d = PlacesClient.data();
        if (pending != null && PlacesClient.revision() == pendingRevision) return pending;
        pending = null;
        return d.rules;
    }

    private static void send(PlaceRules r) {
        pending = r.copy().clamp();
        pendingRevision = PlacesClient.revision();
        PlacesClient.op("rules", pending.toJson());
    }

    /** Вкладка «Перемещения» страницы настроек: возвращает низ. */
    static int draw(GuiGraphics g, Theme t, Kit.Hits hits, int lx, int lw, int y, int mx, int my) {
        PlacesClient.Data d = PlacesClient.data();
        if (!d.present || !d.op) return y;
        PlaceRules r = rules();
        // своя вкладка «Перемещения» (0.2.10): заголовок раздела не повторяет её название, остаётся подсказка о кликах
        String like = Component.translatable("rysmap.settings.places.note").getString();
        Draw.label(g, like, lx + lw - Draw.labelWidth(like, 11, Weight.REGULAR), y + 4, 11, Weight.REGULAR, t.c("text.muted"));
        y += 12;
        int whoX = lx + lw - 152, pauseX = lx + lw - 84, warmX = lx + lw - 40;
        Kit.caps(g, t, Component.translatable("rysmap.settings.places.fn").getString(), lx + 18, y + 4, 10, 0.4f);
        Kit.caps(g, t, Component.translatable("rysmap.settings.places.who").getString(), whoX, y + 4, 10, 0.4f);
        Kit.caps(g, t, Component.translatable("rysmap.settings.places.pause").getString(), pauseX, y + 4, 10, 0.4f);
        Kit.caps(g, t, Component.translatable("rysmap.settings.places.warmup").getString(), warmX, y + 4, 10, 0.4f);
        y += 9;
        PlaceRules.Fn[] fns = PlaceRules.Fn.values();
        for (int i = 0; i < fns.length; i++) {
            PlaceRules.Fn fn = fns[i];
            PlaceRules.Row row = r.row(fn);
            Draw.rect(g, lx, y, lw, 1, t.c("panel.border"));
            Icons.draw(g, ICONS[i], lx + 3, y + 6, 9, t.c("text.muted"));
            Draw.label(g, Component.translatable("rysmap.settings.places.fn." + fn.id).getString(), lx + 18, y + 7, 12.5f, Weight.REGULAR, t.c("text.body"));
            Draw.label(g, Draw.ellipsize(Component.translatable("rysmap.settings.places.cmd." + fn.id).getString(), whoX - lx - 22, 10.5f, Weight.REGULAR),
                lx + 18, y + 14.5f, 10.5f, Weight.REGULAR, t.c("text.muted"));
            String who = Component.translatable("rysmap.settings.places.who." + row.who.name().toLowerCase(java.util.Locale.ROOT)).getString();
            box(g, t, hits, whoX, y + 3, 64, who, row.who == PlaceRules.Who.OFF, mx, my, b -> {
                PlaceRules n = r.copy();
                PlaceRules.Who[] all = {PlaceRules.Who.ALL, PlaceRules.Who.MODERATORS, PlaceRules.Who.OPERATORS, PlaceRules.Who.OFF};
                int k = 0;
                for (int j = 0; j < all.length; j++) if (all[j] == row.who) k = j;
                n.row(fn).who = all[Math.floorMod(k + step(b), all.length)];
                send(n);
            });
            box(g, t, hits, pauseX, y + 3, 40, seconds(row.pause), false, mx, my, b -> {
                PlaceRules n = r.copy();
                n.row(fn).pause = next(PAUSES, row.pause, step(b));
                send(n);
            });
            box(g, t, hits, warmX, y + 3, 40, row.warmup + " " + Component.translatable("rysmap.settings.places.s").getString(), false, mx, my, b -> {
                PlaceRules n = r.copy();
                n.row(fn).warmup = next(WARMUPS, row.warmup, step(b));
                send(n);
            });
            y += ROW;
        }
        y += 6;
        Kit.caps(g, t, Component.translatable("rysmap.settings.places.numbers").getString(), lx, y + 4, 11, 0.44f);
        y += 10;
        y = number(g, t, hits, lx, lw, y, "homes", null, Integer.toString(r.homes), mx, my, b -> {
            PlaceRules n = r.copy();
            n.homes = next(HOMES, r.homes, step(b));
            send(n);
        });
        y = number(g, t, hits, lx, lw, y, "back", "back.note", Integer.toString(r.backSize), mx, my, b -> {
            PlaceRules n = r.copy();
            n.backSize = next(BACK, r.backSize, step(b));
            send(n);
        });
        y = number(g, t, hits, lx, lw, y, "back_death", null, Component.translatable(r.backDeath ? "rysmap.settings.places.yes" : "rysmap.settings.places.no").getString(), mx, my, b -> {
            PlaceRules n = r.copy();
            n.backDeath = !r.backDeath;
            send(n);
        });
        y = number(g, t, hits, lx, lw, y, "random_min", "random.note", works.repka.rysmap.hud.Minimap.group(r.randomMin), mx, my, b -> {
            PlaceRules n = r.copy();
            n.randomMin = next(RANDOM_MIN, r.randomMin, step(b));
            send(n);
        });
        y = number(g, t, hits, lx, lw, y, "random_max", null, works.repka.rysmap.hud.Minimap.group(r.randomMax), mx, my, b -> {
            PlaceRules n = r.copy();
            n.randomMax = next(RANDOM_MAX, r.randomMax, step(b));
            send(n);
        });
        boolean noNether = r.randomExcluded.contains("minecraft:the_nether"), noEnd = r.randomExcluded.contains("minecraft:the_end");
        Draw.label(g, Component.translatable("rysmap.settings.places.random_excluded").getString(), lx, y + 9, 13, Weight.REGULAR, t.c("text.body"));
        int px = lx + lw;
        String end = MapScreen.dimensionName("minecraft:the_end"), nether = MapScreen.dimensionName("minecraft:the_nether");
        px -= (int) Math.ceil(Draw.labelWidth(end, 12.5f, noEnd ? Weight.MEDIUM : Weight.REGULAR)) + 12;
        Kit.pill(g, t, hits, px, y + 2, end, noEnd, mx, my, () -> toggle(r, "minecraft:the_end"));
        px -= (int) Math.ceil(Draw.labelWidth(nether, 12.5f, noNether ? Weight.MEDIUM : Weight.REGULAR)) + 12 + 4;
        Kit.pill(g, t, hits, px, y + 2, nether, noNether, mx, my, () -> toggle(r, "minecraft:the_nether"));
        y += 19;
        y = number(g, t, hits, lx, lw, y, "timeout", null, seconds(r.requestTimeout), mx, my, b -> {
            PlaceRules n = r.copy();
            n.requestTimeout = next(TIMEOUTS, r.requestTimeout, step(b));
            send(n);
        });
        y = number(g, t, hits, lx, lw, y, "pvp", "pvp.note", r.pvpDelay == 0 ? Component.translatable("rysmap.settings.places.pvp.none").getString() : seconds(r.pvpDelay), mx, my, b -> {
            PlaceRules n = r.copy();
            n.pvpDelay = next(PVP, r.pvpDelay, step(b));
            send(n);
        });
        y = number(g, t, hits, lx, lw, y, "move", "move.note", Component.translatable(r.moveCancels ? "rysmap.settings.places.yes" : "rysmap.settings.places.no").getString(), mx, my, b -> {
            PlaceRules n = r.copy();
            n.moveCancels = !r.moveCancels;
            send(n);
        });
        if (d.ftb) {
            java.util.List<String> lines = Draw.wrapLabel(Component.translatable("rysmap.settings.places.ftb").getString(), lw - 22, 11.5f, Weight.REGULAR, 4);
            int h = lines.size() * 7 + 8;
            Draw.roundBox(g, lx, y + 2, lw, h, 4, Draw.alpha(t.c("state.warn"), 0.12f), Draw.alpha(t.c("state.warn"), 0.5f));
            Icons.draw(g, Icons.Shape.DOWN, lx + 5, y + 6, 7, t.c("state.warn"));
            float ly = y + 2 + 4 + 3.5f;
            for (String line : lines) {
                Draw.label(g, line, lx + 16, ly, 11.5f, Weight.REGULAR, t.c("text.body"));
                ly += 7;
            }
            y += h + 6;
        }
        return y + 6;
    }

    private static void toggle(PlaceRules r, String dim) {
        PlaceRules n = r.copy();
        if (!n.randomExcluded.remove(dim)) n.randomExcluded.add(dim);
        send(n);
    }

    private static int step(int button) {
        return button == GLFW.GLFW_MOUSE_BUTTON_RIGHT ? -1 : 1;
    }

    /** Следующее значение ряда; значение не из ряда встаёт на ближайшее. */
    static int next(int[] values, int current, int step) {
        int k = 0;
        for (int i = 0; i < values.length; i++) if (Math.abs(values[i] - current) < Math.abs(values[k] - current)) k = i;
        if (values[k] != current) return values[k];
        return values[Math.floorMod(k + step, values.length)];
    }

    static String seconds(int s) {
        if (s >= 60 && s % 60 == 0) return s / 60 + " " + Component.translatable("rysmap.settings.places.m").getString();
        return s + " " + Component.translatable("rysmap.settings.places.s").getString();
    }

    private static void box(GuiGraphics g, Theme t, Kit.Hits hits, int x, int y, int w, String text, boolean dim, int mx, int my,
                            java.util.function.IntConsumer click) {
        boolean hot = Kit.over(mx, my, x, y, w, 14);
        if (t.beveled()) t.input(g, x, y, w, 14, hot);
        else Draw.roundBox(g, x, y, w, 14, 3.5f, t.c("input.bg"), hot ? t.c("text.muted") : t.c("panel.border"));
        String s = Draw.ellipsize(text, w - 8, 12, Weight.MEDIUM);
        Draw.label(g, s, x + w - 5 - Draw.labelWidth(s, 12, Weight.MEDIUM), y + 7, 12, Weight.MEDIUM, dim ? t.c("text.muted") : t.c("text.primary"));
        hits.add(x, y, w, 14, click);
    }

    private static int number(GuiGraphics g, Theme t, Kit.Hits hits, int lx, int lw, int y, String key, @Nullable String note, String value, int mx, int my,
                              java.util.function.IntConsumer click) {
        String label = Component.translatable("rysmap.settings.places." + key).getString();
        if (note == null) {
            Draw.label(g, label, lx, y + 9, 13, Weight.REGULAR, t.c("text.body"));
        } else {
            Draw.label(g, label, lx, y + 5.5f, 13, Weight.REGULAR, t.c("text.body"));
            Draw.label(g, Component.translatable("rysmap.settings.places." + note).getString(), lx, y + 13, 11, Weight.REGULAR, t.c("text.muted"));
        }
        box(g, t, hits, lx + lw - 64, y + 2, 64, value, false, mx, my, click);
        return y + 19;
    }
}
