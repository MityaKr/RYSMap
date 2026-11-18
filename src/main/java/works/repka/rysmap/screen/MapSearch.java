package works.repka.rysmap.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import works.repka.ryscore.map.MapApi;
import works.repka.ryscore.theme.Theme;
import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.ui.Field;
import works.repka.ryscore.ui.Icons;
import works.repka.ryscore.ui.Kit;
import works.repka.ryscore.ui.Type.Weight;
import works.repka.rysmap.client.ClientEntry;
import works.repka.rysmap.client.MapSession;
import works.repka.rysmap.client.ServerRules;
import works.repka.rysmap.hud.Minimap;
import works.repka.rysmap.net.MapNet;
import works.repka.rysmap.point.PointStore;
import works.repka.rysmap.point.PointsLayer;

/**
 * Поиск в шапке окна карты (макет {@code 14}, этап М7): клавиша {@code /} ставит курсор в поле. Ищет свои точки, игроков,
 * результаты слоёв модов ({@link MapApi.Layer#search}) и координаты «1200 −300» или «1200 64 −300». На вкладке мода ищет
 * её поиск ({@link MapApi.Tab#search}). Enter или клик ведёт карту к результату; своя точка ещё и открывает панель.
 */
final class MapSearch {
    static final int W = 128, H = 18, ROW = 18, MAX = 8;
    /** До восьми цифр: длинное число в поле не роняет игру (перепроверка 18.11, пункт 5), граница мира 30 000 000. */
    private static final Pattern COORDS = Pattern.compile("^\\s*([−-]?\\d{1,8})[\\s,;]+(?:([−-]?\\d{1,8})[\\s,;]+)?([−-]?\\d{1,8})\\s*$");

    final Field field = new Field(100, 10);
    private List<Result> results = List.of();
    private String query = "";
    private int selected;

    /** Результат: вид, заголовок, пояснение, куда вести, своя точка (если это она). */
    record Result(@Nullable Icons.Shape icon, int color, @Nullable UUID head, String title, String note,
                  ResourceKey<Level> dimension, double x, double y, double z, PointStore.@Nullable Point point) {}

    MapSearch() {
        field.setMaxLength(48);
        field.setHint(Component.translatable("rysmap.search.hint"));
        field.setBordered(false);
    }

    boolean focused() {
        return field.isFocused();
    }

    void focus(boolean on) {
        field.setFocused(on);
        if (!on) {
            field.setValue("");
            results = List.of();
            query = "";
        }
    }

    /** Поле справа в шапке. */
    void drawField(GuiGraphics g, Theme t, Kit.Hits hits, MapScreen s, int x, int y, int mx, int my) {
        boolean on = field.isFocused();
        if (t.beveled()) t.input(g, x, y, W, H, on);
        else Draw.roundBox(g, x, y, W, H, 4.5f, t.c("input.bg"), on ? t.c("text.secondary") : t.c("input.border"));
        Icons.draw(g, Icons.Shape.MAGNIFIER, x + 5, y + 5, 8, t.c("text.muted"));
        field.setX(x + 16);
        field.setY(y + (H - 10) / 2 + 1);
        field.setWidth(W - 16 - 16);
        field.setVisible(true);
        field.setTextColor(t.c("input.fg") & 0xFFFFFF);
        field.render(g, mx, my, 0);
        if (!on && field.getValue().isEmpty()) MapScreen.kbd(g, t, "/", x + W - 12, y + H / 2f);
        hits.add(x, y, W, H, b -> {
            field.setFocused(true);
            s.setFocused(field);
        });
        String q = field.getValue().trim();
        if (!q.equals(query)) {
            query = q;
            results = q.isEmpty() ? List.of() : compute(q, s);
            selected = 0;
        }
    }

    /** Список результатов под полем, поверх всего окна. */
    void drawResults(GuiGraphics g, Theme t, Kit.Hits hits, MapScreen s, int x, int y, int mx, int my) {
        if (!field.isFocused() || query.isEmpty()) return;
        int w = 200, rows = Math.max(1, results.size());
        int lx = x + W - w, ly = y + H + 3, h = rows * ROW + 6;
        g.pose().pushPose();
        g.pose().translate(0, 0, 450);
        t.tooltipBox(g, lx, ly, w, h);
        if (results.isEmpty()) {
            Draw.label(g, Component.translatable("rysmap.search.none").getString(), lx + 8, ly + 3 + ROW / 2f, 12, Weight.REGULAR, t.c("text.muted"));
        }
        for (int i = 0; i < results.size(); i++) {
            Result r = results.get(i);
            int ry = ly + 3 + i * ROW;
            boolean hot = Kit.over(mx, my, lx + 2, ry, w - 4, ROW);
            if (hot) selected = i;
            if (i == selected) Draw.round(g, lx + 2, ry, w - 4, ROW, 3, t.c("slot.hover"));
            float cy = ry + ROW / 2f;
            Draw.disc(g, lx + 11, cy, 5.5f, r.color());
            if (r.icon() != null) Icons.draw(g, r.icon(), lx + 7.5f, cy - 3.5f, 7, 0xFF15181F);
            Draw.label(g, Draw.ellipsize(r.title(), w - 90, 12.5f, Weight.MEDIUM), lx + 21, cy, 12.5f, Weight.MEDIUM, t.c("text.primary"));
            String note = Draw.ellipsize(r.note(), 70, 11, Weight.REGULAR);
            Draw.label(g, note, lx + w - 8 - Draw.labelWidth(note, 11, Weight.REGULAR), cy, 11, Weight.REGULAR, t.c("text.muted"));
            hits.add(lx + 2, ry, w - 4, ROW, b -> pick(s, r));
        }
        g.pose().popPose();
    }

