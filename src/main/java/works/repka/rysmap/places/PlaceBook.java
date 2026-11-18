package works.repka.rysmap.places;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Места сервера в памяти (план Essentials 4.7): дома игроков, места сервера (бывшие варпы FTB), история «назад».
 * Чистый класс, без игры: хранение в {@code world/data/rysmap_places.dat} делает {@code server.PlaceStore}, переезд с FTB
 * Essentials пишет сюда же ({@link FtbPlaces}). Не потокобезопасен: только поток сервера.
 *
 * <p>Дом команды здесь не живёт: он в данных команды ({@code TeamsApi.putData}), переживает слияние и уходит при распаде.
 */
public final class PlaceBook {
    /** Имя дома, если игрок его не дал: как у FTB Essentials. */
    public static final String DEFAULT_HOME = "home";

    /** Кто видит место сервера. */
    public enum Vis { ALL, MODERATORS, OPERATORS }

    /** Место: дом или место сервера. */
    public static final class Place {
        public String name = "";
        public String dim = "minecraft:overworld";
        public double x, y, z;
        public float yRot, xRot;
        /** Задан ли взгляд (у части записей FTB его нет). */
        public boolean facing;
        /** Значок по именам значков точек ({@code flag}, {@code home}…). */
        public String icon = "flag";
        /** Номер цвета из двенадцати, -1 цвет измерения. */
        public int color = -1;
        public String note = "";
        public Vis vis = Vis.ALL;
        /** Кто поставил (для мест сервера), может не быть. */
        public @Nullable UUID by;
        public String byName = "";
        public long at;

        public Place copy() {
            Place p = new Place();
            p.name = name;
            p.dim = dim;
            p.x = x;
            p.y = y;
            p.z = z;
            p.yRot = yRot;
            p.xRot = xRot;
            p.facing = facing;
            p.icon = icon;
            p.color = color;
            p.note = note;
            p.vis = vis;
            p.by = by;
            p.byName = byName;
            p.at = at;
            return p;
        }

        public static Place at(String name, String dim, double x, double y, double z, float yRot, float xRot) {
            Place p = new Place();
            p.name = name;
            p.dim = dim;
            p.x = x;
            p.y = y;
            p.z = z;
            p.yRot = yRot;
            p.xRot = xRot;
            p.facing = true;
            return p;
        }
    }

    /** Запись истории «назад»: откуда ушёл игрок и почему. */
    public static final class Back {
        public String dim = "minecraft:overworld";
        public double x, y, z;
        public float yRot, xRot;
        public boolean facing;
        /** Причина ухода: {@code death} для места гибели, иначе id причины перемещения. */
        public String reason = "other";
        public long at;

        public boolean death() {
            return "death".equals(reason);
        }

        public static Back of(String dim, double x, double y, double z, float yRot, float xRot, boolean facing, String reason, long at) {
            Back b = new Back();
            b.dim = dim;
            b.x = x;
            b.y = y;
            b.z = z;
            b.yRot = yRot;
            b.xRot = xRot;
            b.facing = facing;
            b.reason = reason;
            b.at = at;
            return b;
        }
    }

    /** Итог записи дома или места. */
    public enum Put { ADDED, MOVED, LIMIT, BAD_NAME }

    private final Map<UUID, LinkedHashMap<String, Place>> homes = new HashMap<>();
    private final LinkedHashMap<String, Place> places = new LinkedHashMap<>();
    /** Новые первыми. */
    private final Map<UUID, Deque<Back>> back = new HashMap<>();
    private long revision;

    /** Растёт с каждой правкой: по нему сервер решает, что слать клиентам и когда писать файл. */
    public long revision() {
        return revision;
    }

    private void changed() {
        revision++;
    }

    // ---- дома ----

    public List<Place> homes(UUID player) {
        LinkedHashMap<String, Place> m = homes.get(player);
        return m == null ? List.of() : List.copyOf(m.values());
    }

    public Set<UUID> homeOwners() {
        return Set.copyOf(homes.keySet());
    }

    public @Nullable Place home(UUID player, String name) {
        LinkedHashMap<String, Place> m = homes.get(player);
        if (m == null || m.isEmpty()) return null;
        if (name == null || name.isBlank()) return m.size() == 1 ? m.values().iterator().next() : m.get(DEFAULT_HOME);
        return m.get(PlaceNames.key(name));
    }

    /**
     * Дом игрока: новое имя добавляется, если домов меньше {@code limit}, старое переставляется. Имя чистится
     * ({@link PlaceNames#clean}), пустое значит {@link #DEFAULT_HOME}.
     */
    public Put setHome(UUID player, Place place, int limit) {
        String name = place.name == null || place.name.isBlank() ? DEFAULT_HOME : PlaceNames.clean(place.name);
        if (name.isEmpty()) return Put.BAD_NAME;
        Place p = place.copy();
        p.name = name;
        LinkedHashMap<String, Place> m = homes.computeIfAbsent(player, k -> new LinkedHashMap<>());
        String key = PlaceNames.key(name);
        // единственный дом при пределе 1 переставляется под любым именем: «/sethome» после переноса с FTB не упирается в лимит
        if (!m.containsKey(key) && m.size() == 1 && limit == 1) {
            Place old = m.values().iterator().next();
            m.clear();
            p.name = old.name;
            m.put(PlaceNames.key(old.name), p);
            changed();
            return Put.MOVED;
        }
        if (!m.containsKey(key) && m.size() >= limit) {
            if (m.isEmpty()) homes.remove(player);
            return Put.LIMIT;
        }
        Put r = m.containsKey(key) ? Put.MOVED : Put.ADDED;
        m.put(key, p);
        changed();
        return r;
    }

