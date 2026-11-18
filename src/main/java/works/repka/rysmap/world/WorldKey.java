package works.repka.rysmap.world;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * Ключ мира: имя папки карты в {@code rysmap/}. Одиночная игра даёт имя сохранения, сервер без серверной части карты
 * даёт адрес. UUID мира от сервера ({@code MapHello}) придёт во втором этапе вместе с серверной частью.
 *
 * @param id   имя папки: только {@code [a-z0-9._-]}
 * @param name что показать игроку: имя сохранения или адрес
 */
public record WorldKey(String id, String name) {
    private static final int MAX = 64;

    public static WorldKey singleplayer(String saveFolder) {
        return new WorldKey("sp-" + safe(saveFolder), saveFolder);
    }

    public static WorldKey server(String address) {
        String a = address.trim().toLowerCase(Locale.ROOT);
        if (a.endsWith(":25565")) a = a.substring(0, a.length() - 6);
        return new WorldKey("mp-" + safe(a), address.trim());
    }

    public static WorldKey world(UUID id, String name) {
        return new WorldKey("w-" + id, name);
    }

    /** Имя, годное для папки на любой системе; длинное укорачивается с суммой, чтобы два длинных имени не слились. */
    public static String safe(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            b.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-' ? c : '_');
        }
        String out = b.toString();
        while (out.startsWith(".")) out = "_" + out.substring(1);
        if (out.isEmpty()) out = "_";
        if (out.length() > MAX) {
            CRC32 crc = new CRC32();
            crc.update(s.getBytes(StandardCharsets.UTF_8));
            out = out.substring(0, MAX - 9) + "-" + String.format("%08x", crc.getValue());
        }
        return out;
    }
}
