package works.repka.rysmap.screen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import works.repka.ryscore.map.MapApi;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.theme.ThemeManager;
import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.ui.Icons;
import works.repka.ryscore.ui.Kit;
import works.repka.ryscore.ui.ScaledScreen;
import works.repka.ryscore.ui.Scroller;
import works.repka.ryscore.ui.Type.Weight;
import works.repka.rysmap.client.ClientEntry;
import works.repka.rysmap.client.MapSession;
import works.repka.rysmap.hud.Minimap;
import works.repka.rysmap.layer.Layers;
import works.repka.rysmap.point.PointStore;
import works.repka.rysmap.point.PointsLayer;

/**
 * Окно карты (макет {@code 10}, урезано по плану 13.2). Шапка 28: плитка, имя мира, вкладка «Карта», шестерёнка
 * настроек, крестик. Сайдбар 122: «Мир» со списком измерений, «Слои» с глазом и числом меток, «Точки» по группам
 * «Мои» и «Гибель» с расстоянием, внизу «Точка здесь». Полотно: тайлы, области слоёв с обводкой групп, метки
 * с подписями; справа сверху справка и масштаб, слева снизу «Ко мне · Пробел» и координаты с биомом под курсором,
 * справа снизу линейка. Тянется мышью, колесо приближает к курсору, правая кнопка открывает меню (пункты модов из
 * {@link MapApi#menus()} и «Скопировать координаты»). Окно открывается сразу: недостающие тайлы приходят следом.
 */
public final class MapScreen extends ScaledScreen {
    static final int W = 600, H = 364, HEADER = 28, SIDEBAR = 122;
    /** Единиц холста на блок: от дальнего плана до крупного. */
    private static final double MIN_ZOOM = 0.125, MAX_ZOOM = 8;
    private static final int MENU_ROW = 13, MENU_W = 128, ROW = 14, TOOL = 15;
    private static final int[] SCALE_STEPS = {10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000};

    private final Kit.Hits hits = new Kit.Hits();
    private final Kit.Dropdown dimDrop = new Kit.Dropdown();
    private final Scroller listScroll = new Scroller();
    ResourceKey<Level> dimension;
    double centerX, centerZ, zoom = 1;
    private boolean dragging, dragged;
    int wx, wy, ww, wh, cx0, cy0, cw, ch;
    private @Nullable Menu menu;
    private final List<String> dimensions = new ArrayList<>();
    private int listTop, listBottom;
    private @Nullable List<String> tip;
    /** Выбранная точка: панель справа и пунктир от игрока. */
    private java.util.@Nullable UUID selected;
    private @Nullable PointEditor editor;
    private Kit.@Nullable Dialog dialog;
    /** Открыта страница настроек вместо полотна (шестерёнка в шапке). */
    private @Nullable SettingsPage settings;

    private record Menu(int x, int y, MapApi.Spot spot, List<MapApi.MenuItem> items) {}

    private MapScreen(ResourceKey<Level> dimension, double x, double z) {
        super(Component.translatable("rysmap.screen.title"));
        this.dimension = dimension;
        this.centerX = x;
        this.centerZ = z;
    }

