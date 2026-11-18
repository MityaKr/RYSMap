package works.repka.rysmap.hud;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import works.repka.ryscore.map.MapApi;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.theme.ThemeManager;
import works.repka.ryscore.ui.Anchors;
import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.ui.Icons;
import works.repka.ryscore.ui.Type;
import works.repka.ryscore.ui.UiScale;
import works.repka.rysmap.api.MapApiImpl;
import works.repka.rysmap.client.ClientConfig;
import works.repka.rysmap.client.ClientEntry;
import works.repka.rysmap.client.MapSession;
import works.repka.rysmap.layer.Layers;

/**
 * Миникарта (макет {@code 01}): квадрат в углу зоны карты (по умолчанию справа сверху, отступ 8), север сверху,
 * под ней плашка строк: координаты, строки модов из {@link MapApi#statuses()}, путь к цели. Прячется на F1 и F3:
 * слой мода игра сама не прячет. Внутри рамки тайлы рисуются полосами, повторяющими скругление темы.
 *
 * <p>Цена кадра: до четырёх квадов тайлов, метки и строки одной пачкой ядра; заливки текстур в кадре нет.
 */
public final class Minimap {
    /** Пикселей экрана на блок по ступеням приближения. */
    public static final float[] ZOOM = {0.5f, 1f, 2f};
    public static final int MARGIN = 8, GAP = 2, LINE = 10;
    private static final float TEXT = 11f; // кегль 5,5 единицы, в пикселях макета

    private Minimap() {}

    /** Размер в единицах по настройке: «Авто» берёт M, на маленьком окне S (макет {@code 07}). */
    public static int size() {
        int s = ClientConfig.get().minimapSize;
        if (s != 0) return s;
        return UiScale.height() < 300 ? 72 : 96;
    }

    public static boolean hidden(Minecraft mc) {
        return mc.options.hideGui || mc.getDebugOverlay().showDebugScreen() || mc.player == null || mc.level == null
            || ClientEntry.session() == null || !ClientConfig.get().minimap;
    }

    private static long frameNanos, frames;

    public static void render(GuiGraphics parent, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (hidden(mc)) return;
        long t0 = System.nanoTime();
        UiScale.render(parent, g -> draw(g, mc, delta.getGameTimeDeltaPartialTick(false)));
        frameNanos += System.nanoTime() - t0;
        frames++;
    }

    /** Средняя цена кадра миникарты на процессоре, мкс (замер автотеста; сбрасывается вызовом). */
    public static long frameMicrosAndReset() {
        long out = frames == 0 ? 0 : frameNanos / frames / 1000;
        frameNanos = frames = 0;
        return out;
    }

    public static void zoom(int step) {
        ClientConfig c = ClientConfig.get();
        int z = Math.max(0, Math.min(ZOOM.length - 1, c.minimapZoom + step));
        if (z != c.minimapZoom) {
            c.minimapZoom = z;
            c.save();
        }
    }

    private static void draw(GuiGraphics g, Minecraft mc, float partial) {
        MapSession session = ClientEntry.session();
        LocalPlayer p = mc.player;
        if (session == null || p == null) return;
        int size = size();
        Anchors.Corner corner = corner();
        boolean left = corner == Anchors.Corner.TOP_LEFT || corner == Anchors.Corner.BOTTOM_LEFT;
        boolean top = corner == Anchors.Corner.TOP_LEFT || corner == Anchors.Corner.TOP_RIGHT;
        // в левом нижнем углу карта прячется, пока открыт чат
        if (corner == Anchors.Corner.BOTTOM_LEFT && mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen) return;
        List<Line> lines = lines(mc, p);
        int plateH = lines.size() * LINE + 4;
        int x = left ? MARGIN : UiScale.width() - MARGIN - size;
        int y = top ? MARGIN : UiScale.height() - MARGIN - size - GAP - plateH;
        drawAt(g, mc, session, p, partial, x, y, size, lines);
    }

