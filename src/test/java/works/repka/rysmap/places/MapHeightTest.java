package works.repka.rysmap.places;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import works.repka.rysmap.scan.Floors;

class MapHeightTest {
    @Test
    void caveFloorUsesMiddleOfFloor() {
        int floor = Floors.of(50);
        Double y = MapHeight.pick(floor, true, 0, 63, 0, 300, 300);
        assertNotNull(y);
        assertTrue(y >= Floors.bottom(floor) && y <= Floors.top(floor), "высота внутри этажа: " + y);
    }

    @Test
    void nearPlayerUsesPlayerFeet() {
        // автор стоит в цехе на Y 63 и ставит дом в двух блоках от себя: под крышей, не на ней
        assertEquals(63.0, MapHeight.pick(null, true, 5368.3, 63, 23476.7, 5370.5, 23477.5));
    }

    @Test
    void farOrOtherDimensionIsColumnTop() {
        assertNull(MapHeight.pick(null, true, 0, 63, 0, 40.5, 0.5), "далеко: верх столбца");
        assertNull(MapHeight.pick(null, false, 0, 63, 0, 1.5, 0.5), "другое измерение: высота игрока не годится");
    }
}
