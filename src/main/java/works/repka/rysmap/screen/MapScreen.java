package works.repka.rysmap.screen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
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
import works.repka.ryscore.ui.Type.Weight;
import works.repka.rysmap.client.ClientEntry;
import works.repka.rysmap.client.MapSession;
import works.repka.rysmap.hud.Minimap;
import works.repka.rysmap.layer.Layers;

/**
 * Окно карты (макет {@code 10}, урезано по плану 13.2): шапка 28 с именем мира, сайдбар 122 с измерениями и слоями,
 * полотно с тайлами, областями и метками слоёв. Тянется мышью, колесо приближает к курсору, Пробел возвращает к
 * игроку, правая кнопка открывает меню (пункты модов из {@link MapApi#menus()} и «Скопировать координаты»).
 * Окно открывается сразу: недостающие тайлы подгружаются в следующих кадрах.
 */
public final class MapScreen extends ScaledScreen {
    private static final int W = 600, H = 364, HEADER = 28, SIDEBAR = 122;
    /** Единиц холста на блок: от дальнего плана до крупного. */
    private static final double MIN_ZOOM = 0.25, MAX_ZOOM = 8;
    private static final int MENU_ROW = 13, MENU_W = 128;

    private final Kit.Hits hits = new Kit.Hits();
    private ResourceKey<Level> dimension;
    private double centerX, centerZ, zoom = 1;
    private boolean dragging, dragged;
    private int wx, wy, ww, wh, cx0, cy0, cw, ch;
    private @Nullable Menu menu;
    private final List<String> dimensions = new ArrayList<>();

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
        Theme t = ThemeManager.get();
        Theme canvas = slate();
        g.fill(0, 0, width, height, t.c("overlay"));
        t.panel(g, wx, wy, ww, wh);
        header(g, t, mx, my);
        sidebar(g, t, mx, my);

        // полотно тёмное в любой теме
        Draw.rect(g, cx0, cy0, cw, ch, canvas.c("map.bg"));
        MapSession s = ClientEntry.session();
        if (s == null) return;
        String dim = dimension.location().toString();
        s.tiles.draw(g, dim, centerX, centerZ, zoom, cx0, cy0, cw, ch);
        areas(g);
        MapApi.Marker hover = markers(g, canvas, mx, my);
        player(g, partial);

        // «Ко мне · Пробел» и координаты под курсором
        String toMe = Component.translatable("rysmap.screen.to_me").getString();
        int bw = (int) Kit.buttonWidth(toMe, 12) + 8;
        Kit.button(g, t, hits, cx0 + 6, cy0 + ch - 20, bw, 14, toMe, false, mx, my, this::toPlayer);
        boolean overCanvas = Kit.over(mx, my, cx0, cy0, cw, ch) && menu == null;
        if (overCanvas) {
            int bx = (int) Math.floor(blockX(mx)), bz = (int) Math.floor(blockZ(my));
            String c = Minimap.group(bx) + "  " + Minimap.group(bz);
            Draw.label(g, c, cx0 + 12 + bw, cy0 + ch - 13, 12, Weight.MEDIUM, canvas.c("map.label.fg"));
        }
        String scale = zoom >= 1 ? Component.translatable("rysmap.screen.zoom_in", fmt(zoom)).getString() : Component.translatable("rysmap.screen.zoom_out", fmt(1 / zoom)).getString();
        float sw = Draw.labelWidth(scale, 12, Weight.MEDIUM);
        Draw.label(g, scale, cx0 + cw - 8 - sw, cy0 + 10, 12, Weight.MEDIUM, canvas.c("map.label.fg"));

