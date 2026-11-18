package works.repka.rysmap.places;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** «Места» без игры: паузы, история «назад», имена в кавычках, дома и места сервера, правила. */
class PlacesTest {
    static final UUID A = UUID.fromString("216ea99a-d6eb-45be-8a5b-3da7852588a5"), B = UUID.fromString("633d6f3f-f318-4004-a3fb-b5131b22c28b");

    // ---- паузы ----

    @Test
    void cooldownCountsUpAndIsPerFunction() {
        Cooldowns c = new Cooldowns();
        assertEquals(0, c.left(A, "home", 10, 1000));
        c.mark(A, "home", 1000);
        assertEquals(10, c.left(A, "home", 10, 1000));
        assertEquals(10, c.left(A, "home", 10, 1001), "дробная секунда вверх");
        assertEquals(1, c.left(A, "home", 10, 10_999));
        assertEquals(0, c.left(A, "home", 10, 11_000));
        assertEquals(0, c.left(A, "back", 30, 1000), "у «назад» своя пауза");
        assertEquals(0, c.left(B, "home", 10, 1000), "у другого игрока своя");
        assertEquals(0, c.left(A, "home", 0, 1000), "пауза 0 выключена");
        c.mark(A, "back", 2000);
        assertEquals(30, c.left(A, "back", 30, 2000));
        c.reset(A, "back");
        assertEquals(0, c.left(A, "back", 30, 2000));
    }

    @Test
    void cooldownFollowsRuleChange() {
        Cooldowns c = new Cooldowns();
        c.mark(A, "random", 0);
        assertEquals(600, c.left(A, "random", 600, 0));
        // оператор уменьшил паузу: остаток считается от новой
        assertEquals(60, c.left(A, "random", 60, 0));
        assertEquals(0, c.left(A, "random", 60, 60_000));
    }

    // ---- история «назад» ----

    static PlaceBook.Back at(double x, String reason) {
        return PlaceBook.Back.of("minecraft:overworld", x, 64, 0, 0, 0, false, reason, (long) x);
    }

    @Test
    void backKeepsNewestFirstAndLimit() {
        PlaceBook b = new PlaceBook();
        for (int i = 1; i <= 12; i++) b.pushBack(A, at(i * 10, "home"), 10, true);
        List<PlaceBook.Back> list = b.back(A);
        assertEquals(10, list.size());
        assertEquals(120, list.get(0).x, 0.001, "новое первым");
        assertEquals(30, list.get(9).x, 0.001, "старые ушли");
        assertEquals(120, b.popBack(A).x, 0.001);
        assertEquals(110, b.peekBack(A).x, 0.001);
    }

    @Test
    void backDeathRuleAndDuplicates() {
        PlaceBook b = new PlaceBook();
        b.pushBack(A, at(5, "death"), 10, false);
        assertTrue(b.back(A).isEmpty(), "гибель не пишется, если правило выключено");
        b.pushBack(A, at(5, "death"), 10, true);
        assertTrue(b.back(A).get(0).death());
        b.pushBack(A, at(5.3, "death"), 10, true);
        assertEquals(1, b.back(A).size(), "то же место подряд не дублируется");
        b.pushBack(A, at(5, "home"), 10, true);
        assertEquals(2, b.back(A).size(), "гибель и уход с того же места разные записи");
        b.pushBack(A, at(100, "home"), 0, true);
        assertEquals(2, b.back(A).size(), "предел 0 выключает историю");
        b.trimBack(1);
        assertEquals(1, b.back(A).size());
        assertTrue(b.removeBack(A, 0));
        assertTrue(b.back(A).isEmpty());
        assertNull(b.popBack(A));
    }

    // ---- имена ----

    @Test
    void namesQuoteWhenNeeded() {
        assertEquals("oil", PlaceNames.quote("oil"));
        assertEquals("nefti_niz", PlaceNames.quote("nefti_niz"));
        assertEquals("\"boos apoth\"", PlaceNames.quote("boos apoth"));
        assertEquals("\"Шахта\"", PlaceNames.quote("Шахта"), "кириллица без кавычек не читается командой");
        assertEquals("\"a\\\"b\"", PlaceNames.quote("a\"b"));
        assertTrue(PlaceNames.unquoted("a-b.c+1"));
        assertFalse(PlaceNames.unquoted(""));
    }

