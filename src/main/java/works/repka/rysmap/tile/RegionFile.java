package works.repka.rysmap.tile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import org.jetbrains.annotations.Nullable;

/**
 * Файл региона {@code r.<x>.<z>.rmap}. Формат (все числа big-endian):
 *
 * <pre>
 * "RYSM"            магия
 * u8  версия        сейчас 1
 * u8  число слоёв
 * i32 x, i32 z      координаты региона: файл, переименованный руками, не читается чужим местом
 * на слой:
 *   u8   этаж       127 поверхность
 *   i64×16          карта разведанных чанков, 1024 бита
 *   i32×1024        время скана чанка, секунды эпохи
 *   i32  длина      сжатых данных
 *   Deflate( i16×262144 высота, u16×262144 номер блока, u8×262144 биом, u8×262144 вода и свет )
 * i32 CRC32 всего, что выше
 * </pre>
 *
 * Запись идёт во временный файл рядом и переименованием, поэтому падение посередине оставляет прежний файл целым.
 * Файл, который не читается (магия, версия, сумма, обрезан), уходит в {@code _broken} рядом с датой в имени.
 */
public final class RegionFile {
    public static final byte[] MAGIC = {'R', 'Y', 'S', 'M'};
    public static final int VERSION = 1;
    private static final int HEADER = 4 + 1 + 1 + 4 + 4;

    private RegionFile() {}

    public static String name(int regionX, int regionZ) {
        return "r." + regionX + "." + regionZ + ".rmap";
    }

    /** Файл не прочитался: причина для лога и отчёта. */
    public static final class BrokenException extends IOException {
        public BrokenException(String message) {
            super(message);
        }
    }

    // ---- запись ----

