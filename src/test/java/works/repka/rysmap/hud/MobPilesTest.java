package works.repka.rysmap.hud;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class MobPilesTest {
    private static final int ZOMBIE = 1, COW = 2, CREEPER = 3, WITHER = 4;

    @Test
    void separateStaySeparate() {
        float[] xy = {0, 0, 20, 0, 0, 20};
        List<MobPiles.Pile> piles = MobPiles.group(xy, new int[] {2, 0, 1}, new int[] {ZOMBIE, COW, 5}, new float[] {1, 1, 1}, 3, 8);
        assertEquals(3, piles.size());
        for (MobPiles.Pile p : piles) assertEquals(1, p.count());
    }

    @Test
    void crowdMergesUnderMostDangerous() {
        // корова первой во входе, рядом три зомби и крипер: куча красная, голова зомби, число 5
        float[] xy = {10, 10, 11, 10, 12, 11, 10, 12, 13, 13};
        int[] rank = {MobPiles.PASSIVE, MobPiles.HOSTILE, MobPiles.HOSTILE, MobPiles.HOSTILE, MobPiles.HOSTILE};
        int[] type = {COW, ZOMBIE, CREEPER, ZOMBIE, ZOMBIE};
        List<MobPiles.Pile> piles = MobPiles.group(xy, rank, type, new float[] {1, 1, 1, 1, 1}, 5, 8);
        assertEquals(1, piles.size());
        MobPiles.Pile p = piles.get(0);
        assertEquals(5, p.count());
        assertEquals(MobPiles.HOSTILE, p.rank);
        assertEquals(ZOMBIE, type[p.lead()], "голова самого частого враждебного");
        assertEquals(11, p.x, 1e-6, "куча стоит на месте первого враждебного");
    }

    @Test
    void bossLeadsAndAlphaIsBrightest() {
        float[] xy = {0, 0, 3, 0};
        List<MobPiles.Pile> piles = MobPiles.group(xy, new int[] {MobPiles.HOSTILE, MobPiles.BOSS}, new int[] {ZOMBIE, WITHER},
            new float[] {1f, 0.45f}, 2, 8);
        assertEquals(1, piles.size());
        assertEquals(MobPiles.BOSS, piles.get(0).rank);
        assertEquals(1, piles.get(0).lead());
        assertEquals(1f, piles.get(0).alpha(), 1e-6);
    }

    @Test
    void joinsNearestPile() {
        // два враждебных далеко друг от друга, мирный между ними ближе ко второму
        float[] xy = {0, 0, 10, 0, 6.5f, 0};
        List<MobPiles.Pile> piles = MobPiles.group(xy, new int[] {2, 2, 0}, new int[] {ZOMBIE, ZOMBIE, COW}, new float[] {1, 1, 1}, 3, 8);
        assertEquals(2, piles.size());
        assertEquals(1, piles.get(0).count());
        assertEquals(2, piles.get(1).count());
    }

    @Test
    void countText() {
        assertEquals("7", MobPiles.countText(7));
        assertEquals("99+", MobPiles.countText(140));
    }
}
