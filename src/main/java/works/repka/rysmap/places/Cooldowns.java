package works.repka.rysmap.places;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Паузы перемещений по функции (план Essentials 4.6): у дома своя пауза, у «назад» своя, как у команд FTB Essentials.
 * Метки живут в памяти сервера и переживают выход игрока (иначе паузу обходят перезаходом), но не перезапуск.
 * Чистый класс, время передаётся снаружи.
 */
public final class Cooldowns {
    private final Map<UUID, Map<String, Long>> last = new HashMap<>();

    /** Игрок воспользовался функцией в {@code now} мс. */
    public void mark(UUID player, String fn, long now) {
        last.computeIfAbsent(player, k -> new HashMap<>()).put(fn, now);
    }

    /** Сколько секунд паузы осталось (вверх до целой), 0 — можно. */
    public int left(UUID player, String fn, int pauseSeconds, long now) {
        if (pauseSeconds <= 0) return 0;
        Map<String, Long> m = last.get(player);
        Long t = m == null ? null : m.get(fn);
        if (t == null) return 0;
        long ms = t + pauseSeconds * 1000L - now;
        return ms <= 0 ? 0 : (int) ((ms + 999) / 1000);
    }

    /** Снять паузу (оператор, сброс правил). */
    public void reset(UUID player, String fn) {
        Map<String, Long> m = last.get(player);
        if (m != null) m.remove(fn);
    }

    public void clear() {
        last.clear();
    }
}
