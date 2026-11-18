package works.repka.rysmap.tile;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;
import works.repka.ryscore.ui.Batch;

/**
 * Текстуры регионов 512×512 в видеопамяти, только главный поток. Копии пикселей в куче и во внешней памяти не
 * держатся: текстура заливается из одного общего буфера и дальше обновляется кусками 16×16 по грязным чанкам.
 *
 * <p>Потолок жёсткий: при нехватке места отпускается давно не рисованный регион; не рисованный дольше заданного
 * времени отпускается сам. Регион, которого нет на диске, помечается пустым и памяти не занимает.
 */
public final class TileTextures {
    /** Готовые пиксели региона от рабочего потока; null: региона нет. */
    public record Pixels(String dimension, int regionX, int regionZ, int @Nullable [] abgr) {}

    /** Обновлённый чанк 16×16 в мировых координатах чанка. */
    public record Patch(String dimension, int chunkX, int chunkZ, int[] abgr) {}

    /** Кто готовит пиксели региона (рабочий поток). */
    public interface Source {
        void request(String dimension, int regionX, int regionZ);
    }

    private static final int REQUESTED = 0, READY = 1, EMPTY = 2;
    private static final int MAX_IN_FLIGHT = 2;
    private static final long USED_NOW_NANOS = 100_000_000L;
    /** Через сколько после правки региона перепекается устаревшая текстура: правки идут пачками, не печь на каждую. */
    private static final long STALE_NANOS = 5_000_000_000L;
    private static final int GL_TEXTURE_2D = 3553, GL_RGBA = 6408, GL_UNSIGNED_BYTE = 5121;

    private static final class Tile {
        final String dimension;
        final int x, z;
        int state = REQUESTED;
        int glId = -1;
        long lastUsed;
        /** Регион обновился, а текстура старая (дальний план): перепечь, когда пройдёт {@link #STALE_NANOS}. */
        boolean stale;
        long staleAt;

        Tile(String dimension, int x, int z, long now) {
            this.dimension = dimension;
            this.x = x;
            this.z = z;
            this.lastUsed = now;
        }
    }

    private final Source source;
    /** Сторона текстуры региона: 512 у обычных тайлов, меньше у дальнего плана (М6). */
    private final int side;
    /** Множитель яркости следующего рисования (ночь, М6): 1 без затемнения. */
    private float brightness = 1f;
    private final Map<String, Long2ObjectOpenHashMap<Tile>> tiles = new HashMap<>();
    private int cap;
    private long idleNanos;
    private int inFlight, textures;
    private @Nullable IntBuffer scratch;
    private long uploads;

    public TileTextures(Source source, int cap, int idleSeconds) {
        this(source, cap, idleSeconds, RegionData.SIZE);
    }

    /** {@code side}: сторона текстуры региона (дальний план: 64, регион уменьшен в 8 раз). */
    public TileTextures(Source source, int cap, int idleSeconds, int side) {
        this.side = side;
        this.source = source;
        this.cap = Math.max(4, cap);
        this.idleNanos = idleSeconds * 1_000_000_000L;
    }

    // ---- рисование ----

    /**
     * Рисует тайлы измерения вокруг точки в прямоугольник {@code x, y, w, h} единиц текущей матрицы.
     * Недостающие регионы заказываются у рабочего потока и появятся в следующих кадрах.
     *
     * @return нарисован ли хоть один тайл
     */
    public boolean draw(GuiGraphics g, String dimension, double centerX, double centerZ, double pixelsPerBlock,
                        float x, float y, float w, float h) {
        return draw(g, dimension, centerX, centerZ, pixelsPerBlock, x, y, w, h, new float[]{x, y, x + w, y + h});
    }

