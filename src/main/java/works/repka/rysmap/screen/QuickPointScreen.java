package works.repka.rysmap.screen;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.theme.ThemeManager;
import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.ui.Field;
import works.repka.ryscore.ui.Kit;
import works.repka.ryscore.ui.ScaledScreen;
import works.repka.ryscore.ui.Type.Weight;
import works.repka.rysmap.client.ClientEntry;
import works.repka.rysmap.client.MapSession;
import works.repka.rysmap.hud.Minimap;
import works.repka.rysmap.point.PointStore;
import works.repka.rysmap.point.PointsLayer;

/**
 * Маленькое окно новой точки поверх игры (макет {@code 09}): клавиша «Новая точка» (по умолчанию не назначена), имя
 * выделено, восемь значков, восемь цветов, «Поставить · Enter». Точка встаёт там, где стоит игрок. Esc закрывает без
 * точки. Игра за окном не останавливается.
 */
public final class QuickPointScreen extends ScaledScreen {
    private static final int W = 165, H = 97, PAD_X = 7, PAD_Y = 6;
    private final Kit.Hits hits = new Kit.Hits();
    private final Field name = new Field(100, 10);
    private final String dimension;
    private final int bx, by, bz;
    private String icon = "flag";
    private int color;
    private int x, y;

    private QuickPointScreen(String dimension, int bx, int by, int bz, String defaultName, int color) {
        super(Component.translatable("rysmap.point.new"));
        this.dimension = dimension;
        this.bx = bx;
        this.by = by;
        this.bz = bz;
        this.color = color;
        name.setMaxLength(32);
        name.setSpans(List.of(), new int[0]);
        name.setValue(defaultName);
    }

    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        MapSession s = ClientEntry.session();
        if (p == null || s == null || mc.screen != null) return;
        int n = s.points().points().size() + 1;
        mc.setScreen(new QuickPointScreen(p.level().dimension().location().toString(), p.getBlockX(), p.getBlockY(), p.getBlockZ(),
            Component.translatable("rysmap.point.numbered", n).getString(), n % 8));
    }

    @Override
    protected void init() {
        super.init();
        x = (width - W) / 2;
        y = Math.min(105, (height - H) / 2);
        addWidget(name);
        name.setFocused(true);
        name.moveCursorToEnd(false);
        name.setHighlightPos(0);
        setFocused(name);
    }

    @Override
    protected void draw(GuiGraphics g, int mx, int my, float partial) {
        hits.clear();
        Theme t = ThemeManager.get();
        PointWidgets.frame(g, t, x, y, W, H);
        int ix = x + PAD_X, iw = W - PAD_X * 2, cy = y + PAD_Y;
        Draw.label(g, Component.translatable("rysmap.point.new").getString(), ix, cy + 5, 14, Weight.SEMIBOLD, t.c("text.primary"));
        String coords = Minimap.group(bx) + " · " + Minimap.group(by) + " · " + Minimap.group(bz);
        float cw = Draw.labelWidth(coords, 11.5f, Weight.REGULAR);
        Draw.label(g, coords, ix + iw - cw, cy + 5, 11.5f, Weight.REGULAR, t.c("text.muted"));
        cy += 10 + 4;
        PointWidgets.input(g, t, name, ix, cy, iw, mx, my);
        cy += PointWidgets.INPUT_H + 4;
        cy += PointWidgets.icons(g, t, hits, ix, cy, 8, 8, icon, mx, my, i -> icon = PointsLayer.ICONS.get(i)) + 5;
        cy += PointWidgets.colors(g, t, hits, ix + 1, cy + 1, 8, 8, color, mx, my, i -> color = i) + 5;
        String ok = Component.translatable("rysmap.point.place").getString();
        int bw = (int) Math.ceil(Draw.labelWidth(ok, 12, Weight.SEMIBOLD) + Draw.labelWidth("Enter", 11, Weight.REGULAR) + 5 + 3.5f + 10);
        PointWidgets.button(g, t, hits, x + W - PAD_X - bw, y + H - PAD_Y - 14, bw, 14, ok, "Enter", 1, mx, my, this::place);
    }

    private void place() {
        MapSession s = ClientEntry.session();
        String n = name.getValue().trim();
        if (s == null || n.isEmpty()) return;
        PointStore.Point p = new PointStore.Point();
        p.name = n;
        p.dimension = dimension;
        p.x = bx;
        p.y = by;
        p.z = bz;
        p.icon = icon;
        p.color = color;
        s.points().add(p);
        s.savePoints();
        onClose();
    }

    @Override
    protected boolean onClick(double mx, double my, int button) {
        if (hits.click(mx, my, button)) return true;
        if (name.isMouseOver(mx, my)) {
            name.setFocused(true);
            setFocused(name);
            name.mouseClicked(mx, my, button);
            return true;
        }
        return Kit.over(mx, my, x, y, W, H);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            place();
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (name.isFocused()) {
            name.keyPressed(key, scan, modifiers);
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
