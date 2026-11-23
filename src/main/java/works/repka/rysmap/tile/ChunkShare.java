package works.repka.rysmap.tile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * «Поделиться картой с командой» (план карты, раздел 5 «Потом»; 0.2.5), чистая часть: разведанные чанки одного региона
 * поверхности в сжатый пакет и обратно. У каждого клиента своя палитра, поэтому в пакете свой словарь имён блоков и
 * биомов. Получатель дописывает только чанки, которых у него нет: своё не затирается (как перенос FTB).
 *
 * <p>Формат (после Deflate): {@code v=1}, x, z региона, число блоков и их имена, число биомов и их имена, число чанков; на
 * чанк: x и z внутри региона, время скана, 256 столбцов (высота short; если не {@code NO_HEIGHT}: блок varint, биом byte,
 * флаги byte).
 */
public final class ChunkShare {
    public static final int V = 1;
    /** Больше этого распакованный пакет быть не может: 1024 чанка по 256 столбцов с запасом на словарь. */
    static final int MAX_RAW = 1024 * 256 * 9 + 512 * 1024;

    private ChunkShare() {}

    /** Все разведанные чанки региона; null, если отдавать нечего. */
    public static byte[] pack(RegionData r, Palette p) throws IOException {
        List<int[]> chunks = new ArrayList<>();
        for (int cz = 0; cz < RegionData.CHUNKS; cz++)
            for (int cx = 0; cx < RegionData.CHUNKS; cx++)
                if (r.has(cx, cz)) chunks.add(new int[]{cx, cz});
        return pack(r, p, chunks);
    }

    public static byte[] pack(RegionData r, Palette p, List<int[]> chunks) throws IOException {
        if (chunks.isEmpty()) return null;
        Map<Integer, Integer> blocks = new HashMap<>(), biomes = new HashMap<>();
        List<String> blockNames = new ArrayList<>(), biomeNames = new ArrayList<>();
        for (int[] c : chunks) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int lx = c[0] * 16 + x, lz = c[1] * 16 + z;
                    if (r.height(lx, lz) == RegionData.NO_HEIGHT) continue;
                    int b = r.block(lx, lz), bi = r.biome(lx, lz);
                    if (!blocks.containsKey(b)) {
                        blocks.put(b, blockNames.size());
                        blockNames.add(p.blockName(b));
                    }
                    if (!biomes.containsKey(bi)) {
                        biomes.put(bi, biomeNames.size());
                        biomeNames.add(p.biomeName(bi));
                    }
                }
            }
        }
        if (biomeNames.size() > 256) throw new IOException("more than 256 biomes in the region");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes))) {
            out.writeByte(V);
            out.writeInt(r.x);
            out.writeInt(r.z);
            out.writeShort(blockNames.size());
            for (String s : blockNames) out.writeUTF(s);
            out.writeShort(biomeNames.size());
            for (String s : biomeNames) out.writeUTF(s);
            out.writeShort(chunks.size());
            for (int[] c : chunks) {
                out.writeByte(c[0]);
                out.writeByte(c[1]);
                out.writeInt(r.scanned(c[0], c[1]));
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int lx = c[0] * 16 + x, lz = c[1] * 16 + z;
                        int h = r.height(lx, lz);
                        out.writeShort(h);
                        if (h == RegionData.NO_HEIGHT) continue;
                        varint(out, blocks.get(r.block(lx, lz)));
                        out.writeByte(biomes.get(r.biome(lx, lz)));
                        out.writeByte(r.flags(lx, lz));
                    }
                }
            }
        }
        return bytes.toByteArray();
    }

    /** Регион пакета {x, z} без распаковки всего. */
    public static int[] region(byte[] data) throws IOException {
        try (DataInputStream in = open(data)) {
            if (in.readByte() != V) throw new IOException("wrong map packet version");
            return new int[]{in.readInt(), in.readInt()};
        }
    }

    /**
     * Дописать чанки пакета, которых в {@code dst} нет. Регион пакета должен совпасть с {@code dst}.
     *
     * @return сколько чанков дописано
     */
    public static int merge(byte[] data, RegionData dst, Palette p) throws IOException {
        try (DataInputStream in = open(data)) {
            if (in.readByte() != V) throw new IOException("wrong map packet version");
            int rx = in.readInt(), rz = in.readInt();
            if (rx != dst.x || rz != dst.z) throw new IOException("packet region " + rx + " " + rz + ", not " + dst.x + " " + dst.z);
            int nb = in.readUnsignedShort();
            int[] blockIds = new int[nb];
            for (int i = 0; i < nb; i++) blockIds[i] = p.block(in.readUTF());
            int nbi = in.readUnsignedShort();
            int[] biomeIds = new int[nbi];
            for (int i = 0; i < nbi; i++) biomeIds[i] = p.biome(in.readUTF());
            int chunks = in.readUnsignedShort();
            if (chunks > RegionData.CHUNKS * RegionData.CHUNKS) throw new IOException("more chunks than the region holds");
            int written = 0;
            for (int k = 0; k < chunks; k++) {
                int cx = in.readUnsignedByte(), cz = in.readUnsignedByte();
                int time = in.readInt();
                if (cx >= RegionData.CHUNKS || cz >= RegionData.CHUNKS) throw new IOException("chunk outside the region");
                boolean take = !dst.has(cx, cz);
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int lx = cx * 16 + x, lz = cz * 16 + z;
                        short h = in.readShort();
                        if (h == RegionData.NO_HEIGHT) {
                            if (take) dst.clear(lx, lz);
                            continue;
                        }
                        int b = readVarint(in), bi = in.readUnsignedByte(), flags = in.readUnsignedByte();
                        if (b >= nb || bi >= nbi) throw new IOException("block or biome id outside the dictionary");
                        if (take) dst.set(lx, lz, h, blockIds[b], biomeIds[bi], flags);
                    }
                }
                if (take) {
                    dst.mark(cx, cz, Math.max(1, time));
                    written++;
                }
            }
            return written;
        }
    }

    private static DataInputStream open(byte[] data) {
        // распакованный пакет не больше MAX_RAW: защита от «бомбы» в чужом пакете
        return new DataInputStream(new java.io.BufferedInputStream(new LimitedInput(new InflaterInputStream(new ByteArrayInputStream(data)), MAX_RAW)));
    }

    private static void varint(DataOutputStream out, int v) throws IOException {
        while ((v & ~0x7F) != 0) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v);
    }

    private static int readVarint(DataInputStream in) throws IOException {
        int v = 0, shift = 0;
        while (true) {
            int b = in.readUnsignedByte();
            v |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return v;
            shift += 7;
            if (shift > 28) throw new IOException("varint longer than 5 bytes");
        }
    }

    /** Поток, который отказывает после {@code limit} байт. */
    private static final class LimitedInput extends java.io.FilterInputStream {
        private long left;

        LimitedInput(java.io.InputStream in, long limit) {
            super(in);
            this.left = limit;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) throw new IOException("map packet unpacks beyond the limit");
            int b = super.read();
            if (b >= 0) left--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) throw new IOException("map packet unpacks beyond the limit");
            int n = super.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }
    }
}
