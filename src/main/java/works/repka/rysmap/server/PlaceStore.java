package works.repka.rysmap.server;

import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import works.repka.rysmap.places.PlaceBook;

/**
 * Хранение мест в {@code world/data/rysmap_places.dat} (план Essentials 4.7): дома, места сервера, история «назад».
 * Сама книга мест чистая ({@link PlaceBook}); здесь только перевод в NBT и отметка «надо записать» по её ревизии.
 */
public final class PlaceStore extends SavedData {
    public static final String NAME = "rysmap_places";
    public static final int V = 1;

    final PlaceBook book;
    private long savedRevision;

    PlaceStore(PlaceBook book) {
        this.book = book;
        this.savedRevision = book.revision();
    }

    public static PlaceStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(() -> new PlaceStore(new PlaceBook()), PlaceStore::load), NAME);
    }

    public PlaceBook book() {
        return book;
    }

    /** Книга изменилась после последней записи: пометить для сохранения с миром. */
    public void touch() {
        if (book.revision() != savedRevision) setDirty();
    }

    @Override
    public boolean isDirty() {
        return super.isDirty() || book.revision() != savedRevision;
    }

    static PlaceStore load(CompoundTag tag, HolderLookup.Provider registries) {
        PlaceBook b = new PlaceBook();
        ListTag homes = tag.getList("homes", Tag.TAG_COMPOUND);
        for (int i = 0; i < homes.size(); i++) {
            CompoundTag h = homes.getCompound(i);
            if (!h.hasUUID("player")) continue;
            UUID id = h.getUUID("player");
            ListTag list = h.getList("list", Tag.TAG_COMPOUND);
            for (int j = 0; j < list.size(); j++) b.addHomeIfAbsent(id, place(list.getCompound(j)));
        }
        ListTag places = tag.getList("places", Tag.TAG_COMPOUND);
        for (int i = 0; i < places.size(); i++) b.addPlaceIfAbsent(place(places.getCompound(i)));
        ListTag back = tag.getList("back", Tag.TAG_COMPOUND);
        for (int i = 0; i < back.size(); i++) {
            CompoundTag h = back.getCompound(i);
            if (!h.hasUUID("player")) continue;
            UUID id = h.getUUID("player");
            ListTag list = h.getList("list", Tag.TAG_COMPOUND);
            for (int j = 0; j < list.size(); j++) {
                CompoundTag e = list.getCompound(j);
                b.appendBack(id, PlaceBook.Back.of(e.getString("dim"), e.getDouble("x"), e.getDouble("y"), e.getDouble("z"), e.getFloat("yRot"), e.getFloat("xRot"),
                    e.getBoolean("facing"), e.getString("reason"), e.getLong("at")), 1000);
            }
        }
        return new PlaceStore(b);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("v", V);
        ListTag homes = new ListTag();
        for (UUID id : book.homeOwners()) {
            CompoundTag h = new CompoundTag();
            h.putUUID("player", id);
            ListTag list = new ListTag();
            for (PlaceBook.Place p : book.homes(id)) list.add(place(p));
            h.put("list", list);
            homes.add(h);
        }
        tag.put("homes", homes);
        ListTag places = new ListTag();
        for (PlaceBook.Place p : book.places()) places.add(place(p));
        tag.put("places", places);
        ListTag back = new ListTag();
        for (UUID id : book.backOwners()) {
            CompoundTag h = new CompoundTag();
            h.putUUID("player", id);
            ListTag list = new ListTag();
            for (PlaceBook.Back e : book.back(id)) {
                CompoundTag c = new CompoundTag();
                c.putString("dim", e.dim);
                c.putDouble("x", e.x);
                c.putDouble("y", e.y);
                c.putDouble("z", e.z);
                c.putFloat("yRot", e.yRot);
                c.putFloat("xRot", e.xRot);
                c.putBoolean("facing", e.facing);
                c.putString("reason", e.reason);
                c.putLong("at", e.at);
                list.add(c);
            }
            h.put("list", list);
            back.add(h);
        }
        tag.put("back", back);
        savedRevision = book.revision();
        return tag;
    }

    static CompoundTag place(PlaceBook.Place p) {
        CompoundTag c = new CompoundTag();
        c.putString("name", p.name);
        c.putString("dim", p.dim);
        c.putDouble("x", p.x);
        c.putDouble("y", p.y);
        c.putDouble("z", p.z);
        c.putFloat("yRot", p.yRot);
        c.putFloat("xRot", p.xRot);
        c.putBoolean("facing", p.facing);
        if (p.surface) c.putBoolean("surface", true);
        c.putString("icon", p.icon);
        c.putInt("color", p.color);
        if (!p.note.isEmpty()) c.putString("note", p.note);
        c.putString("vis", p.vis.name().toLowerCase(java.util.Locale.ROOT));
        if (p.by != null) c.putUUID("by", p.by);
        if (!p.byName.isEmpty()) c.putString("byName", p.byName);
        c.putLong("at", p.at);
        return c;
    }

    static PlaceBook.Place place(CompoundTag c) {
        PlaceBook.Place p = new PlaceBook.Place();
        p.name = c.getString("name");
        p.dim = c.getString("dim");
        p.x = c.getDouble("x");
        p.y = c.getDouble("y");
        p.z = c.getDouble("z");
        p.yRot = c.getFloat("yRot");
        p.xRot = c.getFloat("xRot");
        p.facing = c.getBoolean("facing");
        p.surface = c.getBoolean("surface");
        p.icon = c.contains("icon") ? c.getString("icon") : "flag";
        p.color = c.contains("color") ? c.getInt("color") : -1;
        p.note = c.getString("note");
        p.vis = switch (c.getString("vis")) {
            case "moderators" -> PlaceBook.Vis.MODERATORS;
            case "operators" -> PlaceBook.Vis.OPERATORS;
            default -> PlaceBook.Vis.ALL;
        };
        if (c.hasUUID("by")) p.by = c.getUUID("by");
        p.byName = c.getString("byName");
        p.at = c.getLong("at");
        return p;
    }

    /** Для отчёта переезда: сколько чего. */
    public static String summary(PlaceBook b) {
        int homes = 0, back = 0;
        for (UUID id : b.homeOwners()) homes += b.homes(id).size();
        for (UUID id : b.backOwners()) back += b.back(id).size();
        return "мест сервера " + b.places().size() + ", домов " + homes + ", записей «назад» " + back;
    }
}