        if (menu != null) {
            menu(g, t, mx, my);
        } else if (hover != null) {
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
            Kit.tooltip(g, t, lines, mx, my);
        } else if (overCanvas && !dragging) {
            int chx = Math.floorDiv((int) Math.floor(blockX(mx)), 16), chz = Math.floorDiv((int) Math.floor(blockZ(my)), 16);
            List<Component> tip = Layers.tooltip(dimension, chx, chz);
            if (!tip.isEmpty()) {
                List<String> lines = new ArrayList<>();
                for (Component c : tip) lines.add(c.getString());
                Kit.tooltip(g, t, lines, mx, my);
            }
        }
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? Integer.toString((int) v) : String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    private void header(GuiGraphics g, Theme t, int mx, int my) {
        Kit.iconTile(g, t, wx + 6, wy + 3);
        Icons.draw(g, Icons.Shape.MAP, wx + 10, wy + 7, 15, t.c("text.primary"));
        MapSession s = ClientEntry.session();
        String name = s == null ? "" : s.store.key().name();
        Kit.headerText(g, t, Component.translatable("rysmap.screen.title").getString(), name, wx + 34, wy - 1, ww - 70);
        Kit.close(g, t, hits, wx + ww - 23, wy + 5, mx, my, this::onClose);
        Kit.divider(g, t, wx, wy + HEADER - 0.5f, ww);
    }

    private void sidebar(GuiGraphics g, Theme t, int mx, int my) {
        int x = wx + 8, y = wy + HEADER + 10;
        Kit.section(g, t, Component.translatable("rysmap.screen.dimension").getString(), x, y);
        y += 8;
        for (String id : dimensions) {
            boolean active = id.equals(dimension.location().toString());
            boolean hot = Kit.over(mx, my, wx + 4, y, SIDEBAR - 8, 13);
            if (active || hot) Draw.round(g, wx + 4, y, SIDEBAR - 8, 13, 3, active ? t.c("row.selected.bg") : t.c("slot.hover"));
            String label = dimensionName(id);
            Draw.label(g, Draw.ellipsize(label, SIDEBAR - 20, 12, Weight.REGULAR), x, y + 6.5f, 12, active ? Weight.SEMIBOLD : Weight.REGULAR, t.c("text.primary"));
            final String pick = id;
            hits.add(wx + 4, y, SIDEBAR - 8, 13, b -> switchTo(pick));
            y += 14;
        }
        y += 8;
        List<MapApi.Layer> layers = MapApi.layers();
        if (layers.isEmpty()) return;
        Kit.section(g, t, Component.translatable("rysmap.screen.layers").getString(), x, y);
        y += 8;
        for (MapApi.Layer layer : layers) {
            if (y > wy + wh - 16) break;
            boolean on = Layers.visible(layer);
            Icons.draw(g, layer.icon(), x, y + 2.5f, 8, on ? t.c("text.primary") : t.c("text.muted"));
            Draw.label(g, Draw.ellipsize(layer.name().getString(), SIDEBAR - 44, 12, Weight.REGULAR), x + 12, y + 6.5f, 12, Weight.REGULAR, on ? t.c("text.primary") : t.c("text.muted"));
            Kit.checkbox(g, t, hits, wx + SIDEBAR - 18, y + 2, on, mx, my, () -> Layers.setVisible(layer, !on));
            y += 14;
        }
    }

    private static String dimensionName(String id) {
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
        for (MapApi.Layer layer : Layers.shown()) {
            if (layer.areas() == null) continue;
            for (int rz = chz0 >> 5; rz <= chz1 >> 5; rz++) {
                for (int rx = chx0 >> 5; rx <= chx1 >> 5; rx++) {
                    for (Layers.AreaChunk c : Layers.areas(layer, dimension, rx, rz)) {
                        float sx0 = sx(c.chunkX() * 16), sy0 = sy(c.chunkZ() * 16), sx1 = sx(c.chunkX() * 16 + 16), sy1 = sy(c.chunkZ() * 16 + 16);
                        sx0 = Math.max(sx0, cx0);
                        sy0 = Math.max(sy0, cy0);
                        sx1 = Math.min(sx1, cx0 + cw);
                        sy1 = Math.min(sy1, cy0 + ch);
                        if (sx1 <= sx0 || sy1 <= sy0) continue;
                        float a = c.style() == MapApi.AreaStyle.FILL ? 0.3f : 0.15f;
                        Draw.round(g, sx0, sy0, sx1 - sx0, sy1 - sy0, 0, Draw.alpha(c.color(), a));
                    }
                }
            }
        }
    }

