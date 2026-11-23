package works.repka.rysmap.migrate;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import works.repka.rysmap.tile.Palette;
import works.repka.rysmap.tile.RegionData;
import works.repka.rysmap.tile.RegionFile;

/** Заготовка того же формата, что у FTB Chunks 2101.1.22, собирается в тесте: в git настоящих файлов нет. */
class FtbRegionReaderTest {
    @TempDir
    Path dir;
    Path world;

    @BeforeEach
    void makeWorld() throws IOException {
        world = dir.resolve("data/1111-2222");
        Files.createDirectories(world.resolve("minecraft_overworld"));
        Files.createDirectories(world.resolve("neovitae_dungeon"));
        Files.writeString(world.resolve("dimensions.txt"), "minecraft:overworld\nneovitae:dungeon\n");
        Files.writeString(world.resolve("block_map.txt"), "#00ABCD minecraft:grass_block\n#123456 create:andesite_casing\n");
        Files.writeString(world.resolve("biome_map.txt"), "#001 minecraft:plains\n#152 minecraft:swamp\n");
    }

    /** Регион -1, 2 с двумя разведанными чанками. */
    static byte[] region(boolean extraFile, int chunksVersion) throws IOException {
        ByteArrayOutputStream chunks = new ByteArrayOutputStream();
        DataOutputStream c = new DataOutputStream(chunks);
        c.writeByte(0);
        c.writeByte(chunksVersion);
        c.writeShort(2);
        c.writeByte(4);
        c.writeByte(1);
        c.writeByte(2);
        c.writeLong(1_794_000_000_000L);
        c.writeByte(4);
        c.writeByte(31);
        c.writeByte(0);
        c.writeLong(1_794_000_500_000L);
        BufferedImage data = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
        BufferedImage blocks = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
        for (int z = 0; z < 512; z++) {
            for (int x = 0; x < 512; x++) {
                int cx = x >> 4, cz = z >> 4;
                boolean explored = (cx == 1 && cz == 2) || (cx == 31 && cz == 0);
                int h = explored ? 64 + (x & 3) : FtbRegionReader.NO_HEIGHT;
                int meta = cx == 31 ? (0x8000 | (9 << 11) | 0x152) : 0x001;
                if (explored && x % 16 == 0 && z % 16 == 0) h = -5; // ниже нуля: знак высоты
                int argb = ((h >> 8) & 0xFF) << 24 | (h & 0xFF) << 16 | meta;
                data.setRGB(x, z, argb);
                blocks.setRGB(x, z, cx == 31 ? 0x123456 : (x == 17 && z == 33 ? 0x777777 : 0x00ABCD));
            }
        }
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(zip)) {
            put(out, "chunks.dat", chunks.toByteArray());
            put(out, "data.png", png(data));
            put(out, "blocks.png", png(blocks));
            BufferedImage tint = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
            byte[] t = png(tint);
            put(out, "foliage.png", t);
            put(out, "grass.png", t);
            put(out, "water.png", t);
            if (extraFile) put(out, "readme.txt", "x".getBytes(StandardCharsets.UTF_8));
        }
        return zip.toByteArray();
    }

    static void put(ZipOutputStream out, String name, byte[] bytes) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(bytes);
        out.closeEntry();
    }

    static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void readsWorldAndRegion() throws IOException {
        FtbRegionReader.World w = FtbRegionReader.open(world);
        assertEquals(List.of("minecraft:overworld", "neovitae:dungeon"), List.copyOf(w.dimensions.keySet()));
        assertEquals("neovitae_dungeon", w.dimensions.get("neovitae:dungeon").getFileName().toString());
        assertEquals("minecraft:grass_block", w.block(0x00ABCD));
        assertEquals(FtbRegionReader.MISSING_BLOCK, w.block(0x777777));
        assertEquals("minecraft:swamp", w.biome(0x152));

        // 0EA5F = 60000 − 1, 0EA62 = 60000 + 2
        Path zip = world.resolve("minecraft_overworld/0EA5F-0EA62.zip");
        Files.write(zip, region(false, 3));
        assertArrayEquals(new int[]{-1, 2}, FtbRegionReader.coords(zip.getFileName().toString()));
        assertEquals(List.of(zip), FtbRegionReader.regions(world.resolve("minecraft_overworld")));

        FtbRegionReader.Region r = FtbRegionReader.read(zip);
        assertEquals(-1, r.x);
        assertEquals(2, r.z);
        assertEquals(2, r.chunks.size());
        assertEquals(new FtbRegionReader.Chunk(1, 2, 1_794_000_000_000L), r.chunks.get(0));
        assertEquals(65, r.height(17, 33));
        assertEquals(-5, r.height(16, 32), "отрицательная высота");
        assertEquals(FtbRegionReader.NO_HEIGHT, r.height(0, 0));
        assertTrue(r.water(31 * 16 + 1, 1));
        assertEquals(9, r.light(31 * 16 + 1, 1));
        assertEquals(0x152, r.biome(31 * 16 + 1, 1));

        RegionData dst = new RegionData(-1, 2);
        Palette p = new Palette();
        assertEquals(2, FtbRegionReader.write(r, w, dst, p));
        assertTrue(dst.has(1, 2) && dst.has(31, 0));
        assertFalse(dst.has(0, 0));
        assertEquals(1_794_000_000, dst.scanned(1, 2), "время правки FTB в секундах");
        assertEquals("minecraft:grass_block", p.blockName(dst.block(17 + 1, 33)));
        assertEquals(FtbRegionReader.MISSING_BLOCK, p.blockName(dst.block(17, 33)), "номер без строки в block_map");
        assertEquals("create:andesite_casing", p.blockName(dst.block(31 * 16 + 1, 1)));
        assertEquals(RegionData.WATER_UNKNOWN, RegionData.water(dst.flags(31 * 16 + 1, 1)), "вода без глубины");
        assertEquals(9, RegionData.light(dst.flags(31 * 16 + 1, 1)));
        assertEquals("minecraft:swamp", p.biomeName(dst.biome(31 * 16 + 1, 1)));

        // живой скан новее: уже снятый чанк не перезаписывается
        RegionData scanned = new RegionData(-1, 2);
        scanned.set(16, 32, 99, 0, 0, 0);
        scanned.mark(1, 2, 2_000_000_000);
        assertEquals(1, FtbRegionReader.write(r, w, scanned, p));
        assertEquals(99, scanned.height(16, 32));

        // и в наш формат без потерь
        RegionData back = RegionFile.decode(RegionFile.encode(List.of(dst)), -1, 2).get(0);
        assertEquals(dst.height(17, 33), back.height(17, 33));
        assertEquals(dst.chunkCount(), back.chunkCount());
    }

    @Test
    void formatSignsChecked() throws IOException {
        Path zip = world.resolve("minecraft_overworld/0EA60-0EA60.zip");
        Files.write(zip, region(true, 3));
        var extra = assertThrows(FtbRegionReader.FormatException.class, () -> FtbRegionReader.read(zip));
        assertTrue(extra.getMessage().contains("extra"), extra.getMessage());

        Files.write(zip, region(false, 2));
        var version = assertThrows(FtbRegionReader.FormatException.class, () -> FtbRegionReader.read(zip));
        assertTrue(version.getMessage().contains("version"), version.getMessage());

        byte[] good = region(false, 3);
        Files.write(zip, java.util.Arrays.copyOf(good, good.length / 2));
        assertThrows(IOException.class, () -> FtbRegionReader.read(zip), "испорченный zip");

        Files.delete(world.resolve("biome_map.txt"));
        var missing = assertThrows(FtbRegionReader.FormatException.class, () -> FtbRegionReader.open(world));
        assertTrue(missing.getMessage().contains("biome_map.txt"));

        assertNull(FtbRegionReader.coords("dimension.regions"));
        assertNull(FtbRegionReader.coords("waypoints.json"));
    }
}