    /** Угол миникарты: свой выбор игрока в настройках карты, иначе угол зоны карты ядра. */
    public static Anchors.Corner corner() {
        String c = ClientConfig.get().minimapCorner;
        if (c != null) {
            try {
                return Anchors.Corner.valueOf(c);
            } catch (IllegalArgumentException ignored) {
                // неизвестное значение в файле: угол ядра
            }
        }
        return Anchors.mapCorner();
    }

    /** Живой образец для страницы настроек: та же миникарта с плашкой в точке {@code x, y}. Возвращает высоту. */
    public static int preview(GuiGraphics g, int x, int y, int size, boolean plateBelow) {
        Minecraft mc = Minecraft.getInstance();
        MapSession session = ClientEntry.session();
        LocalPlayer p = mc.player;
        if (session == null || p == null) return 0;
        List<Line> lines = lines(mc, p);
        int plateH = lines.size() * LINE + 4;
        drawAt(g, mc, session, p, 1f, x, plateBelow ? y : y + plateH + GAP, size, lines);
        return size + GAP + plateH;
    }

    private static void drawAt(GuiGraphics g, Minecraft mc, MapSession session, LocalPlayer p, float partial, int x, int y, int size, List<Line> lines) {
        Theme t = ThemeManager.get();
        int plateH = lines.size() * LINE + 4;
        float r = t.beveled() ? 0 : Math.max(2f, t.r("panel.radius"));

        // место игрока между тиками: тайлы, области и метки едут плавно и одним кадром со стрелкой
        double px = net.minecraft.util.Mth.lerp(partial, p.xo, p.getX()), pz = net.minecraft.util.Mth.lerp(partial, p.zo, p.getZ());
        double ppb = ZOOM[ClientConfig.get().minimapZoom] / UiScale.pixels();
        ResourceKey<Level> dim = p.level().dimension();
        Draw.round(g, x, y, size, size, r, t.c("map.bg"));
        session.tiles.draw(g, dim.location().toString(), px, pz, ppb, x, y, size, size, rounded(x, y, size, r));

        float cx = x + size / 2f, cy = y + size / 2f;
        areas(g, dim, px, pz, ppb, x, y, size);
        markers(g, dim, px, pz, ppb, x, y, size, cx, cy);
        target(g, t, dim, px, pz, ppb, x, y, size, cx, cy);

        // игрок: стрелка по взгляду, белая на тёмной подложке
        float yaw = p.getViewYRot(partial);
        Draw.disc(g, cx, cy, 4.5f, 0xB0101216);
        Icons.drawRotated(g, Icons.Shape.NAV, cx - 5, cy - 5, 10, yaw + 180f, 0xFFF7F8FB);

        if (t.beveled()) {
            Draw.rect(g, x - 1, y - 1, size + 2, 1, t.c("hud.map.border"));
            Draw.rect(g, x - 1, y + size, size + 2, 1, t.c("hud.map.border"));
            Draw.rect(g, x - 1, y, 1, size, t.c("hud.map.border"));
            Draw.rect(g, x + size, y, 1, size, t.c("hud.map.border"));
        } else {
            // макет 01: кольцо 2 px цвета плашки и тонкая рамка снаружи
            Draw.roundBorder(g, x - 1, y - 1, size + 2, size + 2, r + 1, t.c("hud.bg"), 1.25f);
            Draw.roundBorder(g, x - 1.5f, y - 1.5f, size + 3, size + 3, r + 1.5f, t.c("hud.map.border"), 0.5f);
        }
        north(g, t, cx, y);
        plate(g, t, lines, x, y + size + GAP, size, plateH, r);
    }

    /**
     * Буква севера в кружке 7,5 на верхней кромке (макет {@code 01}). Рисуется после рамки, иначе кольцо рамки ложится
     * поперёк буквы; кружок обведён цветом рамки, чтобы читался и на светлой карте.
     */
    private static void north(GuiGraphics g, Theme t, float cx, float y) {
        String north = Component.translatable("rysmap.north").getString();
        float cy = y + 0.5f;
        Draw.disc(g, cx, cy, 4.25f, t.c("hud.map.border"));
        Draw.disc(g, cx, cy, 3.75f, 0xFF101216);
        Draw.label(g, north, cx - Draw.labelWidth(north, 9f, Type.Weight.SEMIBOLD) / 2f, cy, 9f, Type.Weight.SEMIBOLD, 0xFFF7F8FB);
    }

