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

    public enum Kind { TEAM, PLAYER, HOSTILE, PASSIVE, PET }

    /** Цвет рамки питомца. */
    public static final int PET = 0xFF6FB7E8;

    /** Сущность для рисования: место в блоках, вид, имя (игрок) или сама сущность (моб, имя по запросу). */
    public record Dot(UUID id, double x, double y, double z, Kind kind, String name, @Nullable Entity entity, float alpha) {
        public Dot(UUID id, double x, double y, double z, Kind kind, String name, @Nullable Entity entity) {
            this(id, x, y, z, kind, name, entity, 1f);
        }

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

        private int counted;
        private long countedAt = Long.MIN_VALUE;
        private @Nullable Object countedFor;

        /** Число в списке слоёв: сколько сейчас видно в измерении. Считается раз в 10 тиков (разбор логики 19.11, п. 2). */
        public int count(ResourceKey<Level> dimension, @Nullable Integer floor) {
            long now = gameTime();
            Object key = java.util.Arrays.asList(dimension, floor);
            if (now - countedAt >= 10 || now < countedAt || !key.equals(countedFor)) {
                counted = collect(dimension, -3e7, -3e7, 3e7, 3e7, 1f, id.equals(PLAYERS), id.equals(MOBS), floor).size();
                countedAt = now;
                countedFor = key;
            }
            return counted;
        }
    }

    private static long gameTime() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? 0 : mc.level.getGameTime();
    }

    /** Мобы вокруг игрока: собираются раз в тик рамкой дальности прорисовки, кадры берут готовый список. */
    private static List<Mob> mobs = List.of();
    private static long mobsAt = Long.MIN_VALUE;
    private static @Nullable Level mobsLevel;

    private static List<Mob> mobsNow(Minecraft mc) {
        long now = mc.level.getGameTime();
        if (now != mobsAt || mobsLevel != mc.level) {
            double r = (mc.options.getEffectiveRenderDistance() + 1) * 16.0;
            net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(mc.player.getX() - r, mc.level.getMinBuildHeight(), mc.player.getZ() - r,
                mc.player.getX() + r, mc.level.getMaxBuildHeight(), mc.player.getZ() + r);
            mobs = mc.level.getEntitiesOfClass(Mob.class, box, e -> !e.isInvisible() && e.isAlive());
            mobsAt = now;
            mobsLevel = mc.level;
        }
        return mobs;
    }

    private static boolean shown(ResourceLocation layer) {
        MapApi.Layer l = Layers.find(layer);
        return l != null && Layers.visible(l);
    }

    /** Что видно в прямоугольнике блоков сейчас по глазам слоёв и правилам сервера; слой: поверхность. */
    public static List<Dot> collect(ResourceKey<Level> dimension, double minX, double minZ, double maxX, double maxZ, float partial) {
        return collect(dimension, minX, minZ, maxX, maxZ, partial, shown(PLAYERS), shown(MOBS), null);
    }

    /**
     * То же для показанного слоя: {@code floor} этаж пещер или null (поверхность). При настройке «Мобы и чужие игроки:
     * текущий слой» (по умолчанию) мобы и чужие игроки видны только на высотах этого слоя: на этаже в его 16 блоках
     * (с запасом в блок), на поверхности у поверхности (не ниже 12 блоков под верхним блоком столбца или под открытым
     * небом), в Нижнем мире на срезе в 24 блоках от игрока. Участники своей команды видны на любом слое: их ищут.
     */
    public static List<Dot> collect(ResourceKey<Level> dimension, double minX, double minZ, double maxX, double maxZ, float partial,
                                    @Nullable Integer floor) {
        return collect(dimension, minX, minZ, maxX, maxZ, partial, shown(PLAYERS), shown(MOBS), floor);
    }

    /**
     * Прозрачность сущности по слою (скептик s2): «Только свой слой ±8 / ±16» считается от игрока, а на карте с показанным
     * этажом от середины этажа. В первой половине диапазона сущность видна целиком, во второй бледнее, дальше не видна (0).
     * «Все слои»: всегда 1.
     */
    static float layerAlpha(double y, @Nullable Integer floor, double playerY) {
        int range = works.repka.rysmap.client.ClientConfig.get().mobsRange;
        if (range <= 0) return 1f;
        double ref = floor != null ? works.repka.rysmap.scan.Floors.bottom(floor) + 8 : playerY;
        double dy = Math.abs(y - ref);
        if (dy <= range / 2.0) return 1f;
        return dy <= range ? 0.45f : 0f;
    }

    static List<Dot> collect(ResourceKey<Level> dimension, double minX, double minZ, double maxX, double maxZ, float partial,
                             boolean players, boolean mobs, @Nullable Integer floor) {
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
                    float alpha = team ? 1f : layerAlpha(p.getY(), floor, mc.player.getY());
                    if (alpha <= 0) continue;
                    if (x < minX || x > maxX || z < minZ || z > maxZ) {
                        // за краем рисуется только участник команды (миникарта прижимает его к рамке)
                        if (!team) continue;
                    }
                    out.add(new Dot(p.getUUID(), x, p.getY(), z, team ? Kind.TEAM : Kind.PLAYER, p.getGameProfile().getName(), null, alpha));
                }
            }
            String dim = dimension.location().toString();
            for (MapNet.PlayerSpot s : feed.values()) {
                if (seen.contains(s.id()) || !s.dimension().equals(dim)) continue;
                if (!s.team() && !others) continue;
                double x = s.x() + 0.5, z = s.z() + 0.5;
                if (!s.team() && (x < minX || x > maxX || z < minZ || z > maxZ)) continue;
                float alpha = s.team() ? 1f : layerAlpha(s.y(), floor, mc.player.getY());
                if (alpha <= 0) continue;
                out.add(new Dot(s.id(), x, s.y(), z, s.team() ? Kind.TEAM : Kind.PLAYER, s.name(), null, alpha));
            }
        }
        works.repka.rysmap.client.ClientConfig cfg = works.repka.rysmap.client.ClientConfig.get();
        if (mobs && here && ServerRules.mobsAllowed() && cfg.mobsView != 0) {
            int n = 0;
            for (Mob e : mobsNow(mc)) {
                if (e.isRemoved() || !e.isAlive()) continue;
                Kind kind = e instanceof Enemy ? Kind.HOSTILE
                    : e instanceof net.minecraft.world.entity.OwnableEntity own && own.getOwnerUUID() != null ? Kind.PET : Kind.PASSIVE;
                if (kind == Kind.HOSTILE && !cfg.mobsHostile || kind == Kind.PASSIVE && !cfg.mobsPassive || kind == Kind.PET && !cfg.mobsPets) continue;
                double x = Mth.lerp(partial, e.xo, e.getX()), z = Mth.lerp(partial, e.zo, e.getZ());
                if (x < minX || x > maxX || z < minZ || z > maxZ) continue;
                float alpha = layerAlpha(e.getY(), floor, mc.player.getY());
                if (alpha <= 0) continue;
                out.add(new Dot(e.getUUID(), x, e.getY(), z, kind, "", e, alpha));
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

    /** Точка моба: кружок с тёмной кромкой (запасной вид, пока голова не испечена или не печётся). */
    public static void mob(GuiGraphics g, Dot d, float x, float y, float r) {
        Draw.disc(g, x, y, r + 0.6f, Draw.alpha(0xCC101216, d.alpha()));
        Draw.disc(g, x, y, r, Draw.alpha(rim(d), d.alpha()));
    }

    static int rim(Dot d) {
        return d.kind() == Kind.HOSTILE ? HOSTILE : d.kind() == Kind.PET ? PET : PASSIVE;
    }

    /**
     * Мобы пачкой: голова моба ({@link MobHeads}) в рамке цвета «враждебный / мирный», без головы точка. Рамки идут
     * общей пачкой ядра, головы одним вызовом отрисовки на тип моба.
     *
     * @param xy места на экране по паре чисел на моба
     */
    public static void mobs(GuiGraphics g, List<Dot> dots, float[] xy, float size) {
        if (dots.isEmpty()) return;
        java.util.Map<Integer, MobHeads.Batch> heads = new java.util.HashMap<>();
        float half = size / 2f;
        for (int i = 0; i < dots.size(); i++) {
            Dot d = dots.get(i);
            float x = xy[i * 2], y = xy[i * 2 + 1];
            int tex = d.entity() == null || works.repka.rysmap.client.ClientConfig.get().mobsView == 2 ? -1 : MobHeads.texture(d.entity().getType());
            if (tex < 0) {
                mob(g, d, x, y, Math.max(1.5f, size * 0.28f));
                continue;
            }
            Draw.round(g, x - half - 1f, y - half - 1f, size + 2f, size + 2f, 1.5f, Draw.alpha(0xE0101216, d.alpha()));
            Draw.round(g, x - half - 0.6f, y - half - 0.6f, size + 1.2f, size + 1.2f, 1.2f, Draw.alpha(rim(d), d.alpha()));
            heads.computeIfAbsent(tex, k -> new MobHeads.Batch()).add(x - half, y - half, size, d.alpha());
        }
        if (!heads.isEmpty()) MobHeads.draw(g, heads);
    }

    /** Голова игрока размером {@code size} в рамке: синей у своей команды, серой у чужих. */
    public static void head(GuiGraphics g, Dot d, float x, float y, float size) {
        if (d.alpha() < 0.99f) {
            // чужой игрок на соседнем слое: бледный значок вместо головы
            Draw.disc(g, x, y, size / 2f, Draw.alpha(OTHER, d.alpha()));
            return;
        }
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
