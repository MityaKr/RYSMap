package works.repka.rysmap.tile;

import java.util.Arrays;

/**
 * Пекарь: столбцы региона в пиксели ABGR (порядок байтов текстуры игры). Цвет блока умножается на оттенок биома,
 * тень берётся по разнице высоты с северо-западным соседом, вода темнеет с глубиной. Неразведанное прозрачно.
 *
 * <p>Чистая Java; владелец один: рабочий поток карты. Номера палитры переводятся в цвета один раз и кэшируются.
 */
public final class Baker {
    /** Высоты вне региона: соседний регион, если он уже в памяти. */
    public interface Neighbors {
        /** Высота столбца в мировых координатах или {@link RegionData#NO_HEIGHT}. */
        int height(int worldX, int worldZ);

        Neighbors NONE = (x, z) -> RegionData.NO_HEIGHT;
    }

    /** Слои подсветки поверх цвета (0.2.3): бит {@link #HEIGHTS} высоты горизонталями, бит {@link #LIGHT} источники света. */
    public static final int HEIGHTS = 1, LIGHT = 2;
    private static volatile int overlay;

    /** Ставит главный поток по видимости слоёв «Высоты» и «Свет»; после смены тайлы перепекаются. */
    public static void overlay(int bits) {
        overlay = bits;
    }

    public static int overlay() {
        return overlay;
    }

    private final MapColors colors;
    private Palette palette;
    private long version = Long.MIN_VALUE;
    private int[] blockArgb = new int[0], blockTint = new int[0];
    private boolean[] blockUnknown = new boolean[0];
    private int[] blockLight = new int[0];
    private int[] grass = new int[0], foliage = new int[0], water = new int[0];

    public Baker(MapColors colors) {
        this.colors = colors;
    }

    /** Весь регион: {@code int[512×512]}, строка за строкой с севера. */
    public int[] bakeRegion(RegionData r, Palette p, Neighbors n) {
        int[] out = new int[RegionData.AREA];
        for (int cz = 0; cz < RegionData.CHUNKS; cz++)
            for (int cx = 0; cx < RegionData.CHUNKS; cx++)
                if (r.has(cx, cz)) bakeChunk(r, p, n, cx, cz, out, cz * 16 * RegionData.SIZE + cx * 16, RegionData.SIZE);
        return out;
    }

