package works.repka.rysmap.tile;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.GrassColor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;
import works.repka.rysmap.RYSMap;

/**
 * Цвета блоков и биомов для карты. Цвет блока это средний цвет верхней грани его модели из атласа (у блоков модов
 * цвета карты часто нет, а текстура есть), оттенок травы, листвы и воды берётся по биому. Поправки лежат данными в
 * {@code assets/<ns>/rysmap/colors.json}: {@code "мод:блок": "#rrggbb"} или {@code {"color": "#rrggbb", "tint": "grass"}}.
 *
 * <p>Таблица строится в главном потоке порциями по бюджету времени (модели и атлас трогаются только там), потом
 * публикуется неизменяемой: рабочий поток читает её без блокировок. После смены ресурспаков строится заново.
 */
public final class ColorTable implements MapColors {
    public static final ColorTable INSTANCE = new ColorTable();

    /** Что сканер и пекарь знают о блоке. {@code visible} ложь: сканер смотрит сквозь блок (стекло, воздух, барьер). */
    public record Info(String id, int argb, int tint, boolean visible) {}

    private static final int PLAINS_GRASS = 0x91BD59, PLAINS_FOLIAGE = 0x77AB2F, PLAINS_WATER = 0x3F76E4;

    private volatile Map<Block, Info> blocks = Map.of();
    private volatile Map<String, Info> byName = Map.of();
    private volatile Map<String, int[]> biomes = Map.of();
    private volatile boolean ready;
    private volatile int waterTexture = 0xB0B0B0, unknown = 0xFF1C1F26;
    private final AtomicLong version = new AtomicLong();

    private @Nullable Iterator<Block> pending;
    private IdentityHashMap<Block, Info> building = new IdentityHashMap<>();
    private Map<String, Fix> overrides = Map.of();
    private final RandomSource random = RandomSource.create(42L);
    private long buildNanos;

    private record Fix(int rgb, int tint) {}

    private ColorTable() {}

    /** Таблица готова: сканер может разбирать снимки. */
    public boolean ready() {
        return ready;
    }

    /** Смена ресурспаков: строится заново, старая служит пекарю, пока новая не готова. */
    public void invalidate() {
        pending = null;
        ready = false;
    }

    /** Цвет неизвестного блока из темы (токен {@code map.unknown} полотна). */
    public void setUnknown(int argb) {
        if (unknown != argb) {
            unknown = argb;
            version.incrementAndGet();
        }
    }

    /**
     * Порция построения в главном потоке.
     *
     * @return готова ли таблица
     */
    public boolean step(long budgetNanos) {
        if (ready) return true;
        long start = System.nanoTime();
        if (pending == null) {
            building = new IdentityHashMap<>();
            overrides = readOverrides(Minecraft.getInstance().getResourceManager());
            pending = BuiltInRegistries.BLOCK.iterator();
            buildNanos = 0;
            waterTexture = average(particle(Blocks.WATER.defaultBlockState())) & 0xFFFFFF;
        }
        while (pending.hasNext()) {
            Block block = pending.next();
            try {
                int glow = block.defaultBlockState().getLightEmission();
                if (glow > 0) lightsBuilding.put(BuiltInRegistries.BLOCK.getKey(block).toString(), glow);
                building.put(block, describe(block));
            } catch (Throwable t) {
                // модель мода упала на вопросе: блок остаётся видимым, цвет из цвета карты
                String id = BuiltInRegistries.BLOCK.getKey(block).toString();
                building.put(block, new Info(id, 0xFF000000 | block.defaultMapColor().col, TINT_NONE, block.defaultMapColor() != MapColor.NONE));
            }
            if (System.nanoTime() - start > budgetNanos) {
                buildNanos += System.nanoTime() - start;
                return false;
            }
        }
        buildNanos += System.nanoTime() - start;
        Map<String, Info> names = new HashMap<>(building.size() * 2);
        for (Info info : building.values()) names.put(info.id(), info);
        blocks = java.util.Collections.unmodifiableMap(building);
        byName = java.util.Collections.unmodifiableMap(names);
        building = new IdentityHashMap<>();
        lights = Map.copyOf(lightsBuilding);
        lightsBuilding = new HashMap<>();
        pending = null;
        version.incrementAndGet();
        ready = true;
        RYSMap.LOG.info("[rysmap] colors of {} blocks ready in {} ms of the main thread, adjustments {}", names.size(), buildNanos / 1_000_000, overrides.size());
        return true;
    }

