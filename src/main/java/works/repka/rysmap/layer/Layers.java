package works.repka.rysmap.layer;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import works.repka.ryscore.map.MapApi;
import works.repka.rysmap.RYSMap;
import works.repka.rysmap.client.ClientConfig;
import works.repka.rysmap.tile.RegionData;

/**
 * Слои карты: чужие из реестра ядра ({@link MapApi#layers()}) с глазом игрока. Метки пересобираются, только когда
 * у слоя растёт {@code revision}; области по чанкам кэшируются на регион 32×32 чанка и тоже по номеру ревизии.
 * Только главный поток. Ошибка чужого слоя пишется в лог один раз и слой пропускается.
 */
public final class Layers {
    /** Чанк области, готовый к рисованию. */
    public record AreaChunk(int chunkX, int chunkZ, int group, int color, MapApi.AreaStyle style, boolean badge) {}

    private record MarkerCache(long revision, String dimension, Collection<MapApi.Marker> markers) {}

    private record AreaCache(long revision, List<AreaChunk> chunks) {}

    private static final Map<ResourceLocation, MarkerCache> MARKERS = new HashMap<>();
    private static final Map<String, Long2ObjectOpenHashMap<AreaCache>> AREAS = new HashMap<>();
    private static final java.util.Set<ResourceLocation> BROKEN = new java.util.HashSet<>();

    private Layers() {}

    public static boolean visible(MapApi.Layer layer) {
        Boolean on = ClientConfig.get().layers.get(layer.id().toString());
        return on != null ? on : layer.onByDefault();
    }

    public static void setVisible(MapApi.Layer layer, boolean on) {
        ClientConfig.get().layers.put(layer.id().toString(), on);
        ClientConfig.get().save();
    }

    /** Слои, которые сейчас видны. */
    public static List<MapApi.Layer> shown() {
        List<MapApi.Layer> out = new ArrayList<>();
        for (MapApi.Layer l : MapApi.layers()) if (!BROKEN.contains(l.id()) && visible(l)) out.add(l);
        return out;
    }

    /** Метки слоя в измерении; при неизменной ревизии из кэша. */
    public static Collection<MapApi.Marker> markers(MapApi.Layer layer, ResourceKey<Level> dimension) {
        try {
            long rev = layer.revision();
            String dim = dimension.location().toString();
            MarkerCache c = MARKERS.get(layer.id());
            if (c == null || c.revision != rev || !c.dimension.equals(dim)) {
                c = new MarkerCache(rev, dim, List.copyOf(layer.markers(dimension)));
                MARKERS.put(layer.id(), c);
            }
            return c.markers;
        } catch (Throwable t) {
            broken(layer, t);
            return List.of();
        }
    }

    /** Чанки областей слоя в регионе 32×32 чанка. */
    public static List<AreaChunk> areas(MapApi.Layer layer, ResourceKey<Level> dimension, int regionX, int regionZ) {
        MapApi.Areas areas;
        try {
            areas = layer.areas();
            if (areas == null) return List.of();
            long rev = areas.revision(dimension, regionX, regionZ);
            String key = layer.id() + "|" + dimension.location();
            Long2ObjectOpenHashMap<AreaCache> map = AREAS.computeIfAbsent(key, k -> new Long2ObjectOpenHashMap<>());
            long rk = RegionData.key(regionX, regionZ);
            AreaCache c = map.get(rk);
            if (c == null || c.revision != rev) {
                List<AreaChunk> list = new ArrayList<>();
                areas.fill(dimension, regionX, regionZ, (cx, cz, group, color, style, badge) -> list.add(new AreaChunk(cx, cz, group, color, style, badge)));
                c = new AreaCache(rev, List.copyOf(list));
                map.put(rk, c);
                if (map.size() > 256) map.clear(); // потолок кэша: окно карты ушло далеко
            }
            return c.chunks;
        } catch (Throwable t) {
            broken(layer, t);
            return List.of();
        }
    }

