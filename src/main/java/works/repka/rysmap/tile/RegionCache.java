package works.repka.rysmap.tile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;

/**
 * Сырые регионы одного измерения в куче с жёстким потолком. Вытесняется давно не нужный регион; несохранённый перед
 * вытеснением пишется на диск. Регионы вокруг игрока (3×3) закреплены и не вытесняются, пока потолок это позволяет.
 *
 * <p>Владелец один: рабочий поток карты. Отсутствие файла запоминается, чтобы не стучаться в диск при каждом вопросе.
 */
public final class RegionCache {
    /** Где регионы лежат на диске. */
    public interface Store {
        /** Регион с диска или null, если файла нет. Нечитаемый файл реализация убирает сама и отвечает null. */
        @Nullable RegionData load(int regionX, int regionZ) throws IOException;

        void save(RegionData region) throws IOException;
    }

    private final Store store;
    private final int cap;
    private final Map<Long, RegionData> loaded = new HashMap<>();
    private final Set<Long> absent = new HashSet<>();
    private final Set<Long> pinned = new HashSet<>();
    private long clock;
    private int loads, saves, evictions;

    /** @param cap сколько сырых регионов держать, не меньше 9 (закреплённые 3×3) */
    public RegionCache(Store store, int cap) {
        this.store = store;
        this.cap = Math.max(9, cap);
    }

    /** Регион из памяти или с диска; null, если его нет нигде. */
    public @Nullable RegionData get(int regionX, int regionZ) throws IOException {
        long key = RegionData.key(regionX, regionZ);
        RegionData r = loaded.get(key);
        if (r != null) {
            r.lastUse = ++clock;
            return r;
        }
        if (absent.contains(key)) return null;
        r = store.load(regionX, regionZ);
        loads++;
        if (r == null) {
            absent.add(key);
            return null;
        }
        put(key, r);
        return r;
    }

    /** Регион, который будет записан: если его нет, создаётся пустой. */
    public RegionData getOrCreate(int regionX, int regionZ) throws IOException {
        RegionData r = get(regionX, regionZ);
        if (r != null) return r;
        long key = RegionData.key(regionX, regionZ);
        absent.remove(key);
        r = new RegionData(regionX, regionZ);
        put(key, r);
        return r;
    }

    /** Только то, что уже в памяти: пекарю для теней соседа, без обращения к диску. */
    public @Nullable RegionData peek(int regionX, int regionZ) {
        return loaded.get(RegionData.key(regionX, regionZ));
    }

    /** Закрепляет 3×3 региона вокруг этого. */
    public void pinAround(int regionX, int regionZ) {
        pinned.clear();
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) pinned.add(RegionData.key(regionX + dx, regionZ + dz));
    }

    private void put(long key, RegionData r) throws IOException {
        r.lastUse = ++clock;
        loaded.put(key, r);
        trim();
    }

    /** Убирает лишнее сверх потолка: сначала незакреплённые, по давности. */
    public void trim() throws IOException {
        while (loaded.size() > cap) {
            RegionData victim = oldest(false);
            if (victim == null) victim = oldest(true);
            if (victim == null) return;
            evict(victim);
        }
    }

    private @Nullable RegionData oldest(boolean includePinned) {
        RegionData best = null;
        for (Map.Entry<Long, RegionData> e : loaded.entrySet()) {
            if (!includePinned && pinned.contains(e.getKey())) continue;
            if (best == null || e.getValue().lastUse < best.lastUse) best = e.getValue();
        }
        return best;
    }

    private void evict(RegionData r) throws IOException {
        if (r.dirty) save(r);
        loaded.remove(RegionData.key(r.x, r.z));
        evictions++;
    }

    private void save(RegionData r) throws IOException {
        store.save(r);
        r.dirty = false;
        saves++;
    }

    /** Пишет всё несохранённое. Ошибка одного региона не мешает остальным; первая ошибка бросается в конце. */
    public void flush() throws IOException {
        IOException first = null;
        for (RegionData r : new ArrayList<>(loaded.values())) {
            if (!r.dirty) continue;
            try {
                save(r);
            } catch (IOException e) {
                if (first == null) first = e;
            }
        }
        if (first != null) throw first;
    }

    /** Сохраняет и отпускает всё: смена измерения, выход из мира. */
    public void close() throws IOException {
        try {
            flush();
        } finally {
            loaded.clear();
            absent.clear();
            pinned.clear();
        }
    }

    public int size() {
        return loaded.size();
    }

    public int cap() {
        return cap;
    }

    public long bytes() {
        return loaded.size() * RegionData.BYTES;
    }

    public boolean contains(int regionX, int regionZ) {
        return loaded.containsKey(RegionData.key(regionX, regionZ));
    }

    public List<RegionData> regions() {
        return new ArrayList<>(loaded.values());
    }

    public int loads() {
        return loads;
    }

    public int saves() {
        return saves;
    }

    public int evictions() {
        return evictions;
    }
}
