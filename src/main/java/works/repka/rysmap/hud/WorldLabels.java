package works.repka.rysmap.hud;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import works.repka.ryscore.map.MapApi;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.theme.ThemeManager;
import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.ui.Icons;
import works.repka.ryscore.ui.Type;
import works.repka.ryscore.ui.UiScale;
import works.repka.rysmap.api.MapApiImpl;
import works.repka.rysmap.client.ClientConfig;
import works.repka.rysmap.client.ClientEntry;
import works.repka.rysmap.client.MapSession;
import works.repka.rysmap.point.PointStore;
import works.repka.rysmap.point.PointsLayer;
import works.repka.rysmap.screen.Words;

/**
 * Подписи точек в мире сквозь стены (решение автора 18.11): свёрнутая метка это значок цвета точки, раскрытая это
 * тёмная плашка slate со значком, именем и расстоянием. Раскрыта одна метка у прицела, цель пути и последнее место
 * гибели всегда, все сразу, пока зажата клавиша «показать все точки». Что видно и что раскрыто, решает {@link LabelPlan}.
 * Рядом со своими точками идут подписи модов семьи из ядра ({@link works.repka.ryscore.world.Labels}, 1.12): пинги,
 * тревоги и цели закреплены, остальные слушаются режима игрока.
 *
 * <p>Как рисуется. Матрица проекции и поворота камеры снимается в {@code RenderLevelStageEvent} на стадии
 * {@code AFTER_LEVEL} (та же, по которой игра нарисовала мир, с покачиванием и тошнотой), а сами метки рисуются слоем
 * HUD внутри {@link UiScale#render}: позиция точки переводится на экран одним умножением на матрицу. Так метки идут
 * одной пачкой ядра вместе с остальным HUD, текст и скругления те же, что в окнах семьи, F1 и открытый экран
 * проверяются в одном месте, а в мировом проходе нет ни своей отрисовки, ни смены состояния глубины.
 *
 * <p>Цена кадра: обход точек мира, одно умножение матрицы на метку, не больше {@code labelLimit} меток. Массивы и
 * вектор заведены заранее; строки имени и расстояния строятся, только когда меняются, и только у раскрытых меток.
 */
public final class WorldLabels {
    private static final float PLATE_H = 14, ICON_R = 5f, NAME_PX = 12f, DIST_PX = 11f, NAME_MAX = 120;
    private static final int GOAL_COLOR = 0xFFF7F8FB, RIM = 0xCC0F1116, INK = 0xFF15181F;
    /** Ключ цели пути, которая не своя точка (место с карты или цель другого мода). */
    private static final Object SPOT_GOAL = new Object();

    private static final LabelPlan PLAN = new LabelPlan();
    private static final Matrix4f VIEW_PROJECTION = new Matrix4f();
    private static final Vector4f V = new Vector4f();
    private static double camX, camY, camZ;
    private static boolean captured;
    private static long lastNanos;

    // кандидаты кадра, по номеру кандидата LabelPlan
    private static double[] px = new double[64], py = new double[64], pz = new double[64];
    private static Icons.Shape[] icons = new Icons.Shape[64];
    private static int[] colors = new int[64];
    private static boolean[] goals = new boolean[64];
    private static Object[] sources = new Object[64];

    private static @Nullable Theme slate;
    private static long slateCheck;
    private static final Consumer<GuiGraphics> DRAW = WorldLabels::draw;

    private WorldLabels() {}

    /** Стадия мира: матрица, по которой игра нарисовала кадр, и место камеры. */
    public static void capture(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        VIEW_PROJECTION.set(e.getProjectionMatrix()).mul(e.getModelViewMatrix());
        Vec3 c = e.getCamera().getPosition();
        camX = c.x;
        camY = c.y;
        camZ = c.z;
        captured = true;
    }

    /** Прячется на F1 и при открытом экране (чат, инвентарь, карта); на F3 остаётся. */
    public static boolean hidden(Minecraft mc) {
        return mc.options.hideGui || mc.screen != null || mc.player == null || mc.level == null || ClientEntry.session() == null || !captured;
    }