    /** Полосы внутри скруглённого квадрата: середина целиком, у углов по строке на единицу радиуса. */
    static float[] rounded(float x, float y, float size, float r) {
        if (r <= 0.5f) return new float[]{x, y, x + size, y + size};
        int rows = (int) Math.ceil(r);
        float[] out = new float[(rows * 2 + 1) * 4];
        int n = 0;
        for (int i = 0; i < rows; i++) {
            float dy = r - (i + 0.5f);
            float inset = r - (float) Math.sqrt(Math.max(0, r * r - dy * dy));
            float y0 = y + i, y1 = y + i + 1;
            float b0 = y + size - i - 1, b1 = y + size - i;
            out[n++] = x + inset; out[n++] = y0; out[n++] = x + size - inset; out[n++] = y1;
            out[n++] = x + inset; out[n++] = b0; out[n++] = x + size - inset; out[n++] = b1;
        }
        out[n++] = x; out[n++] = y + rows; out[n++] = x + size; out[n] = y + size - rows;
        return out;
    }

    private static void areas(GuiGraphics g, ResourceKey<Level> dim, double px, double pz, double ppb, int x, int y, int size) {
        double half = size / 2.0 / ppb;
        int cx0 = (int) Math.floor((px - half) / 16), cx1 = (int) Math.floor((px + half) / 16);
        int cz0 = (int) Math.floor((pz - half) / 16), cz1 = (int) Math.floor((pz + half) / 16);
        double ox = x + size / 2.0 - px * ppb, oz = y + size / 2.0 - pz * ppb;
        for (MapApi.Layer layer : Layers.shown()) {
            if (layer.areas() == null) continue;
            Layers.drawAreas(g, layer, dim, cx0 - 1, cz0 - 1, cx1 + 1, cz1 + 1, ox, oz, ppb, x, y, x + size, y + size);
        }
    }

    private static void markers(GuiGraphics g, ResourceKey<Level> dim, double px, double pz, double ppb, int x, int y, int size, float cx, float cy) {
        int drawn = 0;
        for (MapApi.Layer layer : Layers.shown()) {
            for (MapApi.Marker m : Layers.markers(layer, dim)) {
                if (!m.minimap() || drawn >= 64) continue;
                float sx = (float) (cx + (m.x() - px) * ppb), sy = (float) (cy + (m.z() - pz) * ppb);
                boolean inside = sx >= x + 3 && sx <= x + size - 3 && sy >= y + 3 && sy <= y + size - 3;
                if (!inside) {
                    // участник и цель за краем прижаты к рамке, остальное не видно
                    if (m.kind() != MapApi.Kind.PLAYER && m.kind() != MapApi.Kind.GOAL) continue;
                    sx = Math.max(x + 3.5f, Math.min(x + size - 3.5f, sx));
                    sy = Math.max(y + 3.5f, Math.min(y + size - 3.5f, sy));
                }
                Draw.disc(g, sx, sy, 3.5f, m.color());
                if (m.icon() != null) Icons.draw(g, m.icon(), sx - 2.5f, sy - 2.5f, 5, 0xFF101216);
                drawn++;
            }
        }
    }

    private static void target(GuiGraphics g, Theme t, ResourceKey<Level> dim, double px, double pz, double ppb, int x, int y, int size, float cx, float cy) {
        if (!(MapApi.get() instanceof MapApiImpl api) || api.tracked() == null) return;
        MapApi.Target target = api.tracked();
        if (!target.dimension().equals(dim)) return;
        float sx = (float) (cx + (target.x() - px) * ppb), sy = (float) (cy + (target.z() - pz) * ppb);
        sx = Math.max(x + 4f, Math.min(x + size - 4f, sx));
        sy = Math.max(y + 4f, Math.min(y + size - 4f, sy));
        Draw.disc(g, sx, sy, 4f, 0xFFF7F8FB);
        Icons.draw(g, Icons.Shape.TARGET, sx - 3, sy - 3, 6, 0xFF101216);
    }

