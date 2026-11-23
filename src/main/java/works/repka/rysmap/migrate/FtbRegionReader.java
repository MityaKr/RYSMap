package works.repka.rysmap.migrate;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import org.jetbrains.annotations.Nullable;
import works.repka.rysmap.tile.Palette;
import works.repka.rysmap.tile.RegionData;

/**
 * Читатель разведанной карты FTB Chunks 2101.1.22 ({@code local/ftbchunks/data/<id сервера>/}). Чистая Java без игры:
 * код FTB не используется, читаются только файлы. Формат разобран в плане, раздел 10.3, и сверен по настоящим файлам.
 *
 * <pre>
 * dimensions.txt           по строке на измерение, полный id
 * block_map.txt            "#RRGGBB мод:блок": 24-битный номер блока (не цвет)
 * biome_map.txt            "#XXX мод:биом": номер биома 11 бит
 * &lt;ns_путь&gt;/XXXXX-ZZZZZ.zip регион 512×512, координаты плюс 60000 в шестнадцатеричном виде
 *   chunks.dat             u8 0, u8 версия (3), i16 число; на чанк u8 версия, u8 x, u8 z (0–31), i64 время
 *   data.png  RGBA         высота i16 = A·256 + R (−32767 нет данных); G·256 + B: бит 15 вода, 11–14 свет, 0–10 биом
 *   blocks.png RGB         номер блока столбца
 *   foliage, grass, water  готовые оттенки, не читаются: пекарь считает оттенок сам
 * </pre>
 *
 * Формат нигде не помечен версией, поэтому читатель проверяет признаки и отказывает с причиной, а не гадает.
 */
public final class FtbRegionReader {
    public static final int OFFSET = 60000, NO_HEIGHT = -32767, CHUNKS_VERSION = 3;
    public static final Set<String> ENTRIES = Set.of("chunks.dat", "data.png", "blocks.png", "foliage.png", "grass.png", "water.png");
    /** Имя блока, которого нет в {@code block_map.txt}: в игре его тоже нет, пекарь покрасит его неизвестным. */
    public static final String MISSING_BLOCK = "rysmap:unknown";

    private FtbRegionReader() {}

    /** Формат не тот: причина пишется в лог и в отчёт переезда. */
    public static final class FormatException extends IOException {
        public FormatException(String message) {
            super(message);
        }
    }

    /** Папка карты одного мира FTB: измерения и таблицы номеров. */
    public static final class World {
        public final Path dir;
        /** id измерения → папка, в порядке {@code dimensions.txt}. */
        public final Map<String, Path> dimensions;
        public final Map<Integer, String> blocks, biomes;

        private World(Path dir, Map<String, Path> dimensions, Map<Integer, String> blocks, Map<Integer, String> biomes) {
            this.dir = dir;
            this.dimensions = dimensions;
            this.blocks = blocks;
            this.biomes = biomes;
        }

        public String block(int ftbId) {
            String id = blocks.get(ftbId);
            return id == null ? MISSING_BLOCK : id;
        }

        public String biome(int ftbId) {
            String id = biomes.get(ftbId);
            return id == null ? Palette.NO_BIOME : id;
        }
    }

    public static World open(Path dir) throws IOException {
        for (String f : List.of("dimensions.txt", "block_map.txt", "biome_map.txt"))
            if (!Files.isRegularFile(dir.resolve(f))) throw new FormatException("no " + f + " in " + dir.getFileName());
        Map<String, Path> dims = new LinkedHashMap<>();
        for (String line : Files.readAllLines(dir.resolve("dimensions.txt"), StandardCharsets.UTF_8)) {
            String id = line.trim();
            if (id.isEmpty()) continue;
            // обратное имя берётся отсюда, а не делением папки по подчёркиванию
            dims.put(id, dir.resolve(id.replace(':', '_')));
        }
        return new World(dir, dims, table(dir.resolve("block_map.txt"), 6), table(dir.resolve("biome_map.txt"), 3));
    }

