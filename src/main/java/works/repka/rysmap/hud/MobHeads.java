package works.repka.rysmap.hud;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import works.repka.rysmap.RYSMap;

/**
 * Головы мобов для карты (просьба автора 18.11): модель моба рисуется один раз на тип в маленькую текстуру
 * {@value #SIZE}×{@value #SIZE} лицом к зрителю, кадр берётся вокруг глаз ({@code getEyeHeight}), так у зомби, коровы,
 * крипера и мобов модов в кадре лицо. Печётся в начале кадра ({@code RenderFrameEvent.Pre}), не больше двух типов за
 * кадр; дальше карта рисует только готовую текстуру, по одному вызову отрисовки на тип. Не вышло (моб не создаётся,
 * слишком большой, рендерер упал): тип помечается, карта рисует для него точку. Потолок {@value #CAP} типов.
 */
public final class MobHeads {
    static final int SIZE = 48, CAP = 160, PER_FRAME = 2;
    private static final Map<EntityType<?>, TextureTarget> HEADS = new HashMap<>();
    private static final java.util.Set<EntityType<?>> FAILED = new java.util.HashSet<>();
    private static final ArrayDeque<EntityType<?>> QUEUE = new ArrayDeque<>();
    private static long bakeNanos;
    private static int baked;

    private MobHeads() {}

    /** Текстура головы типа или −1 (печётся или не вышла). Главный поток. */
    public static int texture(EntityType<?> type) {
        TextureTarget t = HEADS.get(type);
        if (t != null) return t.getColorTextureId();
        if (!FAILED.contains(type) && !QUEUE.contains(type) && HEADS.size() + QUEUE.size() < CAP) QUEUE.add(type);
        return -1;
    }

    /** Начало кадра: испечь очередь понемногу. */
    public static void frame() {
        for (int i = 0; i < PER_FRAME && !QUEUE.isEmpty(); i++) {
            EntityType<?> type = QUEUE.poll();
            long t0 = System.nanoTime();
            try {
                TextureTarget t = bake(type);
                if (t != null) HEADS.put(type, t);
                else FAILED.add(type);
            } catch (Throwable e) {
                FAILED.add(type);
                RYSMap.LOG.debug("[rysmap] голова {} не испеклась: {}", EntityType.getKey(type), e.toString());
                restore();
            }
            bakeNanos += System.nanoTime() - t0;
            baked++;
        }
    }

    /** Среднее время печи головы, мкс (замер автотеста). */
    public static long bakeMicros() {
        return baked == 0 ? 0 : bakeNanos / baked / 1000;
    }

    public static int count() {
        return HEADS.size();
    }

    private static TextureTarget bake(EntityType<?> type) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        Entity entity = type.create(mc.level);
        if (!(entity instanceof LivingEntity living)) return null;
        float height = entity.getBbHeight();
        if (height > 4.5f || entity.getBbWidth() > 4f) return null; // дракон, гаст: в кадр не помещаются
        float eye = entity.getEyeHeight();
        // ширина кадра в блоках: голова зомби 0,5 блока, у высоких мобов кадр шире
        float frame = Math.max(0.6f, Math.min(1.5f, height * 0.42f));
        float scale = SIZE / frame;
        living.setYRot(180f);
        living.yRotO = 180f;
        living.yBodyRot = 180f;
        living.yBodyRotO = 180f;
        living.yHeadRot = 180f;
        living.yHeadRotO = 180f;
        living.setXRot(0f);