    /** Добавить дом без лимита (переезд): существующее имя не трогается. */
    public boolean addHomeIfAbsent(UUID player, Place place) {
        String name = PlaceNames.clean(place.name);
        if (name.isEmpty()) return false;
        LinkedHashMap<String, Place> m = homes.computeIfAbsent(player, k -> new LinkedHashMap<>());
        String key = PlaceNames.key(name);
        if (m.containsKey(key)) return false;
        Place p = place.copy();
        p.name = name;
        m.put(key, p);
        changed();
        return true;
    }

    public boolean removeHome(UUID player, String name) {
        LinkedHashMap<String, Place> m = homes.get(player);
        if (m == null) return false;
        Place p = home(player, name);
        if (p == null) return false;
        m.remove(PlaceNames.key(p.name));
        if (m.isEmpty()) homes.remove(player);
        changed();
        return true;
    }

    // ---- места сервера ----

    public List<Place> places() {
        return List.copyOf(places.values());
    }

    public @Nullable Place place(String name) {
        return places.get(PlaceNames.key(name));
    }

    /** Место сервера: новое добавляется, старое заменяется (значок, цвет, описание переносятся, если в новом не заданы). */
    public Put setPlace(Place place) {
        String name = PlaceNames.clean(place.name);
        if (name.isEmpty()) return Put.BAD_NAME;
        Place p = place.copy();
        p.name = name;
        Place old = places.put(PlaceNames.key(name), p);
        changed();
        return old == null ? Put.ADDED : Put.MOVED;
    }

    public boolean addPlaceIfAbsent(Place place) {
        String name = PlaceNames.clean(place.name);
        if (name.isEmpty() || places.containsKey(PlaceNames.key(name))) return false;
        Place p = place.copy();
        p.name = name;
        places.put(PlaceNames.key(name), p);
        changed();
        return true;
    }

    /** Переименовать место сервера; false, если старого нет или новое имя занято. */
    public boolean renamePlace(String from, String to) {
        Place p = place(from);
        String name = PlaceNames.clean(to);
        if (p == null || name.isEmpty()) return false;
        String key = PlaceNames.key(name);
        if (!key.equals(PlaceNames.key(p.name)) && places.containsKey(key)) return false;
        // порядок сохраняется: пересобрать карту
        List<Map.Entry<String, Place>> entries = new ArrayList<>(places.entrySet());
        places.clear();
        for (Map.Entry<String, Place> e : entries) {
            if (e.getValue() == p) {
                p.name = name;
                places.put(key, p);
            } else {
                places.put(e.getKey(), e.getValue());
            }
        }
        changed();
        return true;
    }

    public boolean removePlace(String name) {
        boolean r = places.remove(PlaceNames.key(name)) != null;
        if (r) changed();
        return r;
    }

    // ---- назад ----

    /** История «назад», новые первыми. */
    public List<Back> back(UUID player) {
        Deque<Back> d = back.get(player);
        return d == null ? List.of() : List.copyOf(d);
    }

    /**
     * Записать место в историю. {@code limit} 0 выключает историю. Гибель пишется, только если {@code deaths}.
     * Повтор того же места подряд (в пределах блока) не пишется.
     */
    public void pushBack(UUID player, Back entry, int limit, boolean deaths) {
        if (limit <= 0 || (entry.death() && !deaths)) return;
        Deque<Back> d = back.computeIfAbsent(player, k -> new ArrayDeque<>());
        Back top = d.peekFirst();
        if (top != null && top.dim.equals(entry.dim) && Math.abs(top.x - entry.x) < 1 && Math.abs(top.y - entry.y) < 1 && Math.abs(top.z - entry.z) < 1
            && top.death() == entry.death()) {
            d.pollFirst();
        }
        d.addFirst(entry);
        while (d.size() > limit) d.pollLast();
        changed();
    }

    /** Добавить старую запись в конец истории (переезд): новее уже записанных она не становится. */
    public void appendBack(UUID player, Back entry, int limit) {
        if (limit <= 0) return;
        Deque<Back> d = back.computeIfAbsent(player, k -> new ArrayDeque<>());
        if (d.size() >= limit) return;
        d.addLast(entry);
        changed();
    }

    /** Последнее место истории или null. */
    public @Nullable Back peekBack(UUID player) {
        Deque<Back> d = back.get(player);
        return d == null ? null : d.peekFirst();
    }

    /** Снять последнее место (после телепорта «назад»). */
    public @Nullable Back popBack(UUID player) {
        Deque<Back> d = back.get(player);
        if (d == null || d.isEmpty()) return null;
        Back b = d.pollFirst();
        if (d.isEmpty()) back.remove(player);
        changed();
        return b;
    }

    /** Убрать запись истории (игрок удалил её из списка «Назад»). */
    public boolean removeBack(UUID player, int index) {
        Deque<Back> d = back.get(player);
        if (d == null || index < 0 || index >= d.size()) return false;
        List<Back> list = new ArrayList<>(d);
        list.remove(index);
        d.clear();
        d.addAll(list);
        changed();
        return true;
    }

    /** Обрезать все истории до нового предела (оператор уменьшил «История назад»). */
    public void trimBack(int limit) {
        boolean any = false;
        for (Deque<Back> d : back.values()) {
            while (d.size() > Math.max(0, limit)) {
                d.pollLast();
                any = true;
            }
        }
        back.values().removeIf(Collection::isEmpty);
        if (any) changed();
    }

    public Set<UUID> backOwners() {
        return Set.copyOf(back.keySet());
    }

    /** Игрок давно ушёл и всё о нём: для сверки переезда. */
    public boolean knows(UUID player) {
        return homes.containsKey(player) || back.containsKey(player);
    }
}