    /** Цвета биомов мира: зовётся при входе в мир, в главном потоке. */
    public void biomes(RegistryAccess access) {
        Map<String, int[]> out = new HashMap<>();
        access.registry(Registries.BIOME).ifPresent(reg -> reg.entrySet().forEach(e -> {
            Biome b = e.getValue();
            out.put(e.getKey().location().toString(), new int[]{b.getGrassColor(0, 0) & 0xFFFFFF, b.getFoliageColor() & 0xFFFFFF, b.getWaterColor() & 0xFFFFFF});
        }));
        biomes = Map.copyOf(out);
        version.incrementAndGet();
    }

    public @Nullable Info info(Block block) {
        return blocks.get(block);
    }

    private Info describe(Block block) {
        String id = BuiltInRegistries.BLOCK.getKey(block).toString();
        BlockState state = block.defaultBlockState();
        if (state.isAir()) return new Info(id, 0, TINT_NONE, false);
        boolean liquid = block instanceof LiquidBlock;
        int tint = TINT_NONE;
        TextureAtlasSprite sprite;
        if (liquid) {
            sprite = particle(state);
        } else {
            BlockRenderDispatcher renderer = Minecraft.getInstance().getBlockRenderer();
            BakedModel model = renderer.getBlockModel(state);
            List<BakedQuad> quads = model.getQuads(state, Direction.UP, random, ModelData.EMPTY, null);
            if (quads.isEmpty()) quads = model.getQuads(state, null, random, ModelData.EMPTY, null);
            BakedQuad quad = quads.isEmpty() ? null : quads.get(0);
            sprite = quad != null ? quad.getSprite() : model.getParticleIcon(ModelData.EMPTY);
            if (quad != null && quad.isTinted()) tint = tintOf(state, quad.getTintIndex());
        }
        if (!state.getFluidState().isEmpty() && state.getFluidState().getType().isSame(Fluids.WATER) && liquid) tint = TINT_WATER;
        int argb = average(sprite);
        boolean visible;
        if (liquid) {
            visible = true;
        } else if (state.getRenderShape() == RenderShape.INVISIBLE) {
            visible = false;
        } else {
            float coverage = ((argb >>> 24) & 0xFF) / 255f;
            visible = block.defaultMapColor() != MapColor.NONE ? coverage >= 0.2f : coverage >= 0.55f;
        }
        Fix o = overrides.get(id);
        if (o != null) {
            argb = 0xFF000000 | o.rgb;
            tint = o.tint;
            visible = true;
        }
        if ((argb >>> 24) == 0 && visible) argb = 0xFF000000 | block.defaultMapColor().col;
        return new Info(id, argb == 0 ? 0xFF000000 : argb | 0x01000000, tint, visible);
    }

    private static TextureAtlasSprite particle(BlockState state) {
        return Minecraft.getInstance().getBlockRenderer().getBlockModelShaper().getParticleIcon(state);
    }

    /** Оттенок по ответу цветов блока без мира: так игра отдаёт цвет травы и листвы по умолчанию. */
    private static int tintOf(BlockState state, int index) {
        int c;
        try {
            c = Minecraft.getInstance().getBlockColors().getColor(state, null, null, index);
        } catch (Throwable t) {
            return TINT_GRASS; // мод ждёт мир: такие блоки почти всегда красятся травой
        }
        if (c == -1 || (c & 0xFFFFFF) == 0xFFFFFF) return TINT_NONE;
        c &= 0xFFFFFF;
        if (c == (GrassColor.getDefaultColor() & 0xFFFFFF)) return TINT_GRASS;
        if (c == (FoliageColor.getDefaultColor() & 0xFFFFFF)) return TINT_FOLIAGE;
        return 0xFF000000 | c;
    }