    /** Слой HUD. */
    public static void render(GuiGraphics parent, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0 : Math.min(0.1f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        if (hidden(mc)) return;
        if (!collect(mc)) return;
        PLAN.animate(dt);
        UiScale.render(parent, DRAW);
    }

    /** Кандидаты, отбор, проекция и раскрытие. Ложь, если рисовать нечего. */
    private static boolean collect(Minecraft mc) {
        MapSession session = ClientEntry.session();
        if (session == null || mc.player == null) return false;
        ClientConfig c = ClientConfig.get();
        String dim = mc.player.level().dimension().location().toString();
        MapApiImpl api = MapApi.get() instanceof MapApiImpl impl ? impl : null;
        MapApi.Target target = api == null ? null : api.tracked();
        UUID goalPoint = api == null ? null : api.trackedPoint();

        PLAN.begin();
        List<PointStore.Point> points = session.points().points();
        // последнее место гибели закреплено; прежние места гибели ведут себя как обычные точки
        PointStore.Point lastDeath = null;
        for (int i = 0, n = points.size(); i < n; i++) {
            PointStore.Point p = points.get(i);
            if (p.death() && p.dimension.equals(dim) && (lastDeath == null || p.created > lastDeath.created)) lastDeath = p;
        }
        boolean goalFound = false;
        for (int i = 0, n = points.size(); i < n; i++) {
            PointStore.Point p = points.get(i);
            if (!p.dimension.equals(dim)) continue;
            boolean goal = goalPoint != null && goalPoint.equals(p.id);
            goalFound |= goal;
            int flag = (goal || p == lastDeath ? LabelPlan.PINNED : 0) | (p.world ? LabelPlan.ALWAYS : 0);
            double x = p.x + 0.5, y = p.y + 1.0, z = p.z + 0.5;
            int k = PLAN.add(p.id, distance(x, y, z), flag);
            put(k, x, y, z, PointsLayer.icon(p.icon), PointsLayer.color(p), goal, p);
        }
        if (target != null && !goalFound && target.dimension().location().toString().equals(dim)) {
            double x = target.x(), y = target.y() + 1.0, z = target.z();
            int k = PLAN.add(SPOT_GOAL, distance(x, y, z), LabelPlan.PINNED);
            put(k, x, y, z, Icons.Shape.TARGET, GOAL_COLOR, true, target);
        }
        // подписи модов семьи через ядро: с 1.15 готовый отбор Labels.visible (общий лимит ближних, одна отсечка по
        // дальности подписей игрока, пинги любого мода источником ядра); пинги и тревоги закреплены, «always» видны всегда
        works.repka.ryscore.world.Labels.setCutoff(c.labelRange);
        java.util.List<works.repka.ryscore.world.Labels.Label> mods;
        try {
            mods = works.repka.ryscore.world.Labels.visible(mc.player.level().dimension(), mc.player.getX(), mc.player.getY(), mc.player.getZ());
        } catch (Throwable e) {
            mods = java.util.List.of();
        }
        for (works.repka.ryscore.world.Labels.Label l : mods) {
            var kind = l.kind();
            boolean pin = kind == works.repka.ryscore.world.Labels.Kind.PING || kind == works.repka.ryscore.world.Labels.Kind.ALERT
                || kind == works.repka.ryscore.world.Labels.Kind.GOAL;
            int flag = (pin ? LabelPlan.PINNED : 0) | (l.always() ? LabelPlan.ALWAYS : 0);
            int k = PLAN.add(l.id(), distance(l.x(), l.y(), l.z()), flag);
            Icons.Shape icon = l.icon() != null ? l.icon() : l.head() != null ? Icons.Shape.USERS : Icons.Shape.DOT;
            put(k, l.x(), l.y(), l.z(), icon, l.color(), kind == works.repka.ryscore.world.Labels.Kind.GOAL, l);
        }
        PLAN.choose(LabelPlan.Mode.of(c.worldLabels), c.labelRange, c.labelLimit, ClientEntry.SHOW_POINTS.isDown());
        if (PLAN.chosenCount() == 0) return false;

        var window = mc.getWindow();
        double pixels = UiScale.pixels();
        float w = (float) (window.getWidth() / pixels), h = (float) (window.getHeight() / pixels);
        for (int k = 0; k < PLAN.chosenCount(); k++) {
            int i = PLAN.chosen(k);
            V.set((float) (px[i] - camX), (float) (py[i] - camY), (float) (pz[i] - camZ), 1f);
            VIEW_PROJECTION.transform(V);
            if (V.w <= 0.05f) {
                PLAN.screen(i, 0, 0, false);
                continue;
            }
            float nx = V.x / V.w, ny = V.y / V.w;
            boolean on = nx >= -1.05f && nx <= 1.05f && ny >= -1.05f && ny <= 1.05f;
            PLAN.screen(i, (nx * 0.5f + 0.5f) * w, (0.5f - ny * 0.5f) * h, on);
        }
        PLAN.focus(w / 2f, h / 2f, c.labelFocus);
        return true;
    }

    private static double distance(double x, double y, double z) {
        double dx = x - camX, dy = y - camY, dz = z - camZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static void put(int k, double x, double y, double z, Icons.Shape icon, int color, boolean goal, Object source) {
        if (k >= px.length) {
            int n = px.length * 2;
            px = java.util.Arrays.copyOf(px, n);
            py = java.util.Arrays.copyOf(py, n);
            pz = java.util.Arrays.copyOf(pz, n);
            icons = java.util.Arrays.copyOf(icons, n);
            colors = java.util.Arrays.copyOf(colors, n);
            goals = java.util.Arrays.copyOf(goals, n);
            sources = java.util.Arrays.copyOf(sources, n);
        }
        px[k] = x;
        py[k] = y;
        pz[k] = z;
        icons[k] = icon;
        colors[k] = color;
        goals[k] = goal;
        sources[k] = source;
    }

    /** Тема полотна: метки лежат на мире, тёмные в любой теме (как полотно карты). Ищется раз в секунду. */
    private static Theme slate() {
        long now = System.currentTimeMillis();
        if (slate == null || now - slateCheck > 1000) {
            slateCheck = now;
            Theme found = null;
            for (Theme t : ThemeManager.all()) if (t.id.equals("ryscore:slate")) found = t;
            slate = found != null ? found : ThemeManager.get();
        }
        return slate;
    }

    private static void draw(GuiGraphics g) {
        Theme t = slate();
        int range = ClientConfig.get().labelRange;
        // сначала свёрнутые от дальних к ближним (ближняя сверху), потом раскрытые поверх
        for (int pass = 0; pass < 2; pass++) {
            for (int k = PLAN.chosenCount() - 1; k >= 0; k--) {
                int i = PLAN.chosen(k);
                if (!PLAN.onScreen(i)) continue;
                LabelPlan.Anim a = PLAN.anim(i);
                if (a == null || (a.expand > 0.001f) != (pass == 1)) continue;
                label(g, t, i, a, range);
            }
        }
    }

    private static void label(GuiGraphics g, Theme t, int i, LabelPlan.Anim a, int range) {
        double d = PLAN.distance(i);
        float e = LabelPlan.ease(a.expand);
        float scale = lerp(LabelPlan.scale(d, range), 1, e);
        float alpha = lerp(LabelPlan.fade(d, range), 1, e) * a.appear;
        if (alpha <= 0.01f) return;
        float x = PLAN.x(i), y = PLAN.y(i), r = ICON_R * scale;

        if (e > 0.01f) {
            texts(a, i, d);
            float half = PLATE_H / 2f;
            float full = PLATE_H + 2 + a.nameWidth + 4 + a.distWidth + 5;
            float pw = lerp(PLATE_H, full, e);
            Draw.roundBox(g, x - half, y - half, pw, PLATE_H, 4f, Draw.alpha(t.c("map.label.bg"), alpha), Draw.alpha(t.c("hud.border"), alpha * e));
            float ta = alpha * Math.max(0, Math.min(1, (e - 0.35f) / 0.65f));
            if (ta > 0.02f) {
                float tx = x + half + 2;
                Draw.label(g, a.name, tx, y, NAME_PX, Type.Weight.SEMIBOLD, Draw.alpha(t.c("map.label.fg"), ta));
                Draw.label(g, a.distText, tx + a.nameWidth + 4, y, DIST_PX, Type.Weight.REGULAR, Draw.alpha(t.c("text.muted"), ta));
            }
        }
        if (goals[i]) Draw.round(g, x - r - 2f, y - r - 2f, 2 * r + 4f, 2 * r + 4f, r + 2f, Draw.alpha(0x60F7F8FB, alpha));
        if (sources[i] instanceof works.repka.ryscore.world.Labels.Label l && l.ring()) {
            // кольцо пинга (ядро 1.15): мягко дышит цветом метки
            float pulse = 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 220.0);
            float rr = r + 2.5f + 2.5f * pulse;
            Draw.round(g, x - rr, y - rr, 2 * rr, 2 * rr, rr, Draw.alpha(colors[i], alpha * (0.45f - 0.25f * pulse)));
        }
        Draw.round(g, x - r - 0.75f, y - r - 0.75f, 2 * r + 1.5f, 2 * r + 1.5f, r + 0.75f, Draw.alpha(RIM, alpha));
        Draw.round(g, x - r, y - r, 2 * r, 2 * r, r, Draw.alpha(colors[i], alpha));
        float is = 6.5f * scale;
        Icons.draw(g, icons[i], x - is / 2f, y - is / 2f, is, Draw.alpha(INK, alpha));
    }

    /** Имя и расстояние раскрытой метки: строки строятся заново, только когда меняется имя или видимое число. */
    private static void texts(LabelPlan.Anim a, int i, double d) {
        Object src = sources[i];
        Object nameKey = src instanceof PointStore.Point p ? p.name
            : src instanceof works.repka.ryscore.world.Labels.Label l ? l.text().getString() : src;
        if (a.name == null || !java.util.Objects.equals(a.source, nameKey)) {
            String name;
            if (src instanceof PointStore.Point p) name = PointsLayer.name(p);
            else if (src instanceof works.repka.ryscore.world.Labels.Label l) name = (String) nameKey;
            else if (src instanceof MapApi.Target target && !target.label().getString().isEmpty()) name = target.label().getString();
            else name = Component.translatable("rysmap.labels.goal").getString();
            a.name = Draw.ellipsize(name, NAME_MAX, NAME_PX, Type.Weight.SEMIBOLD);
            a.nameWidth = Draw.labelWidth(a.name, NAME_PX, Type.Weight.SEMIBOLD);
            a.source = nameKey;
        }
        int key = d < 10_000 ? (int) Math.round(d) : 1_000_000 + (int) (d / 100);
        if (a.distText == null || a.distKey != key) {
            a.distText = Words.distance(d);
            a.distWidth = Draw.labelWidth(a.distText, DIST_PX, Type.Weight.REGULAR);
            a.distKey = key;
        }
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** Для автотеста: сколько меток отобрано в последнем кадре, сколько на экране и чья раскрыта у прицела. */
    public static String debugState() {
        int on = 0;
        for (int k = 0; k < PLAN.chosenCount(); k++) if (PLAN.onScreen(PLAN.chosen(k))) on++;
        int f = PLAN.focused();
        Object key = f >= 0 ? PLAN.key(f) : null;
        return "chosen=" + PLAN.chosenCount() + " onScreen=" + on + " focused=" + key;
    }

    /** Для автотеста: раскрыта ли (полностью) метка с этим ключом. */
    public static boolean expanded(Object key) {
        for (int k = 0; k < PLAN.chosenCount(); k++) {
            int i = PLAN.chosen(k);
            if (PLAN.key(i).equals(key)) return PLAN.anim(i) != null && PLAN.anim(i).expand >= 0.99f;
        }
        return false;
    }

    /** Для автотеста: раскрыта ли полностью цель пути, которая не своя точка. */
    public static boolean spotGoalExpanded() {
        return expanded(SPOT_GOAL);
    }
}
