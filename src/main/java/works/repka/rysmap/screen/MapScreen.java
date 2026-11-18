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

    /** Чужая вкладка окна ({@link MapApi#tab}) вместо карты или null. */
    private MapApi.@Nullable Tab tab;
    /** Режим полотна ({@link MapApi#mode}) и его вид, пока он включён; null: «Обзор». */
    private MapApi.@Nullable Mode mode;
    private MapApi.@Nullable ModeView modeView;
    private final CanvasSurface surface = new CanvasSurface();
    private boolean modeMouse;
    /** Длинный список точек (макет 25): раскрыт на всю высоту, порядок, свёрнутые группы, выбранные Shift+кликом. */
    private boolean listExpanded;
    private int listSort;
    private final java.util.Set<String> collapsed = new java.util.HashSet<>();
    private final java.util.Set<java.util.UUID> multi = new java.util.LinkedHashSet<>();
    /** «Поверхность | Пещеры» (М5) и этаж, который листается Ctrl+колесом. */
    boolean caves;
    int floor;
    /** Чужая метка с панелью ({@link MapApi.Marker#panel()}), открытая кликом. */
    private MapApi.@Nullable Marker panelMarker;
    /** Поиск в шапке (макет 14) и его место в прошлом кадре. */
    private final MapSearch search = new MapSearch();
    private int searchX, searchY;
    /** Клавиша / поставила курсор в поиск: её символ в поле не пишется. */
    private boolean skipSlash;

    /** Мышь в прошлом кадре, единицы окна: клавиша T телепортирует под курсор. */
    private int lastMx = -1, lastMy = -1;
    static final ResourceLocation TELEPORT_ITEM = ResourceLocation.fromNamespaceAndPath("rysmap", "teleport");

    private MapScreen(ResourceKey<Level> dimension, double x, double z) {
        super(Component.translatable("rysmap.screen.title"));
        // правило семьи: база 480×270, когда 640×400 не помещается при 100 % (макеты 80–87)
        useCompactBase();
        this.dimension = dimension;
        this.centerX = x;
        this.centerZ = z;
    }

    /** Открыть на игроке; под землёй сразу на этаже игрока. */
    public static void open() {
        LocalPlayer p = Minecraft.getInstance().player;
        MapSession session = ClientEntry.session();
        if (p == null || session == null) return;
        MapScreen s = new MapScreen(p.level().dimension(), p.getX(), p.getZ());
        Integer cave = session.caveFloor();
        s.floor = works.repka.rysmap.scan.Floors.of(p.getBlockY());
        s.caves = cave != null;
        Minecraft.getInstance().setScreen(s);
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
        int side = compact() ? RAIL : SIDEBAR;
        cx0 = wx + side;
        cy0 = wy + HEADER;
        cw = ww - side;
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
        lastMx = mx;
        lastMy = my;
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
            search.drawResults(g, t, hits, this, searchX, searchY, mx, my);
            if (tip != null && !search.focused()) Kit.tooltip(g, t, tip, mx, my);
            return;
        }
        if (tab != null && !safeAvailable(tab)) setTab(null);
        if (tab != null) {
            MapApi.Tab current = tab;
            try {
                current.render(g, t, wx, wy + HEADER, ww, wh - HEADER, compact(), hits, mx, my);
            } catch (Throwable e) {
                works.repka.rysmap.RYSMap.LOG.error("[rysmap] вкладка {} упала", current.id(), e);
                tab = null;
            }
            header(g, t, s, mx, my);
            search.drawResults(g, t, hits, this, searchX, searchY, mx, my);
            if (tip != null && !search.focused()) Kit.tooltip(g, t, tip, mx, my);
            return;
        }
        canvas(g, t, canvas, s, mx, my, partial);
        PointStore.Point sel = selectedPoint();
        MapApi.Panel foreign = sel == null ? markerPanel() : null;
        if (foreign != null) MarkerPanel.draw(g, t, hits, this, panelMarker, foreign, mx, my);
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
        else search.drawResults(g, t, hits, this, searchX, searchY, mx, my);
    }

    private void header(GuiGraphics g, Theme t, MapSession s, int mx, int my) {
        int y = wy;
        Draw.round(g, wx + 8, y + 7, 14, 14, 4, 0xFF4F8F6A);
        Icons.draw(g, Icons.Shape.COMPASS, wx + 11, y + 10, 8, 0xFFFFFFFF);
        String world = s.store.key().name();
        float bx = wx + 8 + 14 + 4.5f;
        // сжатая раскладка: имя мира спрятано (макет 80)
        String shown = compact() ? "" : Draw.ellipsize(world, 110, 15, Weight.SEMIBOLD);
        Draw.label(g, shown, bx, y + HEADER / 2f, 15, Weight.SEMIBOLD, t.c("text.primary"));
        int tx = (int) (bx + Draw.labelWidth(shown, 15, Weight.SEMIBOLD) + 9);
        tx += Kit.sectionTab(g, t, hits, tx, y, HEADER, Component.translatable("rysmap.screen.tab.map").getString(), settings == null && tab == null, mx, my, () -> {
            closeSettings();
            setTab(null);
        });
        for (MapApi.Tab other : MapApi.tabs()) {
            if (!safeAvailable(other)) continue;
            tx += Kit.sectionTab(g, t, hits, tx, y, HEADER, other.name().getString(), settings == null && tab == other, mx, my, () -> {
                closeSettings();
                setTab(other);
            });
        }
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
        searchX = gearX - 6 - MapSearch.W;
        searchY = y + 5;
        if (searchX > tx + 8) search.drawField(g, t, hits, this, searchX, searchY, mx, my);
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

    /** Для автотеста: строка в поиске; возвращает заголовок первого результата или пусто. */
    public String searchForTest(String q) {
        search.focus(true);
        search.field.setValue(q);
        List<MapSearch.Result> r = search.compute(q, this);
        return r.isEmpty() ? "" : r.get(0).title();
    }

    /** Для автотеста: страница настроек. */
    public void settingsForTest() {
        openSettings();
    }

    private void sidebar(GuiGraphics g, Theme t, MapSession s, int mx, int my) {
        int top = wy + HEADER, bottom = wy + wh;
        if (compact() && !railOpen) {
            rail(g, t, top, bottom, mx, my);
            return;
        }
        if (compact()) {
            // раскрытая рейка ложится поверх полотна; клик мимо закрывает её
            g.pose().pushPose();
            g.pose().translate(0, 0, 200);
            hits.add(wx, top, SIDEBAR, bottom - top, b -> {});
        }
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
        y = caveSwitch(g, t, wx + 7, y + 3, SIDEBAR - 14, mx, my) + 2;

        // слои
        List<MapApi.Layer> layers = MapApi.layers();
        if (!layers.isEmpty() && !listExpanded) {
            Kit.caps(g, t, Component.translatable("rysmap.screen.layers").getString(), wx + 9, y + 6 + 3.25f, 11, 0.44f);
            y += 15;
            for (MapApi.Layer layer : layers) {
                boolean on = Layers.visible(layer);
                boolean hot = Kit.over(mx, my, x0, y, rw, ROW);
                if (hot) Draw.round(g, x0, y, rw, ROW, 3.5f, t.c("slot.hover"));
                int fg = on ? t.c("text.secondary") : t.c("text.dim");
                Icons.draw(g, layer.icon(), x0 + 4.5f, y + 3, 8, on ? t.c("text.muted") : t.c("text.dim"));
                int count = layer instanceof works.repka.rysmap.hud.Entities.Layer el ? el.count(dimension) : Layers.markers(layer, dimension).size();
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
        // заголовок «Точки»: клик раскрывает список на всю высоту; справа порядок
        String ptitle = Component.translatable("rysmap.screen.points").getString();
        boolean hotTitle = Kit.over(mx, my, wx + 4, y + 2, 60, 12);
        Kit.capsColored(g, ptitle, wx + 9, y + 6 + 3.25f, 11, 0.44f, hotTitle ? t.c("text.primary") : t.c("text.dim"));
        Icons.draw(g, listExpanded ? Icons.Shape.DOWN : Icons.Shape.UP, wx + 9 + Draw.labelWidth(ptitle.toUpperCase(java.util.Locale.ROOT), 11, Weight.MEDIUM) + ptitle.length() * 0.44f + 3, y + 6, 6, t.c("text.dim"));
        hits.add(wx + 4, y + 2, 60, 12, b -> listExpanded = !listExpanded);
        if (hotTitle) tip = List.of(Component.translatable(listExpanded ? "rysmap.list.collapse" : "rysmap.list.expand").getString());
        String sortName = Component.translatable("rysmap.list.sort." + listSort).getString();
        float sw = Draw.labelWidth(sortName, 11, Weight.REGULAR);
        boolean hotSort = Kit.over(mx, my, (int) (wx + SIDEBAR - 9 - sw - 2), y + 2, (int) sw + 4, 12);
        Draw.label(g, sortName, wx + SIDEBAR - 9 - sw, y + 6 + 3.25f, 11, Weight.REGULAR, hotSort ? t.c("text.primary") : t.c("link"));
        hits.add((int) (wx + SIDEBAR - 9 - sw - 2), y + 2, (int) sw + 4, 12, b -> listSort = (listSort + 1) % 3);
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
        mine.sort(switch (listSort) {
            case 1 -> Comparator.comparing((PointStore.Point p) -> PointsLayer.name(p).toLowerCase(java.util.Locale.ROOT));
            case 2 -> Comparator.comparingLong((PointStore.Point p) -> p.created).reversed();
            default -> near;
        });
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
        multi.removeIf(id -> s.points().find(id) == null);
        if (!multi.isEmpty()) {
            int half = (SIDEBAR - 12 - 3) / 2;
            String hide = Component.translatable("rysmap.list.hide", multi.size()).getString();
            String del = Component.translatable("rysmap.list.delete", multi.size()).getString();
            toolButton(g, t, wx + 6, footer + 5, half, 14, Icons.Shape.EYE, hide, null, true, mx, my, this::hideMulti);
            toolButton(g, t, wx + 6 + half + 3, footer + 5, SIDEBAR - 12 - half - 3, 14, null, del, null, true, mx, my, this::askDeleteMulti);
        } else {
            String label = Component.translatable("rysmap.screen.point_here").getString();
            toolButton(g, t, wx + 6, footer + 5, SIDEBAR - 12, 14, Icons.Shape.PLUS, label, keyName(ClientEntry.NEW_POINT), true, mx, my, this::pointHere);
        }
        if (compact()) g.pose().popPose();
    }

    private float group(GuiGraphics g, Theme t, String title, List<PointStore.Point> points, int x0, int rw, float y, @Nullable LocalPlayer player, int mx, int my) {
        if (points.isEmpty()) return y;
        boolean folded = collapsed.contains(title);
        int ty = Math.round(y);
        boolean hotGroup = Kit.over(mx, my, x0, ty, rw, 10) && my >= listTop && my < listBottom;
        Icons.draw(g, folded ? Icons.Shape.CHEVRON_RIGHT : Icons.Shape.CHEVRON_DOWN, x0 + 1, y + 2.5f, 6, t.c("text.dim"));
        Draw.label(g, title, x0 + 8, y + 3.5f + 2.75f, 11, Weight.REGULAR, hotGroup ? t.c("text.primary") : t.c("text.dim"));
        String n = Integer.toString(points.size());
        Draw.label(g, n, x0 + rw - 4 - Draw.labelWidth(n, 11, Weight.REGULAR), y + 6.25f, 11, Weight.REGULAR, t.c("text.dim"));
        hits.add(x0, ty, rw, 10, b -> {
            if (!collapsed.remove(title)) collapsed.add(title);
        });
        y += 10;
        if (folded) return y;
        for (PointStore.Point p : points) {
            int iy = Math.round(y);
            boolean hot = Kit.over(mx, my, x0, iy, rw, ROW) && my >= listTop && my < listBottom;
            boolean sel = isSelected(p) || multi.contains(p.id);
            if (sel) Draw.round(g, x0, y, rw, ROW, 3.5f, t.c("tab.active.bg"));
            else if (hot) Draw.round(g, x0, y, rw, ROW, 3.5f, t.c("button.bg"));
            Draw.disc(g, x0 + 8.5f, y + ROW / 2f, 4.5f, PointsLayer.color(p));
            Icons.draw(g, PointsLayer.icon(p.icon), x0 + 6f, y + ROW / 2f - 2.5f, 5, 0xFF15181F);
            String d = player != null && player.level().dimension().location().toString().equals(p.dimension) ? Words.distance(distance(player, p)) : "";
            float dw = Draw.labelWidth(d, 11, Weight.REGULAR);
            Draw.label(g, Draw.ellipsize(PointsLayer.name(p), rw - 22 - dw, 12.5f, Weight.REGULAR), x0 + 17, y + ROW / 2f, 12.5f, Weight.REGULAR,
                sel || hot ? t.c("text.primary") : t.c("text.secondary"));
            if (!d.isEmpty()) Draw.label(g, d, x0 + rw - 4 - dw, y + ROW / 2f, 11, Weight.REGULAR, t.c("text.muted"));
            hits.add(x0, iy, rw, ROW, b -> {
                // Shift+клик выбирает несколько; обычный клик снимает выбор и открывает панель
                if (hasShiftDown()) {
                    if (!multi.remove(p.id)) multi.add(p.id);
                    selected = null;
                } else {
                    multi.clear();
                    selectPoint(p, true);
                }
            });
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
        String dim = tileId();
        s.viewFloor(caves && playerHere() ? floor : null);
        // дальний план (М6): мельче 0,5 единицы на блок рисуются уменьшенные тайлы и сетка регионов
        boolean far = zoom < FAR_ZOOM;
        works.repka.rysmap.tile.TileTextures layer = far ? s.overview : s.tiles;
        layer.brightness(caves ? 1f : MapSession.nightBrightness(Minecraft.getInstance().level, partial));
        layer.draw(g, dim, centerX, centerZ, zoom, cx0, cy0, cw, ch, clips(r));
        layer.brightness(1f);
        if (far) regionGrid(g, canvas);
        // подсветка биомов (М7, макет 20): карта приглушена, подходящие места цветом подсветки
        MapApi.Highlight hl = caves ? null : s.syncHighlight();
        if (hl != null) {
            Draw.rect(g, cx0, cy0, cw - (int) r, ch - 1, 0x8C101216);
            s.highlight.draw(g, dim, centerX, centerZ, zoom, cx0, cy0, cw, ch, clips(r));
        }
        MapApi.ModeView view = modeView;
        if (view != null && safe(view::dimTiles, false)) Draw.rect(g, cx0, cy0, cw - (int) r, ch - 1, 0x99101216);
        if (view == null || safe(view::drawAreas, true)) areas(g);
        if (view != null) {
            try {
                view.render(g, canvas, surface, mx, my);
            } catch (Throwable e) {
                works.repka.rysmap.RYSMap.LOG.error("[rysmap] режим {} упал", mode == null ? "?" : mode.id(), e);
                setMode(null);
            }
        }
        selectionLine(g, partial);
        MapApi.Marker hover = markers(g, canvas, mx, my);
        works.repka.rysmap.hud.Entities.Dot hoverDot = hover == null ? entities(g, canvas, mx, my, partial) : null;
        player(g, partial);
        modes(g, t, mx, my);
        highlightChip(g, t, mx, my);
        tools(g, t, mx, my);
        modePanel(g, t, mx, my);
        message(g, t, mx, my);

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
        } else if (hoverDot != null && overCanvas) {
            List<String> lines = new ArrayList<>();
            lines.add(hoverDot.label());
            LocalPlayer me = Minecraft.getInstance().player;
            if (me != null && me.level().dimension().equals(dimension)) {
                double dx = hoverDot.x() - me.getX(), dz = hoverDot.z() - me.getZ();
                lines.add(Words.distance(Math.sqrt(dx * dx + dz * dz)) + " · " + Words.direction(dx, dz));
            }
            if (hoverDot.kind() == works.repka.rysmap.hud.Entities.Kind.TEAM) lines.add(Component.translatable("rysmap.entity.team").getString());
            tip = lines;
        } else if (overCanvas && !dragging && modeTooltip(mx, my) != null) {
            tip = modeTooltip(mx, my);
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
        MapApi.PanelView pv = modeView == null ? null : safe(modeView::panel, null);
        if (pv != null) return cx0 + cw - pv.width();
        return cx0 + cw - (selectedPoint() != null || markerPanel() != null ? PointPanel.W : 0);
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

    public static String dimensionName(String id) {
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

    /** Выбранные Shift+кликом: убрать с миникарты. */
    private void hideMulti() {
        MapSession s = ClientEntry.session();
        if (s == null) return;
        for (java.util.UUID id : multi) {
            PointStore.Point p = s.points().find(id);
            if (p != null) p.minimap = false;
        }
        pointsChanged();
        multi.clear();
    }

    private void askDeleteMulti() {
        int n = multi.size();
        dialog = new Kit.Dialog(Component.translatable("rysmap.list.delete.title", n).getString())
            .text(Component.translatable("rysmap.point.delete.text").getString())
            .no(Component.translatable("rysmap.point.cancel").getString(), () -> dialog = null)
            .yes(Component.translatable("rysmap.point.delete").getString(), () -> {
                dialog = null;
                MapSession s = ClientEntry.session();
                if (s == null) return;
                for (java.util.UUID id : new java.util.ArrayList<>(multi)) {
                    PointStore.Point p = s.points().find(id);
                    if (p != null) delete(p);
                }
                multi.clear();
            });
    }

    /** Для автотеста: длинный список раскрыт и порядок. */
    public void listForTest(boolean expanded, int sort) {
        listExpanded = expanded;
        listSort = sort;
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

    /**
     * Игроки и мобы на полотне: голова 10 с подписью имени у игрока, точка у моба. Возвращает сущность под курсором
     * (игрок важнее моба).
     */
    private works.repka.rysmap.hud.Entities.@Nullable Dot entities(GuiGraphics g, Theme canvas, int mx, int my, float partial) {
        int right = visibleRight();
        double x0 = blockX(cx0), x1 = blockX(right), z0 = blockZ(cy0), z1 = blockZ(cy0 + ch);
        works.repka.rysmap.hud.Entities.Dot hover = null;
        float mobR = (float) Math.max(1.5, Math.min(3, zoom * 0.9));
        for (works.repka.rysmap.hud.Entities.Dot d : works.repka.rysmap.hud.Entities.collect(dimension, x0, z0, x1, z1, partial)) {
            float x = sx(d.x()), y = sy(d.z());
            if (x < cx0 + 5 || x > right - 5 || y < cy0 + 5 || y > cy0 + ch - 5) continue;
            if (d.player()) {
                boolean hot = Math.abs(mx - x) <= 6 && Math.abs(my - y) <= 6;
                if (hot) hover = d;
                works.repka.rysmap.hud.Entities.head(g, d, x, y, 10);
                String label = d.name();
                float lw = Draw.labelWidth(label, 11, Weight.MEDIUM);
                float ly = y + 6 + 1.5f;
                Draw.round(g, x - lw / 2 - 3, ly, lw + 6, 8.5f, 2.5f, canvas.c("map.label.bg"));
                Draw.label(g, label, x - lw / 2, ly + 4.25f, 11, Weight.MEDIUM, canvas.c("map.label.fg"));
            } else {
                if (hover == null && Math.abs(mx - x) <= 3 && Math.abs(my - y) <= 3) hover = d;
                works.repka.rysmap.hud.Entities.mob(g, d, x, y, mobR);
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

    /** Плашка подсветки сверху полотна: «Подсветка: Равнины» и крестик, который её снимает. */
    private void highlightChip(GuiGraphics g, Theme t, int mx, int my) {
        MapApi.Highlight hl = MapApi.highlighted();
        if (hl == null || caves) return;
        String text = Component.translatable("rysmap.highlight", hl.title()).getString();
        float tw = Draw.labelWidth(text, 12, Weight.MEDIUM);
        int w = (int) Math.ceil(8 + 8 + 4 + tw + 6 + 10 + 4), h = 16;
        int x = (int) (cx0 + (visibleRight() - cx0 - w) / 2f), y = cy0 + 6;
        Draw.roundBox(g, x, y, w, h, 4.5f, Draw.alpha(t.c("panel.bg"), 0.94f), Draw.alpha(hl.color() | 0xFF000000, 0.8f));
        Draw.disc(g, x + 10, y + h / 2f, 3.5f, hl.color() | 0xFF000000);
        Draw.label(g, text, x + 18, y + h / 2f, 12, Weight.MEDIUM, t.c("text.primary"));
        int crossX = x + w - 14;
        boolean hot = Kit.over(mx, my, crossX - 2, y + 2, 12, 12);
        Draw.cross(g, crossX, y + 5, hot ? t.c("text.primary") : t.c("text.muted"));
        hits.add(x, y, w, h, b -> {});
        hits.add(crossX - 2, y + 2, 12, 12, b -> MapApi.get().highlight(null));
    }

    /** С какого масштаба (единиц на блок) окно переходит на дальний план. */
    static final double FAR_ZOOM = 0.5;

    /** Сетка регионов 512×512 на дальнем плане (макет {@code 12}). */
    private void regionGrid(GuiGraphics g, Theme canvas) {
        int right = cx0 + cw - 1, bottom = cy0 + ch - 1;
        int rx0 = (int) Math.floor(blockX(cx0) / 512), rx1 = (int) Math.ceil(blockX(right) / 512);
        int rz0 = (int) Math.floor(blockZ(cy0) / 512), rz1 = (int) Math.ceil(blockZ(bottom) / 512);
        int n = (rx1 - rx0 + 1) + (rz1 - rz0 + 1);
        if (n > 200) return;
        float[] xy = new float[n * 4];
        int k = 0;
        for (int rx = rx0; rx <= rx1; rx++) {
            float x = sx(rx * 512.0);
            if (x <= cx0 || x >= right) continue;
            xy[k * 4] = x; xy[k * 4 + 1] = cy0; xy[k * 4 + 2] = x; xy[k * 4 + 3] = bottom;
            k++;
        }
        for (int rz = rz0; rz <= rz1; rz++) {
            float y = sy(rz * 512.0);
            if (y <= cy0 || y >= bottom) continue;
            xy[k * 4] = cx0; xy[k * 4 + 1] = y; xy[k * 4 + 2] = right; xy[k * 4 + 3] = y;
            k++;
        }
        if (k > 0) Draw.lines(g, xy, k, 0.5f, Draw.alpha(canvas.c("map.grid") | 0xFF000000, 0.35f));
    }

    // ---- сжатая раскладка (М9) ----

    static final int RAIL = 24;
    /** Рейка раскрыта в полный сайдбар поверх полотна. */
    private boolean railOpen;

    /**
     * Рейка сжатой раскладки (макет {@code 80}): развернуть, мир, слои, точки, игроки; внизу «Точка здесь». Любой значок
     * раскрывает полный сайдбар поверх полотна.
     */
    private void rail(GuiGraphics g, Theme t, int top, int bottom, int mx, int my) {
        float r = t.beveled() ? 0 : t.r("panel.radius");
        if (t.beveled()) t.sidebar(g, wx, top, RAIL, bottom - top);
        else {
            Draw.rect(g, wx + 1, top, RAIL - 1, (int) (bottom - top - r - 1), t.c("sidebar.bg"));
            Draw.round(g, wx + 1, bottom - 2 * r - 1, RAIL - 1, 2 * r, r - 1, t.c("sidebar.bg"));
            Draw.rect(g, wx + RAIL - (int) r, (int) (bottom - 2 * r - 1), (int) r, (int) (2 * r), t.c("sidebar.bg"));
            Draw.rect(g, wx + RAIL - 1, top, 1, bottom - top - 1, t.c("panel.border"));
        }
        Icons.Shape[] icons = {Icons.Shape.SIDEBAR, Icons.Shape.COMPASS, Icons.Shape.LAYERS, Icons.Shape.FLAG, Icons.Shape.USERS};
        String[] tips = {"rysmap.rail.open", "rysmap.screen.world", "rysmap.screen.layers", "rysmap.screen.points", "rysmap.layer.players"};
        int y = top + 6;
        for (int i = 0; i < icons.length; i++) {
            int iy = y + i * 17 + (i > 0 ? 6 : 0);
            boolean hot = Kit.over(mx, my, wx + 3, iy, RAIL - 6, 15);
            if (hot) Draw.round(g, wx + 3, iy, RAIL - 6, 15, 3.5f, t.c("slot.hover"));
            Icons.draw(g, icons[i], wx + RAIL / 2f - 4, iy + 3.5f, 8, hot ? t.c("text.primary") : t.c("text.muted"));
            hits.add(wx + 3, iy, RAIL - 6, 15, b -> railOpen = true);
            if (hot) tip = List.of(Component.translatable(tips[i]).getString());
        }
        int py = bottom - 6 - 15;
        boolean hot = Kit.over(mx, my, wx + 3, py, RAIL - 6, 15);
        if (hot) Draw.round(g, wx + 3, py, RAIL - 6, 15, 3.5f, t.c("slot.hover"));
        Icons.draw(g, Icons.Shape.PLUS, wx + RAIL / 2f - 4, py + 3.5f, 8, hot ? t.c("text.primary") : t.c("text.muted"));
        hits.add(wx + 3, py, RAIL - 6, 15, b -> pointHere());
        if (hot) tip = List.of(Component.translatable("rysmap.screen.point_here").getString());
    }

    /** Подсказка от частей окна (страница настроек). */
    void tooltip(List<String> lines) {
        tip = lines;
    }

    /** Для автотеста: раскрыть рейку. */
    public void railForTest(boolean open) {
        railOpen = open;
    }

    // ---- этажи пещер (М5) ----

    /** id тайлов полотна: поверхность или этаж. */
    String tileId() {
        String dim = dimension.location().toString();
        return caves ? works.repka.rysmap.scan.Floors.id(dim, floor) : dim;
    }

    private boolean playerHere() {
        LocalPlayer p = Minecraft.getInstance().player;
        return p != null && p.level().dimension().equals(dimension);
    }

    void stepFloor(int d) {
        MapSession s = ClientEntry.session();
        int lo = s != null && playerHere() ? s.minFloor() : -4, hi = s != null && playerHere() ? s.maxFloor() : 19;
        floor = Math.max(lo, Math.min(hi, floor + d));
    }

    /**
     * «Поверхность | Пещеры» в сайдбаре (макеты {@code 21}, {@code 22}) и строка этажа со стрелками. В Нижнем мире
     * «Срез | Этажи». Сервер может выключить пещеры: переключатель тогда серый с подсказкой. Возвращает низ.
     */
    private int caveSwitch(GuiGraphics g, Theme t, int x, int y, int w, int mx, int my) {
        boolean allowed = works.repka.rysmap.client.ServerRules.cavesAllowed();
        if (!allowed) caves = false;
        MapSession s = ClientEntry.session();
        boolean nether = s != null && playerHere() && s.ceiling();
        String a = Component.translatable(nether ? "rysmap.cave.slice" : "rysmap.cave.surface").getString();
        String b = Component.translatable(nether ? "rysmap.cave.floors" : "rysmap.cave.caves").getString();
        int h = 16, half = (w - 4) / 2;
        if (t.beveled()) t.input(g, x, y, w, h, false);
        else Draw.roundBox(g, x, y, w, h, 4.5f, t.c("input.bg"), t.c("panel.border"));
        cavePill(g, t, x + 2, y + 2, half, Icons.Shape.SUN, a, !caves, true, mx, my, () -> caves = false);
        cavePill(g, t, x + 2 + half, y + 2, w - 4 - half, Icons.Shape.CAVE, b, caves, allowed, mx, my, () -> caves = true);
        if (!allowed && Kit.over(mx, my, x + 2 + half, y + 2, w - 4 - half, h - 4))
            tip = List.of(Component.translatable("rysmap.cave.off_server").getString());
        y += h + 2;
        if (!caves) return y;
        String text = Component.translatable("rysmap.cave.floor_short", Minimap.group(floor),
            Minimap.group(works.repka.rysmap.scan.Floors.bottom(floor)), Minimap.group(works.repka.rysmap.scan.Floors.top(floor))).getString();
        Draw.label(g, Draw.ellipsize(text, w - 30, 11.5f, Weight.REGULAR), x + 2, y + 6, 11.5f, Weight.REGULAR, t.c("text.secondary"));
        int bx = x + w - 26;
        boolean hotDown = Kit.over(mx, my, bx, y, 12, 12), hotUp = Kit.over(mx, my, bx + 14, y, 12, 12);
        Icons.draw(g, Icons.Shape.DOWN, bx + 2, y + 2, 8, hotDown ? t.c("text.primary") : t.c("text.muted"));
        Icons.draw(g, Icons.Shape.UP, bx + 16, y + 2, 8, hotUp ? t.c("text.primary") : t.c("text.muted"));
        hits.add(bx, y, 12, 12, btn -> stepFloor(-1));
        hits.add(bx + 14, y, 12, 12, btn -> stepFloor(1));
        if (hotDown || hotUp) tip = List.of(Component.translatable("rysmap.cave.wheel").getString());
        return y + 13;
    }

    private void cavePill(GuiGraphics g, Theme t, int x, int y, int w, Icons.Shape icon, String label, boolean active, boolean enabled, int mx, int my, Runnable run) {
        int h = 12;
        boolean hot = enabled && Kit.over(mx, my, x, y, w, h);
        if (active) Draw.round(g, x, y, w, h, 3.5f, t.c("filter.active.bg"));
        else if (hot) Draw.round(g, x, y, w, h, 3.5f, Draw.alpha(t.c("filter.active.bg"), 0.5f));
        int fg = !enabled ? t.c("text.dim") : active ? t.c("text.primary") : t.c("text.muted");
        String shown = Draw.ellipsize(label, w - 14, 11.5f, active ? Weight.MEDIUM : Weight.REGULAR);
        float tw = Draw.labelWidth(shown, 11.5f, active ? Weight.MEDIUM : Weight.REGULAR);
        float cx = x + (w - tw - 9) / 2f;
        Icons.draw(g, icon, cx, y + 2.5f, 7, fg);
        Draw.label(g, shown, cx + 9, y + h / 2f, 11.5f, active ? Weight.MEDIUM : Weight.REGULAR, fg);
        if (enabled) hits.add(x, y, w, h, b -> run.run());
    }

    /** Для автотеста: пещеры и этаж. */
    public void cavesForTest(boolean on, int f) {
        caves = on;
        floor = f;
    }

    // ---- вкладки и режимы модов (ядро 1.12) ----

    private static <T> T safe(java.util.function.Supplier<T> call, T fallback) {
        try {
            T v = call.get();
            return v == null ? fallback : v;
        } catch (Throwable e) {
            works.repka.rysmap.RYSMap.LOG.error("[rysmap] вкладка или режим мода упали", e);
            return fallback;
        }
    }

    private static boolean safeAvailable(MapApi.Tab t) {
        return safe(t::available, false);
    }

    private @Nullable List<String> modeTooltip(int mx, int my) {
        MapApi.ModeView view = modeView;
        if (view == null) return null;
        List<Component> lines = safe(() -> view.tooltip(surface, mx, my), null);
        if (lines == null) return null;
        List<String> out = new ArrayList<>();
        for (Component c : lines) out.add(c.getString());
        return out;
    }

    /** Данные панели чужой метки этого кадра или null (метка в другом измерении, мод вернул null или упал). */
    private MapApi.@Nullable Panel markerPanel() {
        MapApi.Marker m = panelMarker;
        if (m == null || m.panel() == null || !m.dimension().equals(dimension)) return null;
        MapApi.Panel p = safe(() -> m.panel().get(), null);
        if (p == null) panelMarker = null;
        return p;
    }

    void closeMarkerPanel() {
        panelMarker = null;
    }

    MapApi.@Nullable Tab tab() {
        return tab;
    }

    /** Поле окна точки или поиска в фокусе: клавиши идут туда. */
    private boolean anyFieldFocused() {
        if (search.focused()) return true;
        if (editor != null) for (works.repka.ryscore.ui.Field f : editor.fields()) if (f.isFocused()) return true;
        return false;
    }

    /** Карта к месту результата поиска; своя точка открывает панель. */
    void goTo(ResourceKey<Level> dim, double x, double z, PointStore.@Nullable Point point) {
        closeSettings();
        setTab(null);
        menu = null;
        dimension = dim;
        centerX = x;
        centerZ = z;
        if (point != null) selectPoint(point, true);
    }

    /** Вкладка мода или карта (null). */
    public void setTab(MapApi.@Nullable Tab next) {
        if (next == tab) return;
        MapApi.Tab old = tab;
        if (old != null) safe(() -> {
            old.closed();
            return Boolean.TRUE;
        }, Boolean.TRUE);
        tab = next;
        menu = null;
        if (next != null) safe(() -> {
            next.opened();
            return Boolean.TRUE;
        }, Boolean.TRUE);
    }

    /** Режим полотна или «Обзор» (null). */
    public void setMode(MapApi.@Nullable Mode next) {
        MapApi.ModeView old = modeView;
        if (old != null) safe(() -> {
            old.close();
            return Boolean.TRUE;
        }, Boolean.TRUE);
        modeView = null;
        mode = null;
        modeMouse = false;
        if (next != null) {
            MapApi.ModeView v = safe(() -> next.open(surface), null);
            if (v != null) {
                mode = next;
                modeView = v;
                selected = null;
                zoom = Math.max(zoom, minZoom());
            }
        }
    }

    @Override
    public void removed() {
        MapSession session = ClientEntry.session();
        if (session != null) session.viewFloor(null);
        setMode(null);
        setTab(null);
        super.removed();
    }

    /** Для {@link works.repka.rysmap.api.MapApiImpl#openTab}. */
    public static void openTab(ResourceLocation id) {
        if (!(Minecraft.getInstance().screen instanceof MapScreen)) open();
        if (Minecraft.getInstance().screen instanceof MapScreen s)
            for (MapApi.Tab t : MapApi.tabs()) if (t.id().equals(id) && safeAvailable(t)) s.setTab(t);
    }

    /** Для {@link works.repka.rysmap.api.MapApiImpl#openMode}. */
    public static void openMode(ResourceLocation id, MapApi.@Nullable Target at) {
        if (at != null) open(at);
        else if (!(Minecraft.getInstance().screen instanceof MapScreen)) open();
        if (Minecraft.getInstance().screen instanceof MapScreen s) {
            s.setTab(null);
            for (MapApi.Mode m : MapApi.modes()) if (m.id().equals(id) && safe(m::available, false)) s.setMode(m);
        }
    }

    /** «Обзор | режимы модов» слева сверху полотна (макет {@code 10}); без режимов ничего. */
    private void modes(GuiGraphics g, Theme t, int mx, int my) {
        List<MapApi.Mode> list = new ArrayList<>();
        for (MapApi.Mode m : MapApi.modes()) if (safe(m::available, false)) list.add(m);
        if (list.isEmpty()) {
            if (mode != null) setMode(null);
            return;
        }
        int x = cx0 + 6, y = cy0 + 6;
        String overview = Component.translatable("rysmap.screen.mode.overview").getString();
        float w = 4 + modePillWidth(overview, mode == null);
        for (MapApi.Mode m : list) w += 1 + modePillWidth(m.name().getString(), mode == m);
        int bw = (int) Math.ceil(w), bh = 18;
        hits.add(x, y, bw, bh, b -> {});
        if (t.beveled()) t.input(g, x, y, bw, bh, false);
        else Draw.roundBox(g, x, y, bw, bh, 4.5f, Draw.alpha(t.c("panel.bg"), 0.92f), t.c("panel.border"));
        int px = x + 2;
        px += modePill(g, t, px, y + 2, Icons.Shape.COMPASS, overview, mode == null, mx, my, () -> setMode(null)) + 1;
        for (MapApi.Mode m : list) px += modePill(g, t, px, y + 2, m.icon(), m.name().getString(), mode == m, mx, my, () -> setMode(m)) + 1;
    }

    private static int modePillWidth(String label, boolean active) {
        return (int) Math.ceil(Draw.labelWidth(label, 12.5f, active ? Weight.MEDIUM : Weight.REGULAR)) + 12 + 12;
    }

    private int modePill(GuiGraphics g, Theme t, int x, int y, Icons.Shape icon, String label, boolean active, int mx, int my, Runnable run) {
        Weight weight = active ? Weight.MEDIUM : Weight.REGULAR;
        int w = modePillWidth(label, active), h = 14;
        boolean hot = Kit.over(mx, my, x, y, w, h);
        if (active) Draw.round(g, x, y, w, h, 4f, t.c("filter.active.bg"));
        else if (hot) Draw.round(g, x, y, w, h, 4f, Draw.alpha(t.c("filter.active.bg"), 0.5f));
        int fg = active ? t.c("text.primary") : t.c("text.muted");
        Icons.draw(g, icon, x + 5, y + 3, 8, fg);
        Draw.label(g, label, x + 17, y + h / 2f, 12.5f, weight, fg);
        hits.add(x, y, w, h, b -> run.run());
        return w;
    }

    /** Правая панель режима поверх полотна. */
    private void modePanel(GuiGraphics g, Theme t, int mx, int my) {
        if (modeView == null) return;
        MapApi.PanelView pv = safe(modeView::panel, null);
        if (pv == null) return;
        int w = pv.width(), x = cx0 + cw - w;
        hits.add(x, cy0, w, ch, b -> {});
        Draw.rect(g, x, cy0, w - 1, ch - 1, t.c("panel.bg"));
        Draw.rect(g, x, cy0, 1, ch - 1, t.c("panel.border"));
        try {
            pv.render(g, t, x, cy0, w, ch, hits, mx, my);
        } catch (Throwable e) {
            works.repka.rysmap.RYSMap.LOG.error("[rysmap] панель режима упала", e);
        }
    }

    /** Полотно для режимов модов: координаты окна, тот же перевод, что у карты. */
    private final class CanvasSurface implements MapApi.Surface {
        @Override public ResourceKey<Level> dimension() { return dimension; }
        @Override public int x() { return cx0; }
        @Override public int y() { return cy0; }
        @Override public int width() { return visibleRight() - cx0; }
        @Override public int height() { return ch; }
        @Override public double pixelsPerBlock() { return zoom; }
        @Override public double screenX(double blockX) { return sx(blockX); }
        @Override public double screenY(double blockZ) { return sy(blockZ); }
        @Override public double blockX(double screenX) { return MapScreen.this.blockX(screenX); }
        @Override public double blockZ(double screenY) { return MapScreen.this.blockZ(screenY); }

        @Override
        public void center(double blockX, double blockZ) {
            centerX = blockX;
            centerZ = blockZ;
        }

        @Override
        public void zoom(double pixelsPerBlock) {
            setZoom(pixelsPerBlock);
        }
    }

    // ---- телепорт и строка сообщения ----

    /** Телепорт в столбец карты: место ищет сервер (макет {@code 15b}, этап М8). Отказ приходит строкой сообщения. */
    void teleport(ResourceKey<Level> dim, int x, int z) {
        // в пещерах место ищется у пола показанного этажа
        int hint = caves && dim.equals(dimension) ? works.repka.rysmap.scan.Floors.bottom(floor) + 1 : works.repka.rysmap.server.SafeSpot.NO_HINT;
        works.repka.rysmap.client.ServerRules.teleport(dim, x, hint, z);
    }

    /** Телепорт прошёл: карта встаёт на игрока. */
    public void arrived() {
        toPlayer();
    }

    /** Строка сообщения внизу полотна (макет {@code 24}): значок, заголовок жирным, пояснение, крестик. Одна за раз. */
    private void message(GuiGraphics g, Theme t, int mx, int my) {
        works.repka.rysmap.client.ServerRules.Message m = works.repka.rysmap.client.ServerRules.message();
        if (m == null && modeView != null) {
            Component text = safe(modeView::message, null);
            if (text != null) m = new works.repka.rysmap.client.ServerRules.Message(text, Component.empty());
        }
        if (m == null) return;
        String title = m.title().getString(), text = m.text().getString();
        float size = 12.5f;
        int right = visibleRight();
        float maxW = Math.min(360, right - cx0 - 24);
        float tw = Draw.labelWidth(title, size, Weight.SEMIBOLD);
        String body = Draw.ellipsize(text, Math.max(20, maxW - 8 - 12 - tw - 4 - 16), size, Weight.REGULAR);
        float bw = Draw.labelWidth(body, size, Weight.REGULAR);
        int w = (int) Math.ceil(8 + 12 + tw + 4 + bw + 6 + 10 + 6);
        int h = 18;
        int x = (int) (cx0 + (right - cx0 - w) / 2f), y = cy0 + ch - 6 - TOOL - 4 - h;
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        Draw.roundBox(g, x, y, w, h, 5, t.c("tooltip.bg"), t.c("panel.border"));
        float cy = y + h / 2f;
        Icons.draw(g, Icons.Shape.WARN, x + 7, cy - 4, 8, t.c("state.warn"));
        Draw.label(g, title, x + 20, cy, size, Weight.SEMIBOLD, t.c("text.primary"));
        Draw.label(g, body, x + 20 + tw + 4, cy, size, Weight.REGULAR, t.c("text.secondary"));
        int crossX = x + w - 6 - 8;
        boolean hot = Kit.over(mx, my, crossX - 2, y + 3, 12, 12);
        Draw.cross(g, crossX, (int) cy - 3, hot ? t.c("text.primary") : t.c("text.muted"));
        hits.add(crossX - 2, y + 3, 12, 12, b -> works.repka.rysmap.client.ServerRules.closeMessage());
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
        zoom = Math.max(minZoom(), Math.min(MAX_ZOOM, dir > 0 ? zoom * 2 : zoom / 2));
        // точка под курсором остаётся под курсором
        centerX = bx - (x - cx0 - cw / 2.0) / zoom;
        centerZ = bz - (y - cy0 - ch / 2.0) / zoom;
    }

    @Override
    protected boolean onClick(double x, double y, int button) {
        if (search.focused() && dialog == null) {
            if (hits.click(x, y, button)) return true;
            search.focus(false);
        }
        if (settings != null) {
            hits.click(x, y, button);
            return true;
        }
        if (railOpen && compact() && !Kit.over(x, y, wx, cy0, SIDEBAR, ch)) {
            railOpen = false;
            if (Kit.over(x, y, cx0, cy0, cw, ch)) return true;
        }
        if (tab != null) {
            if (hits.click(x, y, button)) return true;
            MapApi.Tab current = tab;
            return safe(() -> current.mouse(x, y, button, MapApi.MouseAction.PRESS), false);
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
        MapApi.ModeView view = modeView;
        if (view != null && x < visibleRight() && safe(() -> view.mouse(surface, x, y, button, MapApi.MouseAction.PRESS), false)) {
            modeMouse = true;
            return true;
        }
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
        if (tab != null) {
            MapApi.Tab current = tab;
            return safe(() -> current.mouse(x, y, button, MapApi.MouseAction.RELEASE), false);
        }
        if (modeMouse) {
            modeMouse = false;
            MapApi.ModeView view = modeView;
            if (view != null) safe(() -> view.mouse(surface, x, y, button, MapApi.MouseAction.RELEASE), false);
            return true;
        }
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
                    if (m.panel() != null) {
                        panelMarker = m;
                        selected = null;
                        return;
                    }
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
        else {
            selected = null;
            panelMarker = null;
        }
    }

    /** Для автотеста: открыть панель чужой метки. */
    public void markerPanelForTest(MapApi.Marker m) {
        selected = null;
        panelMarker = m;
    }

    @Override
    protected boolean onDrag(double x, double y, int button, double dx, double dy) {
        if (tab != null) {
            MapApi.Tab current = tab;
            return safe(() -> current.mouse(x, y, button, MapApi.MouseAction.DRAG), false);
        }
        if (modeMouse) {
            MapApi.ModeView view = modeView;
            if (view != null) safe(() -> view.mouse(surface, x, y, button, MapApi.MouseAction.DRAG), false);
            return true;
        }
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
        if (tab != null) {
            MapApi.Tab current = tab;
            return safe(() -> current.scroll(x, y, scrollY), false);
        }
        if (modeView != null) {
            MapApi.PanelView pv = safe(modeView::panel, null);
            if (pv != null && x >= visibleRight() && Kit.over(x, y, cx0, cy0, cw, ch)) return safe(() -> pv.scroll(x, y, scrollY), false);
        }
        if (dimDrop.scrolled(x, y, scrollY)) return true;
        if ((!compact() || railOpen) && Kit.over(x, y, wx, listTop, SIDEBAR, listBottom - listTop)) {
            listScroll.by((float) (-scrollY * ROW * 2));
            return true;
        }
        if (!Kit.over(x, y, cx0, cy0, cw, ch) || scrollY == 0) return false;
        if (caves && hasControlDown()) {
            stepFloor(scrollY > 0 ? 1 : -1);
            return true;
        }
        zoomAt(x, y, scrollY > 0 ? 1 : -1);
        return true;
    }

    /** Масштаб окна, единиц на блок (автотест, будущая настройка). */
    public void setZoom(double z) {
        zoom = Math.max(minZoom(), Math.min(MAX_ZOOM, z));
    }

    /** Самый мелкий масштаб: режим может не пускать дальше ({@link MapApi.ModeView#minChunkSize()}). */
    private double minZoom() {
        float chunk = modeView == null ? 0 : safe(modeView::minChunkSize, 0f);
        return Math.max(MIN_ZOOM, chunk / 16.0);
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
        if (works.repka.rysmap.client.ServerRules.teleportAllowed()) {
            items.add(MapApi.MenuItem.builder(TELEPORT_ITEM, Component.translatable("rysmap.menu.teleport"))
                .icon(Icons.Shape.TELEPORT).order(900).run(s -> teleport(s.dimension(), s.x(), s.z())).build());
        }
        items.add(MapApi.MenuItem.builder(ResourceLocation.fromNamespaceAndPath("rysmap", "copy"), Component.translatable("rysmap.menu.copy"))
            .icon(Icons.Shape.COPY).order(1000).run(s -> Minecraft.getInstance().keyboardHandler.setClipboard(s.x() + " " + s.y() + " " + s.z())).build());
        menu = new Menu(x, y, spot, items);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (dialog != null) return dialog.keyPressed(key);
        if (search.key(key, scan, modifiers, this)) return true;
        if (key == GLFW.GLFW_KEY_SLASH && editor == null && (tab == null || settings != null || !anyFieldFocused())) {
            search.focus(true);
            setFocused(search.field);
            skipSlash = true;
            return true;
        }
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
        if (tab != null) {
            MapApi.Tab current = tab;
            if (safe(() -> current.key(key, scan, modifiers), false)) return true;
            if (ClientEntry.OPEN.matches(key, scan)) {
                onClose();
                return true;
            }
            return super.keyPressed(key, scan, modifiers);
        }
        MapApi.ModeView view = modeView;
        if (view != null && safe(() -> view.key(key, scan, modifiers), false)) return true;
        if (dimDrop.keyPressed(key)) return true;
        if (key == GLFW.GLFW_KEY_ESCAPE && modeView != null && selected == null && menu == null) {
            setMode(null);
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE && panelMarker != null) {
            panelMarker = null;
            return true;
        }
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
        if (ClientEntry.TELEPORT.matches(key, scan) && menu == null && Kit.over(lastMx, lastMy, cx0, cy0, visibleRight() - cx0, ch) && !overTools(lastMx, lastMy)) {
            teleport(dimension, (int) Math.floor(blockX(lastMx)), (int) Math.floor(blockZ(lastMy)));
            return true;
        }
        if (ClientEntry.OPEN.matches(key, scan)) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (skipSlash && c == '/') {
            skipSlash = false;
            return true;
        }
        skipSlash = false;
        if (search.chars(c, modifiers)) return true;
        if (tab != null && settings == null) {
            MapApi.Tab current = tab;
            if (safe(() -> current.charTyped(c, modifiers), false)) return true;
        }
        return super.charTyped(c, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