    private static Map<Integer, String> table(Path file, int digits) throws IOException {
        Map<Integer, String> out = new HashMap<>();
        int n = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            n++;
            line = line.trim();
            if (line.isEmpty()) continue;
            int space = line.indexOf(' ');
            if (!line.startsWith("#") || space != digits + 1)
                throw new FormatException(file.getFileName() + ", line " + n + ": expected '#" + "X".repeat(digits) + " id'");
            try {
                out.put(Integer.parseInt(line.substring(1, space), 16), line.substring(space + 1).trim());
            } catch (NumberFormatException e) {
                throw new FormatException(file.getFileName() + ", line " + n + ": the number is not a number");
            }
        }
        return out;
    }

    /** Координаты региона по имени файла {@code XXXXX-ZZZZZ.zip} или null. */
    public static int @Nullable [] coords(String fileName) {
        if (!fileName.endsWith(".zip")) return null;
        String base = fileName.substring(0, fileName.length() - 4);
        int dash = base.indexOf('-');
        if (dash <= 0) return null;
        try {
            return new int[]{Integer.parseInt(base.substring(0, dash), 16) - OFFSET, Integer.parseInt(base.substring(dash + 1), 16) - OFFSET};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Регионы папки измерения (по именам файлов; {@code dimension.regions} не нужен). */
    public static List<Path> regions(Path dimensionDir) throws IOException {
        if (!Files.isDirectory(dimensionDir)) return List.of();
        try (var s = Files.list(dimensionDir)) {
            return s.filter(p -> coords(p.getFileName().toString()) != null).sorted().toList();
        }
    }

    /** Разведанный чанк из {@code chunks.dat}: координаты внутри региона и время правки. */
    public record Chunk(int x, int z, long time) {}

    /** Регион FTB в памяти: на столбец высота и упакованные поля, номера блоков. */
    public static final class Region {
        public final int x, z;
        public final List<Chunk> chunks;
        final short[] height;
        final char[] meta;
        final int[] block;

        Region(int x, int z, List<Chunk> chunks, short[] height, char[] meta, int[] block) {
            this.x = x;
            this.z = z;
            this.chunks = chunks;
            this.height = height;
            this.meta = meta;
            this.block = block;
        }

        public int height(int lx, int lz) {
            return height[lz * RegionData.SIZE + lx];
        }

        public int block(int lx, int lz) {
            return block[lz * RegionData.SIZE + lx];
        }

        public int biome(int lx, int lz) {
            return meta[lz * RegionData.SIZE + lx] & 0x7FF;
        }

        public int light(int lx, int lz) {
            return (meta[lz * RegionData.SIZE + lx] >> 11) & 15;
        }

        public boolean water(int lx, int lz) {
            return (meta[lz * RegionData.SIZE + lx] & 0x8000) != 0;
        }
    }

    /** Читает регион. Картинки живут только внутри вызова. */
    public static Region read(Path zip) throws IOException {
        int[] xz = coords(zip.getFileName().toString());
        if (xz == null) throw new FormatException("name " + zip.getFileName() + " is not XXXXX-ZZZZZ.zip");
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (!ENTRIES.contains(e.getName())) throw new FormatException("extra file in the archive: " + e.getName());
                // оттенки не нужны: пропускаются без распаковки в кучу
                if (e.getName().equals("chunks.dat") || e.getName().equals("data.png") || e.getName().equals("blocks.png")) files.put(e.getName(), in.readAllBytes());
                else files.put(e.getName(), new byte[0]);
            }
        } catch (java.util.zip.ZipException e) {
            throw new FormatException("archive is corrupt: " + e.getMessage());
        }
        if (files.size() != ENTRIES.size()) throw new FormatException("the archive has " + files.size() + " files of " + ENTRIES.size() + ": " + files.keySet());
        List<Chunk> chunks = chunks(files.get("chunks.dat"));
        BufferedImage data = image(files.remove("data.png"), "data.png");
        BufferedImage blocks = image(files.remove("blocks.png"), "blocks.png");
        short[] height = new short[RegionData.AREA];
        char[] meta = new char[RegionData.AREA];
        int[] block = new int[RegionData.AREA];
        int[] row = new int[RegionData.SIZE];
        for (int z = 0; z < RegionData.SIZE; z++) {
            data.getRGB(0, z, RegionData.SIZE, 1, row, 0, RegionData.SIZE);
            for (int x = 0; x < RegionData.SIZE; x++) {
                int argb = row[x];
                height[z * RegionData.SIZE + x] = (short) ((((argb >>> 24) & 0xFF) << 8) | ((argb >> 16) & 0xFF));
                meta[z * RegionData.SIZE + x] = (char) (argb & 0xFFFF);
            }
            blocks.getRGB(0, z, RegionData.SIZE, 1, row, 0, RegionData.SIZE);
            for (int x = 0; x < RegionData.SIZE; x++) block[z * RegionData.SIZE + x] = row[x] & 0xFFFFFF;
        }
        return new Region(xz[0], xz[1], chunks, height, meta, block);
    }

    private static List<Chunk> chunks(byte[] bytes) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int zero = in.readUnsignedByte();
            int version = in.readUnsignedByte();
            if (zero != 0 || version != CHUNKS_VERSION) throw new FormatException("chunks.dat: version " + version + ", readable " + CHUNKS_VERSION);
            int count = in.readShort();
            if (count < 0 || count > 1024) throw new FormatException("chunks.dat: chunk count " + count);
            List<Chunk> out = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                in.readUnsignedByte(); // версия чанка, у RepYS 4
                int x = in.readUnsignedByte(), z = in.readUnsignedByte();
                long time = in.readLong();
                if (x > 31 || z > 31) throw new FormatException("chunks.dat: chunk " + x + "," + z + " outside the region");
                out.add(new Chunk(x, z, time));
            }
            return out;
        } catch (java.io.EOFException e) {
            throw new FormatException("chunks.dat is truncated");
        }
    }

    private static BufferedImage image(byte[] bytes, String name) throws IOException {
        ImageIO.setUseCache(false);
        BufferedImage img;
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            img = ImageIO.read(in);
        }
        if (img == null) throw new FormatException(name + " is not an image");
        if (img.getWidth() != RegionData.SIZE || img.getHeight() != RegionData.SIZE)
            throw new FormatException(name + " " + img.getWidth() + "×" + img.getHeight() + ", expected 512×512");
        return img;
    }

    /**
     * Переносит разведанные чанки региона FTB в слой поверхности. Чанк, который уже снят нашим сканером, не трогается:
     * живой скан новее. Вода без глубины помечается {@link RegionData#WATER_UNKNOWN}, свет блока сохраняется.
     *
     * @return сколько чанков перенесено
     */
    public static int write(Region src, World world, RegionData dst, Palette palette) {
        if (src.x != dst.x || src.z != dst.z) throw new IllegalArgumentException("different regions");
        Map<Integer, Integer> blockIds = new HashMap<>(), biomeIds = new HashMap<>();
        int written = 0;
        for (Chunk c : src.chunks) {
            if (dst.has(c.x(), c.z())) continue;
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int lx = c.x() * 16 + x, lz = c.z() * 16 + z;
                    int h = src.height(lx, lz);
                    if (h == NO_HEIGHT) {
                        dst.clear(lx, lz);
                        continue;
                    }
                    int block = blockIds.computeIfAbsent(src.block(lx, lz), id -> palette.block(world.block(id)));
                    int biome = biomeIds.computeIfAbsent(src.biome(lx, lz), id -> palette.biome(world.biome(id)));
                    int water = src.water(lx, lz) ? RegionData.WATER_UNKNOWN : 0;
                    dst.set(lx, lz, h, block, biome, RegionData.pack(water, src.light(lx, lz)));
                }
            }
            dst.mark(c.x(), c.z(), (int) Math.max(1, c.time() / 1000));
            written++;
        }
        return written;
    }
}