    private @Nullable MapApi.Marker markers(GuiGraphics g, Theme canvas, int mx, int my) {
        MapApi.Marker hover = null;
        for (MapApi.Layer layer : Layers.shown()) {
            for (MapApi.Marker m : Layers.markers(layer, dimension)) {
                float x = sx(m.x()), y = sy(m.z());
                if (x < cx0 + 4 || x > cx0 + cw - 4 || y < cy0 + 4 || y > cy0 + ch - 4) continue;
                Draw.disc(g, x, y, 4.5f, m.color());
                if (m.icon() != null) Icons.draw(g, m.icon(), x - 3, y - 3, 6, 0xFF101216);
                if (Math.abs(mx - x) <= 5 && Math.abs(my - y) <= 5) hover = m;
                if (zoom >= 1 && !m.label().getString().isEmpty()) {
                    String label = m.label().getString();
                    float lw = Draw.labelWidth(label, 11, Weight.MEDIUM);
                    Draw.round(g, x - lw / 2 - 3, y + 6, lw + 6, 9, 2, canvas.c("map.label.bg"));
                    Draw.label(g, label, x - lw / 2, y + 10.5f, 11, Weight.MEDIUM, canvas.c("map.label.fg"));
                }
            }
        }
        return hover;
    }

    private void player(GuiGraphics g, float partial) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || !p.level().dimension().equals(dimension)) return;
        float x = sx(p.getX()), y = sy(p.getZ());
        if (x < cx0 || x > cx0 + cw || y < cy0 || y > cy0 + ch) return;
        Draw.disc(g, x, y, 5.5f, 0xB0101216);
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

    private float sx(double blockX) {
        return (float) (cx0 + cw / 2.0 + (blockX - centerX) * zoom);
    }

    private float sy(double blockZ) {
        return (float) (cy0 + ch / 2.0 + (blockZ - centerZ) * zoom);
    }

    private double blockX(double screenX) {
        return centerX + (screenX - cx0 - cw / 2.0) / zoom;
    }

    private double blockZ(double screenY) {
        return centerZ + (screenY - cy0 - ch / 2.0) / zoom;
    }

    private static Theme slate() {
        for (Theme t : ThemeManager.all()) if (t.id.equals("ryscore:slate")) return t;
        return ThemeManager.get();
    }

    // ---- ввод ----

    private void toPlayer() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        dimension = p.level().dimension();
        centerX = p.getX();
        centerZ = p.getZ();
    }

    private void switchTo(String id) {
        ResourceLocation loc = ResourceLocation.tryParse(id);
        if (loc == null) return;
        dimension = ResourceKey.create(Registries.DIMENSION, loc);
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null && p.level().dimension().equals(dimension)) toPlayer();
    }

    @Override
    protected boolean onClick(double x, double y, int button) {
        if (menu != null) {
            boolean hit = hits.click(x, y, button);
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

    private void clickMarker(double mx, double my) {
        for (MapApi.Layer layer : Layers.shown()) {
            for (MapApi.Marker m : Layers.markers(layer, dimension)) {
                if (m.click() != null && Math.abs(mx - sx(m.x())) <= 5 && Math.abs(my - sy(m.z())) <= 5) {
                    m.click().run();
                    return;
                }
            }
        }
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
        if (!Kit.over(x, y, cx0, cy0, cw, ch) || scrollY == 0) return false;
        double bx = blockX(x), bz = blockZ(y);
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, scrollY > 0 ? zoom * 2 : zoom / 2));
        // точка под курсором остаётся под курсором
        centerX = bx - (x - cx0 - cw / 2.0) / zoom;
        centerZ = bz - (y - cy0 - ch / 2.0) / zoom;
        return true;
    }

    private void openMenu(int x, int y) {
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