    /** Открыть на игроке. */
    public static void open() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || ClientEntry.session() == null) return;
        Minecraft.getInstance().setScreen(new MapScreen(p.level().dimension(), p.getX(), p.getZ()));
    }

    /** Открыть на цели ({@link MapApi#open}). */
    public static void open(MapApi.Target target) {
        if (ClientEntry.session() == null) return;
        MapScreen s = new MapScreen(target.dimension(), target.x(), target.z());
        s.zoom = 2;
        Minecraft.getInstance().setScreen(s);
    }

    @Override
    protected void init() {
        super.init();
        ww = Math.min(W, width - 16);
        wh = Math.min(H, height - 16);
        wx = (width - ww) / 2;
        wy = (height - wh) / 2;
        cx0 = wx + SIDEBAR;
        cy0 = wy + HEADER;
        cw = ww - SIDEBAR;
        ch = wh - HEADER;
        if (editor != null) for (works.repka.ryscore.ui.Field f : editor.fields()) addWidget(f);
        scanDimensions();
    }

    private void scanDimensions() {
        dimensions.clear();
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) dimensions.add(p.level().dimension().location().toString());
        MapSession s = ClientEntry.session();
        if (s == null) return;
        Path root = s.store.root();
        if (!Files.isDirectory(root)) return;
        try (Stream<Path> walk = Files.walk(root, 4)) {
            walk.filter(f -> f.getFileName().toString().equals("palette.json")).forEach(f -> {
                Path rel = root.relativize(f.getParent());
                if (rel.getNameCount() < 2) return;
                StringBuilder id = new StringBuilder(rel.getName(0).toString()).append(':');
                for (int i = 1; i < rel.getNameCount(); i++) id.append(i > 1 ? "/" : "").append(rel.getName(i));
                if (!dimensions.contains(id.toString())) dimensions.add(id.toString());
            });
        } catch (IOException ignored) {
            // список измерений не главное: текущее уже есть
        }
    }

    // ---- рисование ----

    @Override
    protected void draw(GuiGraphics g, int mx, int my, float partial) {
        hits.clear();
        tip = null;
        Theme t = ThemeManager.get();
        Theme canvas = slate();
        g.fill(0, 0, width, height, t.c("overlay"));
        t.panel(g, wx, wy, ww, wh);
        MapSession s = ClientEntry.session();
        if (s == null) return;

        if (settings != null) {
            header(g, t, s, mx, my);
            settings.draw(g, t, hits, this, mx, my);
            if (tip != null) Kit.tooltip(g, t, tip, mx, my);
            return;
        }
        canvas(g, t, canvas, s, mx, my, partial);
        PointStore.Point sel = selectedPoint();
        if (sel != null) {
            @SuppressWarnings("unchecked") List<String>[] panelTip = new List[1];
            PointPanel.draw(g, t, hits, this, sel, mx, my, panelTip);
            if (panelTip[0] != null) tip = panelTip[0];
        }
        sidebar(g, t, s, mx, my);
        header(g, t, s, mx, my);
        if (editor != null) editor.draw(g, t, hits, this, mx, my);

        if (dimDrop.open) {
            List<Kit.Option> options = new ArrayList<>();
            int selected = 0;
            for (int i = 0; i < dimensions.size(); i++) {
                options.add(new Kit.Option(dimensionName(dimensions.get(i))));
                if (dimensions.get(i).equals(dimension.location().toString())) selected = i;
            }
            Kit.dropdownList(g, t, hits, dimDrop, options, selected, SIDEBAR - 14, mx, my, i -> switchTo(dimensions.get(i)));
        }
        if (menu != null) menu(g, t, mx, my);
        else if (tip != null && !dimDrop.open && dialog == null && (editor == null || !editor.over(mx, my))) Kit.tooltip(g, t, tip, mx, my);
        if (dialog != null) Kit.dialog(g, t, hits, dialog, mx, my);
    }

    private void header(GuiGraphics g, Theme t, MapSession s, int mx, int my) {
        int y = wy;
        Draw.round(g, wx + 8, y + 7, 14, 14, 4, 0xFF4F8F6A);
        Icons.draw(g, Icons.Shape.COMPASS, wx + 11, y + 10, 8, 0xFFFFFFFF);
        String world = s.store.key().name();
        float bx = wx + 8 + 14 + 4.5f;
        String shown = Draw.ellipsize(world, 110, 15, Weight.SEMIBOLD);
        Draw.label(g, shown, bx, y + HEADER / 2f, 15, Weight.SEMIBOLD, t.c("text.primary"));
        int tx = (int) (bx + Draw.labelWidth(shown, 15, Weight.SEMIBOLD) + 9);
        Kit.sectionTab(g, t, hits, tx, y, HEADER, Component.translatable("rysmap.screen.tab.map").getString(), settings == null, mx, my, this::closeSettings);
        int closeX = wx + ww - 7 - 18;
        Kit.close(g, t, hits, closeX, y + 5, mx, my, this::onClose);
        int gearX = closeX - 4 - 18;
        boolean hot = Kit.over(mx, my, gearX, y + 5, 18, 18);
        boolean on = settings != null;
        if (t.beveled()) {
            if (on) t.pressed(g, gearX, y + 5, 18, 18, 0);
            else t.button(g, gearX, y + 5, 18, 18, hot, false);
        } else {
            Draw.roundBox(g, gearX, y + 5, 18, 18, 4.5f, hot ? Draw.mix(t.c("button.bg"), t.c("text.primary"), 0.08f) : t.c("button.bg"),
                on ? t.c("text.secondary") : t.c("button.border"));
        }
        Icons.draw(g, Icons.Shape.GEAR, gearX + 4.5f, y + 9.5f, 9, hot || on ? t.c("text.primary") : t.c("button.fg"));
        hits.add(gearX, y + 5, 18, 18, b -> {
            if (settings == null) openSettings();
            else closeSettings();
        });
        if (hot) tip = List.of(Component.translatable("rysmap.screen.settings").getString());
        Kit.divider(g, t, wx, wy + HEADER - 0.5f, ww);
    }

    void openSettings() {
        closeEditor();
        menu = null;
        dimDrop.close();
        settings = new SettingsPage();
    }

    void closeSettings() {
        settings = null;
    }

    /** Для автотеста: страница настроек. */
    public void settingsForTest() {
        openSettings();
    }

    private void sidebar(GuiGraphics g, Theme t, MapSession s, int mx, int my) {
        int top = wy + HEADER, bottom = wy + wh;
        float r = t.beveled() ? 0 : t.r("panel.radius");
        if (t.beveled()) {
            t.sidebar(g, wx, top, SIDEBAR, bottom - top);
        } else {
            // левый нижний угол повторяет скругление окна
            Draw.rect(g, wx + 1, top, SIDEBAR - 1, (int) (bottom - top - r - 1), t.c("sidebar.bg"));
            Draw.round(g, wx + 1, bottom - 2 * r - 1, SIDEBAR - 1, 2 * r, r - 1, t.c("sidebar.bg"));
            Draw.rect(g, wx + SIDEBAR - (int) r, (int) (bottom - 2 * r - 1), (int) r, (int) (2 * r), t.c("sidebar.bg"));
            Draw.rect(g, wx + SIDEBAR - 1, top, 1, bottom - top - 1, t.c("panel.border"));
        }
        int x0 = wx + 4, rw = SIDEBAR - 8;
        int y = top;
        Kit.caps(g, t, Component.translatable("rysmap.screen.world").getString(), wx + 7, y + 10, 11, 0.44f);
        y += 20;
        Kit.dropdown(g, t, hits, dimDrop, wx + 7, y, SIDEBAR - 14, dimensionName(dimension.location().toString()), false, mx, my);
        y += Kit.DROP_H + 2;

        // слои
        List<MapApi.Layer> layers = MapApi.layers();
        if (!layers.isEmpty()) {
            Kit.caps(g, t, Component.translatable("rysmap.screen.layers").getString(), wx + 9, y + 6 + 3.25f, 11, 0.44f);
            y += 15;
            for (MapApi.Layer layer : layers) {
                boolean on = Layers.visible(layer);
                boolean hot = Kit.over(mx, my, x0, y, rw, ROW);
                if (hot) Draw.round(g, x0, y, rw, ROW, 3.5f, t.c("slot.hover"));
                int fg = on ? t.c("text.secondary") : t.c("text.dim");
                Icons.draw(g, layer.icon(), x0 + 4.5f, y + 3, 8, on ? t.c("text.muted") : t.c("text.dim"));
                int count = Layers.markers(layer, dimension).size();
                String ct = count > 0 ? Integer.toString(count) : "";
                float ctw = Draw.labelWidth(ct, 11, Weight.REGULAR);
                Draw.label(g, Draw.ellipsize(layer.name().getString(), rw - 30 - ctw, 12.5f, Weight.REGULAR), x0 + 17, y + ROW / 2f, 12.5f, Weight.REGULAR, hot ? t.c("text.primary") : fg);
                if (!ct.isEmpty()) Draw.label(g, ct, x0 + rw - 17 - ctw, y + ROW / 2f, 11, Weight.REGULAR, on ? t.c("text.muted") : t.c("text.dim"));
                eye(g, t, x0 + rw - 13, y + 3, on);
                hits.add(x0, y, rw, ROW, b -> Layers.setVisible(layer, !on));
                y += ROW;
            }
        }

        // точки
        int footer = bottom - 24;
        Kit.caps(g, t, Component.translatable("rysmap.screen.points").getString(), wx + 9, y + 6 + 3.25f, 11, 0.44f);
        y += 15;
        listTop = y;
        listBottom = footer - 1;
        List<PointStore.Point> mine = new ArrayList<>(), deaths = new ArrayList<>();
        String dim = dimension.location().toString();
        for (PointStore.Point p : s.points().points()) {
            if (!p.dimension.equals(dim)) continue;
            (p.death() ? deaths : mine).add(p);
        }
        LocalPlayer player = Minecraft.getInstance().player;
        Comparator<PointStore.Point> near = Comparator.comparingDouble(p -> distance(player, p));
        mine.sort(near);
        deaths.sort(Comparator.comparingLong((PointStore.Point p) -> p.created).reversed());
        int content = 0;
        if (!mine.isEmpty()) content += 10 + mine.size() * ROW;
        if (!deaths.isEmpty()) content += 10 + deaths.size() * ROW;
        if (content == 0) content = 12;
        float scroll = listScroll.update(Math.max(0, content - (listBottom - listTop)));
        Draw.scissor(g, wx, listTop, wx + SIDEBAR - 1, listBottom);
        hits.clip(listTop, listBottom);
        float ly = listTop - scroll;
        if (mine.isEmpty() && deaths.isEmpty()) {
            Draw.label(g, Component.translatable("rysmap.screen.points.empty").getString(), x0 + 4, ly + 6, 11, Weight.REGULAR, t.c("text.dim"));
        }
        ly = group(g, t, Component.translatable("rysmap.screen.points.mine").getString(), mine, x0, rw, ly, player, mx, my);
        group(g, t, Component.translatable("rysmap.screen.points.deaths").getString(), deaths, x0, rw, ly, player, mx, my);
        hits.unclip();
        g.disableScissor();

        // низ: «Точка здесь»
        Draw.rect(g, wx + 1, footer, SIDEBAR - 2, 1, t.c("panel.border"));
        String label = Component.translatable("rysmap.screen.point_here").getString();
        toolButton(g, t, wx + 6, footer + 5, SIDEBAR - 12, 14, Icons.Shape.PLUS, label, keyName(ClientEntry.NEW_POINT), true, mx, my, this::pointHere);
    }

    private float group(GuiGraphics g, Theme t, String title, List<PointStore.Point> points, int x0, int rw, float y, @Nullable LocalPlayer player, int mx, int my) {
        if (points.isEmpty()) return y;
        Draw.label(g, title, x0 + 4, y + 3.5f + 2.75f, 11, Weight.REGULAR, t.c("text.dim"));
        String n = Integer.toString(points.size());
        Draw.label(g, n, x0 + rw - 4 - Draw.labelWidth(n, 11, Weight.REGULAR), y + 6.25f, 11, Weight.REGULAR, t.c("text.dim"));
        y += 10;
        for (PointStore.Point p : points) {
            int iy = Math.round(y);
            boolean hot = Kit.over(mx, my, x0, iy, rw, ROW) && my >= listTop && my < listBottom;
            boolean sel = isSelected(p);
            if (sel) Draw.round(g, x0, y, rw, ROW, 3.5f, t.c("tab.active.bg"));
            else if (hot) Draw.round(g, x0, y, rw, ROW, 3.5f, t.c("button.bg"));
            Draw.disc(g, x0 + 8.5f, y + ROW / 2f, 4.5f, PointsLayer.color(p));
            Icons.draw(g, PointsLayer.icon(p.icon), x0 + 6f, y + ROW / 2f - 2.5f, 5, 0xFF15181F);
            String d = player != null && player.level().dimension().location().toString().equals(p.dimension) ? Words.distance(distance(player, p)) : "";
            float dw = Draw.labelWidth(d, 11, Weight.REGULAR);
            Draw.label(g, Draw.ellipsize(PointsLayer.name(p), rw - 22 - dw, 12.5f, Weight.REGULAR), x0 + 17, y + ROW / 2f, 12.5f, Weight.REGULAR,
                sel || hot ? t.c("text.primary") : t.c("text.secondary"));
            if (!d.isEmpty()) Draw.label(g, d, x0 + rw - 4 - dw, y + ROW / 2f, 11, Weight.REGULAR, t.c("text.muted"));
            hits.add(x0, iy, rw, ROW, b -> selectPoint(p, true));
            y += ROW;
        }
        return y;
    }

    private static double distance(@Nullable LocalPlayer player, PointStore.Point p) {
        if (player == null) return 0;
        double dx = player.getX() - (p.x + 0.5), dz = player.getZ() - (p.z + 0.5);
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Глаз слоя: открытый, у выключенного тусклый и перечёркнутый. */
    private static void eye(GuiGraphics g, Theme t, float x, float y, boolean on) {
        int c = on ? t.c("text.secondary") : t.c("text.dim");
        Icons.draw(g, Icons.Shape.EYE, x, y, 8, c);
        if (!on) Draw.line(g, x + 0.5f, y + 7.5f, x + 7.5f, y + 0.5f, 0.9f, c);
    }

    /** Кнопка-инструмент (макет {@code .tb}, {@code .btn.sm}): значок, подпись, клавиша в рамке. */
    int toolButton(GuiGraphics g, Theme t, int x, int y, int w, int h, @Nullable Icons.Shape icon, String label, @Nullable String key,
                   boolean sidebarBg, int mx, int my, Runnable action) {
        float iconW = icon == null ? 0 : 7 + 3;
        float keyW = key == null ? 0 : Draw.labelWidth(key, 11, Weight.REGULAR) + 5 + 3;
        float textW = Draw.labelWidth(label, 12, Weight.MEDIUM);
        if (w <= 0) w = (int) Math.ceil(5 + iconW + textW + keyW + 5);
        boolean hot = Kit.over(mx, my, x, y, w, h);
        if (t.beveled()) {
            t.button(g, x, y, w, h, hot, false);
        } else {
            int fill = sidebarBg ? t.c("button.bg") : t.c("sidebar.bg");
            if (hot) fill = Draw.mix(fill, t.c("text.primary"), 0.08f);
            Draw.roundBox(g, x, y, w, h, Math.min(t.r("button.radius"), h / 2f), fill, t.c("button.border"));
        }
        float cx = x + (w - (iconW + textW + keyW)) / 2f;
        int fg = hot ? t.c("text.primary") : t.c("text.secondary");
        if (icon != null) {
            Icons.draw(g, icon, cx, y + (h - 7) / 2f, 7, fg);
            cx += iconW;
        }
        Draw.label(g, label, cx, y + h / 2f, 12, Weight.MEDIUM, fg);
        cx += textW + 3;
        if (key != null) kbd(g, t, key, cx, y + h / 2f);
        hits.add(x, y, w, h, b -> {
            if (b == 0) action.run();
        });
        return w;
    }

    /** Клавиша в рамке (макет {@code .kbd}): кегль 11, высота 8. */
    static float kbd(GuiGraphics g, Theme t, String key, float x, float cy) {
        float w = Draw.labelWidth(key, 11, Weight.REGULAR) + 5;
        if (t.beveled()) Draw.rect(g, (int) x, (int) (cy - 4), (int) w, 8, t.c("button.bg"));
        else Draw.roundBox(g, x, cy - 4, w, 8, 2.5f, t.c("button.bg"), t.c("button.border"));
        Draw.label(g, key, x + 2.5f, cy, 11, Weight.REGULAR, t.c("text.muted"));
        return w;
    }

    static @Nullable String keyName(net.minecraft.client.KeyMapping key) {
        if (key.isUnbound()) return null;
        return key.getTranslatedKeyMessage().getString();
    }

    private void canvas(GuiGraphics g, Theme t, Theme canvas, MapSession s, int mx, int my, float partial) {
        float r = t.beveled() ? 0 : t.r("panel.radius");
        Draw.rect(g, cx0, cy0, cw - (int) r, ch - 1, canvas.c("map.bg"));
        Draw.round(g, cx0 + cw - 2 * r - 1, cy0, 2 * r, ch - 1, r - 1, canvas.c("map.bg"));
        String dim = dimension.location().toString();
        s.tiles.draw(g, dim, centerX, centerZ, zoom, cx0, cy0, cw, ch, clips(r));
        areas(g);
        selectionLine(g, partial);
        MapApi.Marker hover = markers(g, canvas, mx, my);
        player(g, partial);
        tools(g, t, mx, my);

        boolean overCanvas = Kit.over(mx, my, cx0, cy0, cw, ch) && menu == null && !dimDrop.open && !overTools(mx, my);
        if (hover != null && overCanvas) {
            List<String> lines = new ArrayList<>();
            lines.add(hover.label().getString());
            if (hover.note() != null) lines.add(hover.note().getString());
            if (hover.tooltip() != null) {
                try {
                    for (Component c : hover.tooltip().get()) lines.add(c.getString());
                } catch (Throwable ignored) {
                    // подсказка чужого слоя упала: показываем то, что есть
                }
            }
            tip = lines;
        } else if (overCanvas && !dragging) {
            int chx = Math.floorDiv((int) Math.floor(blockX(mx)), 16), chz = Math.floorDiv((int) Math.floor(blockZ(my)), 16);
            List<Component> chunkTip = Layers.tooltip(dimension, chx, chz);
            if (!chunkTip.isEmpty()) {
                List<String> lines = new ArrayList<>();
                for (Component c : chunkTip) lines.add(c.getString());
                tip = lines;
            }
        }
    }

    /** Полосы обрезки тайлов: правый нижний угол полотна повторяет скругление окна. */
    private float[] clips(float r) {
        float x0 = cx0, y0 = cy0, x1 = cx0 + cw - 1, y1 = cy0 + ch - 1;
        if (r <= 0.5f) return new float[]{x0, y0, x1, y1};
        int rows = (int) Math.ceil(r);
        float[] out = new float[(rows + 1) * 4];
        int n = 0;
        out[n++] = x0; out[n++] = y0; out[n++] = x1; out[n++] = y1 - rows;
        for (int i = 0; i < rows; i++) {
            float dy = i + 0.5f;
            float inset = r - (float) Math.sqrt(Math.max(0, r * r - dy * dy));
            out[n++] = x0; out[n++] = y1 - i - 1; out[n++] = x1 - inset; out[n++] = y1 - i;
        }
        return out;
    }

    /** Правый край видимого полотна: открытая панель точки закрывает его часть. */
    int visibleRight() {
        return cx0 + cw - (selectedPoint() != null ? PointPanel.W : 0);
    }

    private boolean overTools(int mx, int my) {
        int right = visibleRight();
        return Kit.over(mx, my, right - 6 - TOOL * 3 - 6, cy0 + 6, TOOL * 3 + 6, TOOL)
            || Kit.over(mx, my, cx0 + 6, cy0 + ch - 6 - TOOL, 170, TOOL)
            || Kit.over(mx, my, right - 6 - 110, cy0 + ch - 6 - TOOL, 110, TOOL)
            || mx >= right;
    }

    private void tools(GuiGraphics g, Theme t, int mx, int my) {
        // справа сверху: справка, минус, плюс
        int right = visibleRight();
        int y = cy0 + 6, x = right - 6 - TOOL;
        square(g, t, x, y, Icons.Shape.PLUS, mx, my, () -> zoomAt(cx0 + cw / 2.0, cy0 + ch / 2.0, 1));
        x -= TOOL + 3;
        square(g, t, x, y, Icons.Shape.MINUS, mx, my, () -> zoomAt(cx0 + cw / 2.0, cy0 + ch / 2.0, -1));
        x -= TOOL + 3;
        boolean hot = square(g, t, x, y, null, mx, my, () -> {});
        Draw.info(g, x + 3.5f, y + 3.5f, 8, hot ? t.c("text.primary") : t.c("text.secondary"));
        if (hot) tip = List.of(Component.translatable("rysmap.screen.help").getString());

        // слева снизу: «Ко мне · Пробел», координаты и биом под курсором
        int by = cy0 + ch - 6 - TOOL;
        int bw = toolButton(g, t, cx0 + 6, by, 0, TOOL, Icons.Shape.TARGET, Component.translatable("rysmap.screen.to_me").getString(),
            Component.translatable("rysmap.screen.key.space").getString(), false, mx, my, this::toPlayer);
        // справа снизу: линейка; координаты под курсором не заходят на неё (при открытой панели биом прячется первым)
        int blocks = SCALE_STEPS[0];
        for (int step : SCALE_STEPS) if (step * zoom <= 100) blocks = step;
        float bar = (float) (blocks * zoom);
        String label = Words.plural(blocks, "rysmap.screen.blocks");
        float lw = Draw.labelWidth(label, 11.5f, Weight.REGULAR);
        float w = 5 + bar + 4 + lw + 5;
        float sx = right - 6 - w;
        boolean overCanvas = Kit.over(mx, my, cx0, cy0, right - cx0, ch);
        int px = cx0 + 6 + bw + 3;
        if (overCanvas) {
            int bx = (int) Math.floor(blockX(mx)), bz = (int) Math.floor(blockZ(my));
            String biome = biome(bx, bz);
            float base = 5 + coordWidth("X", bx) + 5 + coordWidth("Z", bz) + 5;
            float cwid = base + (biome.isEmpty() ? 0 : 5 + Draw.labelWidth(biome, 12, Weight.REGULAR) + 5);
            if (px + cwid > sx - 4) {
                biome = "";
                cwid = base;
            }
            if (px + cwid <= right - 6) {
                pill(g, t, px, by, cwid, TOOL);
                float cx = px + 5;
                cx = coord(g, t, "X", bx, cx, by + TOOL / 2f) + 5;
                cx = coord(g, t, "Z", bz, cx, by + TOOL / 2f) + 5;
                if (!biome.isEmpty()) {
                    Draw.rect(g, (int) cx, by + 3, 1, TOOL - 6, t.c("panel.border"));
                    Draw.label(g, biome, cx + 5, by + TOOL / 2f, 12, Weight.REGULAR, t.c("text.muted"));
                }
                if (px + cwid > sx - 4) return;
            }
        }
        if (sx < px) return;
        pill(g, t, sx, by, w, TOOL);
        int lc = t.c("text.secondary");
        float ly = by + TOOL / 2f + 1.5f;
        Draw.rect(g, (int) (sx + 5), (int) ly, (int) bar, 1, lc);
        Draw.rect(g, (int) (sx + 5), (int) ly - 3, 1, 3, lc);
        Draw.rect(g, (int) (sx + 5 + bar) - 1, (int) ly - 3, 1, 3, lc);
        Draw.label(g, label, sx + 5 + bar + 4, by + TOOL / 2f, 11.5f, Weight.REGULAR, t.c("text.muted"));
    }

    private boolean square(GuiGraphics g, Theme t, int x, int y, @Nullable Icons.Shape icon, int mx, int my, Runnable action) {
        boolean hot = Kit.over(mx, my, x, y, TOOL, TOOL);
        if (t.beveled()) {
            t.button(g, x, y, TOOL, TOOL, hot, false);
        } else {
            int fill = t.c("sidebar.bg");
            if (hot) fill = Draw.mix(fill, t.c("text.primary"), 0.08f);
            Draw.roundBox(g, x, y, TOOL, TOOL, Math.min(t.r("button.radius"), TOOL / 2f), fill, t.c("button.border"));
        }
        if (icon != null) Icons.draw(g, icon, x + 3.5f, y + 3.5f, 8, hot ? t.c("text.primary") : t.c("text.secondary"));
        hits.add(x, y, TOOL, TOOL, b -> {
            if (b == 0) action.run();
        });
        return hot;
    }

    private static void pill(GuiGraphics g, Theme t, float x, float y, float w, float h) {
        if (t.beveled()) Draw.bevel(g, x, y, w, h, t.c("sidebar.bg"), t.c("panel.bevel.dark"), t.c("panel.bevel.light"), 1, false);
        else Draw.roundBox(g, x, y, w, h, Math.min(t.r("button.radius"), h / 2f), t.c("sidebar.bg"), t.c("panel.border"));
    }

    private static float coordWidth(String axis, int v) {
        return Draw.labelWidth(axis, 10.5f, Weight.SEMIBOLD) + 2 + Draw.labelWidth(Minimap.group(v), 12, Weight.REGULAR);
    }

    private static float coord(GuiGraphics g, Theme t, String axis, int v, float x, float cy) {
        Draw.label(g, axis, x, cy, 10.5f, Weight.SEMIBOLD, t.c("text.dim"));
        float ax = x + Draw.labelWidth(axis, 10.5f, Weight.SEMIBOLD) + 2;
        String s = Minimap.group(v);
        Draw.label(g, s, ax, cy, 12, Weight.REGULAR, t.c("text.secondary"));
        return ax + Draw.labelWidth(s, 12, Weight.REGULAR);
    }

    /** Биом под курсором: только в прогруженных чанках своего измерения (клиент знает биомы только там). */
    private String biome(int x, int z) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().equals(dimension)) return "";
        if (!mc.level.getChunkSource().hasChunk(x >> 4, z >> 4)) return "";
        var holder = mc.level.getBiome(new BlockPos(x, mc.player == null ? 64 : mc.player.getBlockY(), z));
        return holder.unwrapKey().map(k -> Component.translatable("biome." + k.location().getNamespace() + "." + k.location().getPath()).getString()).orElse("");
    }

    static String dimensionName(String id) {
        String key = "rysmap.dimension." + id.replace(':', '.').replace('/', '.');
        Component c = Component.translatable(key);
        String s = c.getString();
        return s.equals(key) ? id : s;
    }

    private void areas(GuiGraphics g) {
        double halfW = cw / 2.0 / zoom, halfH = ch / 2.0 / zoom;
        int chx0 = (int) Math.floor((centerX - halfW) / 16), chx1 = (int) Math.floor((centerX + halfW) / 16);
        int chz0 = (int) Math.floor((centerZ - halfH) / 16), chz1 = (int) Math.floor((centerZ + halfH) / 16);
        if ((long) (chx1 - chx0) * (chz1 - chz0) > 64 * 64 * 16) return; // слишком далеко: области не рисуются
        double ox = cx0 + cw / 2.0 - centerX * zoom, oz = cy0 + ch / 2.0 - centerZ * zoom;
        for (MapApi.Layer layer : Layers.shown()) {
            if (layer.areas() == null) continue;
            Layers.drawAreas(g, layer, dimension, chx0 - 1, chz0 - 1, chx1 + 1, chz1 + 1, ox, oz, zoom, cx0, cy0, cx0 + cw - 1, cy0 + ch - 1);
        }
    }

    boolean isSelected(PointStore.Point p) {
        return p.id.equals(selected);
    }

    /** Выбранная точка или null (её удалили или выбора нет). */
    @Nullable
    PointStore.Point selectedPoint() {
        MapSession s = ClientEntry.session();
        if (selected == null || s == null) return null;
        PointStore.Point p = s.points().find(selected);
        if (p == null) selected = null;
        return p;
    }

    void select(java.util.@Nullable UUID id) {
        selected = id;
    }

    /** Выбор точки из списка или с полотна: панель справа; из списка карта встаёт на точку левее панели. */
    void selectPoint(PointStore.Point p, boolean center) {
        selected = p.id;
        if (center) {
            switchTo(p.dimension);
            centerX = p.x + 0.5 + PointPanel.W / 2.0 / zoom;
            centerZ = p.z + 0.5;
        }
    }

    /** Пунктир от игрока к выбранной точке (макет {@code 15}). */
    private void selectionLine(GuiGraphics g, float partial) {
        PointStore.Point p = selectedPoint();
        LocalPlayer pl = Minecraft.getInstance().player;
        if (p == null || pl == null || !p.dimension.equals(dimension.location().toString()) || !pl.level().dimension().equals(dimension)) return;
        float x0 = sx(net.minecraft.util.Mth.lerp(partial, pl.xo, pl.getX())), y0 = sy(net.minecraft.util.Mth.lerp(partial, pl.zo, pl.getZ())), x1 = sx(p.x + 0.5), y1 = sy(p.z + 0.5);
        float[] c = clipLine(x0, y0, x1, y1, cx0, cy0, visibleRight(), cy0 + ch);
        if (c != null) Draw.dashed(g, c[0], c[1], c[2], c[3], 1f, 3, 2.5f, 0xE6F7F8FB);
    }

    /** Отрезок, обрезанный прямоугольником (Лианг и Барски), или null. */
    private static float @Nullable [] clipLine(float x0, float y0, float x1, float y1, float minX, float minY, float maxX, float maxY) {
        float t0 = 0, t1 = 1, dx = x1 - x0, dy = y1 - y0;
        float[] p = {-dx, dx, -dy, dy}, q = {x0 - minX, maxX - x0, y0 - minY, maxY - y0};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) {
                if (q[i] < 0) return null;
                continue;
            }
            float r = q[i] / p[i];
            if (p[i] < 0) t0 = Math.max(t0, r);
            else t1 = Math.min(t1, r);
            if (t0 > t1) return null;
        }
        return new float[]{x0 + t0 * dx, y0 + t0 * dy, x0 + t1 * dx, y0 + t1 * dy};
    }

    // ---- окна точки ----

    /** Окно новой точки у места {@code spot}, рядом с экранной точкой {@code ax, ay}. */
    void openEditor(MapApi.Spot spot, int ax, int ay) {
        MapSession s = ClientEntry.session();
        if (s == null) return;
        int n = s.points().points().size() + 1;
        setEditor(new PointEditor(null, spot.dimension().location().toString(), spot.x(), spot.y(), spot.z(),
            Component.translatable("rysmap.point.numbered", n).getString(), n % PointStore.COLORS, ax, ay));
    }

    void edit(PointStore.Point p) {
        setEditor(new PointEditor(p, p.dimension, p.x, p.y, p.z, p.name, p.color, (int) sx(p.x + 0.5), (int) sy(p.z + 0.5)));
    }

    private void setEditor(PointEditor e) {
        closeEditor();
        editor = e;
        for (works.repka.ryscore.ui.Field f : e.fields()) addWidget(f);
        setFocused(e.name);
    }

    void closeEditor() {
        if (editor != null) for (works.repka.ryscore.ui.Field f : editor.fields()) removeWidget(f);
        editor = null;
        setFocused(null);
    }

    void submitEditor() {
        MapSession s = ClientEntry.session();
        if (editor == null || s == null) return;
        boolean isNew = editor.editing == null;
        PointStore.Point p = editor.result();
        if (p == null) return;
        if (isNew) s.points().add(p);
        else s.points().changed();
        s.savePoints();
        if (MapApi.get() instanceof works.repka.rysmap.api.MapApiImpl api && api.tracking(p.id)) trackOn(p);
        closeEditor();
        selected = p.id;
    }

    void pointsChanged() {
        MapSession s = ClientEntry.session();
        if (s == null) return;
        s.points().changed();
        s.savePoints();
    }

    /** «В путь» и «Снять путь». */
    void track(PointStore.Point p) {
        if (MapApi.get() instanceof works.repka.rysmap.api.MapApiImpl api && api.tracking(p.id)) api.track(null);
        else trackOn(p);
    }

    private static void trackOn(PointStore.Point p) {
        ResourceLocation loc = ResourceLocation.tryParse(p.dimension);
        if (loc == null || !(MapApi.get() instanceof works.repka.rysmap.api.MapApiImpl api)) return;
        api.trackPoint(p.id, MapApi.Target.builder(ResourceKey.create(Registries.DIMENSION, loc), p.x + 0.5, p.y, p.z + 0.5)
            .label(Component.literal(PointsLayer.name(p))).build());
    }

    void askDelete(PointStore.Point p) {
        dialog = new Kit.Dialog(Component.translatable("rysmap.point.delete.title", PointsLayer.name(p)).getString())
            .text(Component.translatable("rysmap.point.delete.text").getString())
            .no(Component.translatable("rysmap.point.cancel").getString(), () -> dialog = null)
            .yes(Component.translatable("rysmap.point.delete").getString(), () -> {
                dialog = null;
                delete(p);
            });
    }

    private void delete(PointStore.Point p) {
        MapSession s = ClientEntry.session();
        if (s == null) return;
        if (MapApi.get() instanceof works.repka.rysmap.api.MapApiImpl api && api.tracking(p.id)) api.track(null);
        s.points().remove(p.id);
        s.savePoints();
        selected = null;
    }

    /** Для автотеста: ответ «Удалить» в окне-вопросе. */
    public void confirmDeleteForTest() {
        PointStore.Point p = selectedPoint();
        dialog = null;
        if (p != null) delete(p);
    }

    /** Для автотеста: выбрать последнее место гибели. */
    public boolean selectDeathForTest() {
        MapSession s = ClientEntry.session();
        if (s == null) return false;
        PointStore.Point last = null;
        for (PointStore.Point p : s.points().points()) if (p.death()) last = p;
        if (last != null) selectPoint(last, true);
        return last != null;
    }

    /** Для автотеста: поле имени окна точки. */
    public void editorNameForTest(String name) {
        if (editor != null) editor.name.setValue(name);
    }

    /** Для автотеста: выбрать первую точку с таким именем, открыть правку или новую точку. */
    public boolean selectForTest(String name) {
        MapSession s = ClientEntry.session();
        if (s == null) return false;
        for (PointStore.Point p : s.points().points()) {
            if (p.name.equals(name)) {
                selectPoint(p, true);
                return true;
            }
        }
        return false;
    }

    public void newPointForTest() {
        int ax = cx0 + cw / 2 + 40, ay = cy0 + ch / 2 - 30;
        int bx = (int) Math.floor(blockX(ax)), bz = (int) Math.floor(blockZ(ay));
        openEditor(new MapApi.Spot(dimension, bx, 70, bz, bx >> 4, bz >> 4), ax, ay);
        if (editor != null) {
            editor.name.setValue("Дубрава");
            editor.icon = "star";
            editor.color = 2;
        }
    }

    public void editForTest() {
        PointStore.Point p = selectedPoint();
        if (p != null) edit(p);
    }

    public void deleteForTest() {
        PointStore.Point p = selectedPoint();
        if (p != null) askDelete(p);
    }


    private @Nullable MapApi.Marker markers(GuiGraphics g, Theme canvas, int mx, int my) {
        MapApi.Marker hover = null;
        for (MapApi.Layer layer : Layers.shown()) {
            for (MapApi.Marker m : Layers.markers(layer, dimension)) {
                float x = sx(m.x()), y = sy(m.z());
                if (x < cx0 + 5 || x > cx0 + cw - 5 || y < cy0 + 5 || y > cy0 + ch - 5) continue;
                boolean hot = Math.abs(mx - x) <= 6 && Math.abs(my - y) <= 6;
                if (hot) hover = m;
                marker(g, canvas, m, x, y, hot || isSelectedMarker(m));
            }
        }
        return hover;
    }

    boolean isSelectedMarker(MapApi.Marker m) {
        return m.id().equals(selected);
    }

    /** Метка: круг 11 с тёмной кромкой и значком, под ней подпись на плашке (макет {@code .wp}, {@code .wl}). */
    static void marker(GuiGraphics g, Theme canvas, MapApi.Marker m, float x, float y, boolean selected) {
        if (selected) Draw.disc(g, x, y, 8.5f, 0x38F7F8FB);
        Draw.disc(g, x, y, 6.25f, selected ? 0xFFF7F8FB : 0xCC0F1116);
        Draw.disc(g, x, y, 5.5f, m.color());
        if (m.icon() != null) Icons.draw(g, m.icon(), x - 3.5f, y - 3.5f, 7, 0xFF15181F);
        String label = m.label().getString();
        if (label.isEmpty()) return;
        float lw = Draw.labelWidth(label, 11, Weight.MEDIUM);
        float ly = y + 6.25f + 1.5f;
        Draw.round(g, x - lw / 2 - 3, ly, lw + 6, 8.5f, 2.5f, selected ? 0xFFF7F8FB : canvas.c("map.label.bg"));
        Draw.label(g, label, x - lw / 2, ly + 4.25f, 11, selected ? Weight.SEMIBOLD : Weight.MEDIUM, selected ? 0xFF15181F : canvas.c("map.label.fg"));
    }

    private void player(GuiGraphics g, float partial) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || !p.level().dimension().equals(dimension)) return;
        float x = sx(net.minecraft.util.Mth.lerp(partial, p.xo, p.getX())), y = sy(net.minecraft.util.Mth.lerp(partial, p.zo, p.getZ()));
        if (x < cx0 || x > cx0 + cw || y < cy0 || y > cy0 + ch) return;
        Draw.disc(g, x, y, 5.5f, 0x90101216);
        Icons.drawRotated(g, Icons.Shape.NAV, x - 6, y - 6, 12, p.getViewYRot(partial) + 180f, 0xFFF7F8FB);
    }

    private void menu(GuiGraphics g, Theme t, int mx, int my) {
        Menu m = menu;
        int h = m.items.size() * MENU_ROW + 6;
        int x = Math.min(m.x, width - MENU_W - 4), y = Math.min(m.y, height - h - 4);
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        t.tooltipBox(g, x, y, MENU_W, h);
        int ry = y + 3;
        for (MapApi.MenuItem item : m.items) {
            boolean enabled = item.enabled(m.spot);
            boolean hot = enabled && Kit.over(mx, my, x + 2, ry, MENU_W - 4, MENU_ROW);
            if (hot) Draw.round(g, x + 2, ry, MENU_W - 4, MENU_ROW, 3, t.c("slot.hover"));
            int color = enabled ? t.c("text.primary") : t.c("text.muted");
            if (item.icon() != null) Icons.draw(g, item.icon(), x + 6, ry + 2.5f, 8, color);
            Draw.label(g, Draw.ellipsize(item.label().getString(), MENU_W - 24, 12, Weight.REGULAR), x + 18, ry + MENU_ROW / 2f, 12, Weight.REGULAR, color);
            if (enabled) {
                hits.add(x + 2, ry, MENU_W - 4, MENU_ROW, b -> {
                    menu = null;
                    if (item.id().equals(PointsLayer.POINT_HERE)) {
                        // в окне карты «Точка здесь» открывает окно новой точки (макет 16)
                        openEditor(m.spot, (int) sx(m.spot.x() + 0.5), (int) sy(m.spot.z() + 0.5));
                        return;
                    }
                    try {
                        item.run(m.spot);
                    } catch (Throwable e) {
                        works.repka.rysmap.RYSMap.LOG.error("[rysmap] пункт меню {} упал", item.id(), e);
                    }
                });
            }
            ry += MENU_ROW;
        }
        g.pose().popPose();
    }

    // ---- координаты ----

    float sx(double blockX) {
        return (float) (cx0 + cw / 2.0 + (blockX - centerX) * zoom);
    }

    float sy(double blockZ) {
        return (float) (cy0 + ch / 2.0 + (blockZ - centerZ) * zoom);
    }

    double blockX(double screenX) {
        return centerX + (screenX - cx0 - cw / 2.0) / zoom;
    }

    double blockZ(double screenY) {
        return centerZ + (screenY - cy0 - ch / 2.0) / zoom;
    }

    static Theme slate() {
        for (Theme t : ThemeManager.all()) if (t.id.equals("ryscore:slate")) return t;
        return ThemeManager.get();
    }

    // ---- ввод ----

    void toPlayer() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        dimension = p.level().dimension();
        centerX = p.getX();
        centerZ = p.getZ();
    }

    void switchTo(String id) {
        ResourceLocation loc = ResourceLocation.tryParse(id);
        if (loc == null) return;
        ResourceKey<Level> next = ResourceKey.create(Registries.DIMENSION, loc);
        if (next.equals(dimension)) return;
        dimension = next;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null && p.level().dimension().equals(dimension)) toPlayer();
        else {
            centerX = 0;
            centerZ = 0;
        }
    }

    /** «Точка здесь» в сайдбаре: окно новой точки на месте игрока. */
    void pointHere() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        MapApi.Spot spot = new MapApi.Spot(p.level().dimension(), p.getBlockX(), p.getBlockY(), p.getBlockZ(), p.getBlockX() >> 4, p.getBlockZ() >> 4);
        float ax = sx(p.getX()), ay = sy(p.getZ());
        if (!p.level().dimension().equals(dimension) || ax < cx0 || ax > cx0 + cw || ay < cy0 || ay > cy0 + ch) {
            ax = cx0 + 20;
            ay = cy0 + ch / 2f;
        }
        openEditor(spot, (int) ax, (int) ay);
    }

    private void zoomAt(double x, double y, int dir) {
        double bx = blockX(x), bz = blockZ(y);
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, dir > 0 ? zoom * 2 : zoom / 2));
        // точка под курсором остаётся под курсором
        centerX = bx - (x - cx0 - cw / 2.0) / zoom;
        centerZ = bz - (y - cy0 - ch / 2.0) / zoom;
    }

    @Override
    protected boolean onClick(double x, double y, int button) {
        if (settings != null) {
            hits.click(x, y, button);
            return true;
        }
        if (dialog != null) {
            hits.click(x, y, button);
            return true;
        }
        if (editor != null && editor.over(x, y)) {
            for (works.repka.ryscore.ui.Field f : editor.fields()) {
                if (f.isMouseOver(x, y)) {
                    for (works.repka.ryscore.ui.Field o : editor.fields()) o.setFocused(false);
                    f.setFocused(true);
                    setFocused(f);
                    f.mouseClicked(x, y, button);
                    return true;
                }
            }
            hits.click(x, y, button);
            return true;
        }
        if (menu != null) {
            hits.click(x, y, button);
            menu = null;
            return true;
        }
        if (hits.click(x, y, button)) return true;
        if (!Kit.over(x, y, cx0, cy0, cw, ch)) return super.onClick(x, y, button);
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            openMenu((int) x, (int) y);
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            dragging = true;
            dragged = false;
            return true;
        }
        return false;
    }

    @Override
    protected boolean onRelease(double x, double y, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && dragging) {
            dragging = false;
            if (!dragged) clickMarker(x, y);
            return true;
        }
        return super.onRelease(x, y, button);
    }

    void clickMarker(double mx, double my) {
        for (MapApi.Layer layer : Layers.shown()) {
            for (MapApi.Marker m : Layers.markers(layer, dimension)) {
                if (Math.abs(mx - sx(m.x())) <= 6 && Math.abs(my - sy(m.z())) <= 6) {
                    if (markerClicked(layer, m)) return;
                    if (m.click() != null) {
                        m.click().run();
                        return;
                    }
                }
            }
        }
        canvasClicked();
    }

    /** Свои точки открывают панель; true, если обработано. */
    boolean markerClicked(MapApi.Layer layer, MapApi.Marker m) {
        if (!layer.id().equals(PointsLayer.ID)) return false;
        MapSession s = ClientEntry.session();
        PointStore.Point p = s == null ? null : s.points().find(m.id());
        if (p == null) return false;
        selectPoint(p, false);
        return true;
    }

    /** Клик по пустому месту полотна: закрыть окно точки, иначе снять выбор. */
    void canvasClicked() {
        if (editor != null) closeEditor();
        else selected = null;
    }

    @Override
    protected boolean onDrag(double x, double y, int button, double dx, double dy) {
        if (dragging && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            centerX -= dx / zoom;
            centerZ -= dy / zoom;
            if (Math.abs(dx) + Math.abs(dy) > 0.5) dragged = true;
            return true;
        }
        return super.onDrag(x, y, button, dx, dy);
    }

    @Override
    protected boolean onScroll(double x, double y, double scrollX, double scrollY) {
        if (settings != null) return settings.scrolled(x, y, scrollY);
        if (dimDrop.scrolled(x, y, scrollY)) return true;
        if (Kit.over(x, y, wx, listTop, SIDEBAR, listBottom - listTop)) {
            listScroll.by((float) (-scrollY * ROW * 2));
            return true;
        }
        if (!Kit.over(x, y, cx0, cy0, cw, ch) || scrollY == 0) return false;
        zoomAt(x, y, scrollY > 0 ? 1 : -1);
        return true;
    }

    /** Масштаб окна, единиц на блок (автотест, будущая настройка). */
    public void setZoom(double z) {
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, z));
    }

    /** Для автотеста: меню правой кнопки чуть правее и ниже середины полотна. */
    public void openMenuForTest() {
        openMenu(cx0 + cw / 2 + 30, cy0 + ch / 2 + 20);
    }

    void openMenu(int x, int y) {
        int bx = (int) Math.floor(blockX(x)), bz = (int) Math.floor(blockZ(y));
        LocalPlayer p = Minecraft.getInstance().player;
        int by = p != null ? p.getBlockY() : 64;
        MapApi.Spot spot = new MapApi.Spot(dimension, bx, by, bz, Math.floorDiv(bx, 16), Math.floorDiv(bz, 16));
        List<MapApi.MenuItem> items = new ArrayList<>();
        for (MapApi.MenuItem item : MapApi.menus()) {
            try {
                if (item.visible(spot)) items.add(item);
            } catch (Throwable ignored) {
                // пункт чужого мода упал на вопросе: не показываем
            }
        }
        items.add(MapApi.MenuItem.builder(ResourceLocation.fromNamespaceAndPath("rysmap", "copy"), Component.translatable("rysmap.menu.copy"))
            .icon(Icons.Shape.COPY).order(1000).run(s -> Minecraft.getInstance().keyboardHandler.setClipboard(s.x() + " " + s.y() + " " + s.z())).build());
        menu = new Menu(x, y, spot, items);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (dialog != null) return dialog.keyPressed(key);
        if (settings != null) {
            if (settings.keyPressed(key)) return true;
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                closeSettings();
                return true;
            }
            if (ClientEntry.OPEN.matches(key, scan)) {
                onClose();
                return true;
            }
            return true;
        }
        if (editor != null) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                submitEditor();
                return true;
            }
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                closeEditor();
                return true;
            }
            if (key == GLFW.GLFW_KEY_TAB) {
                editor.tab(this, hasShiftDown());
                return true;
            }
            for (works.repka.ryscore.ui.Field f : editor.fields()) {
                if (f.isFocused()) {
                    f.keyPressed(key, scan, modifiers);
                    return true;
                }
            }
        }
        if (dimDrop.keyPressed(key)) return true;
        if (key == GLFW.GLFW_KEY_ESCAPE && selected != null) {
            selected = null;
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && menu != null) {
            menu = null;
            return true;
        }
        if (key == GLFW.GLFW_KEY_SPACE) {
            toPlayer();
            return true;
        }
        if (ClientEntry.OPEN.matches(key, scan)) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
