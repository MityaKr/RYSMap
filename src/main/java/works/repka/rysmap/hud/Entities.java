package works.repka.rysmap.hud;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.map.MapApi;
import works.repka.ryscore.ui.Batch;
import works.repka.ryscore.ui.Draw;
import works.repka.ryscore.ui.Icons;
import works.repka.rysmap.RYSMap;
import works.repka.rysmap.client.ServerRules;
import works.repka.rysmap.layer.Layers;
import works.repka.rysmap.net.MapNet;

/**
 * Сущности на миникарте и карте мира (просьба автора 18.11, план 5 «Потом»): игроки своей команды и чужие головами,
 * враждебные мобы красными точками, мирные зелёными. Два своих слоя «Игроки» и «Мобы» с глазом в списке слоёв.
 *
 * <p>Откуда данные. Рядом стоящих клиент знает сам и рисует плавно, между тиками. Дальних игроков (и в другом
 * измерении) раз в секунду присылает сервер с RYSMap ({@link MapNet.Players}), уже отобранных по правилу. Сервер может
 * запретить чужих игроков и мобов правилом ({@link ServerRules#playersAllowed()}, {@link ServerRules#mobsAllowed()}): тогда
 * карта их не рисует, участники своей команды видны всегда. Без серверной части RYSMap правил нет и видно всё, что
 * клиент и так знает. Невидимых и наблюдателей карта не показывает.
 */
public final class Entities {
    public static final ResourceLocation PLAYERS = ResourceLocation.fromNamespaceAndPath(RYSMap.MODID, "players");
    public static final ResourceLocation MOBS = ResourceLocation.fromNamespaceAndPath(RYSMap.MODID, "mobs");
    /** Не больше точек мобов за кадр: в толпе у фермы карта не захлёбывается. */
    public static final int MOB_LIMIT = 256;
    public static final int HOSTILE = 0xFFE5534B, PASSIVE = 0xFF7CCB5E, TEAM = 0xFF4FA3D9, OTHER = 0xFFB8BEC9;

    public enum Kind { TEAM, PLAYER, HOSTILE, PASSIVE }

    /** Сущность для рисования: место в блоках, вид, имя (игрок) или сама сущность (моб, имя по запросу). */
    public record Dot(UUID id, double x, double y, double z, Kind kind, String name, @Nullable Entity entity) {
        public boolean player() {
            return kind == Kind.TEAM || kind == Kind.PLAYER;
        }

        public String label() {
            return entity != null && !player() ? entity.getName().getString() : name;
        }
    }

    private Entities() {}

    /** Слои «Игроки» и «Мобы»: метки рисует этот класс, слой нужен для глаза и числа в списке. */
    public static void register() {
        MapApi api = MapApi.get();
        api.layer(new Layer(PLAYERS, "rysmap.layer.players", Icons.Shape.USERS, 5));
        api.layer(new Layer(MOBS, "rysmap.layer.mobs", Icons.Shape.SKULL, 6));
    }

    public static final class Layer implements MapApi.Layer {
        private final ResourceLocation id;
        private final String name;
        private final Icons.Shape icon;
        private final int order;

        Layer(ResourceLocation id, String name, Icons.Shape icon, int order) {
            this.id = id;
            this.name = name;
            this.icon = icon;
            this.order = order;
        }

        @Override public ResourceLocation id() { return id; }
        @Override public Component name() { return Component.translatable(name); }
        @Override public Icons.Shape icon() { return icon; }
        @Override public int order() { return order; }
        @Override public long revision() { return 0; }
        @Override public Collection<MapApi.Marker> markers(ResourceKey<Level> dimension) { return List.of(); }

        /** Число в списке слоёв: сколько сейчас видно в измерении. */
        public int count(ResourceKey<Level> dimension) {
            return collect(dimension, -3e7, -3e7, 3e7, 3e7, 1f, id.equals(PLAYERS), id.equals(MOBS)).size();
        }
    }

    private static boolean shown(ResourceLocation layer) {
        MapApi.Layer l = Layers.find(layer);
        return l != null && Layers.visible(l);
    }

    /** Что видно в прямоугольнике блоков сейчас по глазам слоёв и правилам сервера. */
    public static List<Dot> collect(ResourceKey<Level> dimension, double minX, double minZ, double maxX, double maxZ, float partial) {
        return collect(dimension, minX, minZ, maxX, maxZ, partial, shown(PLAYERS), shown(MOBS));
    }

