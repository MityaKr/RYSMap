package works.repka.rysmap.point;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import works.repka.rysmap.migrate.FtbPoints;
import works.repka.rysmap.migrate.FtbRegionReader;

class PointStoreTest {
    @TempDir
    Path dir;

    static PointStore.Point point(String name, String kind, long created) {
        PointStore.Point p = new PointStore.Point();
        p.name = name;
        p.kind = kind;
        p.created = created;
        p.x = 1192;
        p.y = 68;
        p.z = -409;
        return p;
    }

    @Test
    void saveLoadAndRevision() throws IOException {
        PointStore s = new PointStore();
        long r0 = s.revision();
        PointStore.Point home = s.add(point("Дом", "point", 100));
        home.icon = "home";
        home.color = 2;
        home.from = "ftb";
        home.minimap = false;
        assertTrue(s.revision() > r0);
        Path file = dir.resolve("points.json");
        s.save(file);
        assertTrue(Files.readString(file).contains("\"v\": 1"));
        PointStore back = PointStore.load(file);
        assertEquals(1, back.points().size());
        PointStore.Point p = back.points().get(0);
        assertEquals(home.id, p.id);
        assertEquals("Дом", p.name);
        assertEquals("home", p.icon);
        assertEquals(2, p.color);
        assertEquals(-409, p.z);
        assertFalse(p.minimap);
        assertEquals("ftb", p.from);
        assertTrue(back.remove(p.id));
        assertFalse(back.remove(p.id));
    }

    @Test
    void onlyThreeLatestDeathsKept() {
        PointStore s = new PointStore();
        s.add(point("Шахта", "point", 1));
        for (int i = 1; i <= 5; i++) s.add(point("Гибель " + i, "death", 1000 + i));
        assertEquals(4, s.points().size());
        assertEquals(List.of("Шахта", "Гибель 3", "Гибель 4", "Гибель 5"), s.points().stream().map(p -> p.name).toList());
    }

    @Test
    void nearestColorAndBadVersion() throws IOException {
        assertEquals(0, PointStore.nearestColor(0xE4B23C));
        assertEquals(3, PointStore.nearestColor(0xFF0000), "красный ближе всего к коралловому");
        Path file = dir.resolve("p.json");
        Files.writeString(file, "{\"v\": 3, \"points\": []}");
        assertThrows(IOException.class, () -> PointStore.load(file));
        assertTrue(PointStore.load(dir.resolve("none.json")).points().isEmpty());
    }

    @Test
    void ftbWaypointsSynthetic() throws IOException {
        Path world = dir.resolve("w");
        Files.createDirectories(world.resolve("minecraft_the_end"));
        Files.writeString(world.resolve("dimensions.txt"), "minecraft:the_end\n");
        Files.writeString(world.resolve("block_map.txt"), "");
        Files.writeString(world.resolve("biome_map.txt"), "");
        Files.writeString(world.resolve("minecraft_the_end/waypoints.json"), """
            {"waypoints":[{"hidden":true,"name":"Death #16","x":-15,"y":60,"z":-1,"color":"#FF0000","type":"death"},
            {"hidden":false,"name":"Портал","x":100,"y":70,"z":-200,"color":"#6FA8FF","type":"default"}]}""");
        List<PointStore.Point> points = FtbPoints.read(FtbRegionReader.open(world));
        assertEquals(2, points.size());
        PointStore.Point death = points.get(0), portal = points.get(1);
        assertFalse(death.death(), "место гибели FTB переходит обычной точкой");
        assertEquals(FtbPoints.NOTE_DEATH, death.note);
        assertEquals("skull", death.icon);
        assertFalse(death.minimap, "hidden выключает миникарту");
        assertEquals("minecraft:the_end", death.dimension);
        assertEquals("flag", portal.icon);
        assertEquals(1, portal.color);
        assertEquals("ftb", portal.from);
        assertEquals(-200, portal.z);
    }

    @Test
    void ftbWaypointsFixture() throws IOException {
        String prop = System.getProperty("rysmap.ftbFixture");
        Path root = prop == null ? null : Path.of(prop).resolve("data");
        assumeTrue(root != null && Files.isDirectory(root), "нет fixtures карты FTB");
        int total = 0, expected = 0;
        try (Stream<Path> s = Files.list(root)) {
            for (Path dir : s.filter(Files::isDirectory).toList()) {
                FtbRegionReader.World w = FtbRegionReader.open(dir);
                total += FtbPoints.read(w).size();
                for (Path d : w.dimensions.values()) {
                    Path f = d.resolve("waypoints.json");
                    if (Files.isRegularFile(f)) expected += Files.readString(f).split("\"name\"", -1).length - 1;
                }
            }
        }
        assertEquals(expected, total, "все точки из waypoints.json прочитаны");
        assertTrue(total > 0);
        // все точки FTB остаются: правило «три последних» только для новых смертей
        PointStore store = new PointStore();
        try (Stream<Path> s = Files.list(root)) {
            for (Path dir : s.filter(Files::isDirectory).toList()) for (PointStore.Point p : FtbPoints.read(FtbRegionReader.open(dir))) store.add(p);
        }
        assertEquals(total, store.points().size());
        assertEquals(0, store.points().stream().filter(PointStore.Point::death).count());
        System.out.println("[ftb-fixture] точек " + total + ", после переноса " + store.points().size());
    }
}
