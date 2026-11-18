package works.repka.rysmap.scan;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FloorsTest {
    @Test
    void idRoundTrip() {
        String id = Floors.id("minecraft:overworld", -3);
        assertEquals("minecraft:overworld/caves/-3", id);
        assertEquals(-3, Floors.floor(id));
        assertEquals("minecraft:overworld", Floors.base(id));
        assertNull(Floors.floor("minecraft:the_nether"));
        assertEquals("minecraft:the_nether", Floors.base("minecraft:the_nether"));
    }

    @Test
    void floorOfHeight() {
        assertEquals(-4, Floors.of(-64));
        assertEquals(-1, Floors.of(-1));
        assertEquals(0, Floors.of(0));
        assertEquals(3, Floors.of(63));
        assertEquals(-48, Floors.bottom(-3));
        assertEquals(-33, Floors.top(-3));
    }
}