    static List<Dot> collect(ResourceKey<Level> dimension, double minX, double minZ, double maxX, double maxZ, float partial,
                             boolean players, boolean mobs) {
        Minecraft mc = Minecraft.getInstance();
        List<Dot> out = new ArrayList<>();
        if (mc.level == null || mc.player == null) return out;
        boolean here = mc.level.dimension().equals(dimension);
        Map<UUID, MapNet.PlayerSpot> feed = ServerRules.players();
        boolean others = ServerRules.playersAllowed();
        if (players) {
            Set<UUID> seen = new HashSet<>();
            if (here) {
                for (Player p : mc.level.players()) {
                    if (p == mc.player || p.isSpectator() || p.isInvisible()) continue;
                    MapNet.PlayerSpot s = feed.get(p.getUUID());
                    boolean team = s != null && s.team();
                    if (!team && !others) continue;
                    double x = Mth.lerp(partial, p.xo, p.getX()), z = Mth.lerp(partial, p.zo, p.getZ());
                    seen.add(p.getUUID());
                    if (x < minX || x > maxX || z < minZ || z > maxZ) {
                        // за краем рисуется только участник команды (миникарта прижимает его к рамке)
                        if (!team) continue;
                    }
                    out.add(new Dot(p.getUUID(), x, p.getY(), z, team ? Kind.TEAM : Kind.PLAYER, p.getGameProfile().getName(), null));
                }
            }
            String dim = dimension.location().toString();
            for (MapNet.PlayerSpot s : feed.values()) {
                if (seen.contains(s.id()) || !s.dimension().equals(dim)) continue;
                if (!s.team() && !others) continue;
                double x = s.x() + 0.5, z = s.z() + 0.5;
                if (!s.team() && (x < minX || x > maxX || z < minZ || z > maxZ)) continue;
                out.add(new Dot(s.id(), x, s.y(), z, s.team() ? Kind.TEAM : Kind.PLAYER, s.name(), null));
            }
        }
        if (mobs && here && ServerRules.mobsAllowed()) {
            int n = 0;
            for (Entity e : mc.level.entitiesForRendering()) {
                if (!(e instanceof Mob) || e.isInvisible() || !e.isAlive()) continue;
                double x = Mth.lerp(partial, e.xo, e.getX()), z = Mth.lerp(partial, e.zo, e.getZ());
                if (x < minX || x > maxX || z < minZ || z > maxZ) continue;
                out.add(new Dot(e.getUUID(), x, e.getY(), z, e instanceof Enemy ? Kind.HOSTILE : Kind.PASSIVE, "", e));
                if (++n >= MOB_LIMIT) break;
            }
        }
        // мобы снизу, игроки сверху
        out.sort((a, b) -> Boolean.compare(a.player(), b.player()));
        return out;
    }

    /**
     * Вид метки внутри кружка: голова игрока, если задана, потом предмет, потом значок ({@link MapApi.Marker#icon()}).
     * {@code size}: сторона значка.
     */
    public static void look(GuiGraphics g, MapApi.Marker m, float cx, float cy, float size) {
        if (m.head() != null) {
            face(g, m.head(), cx - size / 2f, cy - size / 2f, size);
        } else if (!m.item().isEmpty()) {
            Batch.flush();
            g.pose().pushPose();
            g.pose().translate(cx - size / 2f, cy - size / 2f, 0);
            g.pose().scale(size / 16f, size / 16f, 1f);
            g.renderItem(m.item(), 0, 0);
            g.pose().popPose();
        } else if (m.icon() != null) {
            Icons.draw(g, m.icon(), cx - size / 2f, cy - size / 2f, size, 0xFF15181F);
        }
    }

    /** Пинг расходится кольцом, тревога пульсирует красным (Т2, Т5). */
    public static void pulse(GuiGraphics g, MapApi.Marker m, float cx, float cy, float r) {
        if (m.kind() != MapApi.Kind.PING && m.kind() != MapApi.Kind.ALERT) return;
        float t = (System.currentTimeMillis() % 1400) / 1400f;
        int color = m.kind() == MapApi.Kind.ALERT ? HOSTILE : m.color();
        Draw.ring(g, cx, cy, r + 1 + t * r * 1.6f, 1f, Draw.alpha(color | 0xFF000000, 1f - t));
    }

    /** Точка моба: кружок с тёмной кромкой. */
    public static void mob(GuiGraphics g, Dot d, float x, float y, float r) {
        Draw.disc(g, x, y, r + 0.6f, 0xCC101216);
        Draw.disc(g, x, y, r, d.kind() == Kind.HOSTILE ? HOSTILE : PASSIVE);
    }

    /** Голова игрока размером {@code size} в рамке: синей у своей команды, серой у чужих. */
    public static void head(GuiGraphics g, Dot d, float x, float y, float size) {
        float half = size / 2f;
        int ring = d.kind() == Kind.TEAM ? TEAM : OTHER;
        Draw.round(g, x - half - 1, y - half - 1, size + 2, size + 2, 1.5f, 0xE0101216);
        Draw.round(g, x - half - 0.5f, y - half - 0.5f, size + 1, size + 1, 1.2f, ring);
        face(g, d.id(), x - half, y - half, size);
    }

    static void face(GuiGraphics g, UUID player, float x, float y, float size) {
        Minecraft mc = Minecraft.getInstance();
        PlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(player);
        ResourceLocation skin = info != null ? info.getSkin().texture() : DefaultPlayerSkin.get(player).texture();
        Batch.flush();
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(size / 8f, size / 8f, 1f);
        PlayerFaceRenderer.draw(g, skin, 0, 0, 8);
        g.pose().popPose();
    }
}