    /** Подсказка чанка от всех видимых слоёв с областями. */
    public static List<net.minecraft.network.chat.Component> tooltip(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        List<net.minecraft.network.chat.Component> out = new ArrayList<>();
        for (MapApi.Layer l : shown()) {
            try {
                MapApi.Areas a = l.areas();
                if (a == null) continue;
                List<net.minecraft.network.chat.Component> lines = a.tooltip(dimension, chunkX, chunkZ);
                if (lines != null) out.addAll(lines);
            } catch (Throwable t) {
                broken(l, t);
            }
        }
        return out;
    }

    public static Collection<MapApi.AreaLabel> labels(MapApi.Layer layer, ResourceKey<Level> dimension) {
        try {
            MapApi.Areas a = layer.areas();
            return a == null ? List.of() : a.labels(dimension);
        } catch (Throwable t) {
            broken(layer, t);
            return List.of();
        }
    }

    /**
     * Области слоя в окне: заливка чанков и обводка по краю группы (сосед другой группы или пустой). Чанки берутся из
     * кэша по регионам в прямоугольнике {@code cx0..cx1, cz0..cz1}; экранное место чанка {@code ox + chunkX·16·ppb}.
     * Всё обрезается по {@code clipX0..clipY1}. Обводка уходит одним вызовом {@link works.repka.ryscore.ui.Draw#lines}.
     */
    public static void drawAreas(net.minecraft.client.gui.GuiGraphics g, MapApi.Layer layer, ResourceKey<Level> dimension, int cx0, int cz0, int cx1, int cz1,
                                 double ox, double oz, double ppb, float clipX0, float clipY0, float clipX1, float clipY1) {
        drawAreas(g, layer, dimension, cx0, cz0, cx1, cz1, ox, oz, ppb, clipX0, clipY0, clipX1, clipY1, null);
    }

