package works.repka.rysmap.screen;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Плитка слоёв и полоса «Мира» (0.2.12): раскладка без игры. */
class SidebarLayoutTest {
    /** Сайдбар 122, плитка от wx + 7 шириной 108. */
    private static final int SIDE_X = 7, SIDE_W = 108;

    @Test
    void twelveLayersFitTwoRows() {
        LayerGrid.Grid g = LayerGrid.of(12, SIDE_X, SIDE_W);
        assertEquals(6, g.cols());
        assertEquals(2, g.rows());
        assertTrue(g.tile() >= LayerGrid.MIN_TILE);
        int used = g.cols() * g.tile() + (g.cols() - 1) * LayerGrid.GAP;
        assertTrue(g.x0() >= SIDE_X && g.x0() + used <= SIDE_X + SIDE_W, "сетка в полосе сайдбара");
        // две строки плиток вместо двенадцати строк по 14: на точки остаётся больше 130 единиц
        assertTrue(12 * 14 - g.height() > 130, "высота " + g.height());
    }

    @Test
    void foreignLayersGrowRows() {
        LayerGrid.Grid g = LayerGrid.of(19, SIDE_X, SIDE_W);
        assertEquals(4, g.rows(), "чужие слои добавляют строки, а не прокрутку");
        assertEquals(g.y(18, 0), 3 * (g.tile() + LayerGrid.ROW_GAP));
        assertEquals(g.x(18), g.x(0));
    }

    @Test
    void hitTestFindsTile() {
        LayerGrid.Grid g = LayerGrid.of(12, SIDE_X, SIDE_W);
        int top = 40;
        assertEquals(0, g.at(g.x(0) + 1, top + 1, top));
        assertEquals(7, g.at(g.x(7) + g.tile() / 2.0, g.y(7, top) + 2, top));
        assertEquals(-1, g.at(g.x(0) + g.tile() + 0.5, top + 1, top), "промежуток между плитками ничей");
        assertEquals(-1, g.at(g.x(0), top - 1, top));
    }

    @Test
    void emptyGridHasNoHeight() {
        assertEquals(0, LayerGrid.of(0, SIDE_X, SIDE_W).height());
        assertEquals(1, LayerGrid.of(3, 0, 10).cols(), "узкая полоса: хоть один столбец");
    }

    @Test
    void settingsOnlyForOwnLayersWithSettings() {
        assertEquals(SettingsTabs.MINIMAP, LayerGrid.settingsTab("rysmap", "mobs", false));
        assertEquals(SettingsTabs.WORLD, LayerGrid.settingsTab("rysmap", "points", false));
        assertEquals(SettingsTabs.PLACES, LayerGrid.settingsTab("rysmap", "places", true));
        assertNull(LayerGrid.settingsTab("rysmap", "places", false), "«Перемещения» только у оператора");
        assertNull(LayerGrid.settingsTab("rysmap", "slime", false));
        assertNull(LayerGrid.settingsTab("rysclaims", "mobs", false), "чужие слои настроек не дают");
    }

    // полоса «Мира»: полотно полной раскладки 478 (600 − 122), сжатой 440; кнопки справа съедают 63

    @Test
    void fullLayoutOneRow() {
        WorldBar.Plan p = WorldBar.plan(6, 6, 478 - 63 - 6, 478 - 6, 104, 70, 50, 150, 38, 0);
        assertNotNull(p.modes());
        assertEquals(6, p.world().y(), "мир в одной строке с режимами");
        assertEquals(6 + 104 + WorldBar.GAP, p.world().x());
        assertEquals(70, p.dropW());
        assertTrue(p.segLabels());
        assertNull(p.floor());
        assertEquals(6 + WorldBar.H, p.bottom());
    }

    @Test
    void floorRowUnderWorld() {
        WorldBar.Plan p = WorldBar.plan(6, 6, 409, 472, 104, 70, 50, 150, 38, 130);
        assertNotNull(p.floor());
        assertEquals(6, p.floor().x());
        assertEquals(6 + WorldBar.H + WorldBar.GAP, p.floor().y());
        assertEquals(p.floor().y() + WorldBar.H, p.bottom());
    }

    @Test
    void longDimensionNameShrinksFirst() {
        // 409 − 6 − 104 − 4 = 295 на мир: список 200 не помещается рядом с сегментами 150
        WorldBar.Plan p = WorldBar.plan(6, 6, 409, 472, 104, 200, 50, 150, 38, 0);
        assertEquals(6, p.world().y());
        assertTrue(p.segLabels(), "подписи сегментов остаются, сужается список");
        assertEquals(295 - WorldBar.worldWidth(0, 150), p.dropW());
        assertTrue(p.world().x() + p.world().w() <= 409);
    }

    @Test
    void iconsBeforeSecondRow() {
        // места на мир 120: список 50 и сегменты 150 не влезают, значками 38 влезают
        WorldBar.Plan p = WorldBar.plan(6, 6, 6 + 104 + 4 + 120, 472, 104, 90, 50, 150, 38, 0);
        assertEquals(6, p.world().y());
        assertFalse(p.segLabels());
        assertTrue(p.world().x() + p.world().w() <= 6 + 104 + 4 + 120);
    }

    @Test
    void narrowCanvasWrapsUnderModes() {
        // открыта панель точки в сжатой раскладке: полотну остаётся мало, мир уходит строкой ниже, где кнопок нет
        WorldBar.Plan p = WorldBar.plan(6, 6, 150, 280, 104, 90, 50, 150, 38, 120);
        assertEquals(6, p.world().x());
        assertEquals(6 + WorldBar.H + WorldBar.GAP, p.world().y());
        assertTrue(p.world().x() + p.world().w() <= 280);
        assertEquals(p.world().y() + WorldBar.H + WorldBar.GAP, p.floor().y());
    }

    @Test
    void noModesStartsAtLeft() {
        WorldBar.Plan p = WorldBar.plan(6, 6, 409, 472, 0, 70, 50, 150, 38, 0);
        assertNull(p.modes());
        assertEquals(6, p.world().x());
        assertEquals(6, p.world().y());
    }

    @Test
    void tinyCanvasStillPlacesSomething() {
        WorldBar.Plan p = WorldBar.plan(6, 6, 20, 60, 104, 90, 50, 150, 38, 120);
        assertTrue(p.dropW() >= 1);
        assertFalse(p.segLabels());
        assertTrue(p.floor().w() <= 54);
    }
}