    public static void write(Path file, List<RegionData> layers) throws IOException {
        if (layers.isEmpty()) throw new IllegalArgumentException("нет слоёв");
        byte[] bytes = encode(layers);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static byte[] encode(List<RegionData> layers) throws IOException {
        RegionData first = layers.get(0);
        ByteArrayOutputStream raw = new ByteArrayOutputStream(256 * 1024);
        DataOutputStream out = new DataOutputStream(raw);
        out.write(MAGIC);
        out.writeByte(VERSION);
        out.writeByte(layers.size());
        out.writeInt(first.x);
        out.writeInt(first.z);
        for (RegionData layer : layers) {
            if (layer.x != first.x || layer.z != first.z) throw new IllegalArgumentException("слои разных регионов");
            out.writeByte(layer.floor);
            for (long bits : layer.present) out.writeLong(bits);
            for (int t : layer.scanned) out.writeInt(t);
            byte[] packed = deflate(layer);
            out.writeInt(packed.length);
            out.write(packed);
        }
        out.flush();
        CRC32 crc = new CRC32();
        byte[] body = raw.toByteArray();
        crc.update(body);
        out.writeInt((int) crc.getValue());
        out.flush();
        return raw.toByteArray();
    }

    private static byte[] deflate(RegionData layer) throws IOException {
        ByteArrayOutputStream packed = new ByteArrayOutputStream(64 * 1024);
        Deflater deflater = new Deflater(5);
        try (DeflaterOutputStream z = new DeflaterOutputStream(packed, deflater, 64 * 1024)) {
            byte[] buf = new byte[8192];
            int n = 0;
            for (short h : layer.height) {
                buf[n++] = (byte) (h >> 8);
                buf[n++] = (byte) h;
                if (n == buf.length) n = drain(z, buf, n);
            }
            for (char b : layer.block) {
                buf[n++] = (byte) (b >> 8);
                buf[n++] = (byte) b;
                if (n == buf.length) n = drain(z, buf, n);
            }
            drain(z, buf, n);
            z.write(layer.biome);
            z.write(layer.flags);
        } finally {
            deflater.end();
        }
        return packed.toByteArray();
    }

    private static int drain(OutputStream out, byte[] buf, int n) throws IOException {
        if (n > 0) out.write(buf, 0, n);
        return 0;
    }

    // ---- чтение ----

    /** Читает все слои. Нет файла: пустой список. */
    public static List<RegionData> read(Path file, int regionX, int regionZ) throws IOException {
        if (!Files.isRegularFile(file)) return List.of();
        return decode(Files.readAllBytes(file), regionX, regionZ);
    }

    /** Слой поверхности или null. */
    public static @Nullable RegionData readSurface(Path file, int regionX, int regionZ) throws IOException {
        for (RegionData layer : read(file, regionX, regionZ)) if (layer.floor == RegionData.SURFACE) return layer;
        return null;
    }

    public static List<RegionData> decode(byte[] bytes, int regionX, int regionZ) throws IOException {
        if (bytes.length < HEADER + 4) throw new BrokenException("файл короче заголовка: " + bytes.length + " байт");
        for (int i = 0; i < 4; i++) if (bytes[i] != MAGIC[i]) throw new BrokenException("нет магии RYSM");
        int version = bytes[4] & 0xFF;
        if (version != VERSION) throw new BrokenException("версия " + version + ", читается " + VERSION);
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, bytes.length - 4);
        int stored = ((bytes[bytes.length - 4] & 0xFF) << 24) | ((bytes[bytes.length - 3] & 0xFF) << 16)
            | ((bytes[bytes.length - 2] & 0xFF) << 8) | (bytes[bytes.length - 1] & 0xFF);
        if (stored != (int) crc.getValue()) throw new BrokenException("сумма не сошлась");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, 5, bytes.length - 9));
            int count = in.readUnsignedByte();
            int x = in.readInt(), z = in.readInt();
            if (x != regionX || z != regionZ) throw new BrokenException("в файле регион " + x + "," + z + ", ждали " + regionX + "," + regionZ);
            List<RegionData> layers = new ArrayList<>(count);
            for (int l = 0; l < count; l++) {
                RegionData layer = new RegionData(x, z, in.readUnsignedByte());
                for (int i = 0; i < layer.present.length; i++) layer.present[i] = in.readLong();
                for (int i = 0; i < layer.scanned.length; i++) layer.scanned[i] = in.readInt();
                int length = in.readInt();
                if (length < 0 || length > in.available()) throw new BrokenException("слой " + l + ": длина " + length + " больше файла");
                inflate(new BoundedStream(in, length), layer);
                layer.dirty = false;
                layers.add(layer);
            }
            return layers;
        } catch (EOFException e) {
            throw new BrokenException("файл обрезан");
        }
    }

    private static void inflate(InputStream packed, RegionData layer) throws IOException {
        Inflater inflater = new Inflater();
        try (DataInputStream in = new DataInputStream(new InflaterInputStream(packed, inflater, 64 * 1024))) {
            byte[] buf = new byte[8192];
            int pos = 0;
            while (pos < RegionData.AREA) {
                int n = Math.min(buf.length / 2, RegionData.AREA - pos);
                in.readFully(buf, 0, n * 2);
                for (int i = 0; i < n; i++) layer.height[pos + i] = (short) (((buf[2 * i] & 0xFF) << 8) | (buf[2 * i + 1] & 0xFF));
                pos += n;
            }
            pos = 0;
            while (pos < RegionData.AREA) {
                int n = Math.min(buf.length / 2, RegionData.AREA - pos);
                in.readFully(buf, 0, n * 2);
                for (int i = 0; i < n; i++) layer.block[pos + i] = (char) (((buf[2 * i] & 0xFF) << 8) | (buf[2 * i + 1] & 0xFF));
                pos += n;
            }
            in.readFully(layer.biome);
            in.readFully(layer.flags);
        } catch (EOFException e) {
            throw new BrokenException("сжатые данные обрезаны");
        } catch (java.util.zip.ZipException e) {
            throw new BrokenException("сжатые данные испорчены: " + e.getMessage());
        } finally {
            inflater.end();
        }
    }

    /**
     * Переносит нечитаемый файл в {@code _broken} рядом: исходник не теряется, а карта перерисует кусок при проходе.
     *
     * @return новое место файла
     */
    public static Path quarantine(Path file) throws IOException {
        Path dir = file.resolveSibling("_broken");
        Files.createDirectories(dir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path target = dir.resolve(file.getFileName() + "." + stamp);
        for (int i = 1; Files.exists(target); i++) target = dir.resolve(file.getFileName() + "." + stamp + "-" + i);
        Files.move(file, target);
        return target;
    }

    /** Ограничивает чтение длиной слоя: Inflater иначе забирает лишнее из буфера. */
    private static final class BoundedStream extends InputStream {
        private final InputStream in;
        private int left;

        BoundedStream(InputStream in, int length) {
            this.in = in;
            this.left = length;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) return -1;
            int b = in.read();
            if (b >= 0) left--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = in.read(b, off, Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }

        @Override
        public void close() throws IOException {
            // остаток слоя пропускается: следующий слой начинается сразу после
            while (left > 0) {
                long skipped = in.skip(left);
                if (skipped <= 0) {
                    if (in.read() < 0) break;
                    left--;
                } else {
                    left -= (int) skipped;
                }
            }
        }
    }
}