    /**
     * То же с обрезкой по полосам формы ({@code x0, y0, x1, y1} подряд, как у тайлов): круглая и скруглённая миникарта.
     * Заливка режется по каждой полосе, обводка по полосам, в которые попадает.
     */
    public static void drawAreas(net.minecraft.client.gui.GuiGraphics g, MapApi.Layer layer, ResourceKey<Level> dimension, int cx0, int cz0, int cx1, int cz1,
                                 double ox, double oz, double ppb, float clipX0, float clipY0, float clipX1, float clipY1, float @Nullable [] strips) {
        it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<AreaChunk> chunks = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        for (int rz = cz0 >> 5; rz <= cz1 >> 5; rz++)
            for (int rx = cx0 >> 5; rx <= cx1 >> 5; rx++)
                for (AreaChunk c : areas(layer, dimension, rx, rz))
                    if (c.chunkX() >= cx0 && c.chunkX() <= cx1 && c.chunkZ() >= cz0 && c.chunkZ() <= cz1) chunks.put(RegionData.key(c.chunkX(), c.chunkZ()), c);
        if (chunks.isEmpty()) return;
        float side = (float) (16 * ppb);
        // заливка: подряд идущие чанки строки одного цвета и вида сливаются в полосу и рисуются дробными квадами.
        // Раньше каждый чанк шёл через Draw.round без радиуса, а тот округляет x и ширину до целых единиц по
        // отдельности: соседи перекрывались полосой двойной прозрачности (сетка цвета команды) и прыгали шагом
        // в единицу, пока тайлы под ними ехали плавно.
        java.util.List<AreaChunk> sorted = new java.util.ArrayList<>(chunks.values());
        sorted.sort((a, b) -> a.chunkZ() != b.chunkZ() ? Integer.compare(a.chunkZ(), b.chunkZ()) : Integer.compare(a.chunkX(), b.chunkX()));
        float[] fill = new float[sorted.size() * 4];
        int[] fillColors = new int[sorted.size()];
        int runs = 0;
        for (int i = 0; i < sorted.size(); ) {
            AreaChunk first = sorted.get(i);
            int j = i + 1;
            while (j < sorted.size()) {
                AreaChunk nx = sorted.get(j), pv = sorted.get(j - 1);
                if (nx.chunkZ() != first.chunkZ() || nx.chunkX() != pv.chunkX() + 1 || nx.color() != first.color() || nx.style() != first.style()) break;
                j++;
            }
            AreaChunk last = sorted.get(j - 1);
            float sx0 = (float) (ox + first.chunkX() * 16 * ppb), sy0 = (float) (oz + first.chunkZ() * 16 * ppb);
            float sx1 = (float) (ox + (last.chunkX() + 1) * 16 * ppb), sy1 = sy0 + side;
            float fx0 = Math.max(sx0, clipX0), fy0 = Math.max(sy0, clipY0), fx1 = Math.min(sx1, clipX1), fy1 = Math.min(sy1, clipY1);
            if (fx1 > fx0 && fy1 > fy0) {
                float a = first.style() == MapApi.AreaStyle.FILL ? 0.22f : 0.12f;
                fill[runs * 4] = fx0; fill[runs * 4 + 1] = fy0; fill[runs * 4 + 2] = fx1; fill[runs * 4 + 3] = fy1;
                fillColors[runs++] = works.repka.ryscore.ui.Draw.alpha(first.color(), a);
            }
            i = j;
        }
        if (strips != null) {
            Cut cut = cut(fill, fillColors, runs, strips, false);
            quads(g, cut.xy(), cut.colors(), cut.count());
        } else {
            quads(g, fill, fillColors, runs);
        }
        float[] xy = new float[chunks.size() * 16];
        int[] colors = new int[chunks.size() * 4];
        int n = 0;
        for (AreaChunk c : chunks.values()) {
            float sx0 = (float) (ox + c.chunkX() * 16 * ppb), sy0 = (float) (oz + c.chunkZ() * 16 * ppb);
            float sx1 = sx0 + side, sy1 = sy0 + side;
            float fx0 = Math.max(sx0, clipX0), fy0 = Math.max(sy0, clipY0), fx1 = Math.min(sx1, clipX1), fy1 = Math.min(sy1, clipY1);
            if (fx1 <= fx0 || fy1 <= fy0) continue;
            // край группы: сверху, снизу, слева, справа
            int[][] dirs = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
            for (int[] d : dirs) {
                AreaChunk nb = chunks.get(RegionData.key(c.chunkX() + d[0], c.chunkZ() + d[1]));
                if (nb != null && nb.group() == c.group()) continue;
                float lx0, ly0, lx1, ly1;
                if (d[1] != 0) {
                    float yy = d[1] < 0 ? sy0 + 0.5f : sy1 - 0.5f;
                    if (yy < clipY0 || yy > clipY1) continue;
                    lx0 = fx0; lx1 = fx1; ly0 = ly1 = yy;
                } else {
                    float xx = d[0] < 0 ? sx0 + 0.5f : sx1 - 0.5f;
                    if (xx < clipX0 || xx > clipX1) continue;
                    ly0 = fy0; ly1 = fy1; lx0 = lx1 = xx;
                }
                if (n * 4 + 4 > xy.length) break;
                xy[n * 4] = lx0; xy[n * 4 + 1] = ly0; xy[n * 4 + 2] = lx1; xy[n * 4 + 3] = ly1;
                colors[n] = c.style() == MapApi.AreaStyle.DASHED ? works.repka.ryscore.ui.Draw.alpha(c.color(), 0.7f) : c.color();
                n++;
            }
        }
        if (n > 0 && strips != null) {
            Cut cut = cut(xy, colors, n, strips, true);
            if (cut.count() > 0) works.repka.ryscore.ui.Draw.lines(g, cut.xy(), cut.colors(), cut.count(), 1f);
        } else if (n > 0) works.repka.ryscore.ui.Draw.lines(g, xy, colors, n, 1f);
    }

