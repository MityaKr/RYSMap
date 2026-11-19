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

    /**
     * Угол миникарты: угол зоны карты ядра. Выбор игрока в настройках карты уходит в ядро ({@code Anchors.setMapCorner},
     * 1.13), соседи по углу подвигаются по нему. Старый свой выбор из {@code client.json} (до 0.1.0-m8) переносится в
     * ядро один раз.
     */
    public static Anchors.Corner corner() {
        ClientConfig cfg = ClientConfig.get();
        String c = cfg.minimapCorner;
        if (c != null) {
            try {
                Anchors.setMapCorner(Anchors.Corner.valueOf(c));
            } catch (IllegalArgumentException ignored) {
                // неизвестное значение в файле: угол ядра
            }
            cfg.minimapCorner = null;
            cfg.save();
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

    /**
     * Вид миникарты в кадре: центр, масштаб, поворот и форма. Мировое место переводится на экран {@link #sx}, {@link #sy}:
     * при повороте по взгляду смещение от игрока поворачивается на {@code 180° − yaw}, так взгляд всегда смотрит вверх.
     */
    static final class View {
        final double px, pz, ppb;
        final float cx, cy, half, angle;
        final boolean circle, rotate;
        final float cos, sin;

        View(double px, double pz, double ppb, float cx, float cy, float half, boolean circle, boolean rotate, float yaw) {
            this.px = px;
            this.pz = pz;
            this.ppb = ppb;
            this.cx = cx;
            this.cy = cy;
            this.half = half;
            this.circle = circle;
            this.rotate = rotate;
            this.angle = rotate ? 180f - yaw : 0f;
            double a = Math.toRadians(angle);
            this.cos = (float) Math.cos(a);
            this.sin = (float) Math.sin(a);
        }

        float sx(double wx, double wz) {
            double dx = (wx - px) * ppb, dy = (wz - pz) * ppb;
            return (float) (cx + (rotate ? dx * cos - dy * sin : dx));
        }

        float sy(double wx, double wz) {
            double dx = (wx - px) * ppb, dy = (wz - pz) * ppb;
            return (float) (cy + (rotate ? dx * sin + dy * cos : dy));
        }

        /** Точка внутри формы с запасом {@code inset}. */
        boolean inside(float x, float y, float inset) {
            float dx = x - cx, dy = y - cy, lim = half - inset;
            return circle ? dx * dx + dy * dy <= lim * lim : Math.abs(dx) <= lim && Math.abs(dy) <= lim;
        }

        /** Прижать точку к кромке формы с запасом {@code inset}: {x, y}. */
        float[] clamp(float x, float y, float inset) {
            float dx = x - cx, dy = y - cy, lim = half - inset;
            if (circle) {
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d <= lim || d == 0) return new float[]{x, y};
                return new float[]{cx + dx / d * lim, cy + dy / d * lim};
            }
            return new float[]{cx + Math.max(-lim, Math.min(lim, dx)), cy + Math.max(-lim, Math.min(lim, dy))};
        }

        /** Сколько блоков от игрока до дальнего угла видимой формы (для отбора). */
        double reachBlocks() {
            return half * (rotate && !circle ? 1.42 : 1.0) / ppb;
        }
    }

    private static void drawAt(GuiGraphics g, Minecraft mc, MapSession session, LocalPlayer p, float partial, int x, int y, int size, List<Line> lines) {
        Theme t = ThemeManager.get();
        int plateH = lines.size() * LINE + 4;
        ClientConfig cfg = ClientConfig.get();
        boolean circle = cfg.minimapCircle;
        float r = circle ? size / 2f : t.beveled() ? 0 : Math.max(2f, t.r("panel.radius"));

        // место игрока между тиками: тайлы, области и метки едут плавно и одним кадром со стрелкой
        double px = net.minecraft.util.Mth.lerp(partial, p.xo, p.getX()), pz = net.minecraft.util.Mth.lerp(partial, p.zo, p.getZ());
        double ppb = ZOOM[cfg.minimapZoom] / UiScale.pixels();
        ResourceKey<Level> dim = p.level().dimension();
        float yaw = p.getViewYRot(partial);
        float cx = x + size / 2f, cy = y + size / 2f;
        View v = new View(px, pz, ppb, cx, cy, size / 2f, circle, cfg.minimapRotate, yaw);
        if (circle) Draw.disc(g, cx, cy, size / 2f, t.c("map.bg"));
        else Draw.round(g, x, y, size, size, r, t.c("map.bg"));
        if (!works.repka.rysmap.client.ServerRules.minimapAllowed()) {
            // сервер выключил миникарту: в рамке причина, а не чёрный квадрат (макет 04)
            List<String> why = Draw.wrapLabel(Component.translatable("rysmap.minimap.off_server").getString(), size - 12, 11, Type.Weight.REGULAR, 4);
            float ty = cy - (why.size() - 1) * 6.5f / 2f;
            for (String line : why) {
                Draw.label(g, line, cx - Draw.labelWidth(line, 11, Type.Weight.REGULAR) / 2f, ty, 11, Type.Weight.REGULAR, t.c("hud.muted"));
                ty += 6.5f;
            }
            frame(g, t, x, y, size, r, circle);
            north(g, t, v);
            plate(g, t, lines, x, y + size + GAP, size, plateH, circle ? Math.max(2f, t.r("panel.radius")) : r);
            return;
        }

        String dimId = dim.location().toString();
        Integer cave = session.caveFloor();
        if (cave != null) dimId = works.repka.rysmap.scan.Floors.id(dimId, cave); // под землёй и в Нижнем мире этаж игрока
        float[] shape = rounded(x, y, size, r);
        // ночь: множитель в шейдере, этажи пещер не темнеют
        session.tiles.brightness(cave == null ? MapSession.nightBrightness(mc.level, partial) : 1f);
        if (!v.rotate) {
            session.tiles.draw(g, dimId, px, pz, ppb, x, y, size, size, shape);
            areas(g, dim, v, x, y, size, shape);
        } else {
            // поворот по взгляду: тайлы и области рисуются в повёрнутой матрице. Круг от поворота не меняется, его полосы
            // годятся как есть; квадрат режется ножницами экрана (углы скругления закрывает рамка).
            float big = size * 1.42f;
            float bx = cx - big / 2f, by = cy - big / 2f;
            works.repka.ryscore.ui.Batch.flush();
            if (!circle) Draw.scissor(g, x, y, x + size, y + size);
            g.pose().pushPose();
            g.pose().rotateAround(com.mojang.math.Axis.ZP.rotationDegrees(v.angle), cx, cy, 0);
            float[] clips = circle ? shape : new float[]{bx, by, bx + big, by + big};
            session.tiles.draw(g, dimId, px, pz, ppb, bx, by, big, big, clips);
            View flat = new View(px, pz, ppb, cx, cy, big / 2f, circle, false, 0);
            areas(g, dim, flat, (int) bx, (int) by, (int) Math.ceil(big), clips);
            works.repka.ryscore.ui.Batch.flush();
            g.pose().popPose();
            if (!circle) g.disableScissor();
        }

        session.tiles.brightness(1f);
        markers(g, dim, v);
        entities(g, dim, v, partial, cave);
        target(g, t, dim, v);

        // игрок: стрелка по взгляду (при повороте карты всегда вверх), белая на тёмной подложке
        Draw.disc(g, cx, cy, 4.5f, 0xB0101216);
        Icons.drawRotated(g, Icons.Shape.NAV, cx - 5, cy - 5, 10, v.rotate ? 0f : yaw + 180f, 0xFFF7F8FB);

        frame(g, t, x, y, size, r, circle);
        north(g, t, v);
        plate(g, t, lines, x, y + size + GAP, size, plateH, circle ? Math.max(2f, t.r("panel.radius")) : r);
    }

    private static void frame(GuiGraphics g, Theme t, int x, int y, int size, float r, boolean circle) {
        if (circle) {
            float cx = x + size / 2f, cy = y + size / 2f;
            Draw.ring(g, cx, cy, size / 2f + 0.5f, 1.25f, t.c("hud.bg"));
            Draw.ring(g, cx, cy, size / 2f + 1.5f, 0.5f, t.c("hud.map.border"));
        } else if (t.beveled()) {
            Draw.rect(g, x - 1, y - 1, size + 2, 1, t.c("hud.map.border"));
            Draw.rect(g, x - 1, y + size, size + 2, 1, t.c("hud.map.border"));
            Draw.rect(g, x - 1, y, 1, size, t.c("hud.map.border"));
            Draw.rect(g, x + size, y, 1, size, t.c("hud.map.border"));
        } else {
            // макет 01: кольцо 2 px цвета плашки и тонкая рамка снаружи
            Draw.roundBorder(g, x - 1, y - 1, size + 2, size + 2, r + 1, t.c("hud.bg"), 1.25f);
            Draw.roundBorder(g, x - 1.5f, y - 1.5f, size + 3, size + 3, r + 1.5f, t.c("hud.map.border"), 0.5f);
        }
    }

    /**
     * Буква севера в кружке 7,5 на кромке (макет {@code 01}): сверху, а при повороте по взгляду там, где север. Рисуется
     * после рамки, иначе кольцо рамки ложится поперёк буквы; кружок обведён цветом рамки, чтобы читался и на светлой карте.
     */
    private static void north(GuiGraphics g, Theme t, View v) {
        // направление на север на экране: мировое (0, −1) после поворота
        float dx = v.rotate ? v.sin : 0, dy = v.rotate ? -v.cos : -1;
        float k;
        if (v.circle) k = v.half;
        else k = v.half / Math.max(Math.abs(dx), Math.abs(dy));
        float nx = v.cx + dx * k, ny = v.cy + dy * k + (dy < -0.99f ? 0.5f : 0);
        String north = Component.translatable("rysmap.north").getString();
        Draw.disc(g, nx, ny, 4.25f, t.c("hud.map.border"));
        Draw.disc(g, nx, ny, 3.75f, 0xFF101216);
        Draw.label(g, north, nx - Draw.labelWidth(north, 9f, Type.Weight.SEMIBOLD) / 2f, ny, 9f, Type.Weight.SEMIBOLD, 0xFFF7F8FB);
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

    /** Области слоёв в осях карты (без поворота: его даёт матрица), обрезка по полосам формы. */
    private static void areas(GuiGraphics g, ResourceKey<Level> dim, View v, int x, int y, int size, float[] clips) {
        double half = size / 2.0 / v.ppb;
        int cx0 = (int) Math.floor((v.px - half) / 16), cx1 = (int) Math.floor((v.px + half) / 16);
        int cz0 = (int) Math.floor((v.pz - half) / 16), cz1 = (int) Math.floor((v.pz + half) / 16);
        double ox = v.cx - v.px * v.ppb, oz = v.cy - v.pz * v.ppb;
        for (MapApi.Layer layer : Layers.shown()) {
            if (layer.areas() == null) continue;
            Layers.drawAreas(g, layer, dim, cx0 - 1, cz0 - 1, cx1 + 1, cz1 + 1, ox, oz, v.ppb, x, y, x + size, y + size, clips);
        }
    }

    private static void markers(GuiGraphics g, ResourceKey<Level> dim, View v) {
        int drawn = 0;
        for (MapApi.Layer layer : Layers.shown()) {
            for (MapApi.Marker m : Layers.markers(layer, dim)) {
                if (!m.minimap() || drawn >= 64) continue;
                float sx = v.sx(m.x(), m.z()), sy = v.sy(m.x(), m.z());
                if (!v.inside(sx, sy, 3)) {
                    // участник и цель за краем прижаты к рамке, остальное не видно
                    // участник, цель, пинг и тревога за краем прижаты к рамке, остальное не видно
                    if (m.kind() == MapApi.Kind.POINT) continue;
                    float[] c = v.clamp(sx, sy, 3.5f);
                    sx = c[0];
                    sy = c[1];
                }
                Entities.pulse(g, m, sx, sy, 3.5f);
                Draw.disc(g, sx, sy, 3.5f, m.color());
                Entities.look(g, m, sx, sy, 5);
                drawn++;
            }
        }
    }

    /** Игроки и мобы (слои «Игроки» и «Мобы»): участник команды за краем прижат к рамке, остальные за краем не видны. */
    private static void entities(GuiGraphics g, ResourceKey<Level> dim, View v, float partial, @org.jetbrains.annotations.Nullable Integer floor) {
        double reach = v.reachBlocks();
        List<Entities.Dot> dots = Entities.collect(dim, v.px - reach, v.pz - reach, v.px + reach, v.pz + reach, partial, floor);
        // мобы пачкой под игроками
        List<Entities.Dot> mobs = new ArrayList<>();
        float[] xy = new float[dots.size() * 2];
        for (Entities.Dot d : dots) {
            if (d.player()) continue;
            float sx = v.sx(d.x(), d.z()), sy = v.sy(d.x(), d.z());
            if (!v.inside(sx, sy, 3f)) continue;
            xy[mobs.size() * 2] = sx;
            xy[mobs.size() * 2 + 1] = sy;
            mobs.add(d);
        }
        Entities.mobs(g, mobs, xy, 5f);
        for (Entities.Dot d : dots) {
            if (!d.player()) continue;
            float sx = v.sx(d.x(), d.z()), sy = v.sy(d.x(), d.z());
            if (!v.inside(sx, sy, 4)) {
                if (d.kind() != Entities.Kind.TEAM) continue;
                float[] c = v.clamp(sx, sy, 4.5f);
                sx = c[0];
                sy = c[1];
            }
            Entities.head(g, d, sx, sy, 7);
        }
    }

    private static void target(GuiGraphics g, Theme t, ResourceKey<Level> dim, View v) {
        if (!(MapApi.get() instanceof MapApiImpl api) || api.tracked() == null) return;
        MapApi.Target target = api.tracked();
        if (!target.dimension().equals(dim)) return;
        float[] c = v.clamp(v.sx(target.x(), target.z()), v.sy(target.x(), target.z()), 4f);
        Draw.disc(g, c[0], c[1], 4f, 0xFFF7F8FB);
        Icons.draw(g, Icons.Shape.TARGET, c[0] - 3, c[1] - 3, 6, 0xFF101216);
    }

    // ---- строки под картой ----

    private record Line(int dot, Icons.Shape icon, String text, boolean coords) {}

    private static List<Line> cachedLines = List.of();
    private static long linesAt = Long.MIN_VALUE;
    private static int linesHeight;

    /**
     * Строки под миникартой собираются раз в 5 тиков, а не каждый кадр (разбор логики 19.11, п. 9): строки модов, этаж,
     * путь. Координаты в первой строке рисуются из игрока каждый кадр.
     */
    private static List<Line> lines(Minecraft mc, LocalPlayer p) {
        long now = mc.level == null ? 0 : mc.level.getGameTime();
        if (now - linesAt >= 5 || now < linesAt || UiScale.height() != linesHeight) {
            cachedLines = buildLines(mc, p);
            linesAt = now;
            linesHeight = UiScale.height();
        }
        return cachedLines;
    }

    private static List<Line> buildLines(Minecraft mc, LocalPlayer p) {
        List<Line> out = new ArrayList<>();
        out.add(new Line(0, null, "", true));
        // маленькое окно (макет 07): миникарта S и под ней одна строка
        if (UiScale.height() < 300 && ClientConfig.get().minimapSize == 0) return out;
        for (MapApi.StatusLine s : MapApi.statuses()) {
            if (out.size() >= 3) break;
            try {
                MapApi.Status st = s.get();
                if (st != null) out.add(new Line(st.color(), st.icon(), st.text().getString(), false));
            } catch (Throwable e) {
                // строка мода упала: в этом кадре её просто нет
            }
        }
        MapSession session = ClientEntry.session();
        Integer cave = session == null ? null : session.caveFloor();
        if (cave != null && out.size() < 4) out.add(new Line(0xFFAAB4C4, Icons.Shape.CAVE, floorText(cave), false));
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

    /** «Пещера · этаж −3 · y −48…−33». */
    public static String floorText(int floor) {
        return Component.translatable("rysmap.cave.floor", group(floor), group(works.repka.rysmap.scan.Floors.bottom(floor)),
            group(works.repka.rysmap.scan.Floors.top(floor))).getString();
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
