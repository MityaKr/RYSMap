package works.repka.rysmap.tile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Номера блоков и биомов одного измерения: {@code palette.json} рядом с регионами. Номер раз выдан и не меняется,
 * поэтому регионы хранят только номера. Блок 0 это «нет блока», биом 0 это «неизвестный биом».
 *
 * <p>Блоков до 65535 ({@code u16} в регионе), биомов до 255 ({@code u8}); лишние биомы получают 0. Владелец один:
 * рабочий поток карты.
 */
public final class Palette {
    public static final int VERSION = 1;
    public static final String NO_BLOCK = "minecraft:air", NO_BIOME = "";
    private static final int MAX_BLOCKS = 65535, MAX_BIOMES = 255;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final List<String> blocks = new ArrayList<>(), biomes = new ArrayList<>();
    private final Map<String, Integer> blockIds = new HashMap<>(), biomeIds = new HashMap<>();
    /** Быстрый путь сканера: объект блока или биома игры к номеру, без строки. */
    private final Map<Object, Integer> fastBlocks = new IdentityHashMap<>(), fastBiomes = new IdentityHashMap<>();
    private boolean dirty;
    private int overflow;

    public Palette() {
        blocks.add(NO_BLOCK);
        blockIds.put(NO_BLOCK, 0);
        biomes.add(NO_BIOME);
        biomeIds.put(NO_BIOME, 0);
    }

    public int block(String id) {
        Integer n = blockIds.get(id);
        if (n != null) return n;
        if (blocks.size() > MAX_BLOCKS) {
            overflow++;
            return 0;
        }
        int next = blocks.size();
        blocks.add(id);
        blockIds.put(id, next);
        dirty = true;
        return next;
    }

    public int biome(String id) {
        Integer n = biomeIds.get(id);
        if (n != null) return n;
        if (biomes.size() > MAX_BIOMES) {
            overflow++;
            return 0;
        }
        int next = biomes.size();
        biomes.add(id);
        biomeIds.put(id, next);
        dirty = true;
        return next;
    }

    /** Номер по объекту игры; {@code name} зовётся только для нового объекта. */
    public int block(Object key, java.util.function.Function<Object, String> name) {
        Integer n = fastBlocks.get(key);
        if (n != null) return n;
        int id = block(name.apply(key));
        fastBlocks.put(key, id);
        return id;
    }

    public int biome(Object key, java.util.function.Function<Object, String> name) {
        Integer n = fastBiomes.get(key);
        if (n != null) return n;
        int id = biome(name.apply(key));
        fastBiomes.put(key, id);
        return id;
    }

    public String blockName(int id) {
        return id >= 0 && id < blocks.size() ? blocks.get(id) : NO_BLOCK;
    }

    public String biomeName(int id) {
        return id >= 0 && id < biomes.size() ? biomes.get(id) : NO_BIOME;
    }

    public int blockCount() {
        return blocks.size();
    }

    public int biomeCount() {
        return biomes.size();
    }

    /** Сколько раз номер не выдался из-за предела. */
    public int overflow() {
        return overflow;
    }

    public boolean dirty() {
        return dirty;
    }

    // ---- файл ----

    public static Palette load(Path file) throws IOException {
        Palette p = new Palette();
        if (!Files.isRegularFile(file)) return p;
        JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        int v = json.has("v") ? json.get("v").getAsInt() : 0;
        if (v != VERSION) throw new IOException("palette.json: version " + v + ", readable " + VERSION);
        read(json.getAsJsonArray("blocks"), p.blocks, p.blockIds, NO_BLOCK);
        read(json.getAsJsonArray("biomes"), p.biomes, p.biomeIds, NO_BIOME);
        p.dirty = false;
        return p;
    }

    private static void read(JsonArray array, List<String> list, Map<String, Integer> ids, String zero) throws IOException {
        if (array == null || array.isEmpty()) return;
        if (!zero.equals(array.get(0).getAsString())) throw new IOException("palette.json: id 0 must be '" + zero + "'");
        list.clear();
        ids.clear();
        for (JsonElement e : array) {
            String id = e.getAsString();
            ids.putIfAbsent(id, list.size());
            list.add(id);
        }
    }

    public void save(Path file) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("v", VERSION);
        JsonArray b = new JsonArray();
        blocks.forEach(b::add);
        JsonArray m = new JsonArray();
        biomes.forEach(m::add);
        json.add("blocks", b);
        json.add("biomes", m);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(json), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        dirty = false;
    }
}