    /** Один чанк 16×16 в {@code out} с этого места и с этим шагом строки. Неразведанный чанк даёт прозрачные пиксели. */
    public void bakeChunk(RegionData r, Palette p, Neighbors n, int localChunkX, int localChunkZ, int[] out, int offset, int stride) {
        if (!r.has(localChunkX, localChunkZ)) {
            for (int z = 0; z < 16; z++) Arrays.fill(out, offset + z * stride, offset + z * stride + 16, 0);
            return;
        }
        prepare(p);
        int x0 = localChunkX * 16, z0 = localChunkZ * 16;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                out[offset + z * stride + x] = pixel(r, n, x0 + x, z0 + z);
            }
        }
    }

    /** Чанк в отдельный массив 16×16. */
    public int[] bakeChunk(RegionData r, Palette p, Neighbors n, int localChunkX, int localChunkZ) {
        int[] out = new int[256];
        bakeChunk(r, p, n, localChunkX, localChunkZ, out, 0, 16);
        return out;
    }

    private int pixel(RegionData r, Neighbors n, int lx, int lz) {
        int i = RegionData.index(lx, lz);
        int h = r.height[i];
        if (h == RegionData.NO_HEIGHT) return 0;
        int block = r.block[i];
        int biome = r.biome[i] & 0xFF;
        int flags = r.flags[i] & 0xFF;
        boolean unknown = block >= blockArgb.length || blockUnknown[block];
        // неизвестный блок (мод убран, цвета нет): токен map.unknown и редкая светлая штриховка, чтобы не слиться с фоном
        int base = unknown ? (((lx + lz) & 3) == 0 ? hatch(colors.unknown()) : colors.unknown()) : blockArgb[block];
        int tint = block < blockTint.length ? blockTint[block] : MapColors.TINT_NONE;
        int rgb = switch (tint) {
            case MapColors.TINT_NONE -> base;
            case MapColors.TINT_GRASS -> multiply(base, at(grass, biome));
            case MapColors.TINT_FOLIAGE -> multiply(base, at(foliage, biome));
            case MapColors.TINT_WATER -> multiply(base, at(water, biome));
            default -> multiply(base, tint);
        };
        int depth = RegionData.water(flags);
        int ov = overlay;
        if (depth == 0) {
            // рельеф по двум соседям, западу и северу (скептик: от одного северо-западного карта плоская)
            int hw = neighborHeight(r, n, lx - 1, lz), hn = neighborHeight(r, n, lx, lz - 1);
            int dh = (hw == RegionData.NO_HEIGHT ? 0 : h - hw) + (hn == RegionData.NO_HEIGHT ? 0 : h - hn);
            rgb = shade(rgb, dh);
            if ((ov & HEIGHTS) != 0) rgb = heights(rgb, h, hw, hn);
        } else {
            int surface = multiply(colors.waterTexture(), at(water, biome));
            float t = depth == RegionData.WATER_UNKNOWN ? 0.75f : Math.min(0.9f, 0.62f + depth * 0.025f);
            rgb = mix(rgb, surface, t);
        }
        if ((ov & LIGHT) != 0) rgb = light(rgb, !unknown && block < blockLight.length ? blockLight[block] : 0);
        return toAbgr(rgb);
    }

    /** Шаг горизонталей слоя «Высоты», блоки. */
    static final int CONTOUR = 8;

    /**
     * Слой «Высоты»: цвет высоты (низины синие, равнины зелёные, холмы жёлтые, горы бурые, вершины белые) поверх цвета блока
     * и горизонталь через каждые {@link #CONTOUR} блоков: пиксель темнее там, где сосед с запада или севера в другом поясе.
     */
    static int heights(int rgb, int h, int hw, int hn) {
        int band = Math.floorDiv(h, CONTOUR);
        boolean line = hw != RegionData.NO_HEIGHT && Math.floorDiv(hw, CONTOUR) != band || hn != RegionData.NO_HEIGHT && Math.floorDiv(hn, CONTOUR) != band;
        int c = mix(rgb, heightColor(h), 0.5f);
        return line ? mix(c, 0x000000, 0.45f) : c;
    }

    /** Цвет высоты от 0 до 255 по высоте блока. */
    static int heightColor(int h) {
        int[] stops = {-64, 0x2B4C8C, 40, 0x3E7FBF, 63, 0x4FA35A, 90, 0xB8C24A, 130, 0xC98A3E, 180, 0x8C5A3A, 240, 0xF2F2F2};
        if (h <= stops[0]) return stops[1];
        for (int i = 2; i < stops.length; i += 2) {
            if (h <= stops[i]) {
                float t = (h - stops[i - 2]) / (float) (stops[i] - stops[i - 2]);
                return mix(stops[i - 1], stops[i + 1], t);
            }
        }
        return stops[stops.length - 1];
    }

    /** Слой «Свет»: блок, который светит сам, ярко-жёлтый тем ярче, чем сильнее свет; остальное приглушено. */
    static int light(int rgb, int glow) {
        if (glow <= 0) return mix(rgb, 0x101418, 0.45f);
        return mix(rgb, 0xFFE45A, 0.55f + 0.03f * glow);
    }

    /** Светлая полоса штриховки неизвестного блока: тот же цвет, на пятую часть к белому. */
    static int hatch(int argb) {
        return mix(argb, 0xFFFFFF, 0.2f);
    }

    private static int neighborHeight(RegionData r, Neighbors n, int lx, int lz) {
        if (lx >= 0 && lz >= 0) {
            if (!r.has(lx >> 4, lz >> 4)) return RegionData.NO_HEIGHT;
            return r.height[RegionData.index(lx, lz)];
        }
        return n.height(r.x * RegionData.SIZE + lx, r.z * RegionData.SIZE + lz);
    }

    private void prepare(Palette p) {
        long v = colors.version();
        if (p != palette || v != version) {
            palette = p;
            version = v;
            blockArgb = new int[0];
            blockTint = new int[0];
            blockUnknown = new boolean[0];
            blockLight = new int[0];
            grass = foliage = water = new int[0];
        }
        if (blockArgb.length < p.blockCount()) {
            int from = blockArgb.length;
            blockArgb = Arrays.copyOf(blockArgb, p.blockCount());
            blockTint = Arrays.copyOf(blockTint, p.blockCount());
            blockUnknown = Arrays.copyOf(blockUnknown, p.blockCount());
            blockLight = Arrays.copyOf(blockLight, p.blockCount());
            for (int id = from; id < p.blockCount(); id++) {
                String name = p.blockName(id);
                blockLight[id] = colors.light(name);
                int c = colors.block(name);
                blockUnknown[id] = c == 0;
                blockArgb[id] = c == 0 ? colors.unknown() : c;
                blockTint[id] = c == 0 ? MapColors.TINT_NONE : colors.tint(name);
            }
        }
        if (grass.length < p.biomeCount()) {
            int from = grass.length;
            grass = Arrays.copyOf(grass, p.biomeCount());
            foliage = Arrays.copyOf(foliage, p.biomeCount());
            water = Arrays.copyOf(water, p.biomeCount());
            for (int id = from; id < p.biomeCount(); id++) {
                String name = p.biomeName(id);
                grass[id] = colors.grass(name);
                foliage[id] = colors.foliage(name);
                water[id] = colors.water(name);
            }
        }
    }

    private static int at(int[] table, int i) {
        return i < table.length ? table[i] : 0xFFFFFF;
    }

    // ---- цвет ----

    static int multiply(int argb, int rgb) {
        int r = ((argb >> 16) & 0xFF) * ((rgb >> 16) & 0xFF) / 255;
        int g = ((argb >> 8) & 0xFF) * ((rgb >> 8) & 0xFF) / 255;
        int b = (argb & 0xFF) * (rgb & 0xFF) / 255;
        return (r << 16) | (g << 8) | b;
    }

    /**
     * Тень по сумме разниц высот с западным и северным соседом: выше светлее, ниже темнее, как на бумажной карте игры.
     * Коэффициенты в 0.1.0-m10 сильнее (было 5 и 6 % на блок с потолком 1,16 и 0,74 от одного соседа).
     */
    static int shade(int rgb, int dh) {
        if (dh == 0) return rgb & 0xFFFFFF;
        float f = dh > 0 ? Math.min(1.24f, 1f + 0.07f * dh) : Math.max(0.64f, 1f + 0.08f * dh);
        int r = Math.min(255, (int) (((rgb >> 16) & 0xFF) * f));
        int g = Math.min(255, (int) (((rgb >> 8) & 0xFF) * f));
        int b = Math.min(255, (int) ((rgb & 0xFF) * f));
        return (r << 16) | (g << 8) | b;
    }

    static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return (r << 16) | (g << 8) | bl;
    }

    /** RGB в ABGR непрозрачный: так байты лягут в текстуру как R, G, B, A. */
    static int toAbgr(int rgb) {
        return 0xFF000000 | ((rgb & 0xFF) << 16) | (rgb & 0xFF00) | ((rgb >> 16) & 0xFF);
    }
}