    @Test
    void namesCleanAndKey() {
        assertEquals("boos apoth", PlaceNames.clean("  boos   apoth "));
        assertEquals("ab", PlaceNames.clean("a\"b\u0007"));
        assertEquals("", PlaceNames.clean("   "));
        assertEquals(PlaceNames.MAX, PlaceNames.clean("x".repeat(50)).length());
        assertEquals("шахта", PlaceNames.key(" Шахта "));
    }

    // ---- дома и места ----

    static PlaceBook.Place place(String name, double x) {
        return PlaceBook.Place.at(name, "minecraft:overworld", x, 64, 0, 0, 0);
    }

    @Test
    void homeLimitAndSingleHomeMoves() {
        PlaceBook b = new PlaceBook();
        assertEquals(PlaceBook.Put.ADDED, b.setHome(A, place("", 1), 1));
        assertEquals("home", b.homes(A).get(0).name, "дом без имени зовётся home, как у FTB");
        assertEquals(PlaceBook.Put.MOVED, b.setHome(A, place("база", 2), 1), "при пределе 1 единственный дом переставляется");
        assertEquals(1, b.homes(A).size());
        assertEquals(2, b.home(A, "").x, 0.001);
        assertEquals("home", b.homes(A).get(0).name);
        b.setHome(B, place("one", 1), 2);
        assertEquals(PlaceBook.Put.ADDED, b.setHome(B, place("two", 2), 2));
        assertEquals(PlaceBook.Put.LIMIT, b.setHome(B, place("three", 3), 2));
        assertEquals(PlaceBook.Put.MOVED, b.setHome(B, place("TWO", 5), 2), "имя без учёта регистра");
        assertEquals(5, b.home(B, "two").x, 0.001);
        assertNull(b.home(B, ""), "домов два, без имени и без home не выбрать");
        assertTrue(b.removeHome(B, "One"));
        assertEquals(1, b.homes(B).size());
        assertEquals(PlaceBook.Put.BAD_NAME, b.setHome(B, place("\"\"", 1), 5));
    }

    @Test
    void placesRenameKeepsOrder() {
        PlaceBook b = new PlaceBook();
        b.setPlace(place("cave", 1));
        b.setPlace(place("boos apoth", 2));
        b.setPlace(place("oil", 3));
        assertNotNull(b.place("BOOS APOTH"));
        assertTrue(b.renamePlace("boos apoth", "Аптека"));
        assertFalse(b.renamePlace("cave", "oil"), "занятое имя");
        assertEquals(List.of("cave", "Аптека", "oil"), b.places().stream().map(p -> p.name).toList());
        assertFalse(b.addPlaceIfAbsent(place("Cave", 9)));
        assertTrue(b.removePlace("аптека"));
        assertEquals(2, b.places().size());
    }

    // ---- правила ----

    @Test
    void rulesDefaultsAndJson() {
        PlaceRules r = new PlaceRules();
        assertEquals(10, r.row(PlaceRules.Fn.HOME).pause);
        assertEquals(30, r.row(PlaceRules.Fn.BACK).pause);
        assertEquals(600, r.row(PlaceRules.Fn.RANDOM).pause);
        assertEquals(0, r.row(PlaceRules.Fn.HOME).warmup);
        assertEquals(1, r.homes);
        assertEquals(List.of("minecraft:the_end"), r.randomExcluded);
        r.row(PlaceRules.Fn.REQUEST).who = PlaceRules.Who.OFF;
        r.row(PlaceRules.Fn.HOME).warmup = 3;
        r.homes = 3;
        PlaceRules back = PlaceRules.fromJson(r.toJson());
        assertEquals(r, back);
        assertEquals(PlaceRules.Who.OFF, back.row(PlaceRules.Fn.REQUEST).who);
        JsonObject bad = new JsonObject();
        bad.addProperty("homes", "много");
        bad.addProperty("randomMin", 900);
        bad.addProperty("randomMax", 100);
        PlaceRules c = PlaceRules.fromJson(bad);
        assertEquals(1, c.homes, "нечитаемое остаётся умолчанием");
        assertTrue(c.randomMax > c.randomMin, "до больше от");
    }
}