    // ---- строки под картой ----

    private record Line(int dot, Icons.Shape icon, String text, boolean coords) {}

    private static List<Line> lines(Minecraft mc, LocalPlayer p) {
        List<Line> out = new ArrayList<>();
        out.add(new Line(0, null, "", true));
        for (MapApi.StatusLine s : MapApi.statuses()) {
            if (out.size() >= 3) break;
            try {
                MapApi.Status st = s.get();
                if (st != null) out.add(new Line(st.color(), st.icon(), st.text().getString(), false));
            } catch (Throwable e) {
                // строка мода упала: в этом кадре её просто нет
            }
        }
        if (MapApi.get() instanceof MapApiImpl api && api.tracked() != null && out.size() < 4) {
            MapApi.Target target = api.tracked();
            if (target.dimension().equals(p.level().dimension())) {
                double d = Math.sqrt(p.distanceToSqr(target.x(), target.y(), target.z()));
                if (d <= target.radius()) {
                    api.track(null);
                } else {
                    String name = target.label().getString();
                    out.add(new Line(0xFFF7F8FB, Icons.Shape.TARGET, (name.isEmpty() ? "" : name + " · ") + Component.translatable("rysmap.meters", group((int) d)).getString(), false));
                }
            }
        }
        return out;
    }

    private static void plate(GuiGraphics g, Theme t, List<Line> lines, int x, int y, int w, int h, float r) {
        if (t.beveled()) Draw.bevel(g, x, y, w, h, t.c("hud.bg"), t.c("panel.bevel.light"), t.c("panel.bevel.dark"), 1, false);
        else Draw.roundBox(g, x, y, w, h, r, t.c("hud.bg"), t.c("hud.border"));
        float ly = y + 2 + LINE / 2f;
        LocalPlayer p = Minecraft.getInstance().player;
        boolean first = true;
        for (Line line : lines) {
            if (!first) Draw.round(g, x + 4, ly - LINE / 2f - 0.25f, w - 8, 0.5f, 0.02f, Draw.alpha(t.c("hud.border"), 0.8f));
            first = false;
            if (line.coords && p != null) {
                coord(g, t, "X", p.getBlockX(), x + 5, ly);
                coord(g, t, "Y", p.getBlockY(), x + 5 + w / 3f, ly);
                coord(g, t, "Z", p.getBlockZ(), x + 5 + 2 * w / 3f, ly);
            } else {
                float tx = x + 5;
                if (line.icon != null) {
                    Icons.draw(g, line.icon, tx - 0.5f, ly - 3, 6, line.dot);
                    tx += 7;
                } else if (line.dot != 0) {
                    Draw.disc(g, tx + 2, ly, 2, line.dot);
                    tx += 7;
                }
                String s = Draw.ellipsize(line.text, x + w - 4 - tx, TEXT, Type.Weight.REGULAR);
                Draw.label(g, s, tx, ly, TEXT, Type.Weight.REGULAR, t.c("hud.fg"));
            }
            ly += LINE;
        }
    }

    private static void coord(GuiGraphics g, Theme t, String axis, int value, float x, float cy) {
        Draw.label(g, axis, x, cy, TEXT, Type.Weight.REGULAR, t.c("hud.muted"));
        Draw.label(g, group(value), x + Draw.labelWidth(axis, TEXT, Type.Weight.REGULAR) + 2, cy, TEXT, Type.Weight.SEMIBOLD, t.c("hud.fg"));
    }

    /** Разряды пробелом и настоящий минус, как в макете: «1 204», «−388». */
    public static String group(int v) {
        String digits = String.format(Locale.ROOT, "%,d", Math.abs(v)).replace(',', ' ');
        return v < 0 ? "−" + digits : digits;
    }
}