        // туман прошлого кадра мира не должен ложиться на голову; мир перед своим кадром ставит туман сам
        net.minecraft.client.renderer.FogRenderer.setupNoFog();
        TextureTarget target = new TextureTarget(SIZE, SIZE, true, Minecraft.ON_OSX);
        boolean ok = false;
        target.setClearColor(0f, 0f, 0f, 0f);
        target.clear(Minecraft.ON_OSX);
        target.bindWrite(true);
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, SIZE, SIZE, 0, 1000f, 21000f), VertexSorting.ORTHOGRAPHIC_Z);
        Matrix4fStack mv = RenderSystem.getModelViewStack();
        mv.pushMatrix();
        mv.translation(0f, 0f, -11000f);
        RenderSystem.applyModelViewMatrix();
        try {
            PoseStack pose = new PoseStack();
            // как окно инвентаря: ноги в точке, глаза в середине кадра
            pose.translate(SIZE / 2f, SIZE / 2f + eye * scale, 50f);
            pose.scale(scale, scale, -scale);
            pose.mulPose(Axis.ZP.rotationDegrees(180f));
            Lighting.setupForEntityInInventory();
            EntityRenderDispatcher d = mc.getEntityRenderDispatcher();
            d.setRenderShadow(false);
            MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
            RenderSystem.runAsFancy(() -> d.render(entity, 0, 0, 0, 0f, 1f, pose, buffers, 15728880));
            buffers.endBatch();
            d.setRenderShadow(true);
            ok = true;
        } finally {
            // рендер упал: текстура не нужна; сущность-образец в мир не добавлялась, но помечается удалённой
            if (!ok) target.destroyBuffers();
            entity.discard();
            mv.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
            Lighting.setupFor3DItems();
            mc.getMainRenderTarget().bindWrite(true);
        }
        return target;
    }

    private static void restore() {
        Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
    }

    /** Места голов одной текстуры: середина, радиус, прозрачность. */
    public static final class Batch {
        float[] xys = new float[32];
        int n;

        void add(float cx, float cy, float radius, float alpha) {
            if (n * 4 + 4 > xys.length) xys = java.util.Arrays.copyOf(xys, xys.length * 2);
            xys[n * 4] = cx;
            xys[n * 4 + 1] = cy;
            xys[n * 4 + 2] = radius;
            xys[n * 4 + 3] = alpha;
            n++;
        }
    }

    /** Отрезков круга головы. */
    private static final int SEGMENTS = 20;
    /** Доля кадра в круге: лицо чуть крупнее, углы кадра (обычно пустые) срезаны. */
    private static final float CROP = 0.46f;
    private static final float[] COS = new float[SEGMENTS + 1], SIN = new float[SEGMENTS + 1];

    static {
        for (int i = 0; i <= SEGMENTS; i++) {
            double a = Math.PI * 2 * i / SEGMENTS;
            COS[i] = (float) Math.cos(a);
            SIN[i] = (float) Math.sin(a);
        }
    }

    /**
     * Головы по текстурам кругами (отзыв автора 19.11: круг отличает моба от игрока, у которого квадрат): один вызов
     * отрисовки на тип, круг треугольниками. Текстура цели перевёрнута по высоте.
     */
    static void draw(GuiGraphics g, Map<Integer, Batch> heads) {
        works.repka.ryscore.ui.Batch.flush();
        g.flush();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        Matrix4f m = g.pose().last().pose();
        for (Map.Entry<Integer, Batch> e : heads.entrySet()) {
            Batch b = e.getValue();
            RenderSystem.setShaderTexture(0, e.getKey());
            BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
            for (int i = 0; i < b.n; i++) {
                float cx = b.xys[i * 4], cy = b.xys[i * 4 + 1], r = b.xys[i * 4 + 2], a = b.xys[i * 4 + 3];
                for (int k = 0; k < SEGMENTS; k++) {
                    buf.addVertex(m, cx, cy, 0).setUv(0.5f, 0.5f).setColor(1f, 1f, 1f, a);
                    buf.addVertex(m, cx + r * COS[k + 1], cy + r * SIN[k + 1], 0).setUv(0.5f + CROP * COS[k + 1], 0.5f - CROP * SIN[k + 1]).setColor(1f, 1f, 1f, a);
                    buf.addVertex(m, cx + r * COS[k], cy + r * SIN[k], 0).setUv(0.5f + CROP * COS[k], 0.5f - CROP * SIN[k]).setColor(1f, 1f, 1f, a);
                }
            }
            var mesh = buf.build();
            if (mesh != null) BufferUploader.drawWithShader(mesh);
        }
        RenderSystem.disableBlend();
    }

    /** Выход из мира: текстуры отпускаются (типы модов могут смениться с сервером). */
    public static void clear() {
        for (TextureTarget t : HEADS.values()) t.destroyBuffers();
        HEADS.clear();
        FAILED.clear();
        QUEUE.clear();
    }
}