    /**
     * Прямоугольники с дробными краями одним проходом (по четыре числа x0, y0, x1, y1 и цвету ARGB на каждый). Соседние
     * полосы делят общий край без зазора и перекрытия. Пачка ядра сбрасывается до рисования, чтобы порядок слоёв сохранился.
     */
    public static void quads(net.minecraft.client.gui.GuiGraphics g, float[] xy, int[] colors, int count) {
        if (count <= 0) return;
        works.repka.ryscore.ui.Batch.flush();
        g.flush();
        com.mojang.blaze3d.systems.RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionColorShader);
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        org.joml.Matrix4f m = g.pose().last().pose();
        com.mojang.blaze3d.vertex.BufferBuilder b = com.mojang.blaze3d.vertex.Tesselator.getInstance()
            .begin(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < count; i++) {
            float x0 = xy[i * 4], y0 = xy[i * 4 + 1], x1 = xy[i * 4 + 2], y1 = xy[i * 4 + 3];
            int c = colors[i];
            b.addVertex(m, x0, y0, 0).setColor(c);
            b.addVertex(m, x0, y1, 0).setColor(c);
            b.addVertex(m, x1, y1, 0).setColor(c);
            b.addVertex(m, x1, y0, 0).setColor(c);
        }
        var mesh = b.build();
        if (mesh != null) com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(mesh);
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
    }

    /** Куски после обрезки по полосам. */
    record Cut(float[] xy, int[] colors, int count) {}

    /**
     * Прямоугольники ({@code lines} ложь) или отрезки по осям ({@code lines} правда), обрезанные полосами формы. Соседние
     * куски одного отрезка сливаются, чтобы обводка не рвалась на стыках полос.
     */
    static Cut cut(float[] src, int[] colors, int count, float[] strips, boolean lines) {
        float[] xy = new float[Math.max(16, count * 8)];
        int[] cs = new int[Math.max(4, count * 2)];
        int n = 0;
        for (int i = 0; i < count; i++) {
            float x0 = Math.min(src[i * 4], src[i * 4 + 2]), x1 = Math.max(src[i * 4], src[i * 4 + 2]);
            float y0 = Math.min(src[i * 4 + 1], src[i * 4 + 3]), y1 = Math.max(src[i * 4 + 1], src[i * 4 + 3]);
            boolean horizontal = lines && y0 == y1, vertical = lines && x0 == x1;
            int first = n;
            for (int k = 0; k + 3 < strips.length; k += 4) {
                float sx0 = strips[k], sy0 = strips[k + 1], sx1 = strips[k + 2], sy1 = strips[k + 3];
                float a0, b0, a1, b1;
                if (horizontal) {
                    if (y0 < sy0 || y0 > sy1) continue;
                    a0 = Math.max(x0, sx0); a1 = Math.min(x1, sx1); b0 = b1 = y0;
                    if (a1 <= a0) continue;
                } else if (vertical) {
                    if (x0 < sx0 || x0 > sx1) continue;
                    b0 = Math.max(y0, sy0); b1 = Math.min(y1, sy1); a0 = a1 = x0;
                    if (b1 <= b0) continue;
                } else {
                    a0 = Math.max(x0, sx0); b0 = Math.max(y0, sy0); a1 = Math.min(x1, sx1); b1 = Math.min(y1, sy1);
                    if (a1 <= a0 || b1 <= b0) continue;
                }
                if (lines && n > first) {
                    int l = (n - 1) * 4;
                    if (vertical && Math.abs(xy[l + 3] - b0) < 0.01f) { xy[l + 3] = b1; continue; }
                    if (horizontal && Math.abs(xy[l + 2] - a0) < 0.01f) { xy[l + 2] = a1; continue; }
                }
                if (n * 4 + 4 > xy.length) {
                    xy = java.util.Arrays.copyOf(xy, xy.length * 2);
                    cs = java.util.Arrays.copyOf(cs, cs.length * 2);
                }
                xy[n * 4] = a0; xy[n * 4 + 1] = b0; xy[n * 4 + 2] = a1; xy[n * 4 + 3] = b1;
                cs[n++] = colors[i];
            }
        }
        return new Cut(xy, cs, n);
    }

    private static void broken(MapApi.Layer layer, Throwable t) {
        if (BROKEN.add(layer.id())) RYSMap.LOG.error("[rysmap] слой {} упал и выключен до перезапуска", layer.id(), t);
    }

    /** Выход из мира: кэши слоёв не нужны. */
    public static void clear() {
        MARKERS.clear();
        AREAS.clear();
    }

    public static @Nullable MapApi.Layer find(ResourceLocation id) {
        for (MapApi.Layer l : MapApi.layers()) if (l.id().equals(id)) return l;
        return null;
    }
}
