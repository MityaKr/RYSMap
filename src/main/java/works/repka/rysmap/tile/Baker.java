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

    private final MapColors colors;
    private Palette palette;
    private long version = Long.MIN_VALUE;
    private int[] blockArgb = new int[0], blockTint = new int[0];
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
        int base = block < blockArgb.length ? blockArgb[block] : colors.unknown();
        int tint = block < blockTint.length ? blockTint[block] : MapColors.TINT_NONE;
        int rgb = switch (tint) {
            case MapColors.TINT_NONE -> base;
            case MapColors.TINT_GRASS -> multiply(base, at(grass, biome));
            case MapColors.TINT_FOLIAGE -> multiply(base, at(foliage, biome));
            case MapColors.TINT_WATER -> multiply(base, at(water, biome));
            default -> multiply(base, tint);
        };
        int depth = RegionData.water(flags);
        if (depth == 0) {
            int hn = neighborHeight(r, n, lx - 1, lz - 1);
            rgb = shade(rgb, hn == RegionData.NO_HEIGHT ? 0 : h - hn);
        } else {
            int surface = multiply(colors.waterTexture(), at(water, biome));
            float t = depth == RegionData.WATER_UNKNOWN ? 0.75f : Math.min(0.9f, 0.62f + depth * 0.025f);
            rgb = mix(rgb, surface, t);
        }
        return toAbgr(rgb);
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
            grass = foliage = water = new int[0];
        }
        if (blockArgb.length < p.blockCount()) {
            int from = blockArgb.length;
            blockArgb = Arrays.copyOf(blockArgb, p.blockCount());
            blockTint = Arrays.copyOf(blockTint, p.blockCount());
            for (int id = from; id < p.blockCount(); id++) {
                String name = p.blockName(id);
                int c = colors.block(name);
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

    /** Тень по разнице высот: выше соседа светлее, ниже темнее, как на бумажной карте игры. */
    static int shade(int rgb, int dh) {
        if (dh == 0) return rgb & 0xFFFFFF;
        float f = dh > 0 ? Math.min(1.16f, 1f + 0.05f * dh) : Math.max(0.74f, 1f + 0.06f * dh);
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