    /** Средний цвет первого кадра, взвешенный по альфе; альфа ответа это доля закрытых пикселей. */
    static int average(TextureAtlasSprite sprite) {
        SpriteContents c = sprite.contents();
        var img = c.getOriginalImage();
        int w = c.width(), h = c.height();
        int step = Math.max(1, Math.max(w, h) / 32);
        long r = 0, g = 0, b = 0, a = 0, n = 0;
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int abgr = img.getPixelRGBA(x, y);
                int alpha = (abgr >>> 24) & 0xFF;
                n++;
                if (alpha == 0) continue;
                r += (long) (abgr & 0xFF) * alpha;
                g += (long) ((abgr >> 8) & 0xFF) * alpha;
                b += (long) ((abgr >> 16) & 0xFF) * alpha;
                a += alpha;
            }
        }
        if (a == 0 || n == 0) return 0;
        int coverage = (int) Math.min(255, a / n);
        return (coverage << 24) | ((int) (r / a) << 16) | ((int) (g / a) << 8) | (int) (b / a);
    }

    private static Map<String, Fix> readOverrides(ResourceManager resources) {
        Map<String, Fix> out = new HashMap<>();
        for (String ns : resources.getNamespaces()) {
            ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(ns, "rysmap/colors.json");
            for (Resource res : resources.getResourceStack(loc)) {
                try (Reader reader = res.openAsReader()) {
                    JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                    for (Map.Entry<String, JsonElement> e : json.entrySet()) {
                        Fix o = parse(e.getValue());
                        if (o != null) out.put(e.getKey(), o);
                    }
                } catch (Exception ex) {
                    RYSMap.LOG.warn("[rysmap] {} from {} could not be read: {}", loc, res.sourcePackId(), ex.toString());
                }
            }
        }
        return out;
    }

    private static @Nullable Fix parse(JsonElement e) {
        String color;
        int tint = TINT_NONE;
        if (e.isJsonPrimitive()) {
            color = e.getAsString();
        } else if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            if (!o.has("color")) return null;
            color = o.get("color").getAsString();
            if (o.has("tint")) {
                tint = switch (o.get("tint").getAsString().toLowerCase(Locale.ROOT)) {
                    case "grass" -> TINT_GRASS;
                    case "foliage" -> TINT_FOLIAGE;
                    case "water" -> TINT_WATER;
                    default -> TINT_NONE;
                };
            }
        } else {
            return null;
        }
        if (color.startsWith("#")) color = color.substring(1);
        if (color.length() != 6) return null;
        try {
            return new Fix(Integer.parseInt(color, 16), tint);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Блоки, которые светят сами, по имени: свет 1–15 (слой «Свет»). */
    private volatile Map<String, Integer> lights = Map.of();
    private Map<String, Integer> lightsBuilding = new HashMap<>();

    @Override
    public int light(String blockId) {
        Integer l = lights.get(blockId);
        return l == null ? 0 : l;
    }

    // ---- MapColors для пекаря ----

    @Override
    public int block(String blockId) {
        Info info = byName.get(blockId);
        return info == null ? 0 : info.argb();
    }

    @Override
    public int tint(String blockId) {
        Info info = byName.get(blockId);
        return info == null ? TINT_NONE : info.tint();
    }

    @Override
    public int grass(String biomeId) {
        int[] c = biomes.get(biomeId);
        return c == null ? PLAINS_GRASS : c[0];
    }

    @Override
    public int foliage(String biomeId) {
        int[] c = biomes.get(biomeId);
        return c == null ? PLAINS_FOLIAGE : c[1];
    }

    @Override
    public int water(String biomeId) {
        int[] c = biomes.get(biomeId);
        return c == null ? PLAINS_WATER : c[2];
    }

    @Override
    public int waterTexture() {
        return waterTexture;
    }

    @Override
    public int unknown() {
        return unknown;
    }

    @Override
    public long version() {
        return version.get();
    }

    public int blockCount() {
        return byName.size();
    }

    /** Таблица хоть раз построена: пекарю есть чем красить (во время перестройки служит старая). */
    public boolean built() {
        return !byName.isEmpty();
    }
}