    /**
     * То же с обрезкой по набору прямоугольников ({@code x0, y0, x1, y1} подряд): скруглённая рамка миникарты
     * собирается полосами, без ножниц и трафарета. Все полосы региона уходят одним вызовом отрисовки.
     */
    public boolean draw(GuiGraphics g, String dimension, double centerX, double centerZ, double pixelsPerBlock,
                        float x, float y, float w, float h, float[] clips) {
        if (pixelsPerBlock <= 0 || w <= 0 || h <= 0) return false;
        long now = System.nanoTime();
        double left = centerX - w / 2.0 / pixelsPerBlock, top = centerZ - h / 2.0 / pixelsPerBlock;
        double right = centerX + w / 2.0 / pixelsPerBlock, bottom = centerZ + h / 2.0 / pixelsPerBlock;
        int rx0 = Math.floorDiv((int) Math.floor(left), RegionData.SIZE), rx1 = Math.floorDiv((int) Math.floor(right), RegionData.SIZE);
        int rz0 = Math.floorDiv((int) Math.floor(top), RegionData.SIZE), rz1 = Math.floorDiv((int) Math.floor(bottom), RegionData.SIZE);
        List<long[]> visible = new ArrayList<>();
        for (int rz = rz0; rz <= rz1; rz++) for (int rx = rx0; rx <= rx1; rx++) visible.add(new long[]{rx, rz});
        if (visible.size() > cap) {
            // больше, чем помещается в потолок: ближние к центру, остальное не трогаем, чтобы не гонять текстуры по кругу
            double ccx = centerX / RegionData.SIZE - 0.5, ccz = centerZ / RegionData.SIZE - 0.5;
            visible.sort((a, b) -> Double.compare(sq(a[0] - ccx) + sq(a[1] - ccz), sq(b[0] - ccx) + sq(b[1] - ccz)));
            visible = visible.subList(0, cap);
        }
        boolean begun = false, drew = false;
        Matrix4f m = g.pose().last().pose();
        for (long[] v : visible) {
            int rx = (int) v[0], rz = (int) v[1];
            Tile t = tile(dimension, rx, rz, now);
            if (t == null || t.state != READY) continue;
            if (!begun) {
                Batch.flush();
                g.flush();
                RenderSystem.setShader(brightness < 0.999f ? GameRenderer::getPositionTexColorShader : GameRenderer::getPositionTexShader);
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                begun = true;
            }
            double ox = (double) rx * RegionData.SIZE, oz = (double) rz * RegionData.SIZE;
            // место региона на холсте; каждая область обрезки даёт свой квад с подрезанными UV
            double rsx0 = x + (ox - left) * pixelsPerBlock, rsy0 = y + (oz - top) * pixelsPerBlock;
            double side = RegionData.SIZE * pixelsPerBlock;
            RenderSystem.setShaderTexture(0, t.glId);
            boolean dim = brightness < 0.999f;
            BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, dim ? DefaultVertexFormat.POSITION_TEX_COLOR : DefaultVertexFormat.POSITION_TEX);
            int quads = 0;
            for (int c = 0; c + 3 < clips.length; c += 4) {
                double qx0 = Math.max(Math.max(rsx0, x), clips[c]), qy0 = Math.max(Math.max(rsy0, y), clips[c + 1]);
                double qx1 = Math.min(Math.min(rsx0 + side, x + w), clips[c + 2]), qy1 = Math.min(Math.min(rsy0 + side, y + h), clips[c + 3]);
                if (qx1 <= qx0 || qy1 <= qy0) continue;
                float u0 = (float) ((qx0 - rsx0) / side), u1 = (float) ((qx1 - rsx0) / side);
                float v0 = (float) ((qy0 - rsy0) / side), v1 = (float) ((qy1 - rsy0) / side);
                if (dim) {
                    // ночь: цвет тайла умножается в шейдере на цвет вершины, текстура не перезаливается
                    float k = brightness;
                    b.addVertex(m, (float) qx0, (float) qy0, 0).setUv(u0, v0).setColor(k, k, k, 1f);
                    b.addVertex(m, (float) qx0, (float) qy1, 0).setUv(u0, v1).setColor(k, k, k, 1f);
                    b.addVertex(m, (float) qx1, (float) qy1, 0).setUv(u1, v1).setColor(k, k, k, 1f);
                    b.addVertex(m, (float) qx1, (float) qy0, 0).setUv(u1, v0).setColor(k, k, k, 1f);
                } else {
                    b.addVertex(m, (float) qx0, (float) qy0, 0).setUv(u0, v0);
                    b.addVertex(m, (float) qx0, (float) qy1, 0).setUv(u0, v1);
                    b.addVertex(m, (float) qx1, (float) qy1, 0).setUv(u1, v1);
                    b.addVertex(m, (float) qx1, (float) qy0, 0).setUv(u1, v0);
                }
                quads++;
            }
            var mesh = b.build();
            if (mesh == null || quads == 0) continue;
            BufferUploader.drawWithShader(mesh);
            drew = true;
        }
        if (begun) RenderSystem.disableBlend();
        return drew;
    }

    private static double sq(double v) {
        return v * v;
    }

    private @Nullable Tile tile(String dimension, int rx, int rz, long now) {
        Long2ObjectOpenHashMap<Tile> map = tiles.computeIfAbsent(dimension, d -> new Long2ObjectOpenHashMap<>());
        long key = RegionData.key(rx, rz);
        Tile t = map.get(key);
        if (t != null) {
            t.lastUsed = now;
            // устаревшая текстура перепекается, пока её видно; старая рисуется до прихода новой (перепроверка 18.11, пункт 9)
            if (t.stale && t.state != REQUESTED && now - t.staleAt > STALE_NANOS && inFlight < MAX_IN_FLIGHT) {
                t.stale = false;
                inFlight++;
                source.request(dimension, rx, rz);
            }
            return t;
        }
        if (inFlight >= MAX_IN_FLIGHT) return null;
        if (textures + inFlight >= cap && !evictOne(now)) return null;
        t = new Tile(dimension, rx, rz, now);
        map.put(key, t);
        inFlight++;
        source.request(dimension, rx, rz);
        return t;
    }

    /** Отпускает давно не рисованную текстуру; рисованные в этом кадре не трогает. */
    private boolean evictOne(long now) {
        Tile oldest = null;
        for (Long2ObjectOpenHashMap<Tile> map : tiles.values())
            for (Tile t : map.values())
                if (t.state == READY && now - t.lastUsed > USED_NOW_NANOS && (oldest == null || t.lastUsed < oldest.lastUsed)) oldest = t;
        if (oldest == null) return false;
        release(oldest);
        tiles.get(oldest.dimension).remove(RegionData.key(oldest.x, oldest.z));
        return true;
    }

    // ---- приём от рабочего потока ----

    public void accept(Pixels p) {
        inFlight = Math.max(0, inFlight - 1);
        Tile t = find(p.dimension(), p.regionX(), p.regionZ());
        if (t == null) return;
        if (p.abgr() == null) {
            release(t);
            t.state = EMPTY;
            return;
        }
        ensureTexture(t);
        IntBuffer buf = scratch();
        buf.clear();
        buf.put(p.abgr());
        buf.flip();
        upload(t.glId, 0, 0, side, side, buf);
        t.state = READY;
    }

    public void accept(Patch p) {
        if (side != RegionData.SIZE) return; // дальний план кусками не обновляется: текстура уйдёт по простою и перепечётся
        Tile t = find(p.dimension(), p.chunkX() >> 5, p.chunkZ() >> 5);
        if (t == null || t.state == REQUESTED) return;
        if (t.state == EMPTY) {
            ensureTexture(t);
            IntBuffer buf = scratch();
            buf.clear();
            for (int i = 0; i < RegionData.AREA; i++) buf.put(0);
            buf.flip();
            upload(t.glId, 0, 0, RegionData.SIZE, RegionData.SIZE, buf);
            t.state = READY;
        }
        IntBuffer buf = scratch();
        buf.clear();
        buf.put(p.abgr());
        buf.flip();
        upload(t.glId, (p.chunkX() & 31) * 16, (p.chunkZ() & 31) * 16, 16, 16, buf);
    }

    private @Nullable Tile find(String dimension, int rx, int rz) {
        Long2ObjectOpenHashMap<Tile> map = tiles.get(dimension);
        return map == null ? null : map.get(RegionData.key(rx, rz));
    }

    private void ensureTexture(Tile t) {
        if (t.glId >= 0) return;
        t.glId = TextureUtil.generateTextureId();
        TextureUtil.prepareImage(t.glId, 0, side, side);
        GlStateManager._texParameter(GL_TEXTURE_2D, 10241, 9728); // MIN_FILTER NEAREST
        GlStateManager._texParameter(GL_TEXTURE_2D, 10240, 9728); // MAG_FILTER NEAREST
        GlStateManager._texParameter(GL_TEXTURE_2D, 10242, 33071); // WRAP_S CLAMP_TO_EDGE
        GlStateManager._texParameter(GL_TEXTURE_2D, 10243, 33071); // WRAP_T CLAMP_TO_EDGE
        textures++;
    }

    private void upload(int glId, int x, int y, int w, int h, IntBuffer pixels) {
        GlStateManager._bindTexture(glId);
        GlStateManager._pixelStore(3314, 0); // UNPACK_ROW_LENGTH
        GlStateManager._pixelStore(3316, 0); // UNPACK_SKIP_PIXELS
        GlStateManager._pixelStore(3315, 0); // UNPACK_SKIP_ROWS
        GlStateManager._pixelStore(3317, 4); // UNPACK_ALIGNMENT
        GlStateManager._texSubImage2D(GL_TEXTURE_2D, 0, x, y, w, h, GL_RGBA, GL_UNSIGNED_BYTE, MemoryUtil.memAddress(pixels));
        uploads++;
    }

    private IntBuffer scratch() {
        if (scratch == null) scratch = MemoryUtil.memAllocInt(side * side);
        return scratch;
    }

    private void release(Tile t) {
        if (t.glId >= 0) {
            TextureUtil.releaseTextureId(t.glId);
            t.glId = -1;
            textures--;
        }
    }

    // ---- обслуживание ----

    /** Раз в секунду: отпускает то, что давно не рисовали. */
    public void tick() {
        long now = System.nanoTime();
        for (Long2ObjectOpenHashMap<Tile> map : tiles.values()) {
            for (Iterator<Tile> it = map.values().iterator(); it.hasNext(); ) {
                Tile t = it.next();
                if (t.state != REQUESTED && now - t.lastUsed > idleNanos) {
                    release(t);
                    it.remove();
                }
            }
        }
    }

    /** Измерение сменилось: текстуры прежних измерений отпускаются сразу. */
    public void keepOnly(String dimension) {
        for (Iterator<Map.Entry<String, Long2ObjectOpenHashMap<Tile>>> it = tiles.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Long2ObjectOpenHashMap<Tile>> e = it.next();
            if (works.repka.rysmap.scan.Floors.base(e.getKey()).equals(dimension)) continue; // этажи того же измерения остаются
            for (Tile t : e.getValue().values()) release(t);
            it.remove();
        }
    }

    /** Перерисовать всё: таблица цветов перестроена. Текстуры отпускаются, следующие кадры закажут их заново. */
    public void invalidate() {
        for (Long2ObjectOpenHashMap<Tile> map : tiles.values()) {
            for (Iterator<Tile> it = map.values().iterator(); it.hasNext(); ) {
                Tile t = it.next();
                if (t.state == REQUESTED) continue;
                release(t);
                it.remove();
            }
        }
    }

    /** Перерисовать один регион (переезд из FTB дописал его): следующий кадр закажет текстуру заново. */
    public void invalidate(String dimension, int regionX, int regionZ) {
        Long2ObjectOpenHashMap<Tile> map = tiles.get(dimension);
        if (map == null) return;
        Tile t = map.get(RegionData.key(regionX, regionZ));
        if (t == null || t.state == REQUESTED) return;
        release(t);
        map.remove(RegionData.key(regionX, regionZ));
    }

    /** Выход из мира: всё отпускается, внешний буфер тоже. */
    public void close() {
        for (Long2ObjectOpenHashMap<Tile> map : tiles.values()) for (Tile t : map.values()) release(t);
        tiles.clear();
        inFlight = 0;
        if (scratch != null) {
            MemoryUtil.memFree(scratch);
            scratch = null;
        }
    }

    /** Регион обновился: текстуру этого региона перепечь (дальний план, куски туда не заливаются). */
    public void markStale(String dimension, int regionX, int regionZ) {
        Tile t = find(dimension, regionX, regionZ);
        if (t == null || t.stale) return;
        t.stale = true;
        t.staleAt = System.nanoTime();
    }

    /** Множитель яркости следующих рисований (ночь): 1 без затемнения. */
    public void brightness(float k) {
        brightness = Math.max(0f, Math.min(1f, k));
    }

    /** Уменьшить пиксели региона 512×512 в {@code side}×{@code side} усреднением (дальний план). Рабочий поток. */
    public static int[] downsample(int[] abgr, int side) {
        int f = RegionData.SIZE / side;
        int[] out = new int[side * side];
        for (int oy = 0; oy < side; oy++) {
            for (int ox = 0; ox < side; ox++) {
                long a = 0, b = 0, g = 0, r = 0;
                int n = 0;
                for (int y = 0; y < f; y++) {
                    int row = (oy * f + y) * RegionData.SIZE + ox * f;
                    for (int x = 0; x < f; x++) {
                        int c = abgr[row + x];
                        int ca = c >>> 24;
                        if (ca == 0) continue;
                        a += ca;
                        b += (c >> 16) & 255;
                        g += (c >> 8) & 255;
                        r += c & 255;
                        n++;
                    }
                }
                if (n == 0) continue;
                // прозрачность по доле разведанного: край разведки мягкий
                int alpha = (int) (a / (f * f));
                out[oy * side + ox] = (alpha << 24) | (int) (b / n) << 16 | (int) (g / n) << 8 | (int) (r / n);
            }
        }
        return out;
    }

    public void setLimits(int cap, int idleSeconds) {
        this.cap = Math.max(4, cap);
        this.idleNanos = idleSeconds * 1_000_000_000L;
    }

    public int textures() {
        return textures;
    }

    /** Видеопамять текстур плюс общий буфер, байты. */
    public long bytes() {
        return (long) textures * side * side * 4 + (scratch != null ? (long) side * side * 4 : 0);
    }

    public long uploads() {
        return uploads;
    }
}
