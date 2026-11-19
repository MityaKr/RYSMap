package works.repka.rysmap.point;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PortalTest {
    @Test
    void overworldToNetherFloors() {
        assertArrayEquals(new int[]{150, -48}, Portal.toOther(Portal.OVERWORLD, 1204, -380));
        assertArrayEquals(new int[]{-1, -1}, Portal.toOther(Portal.OVERWORLD, -1, -8), "вниз, как у игры, а не к нулю");
        assertArrayEquals(new int[]{0, -2}, Portal.toOther(Portal.OVERWORLD, 7, -9));
    }

    @Test
    void netherToOverworldAndOthers() {
        assertArrayEquals(new int[]{1200, -384}, Portal.toOther(Portal.NETHER, 150, -48));
        assertNull(Portal.toOther("minecraft:the_end", 1, 1));
        assertNull(Portal.other("neovitae:dungeon"));
        assertEquals(Portal.NETHER, Portal.other(Portal.OVERWORLD));
    }
}