    boolean key(int key, int scan, int modifiers, MapScreen s) {
        if (!field.isFocused()) return false;
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            focus(false);
            return true;
        }
        if (key == GLFW.GLFW_KEY_DOWN && !results.isEmpty()) {
            selected = (selected + 1) % results.size();
            return true;
        }
        if (key == GLFW.GLFW_KEY_UP && !results.isEmpty()) {
            selected = (selected + results.size() - 1) % results.size();
            return true;
        }
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && !results.isEmpty()) {
            pick(s, results.get(Math.min(selected, results.size() - 1)));
            return true;
        }
        field.keyPressed(key, scan, modifiers);
        return true;
    }

    boolean chars(char c, int modifiers) {
        if (!field.isFocused()) return false;
        field.charTyped(c, modifiers);
        return true;
    }

    private void pick(MapScreen s, Result r) {
        focus(false);
        s.goTo(r.dimension(), r.x(), r.z(), r.point());
    }

    /** Результаты по строке: координаты, свои точки, игроки, слои модов (или поиск вкладки). */
    List<Result> compute(String q, MapScreen s) {
        List<Result> out = new ArrayList<>();
        String low = q.toLowerCase(Locale.ROOT);
        ResourceKey<Level> dim = s.dimension;
        LocalPlayer me = Minecraft.getInstance().player;
        MapApi.Tab tab = s.tab();
        if (tab != null) {
            try {
                for (MapApi.Hit h : tab.search(q)) out.add(hit(h, me));
            } catch (Throwable ignored) {
                // поиск вкладки упал: пусто
            }
            return cut(out);
        }
        Matcher m = COORDS.matcher(q);
        if (m.matches()) {
            int x = num(m.group(1)), z = num(m.group(3));
            Integer y = m.group(2) == null ? null : num(m.group(2));
            out.add(new Result(Icons.Shape.TARGET, 0xFFF7F8FB, null,
                Component.translatable("rysmap.search.goto", Minimap.group(x), Minimap.group(z)).getString(),
                y == null ? "" : "Y " + Minimap.group(y), dim, x + 0.5, y == null ? 64 : y, z + 0.5, null));
        }
        MapSession session = ClientEntry.session();
        if (session != null) {
            for (PointStore.Point p : session.points().points()) {
                String name = PointsLayer.name(p);
                if (!name.toLowerCase(Locale.ROOT).contains(low)) continue;
                ResourceLocation loc = ResourceLocation.tryParse(p.dimension);
                if (loc == null) continue;
                ResourceKey<Level> pd = ResourceKey.create(Registries.DIMENSION, loc);
                out.add(new Result(PointsLayer.icon(p.icon), PointsLayer.color(p), null, name, note(me, pd, p.x + 0.5, p.z + 0.5),
                    pd, p.x + 0.5, p.y, p.z + 0.5, p));
            }
        }
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        if (me != null) {
            for (Player pl : me.level().players()) {
                if (pl == me || pl.isInvisible() || pl.isSpectator()) continue;
                String name = pl.getGameProfile().getName();
                MapNet.PlayerSpot spot = ServerRules.players().get(pl.getUUID());
                if (!(spot != null && spot.team()) && !ServerRules.playersAllowed()) continue;
                if (!name.toLowerCase(Locale.ROOT).contains(low)) continue;
                seen.add(pl.getUUID());
                out.add(new Result(Icons.Shape.USERS, works.repka.rysmap.hud.Entities.OTHER, pl.getUUID(), name,
                    note(me, me.level().dimension(), pl.getX(), pl.getZ()), me.level().dimension(), pl.getX(), pl.getY(), pl.getZ(), null));
            }
        }
        for (MapNet.PlayerSpot sp : ServerRules.playerList()) {
            if (seen.contains(sp.id()) || !sp.name().toLowerCase(Locale.ROOT).contains(low)) continue;
            ResourceLocation loc = ResourceLocation.tryParse(sp.dimension());
            if (loc == null) continue;
            ResourceKey<Level> pd = ResourceKey.create(Registries.DIMENSION, loc);
            out.add(new Result(Icons.Shape.USERS, sp.team() ? works.repka.rysmap.hud.Entities.TEAM : works.repka.rysmap.hud.Entities.OTHER, sp.id(),
                sp.name(), note(me, pd, sp.x(), sp.z()), pd, sp.x() + 0.5, sp.y(), sp.z() + 0.5, null));
        }
        for (MapApi.Layer layer : MapApi.layers()) {
            try {
                for (MapApi.Hit h : layer.search(q)) out.add(hit(h, me));
            } catch (Throwable ignored) {
                // поиск слоя упал: без его результатов
            }
        }
        return cut(out);
    }

    private static List<Result> cut(List<Result> out) {
        return out.size() > MAX ? List.copyOf(out.subList(0, MAX)) : out;
    }

    private static Result hit(MapApi.Hit h, @Nullable LocalPlayer me) {
        MapApi.Target t = h.target();
        String note = h.note().getString();
        if (note.isEmpty()) note = note(me, t.dimension(), t.x(), t.z());
        return new Result(h.icon(), h.color(), h.head(), h.title().getString(), note, t.dimension(), t.x(), t.y(), t.z(), null);
    }

    /** «24 м» в своём измерении, иначе имя измерения. */
    private static String note(@Nullable LocalPlayer me, ResourceKey<Level> dim, double x, double z) {
        if (me == null || !me.level().dimension().equals(dim)) return MapScreen.dimensionName(dim.location().toString());
        return Words.distance(Math.hypot(x - me.getX(), z - me.getZ()));
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replace('−', '-'));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
