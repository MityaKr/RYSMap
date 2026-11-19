package works.repka.rysmap.scan;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class UndergroundTest {
    private static int[] ring(int h) {
        int[] r = new int[Underground.RING_POINTS];
        Arrays.fill(r, h);
        return r;
    }

    @Test
    void tallWorkshopOnFlatGroundIsSurface() {
        // цех VENIK: ноги 63, крыша 75, вокруг ровная земля 63, внутри темно
        assertFalse(Underground.decide(false, false, 0, 75 - 63, ring(63), 63));
    }

    @Test
    void caveUnderPlainsAndMountain() {
        assertTrue(Underground.decide(false, false, 0, 30, ring(64), 34), "пещера под равниной");
        assertTrue(Underground.decide(false, false, 0, 40, ring(110), 70), "пещера в горе");
    }

    @Test
    void skyOrSkyLightKeepsSurface() {
        assertFalse(Underground.decide(false, true, 15, 30, ring(64), 34), "видно небо");
        assertFalse(Underground.decide(false, false, 3, 30, ring(64), 34), "свет неба у ног: не входим");
        assertTrue(Underground.decide(true, false, 3, 30, ring(64), 34), "уже в пещере: слабый свет у входа не выводит");
        assertFalse(Underground.decide(true, false, 9, 30, ring(64), 34), "у выхода светло: наверх");
    }

    @Test
    void lowColumnLikeBefore() {
        assertFalse(Underground.decide(false, false, 0, 8, ring(64), 40), "до крыши 8: как раньше, поверхность");
        assertTrue(Underground.decide(true, false, 0, 4, ring(64), 40), "выход при запасе меньше 4");
        assertFalse(Underground.decide(true, false, 0, 3, ring(64), 40));
    }

    @Test
    void hysteresisAroundRing() {
        assertFalse(Underground.decide(false, false, 0, 20, ring(66), 60), "земля выше на 6: не входим");
        assertTrue(Underground.decide(false, false, 0, 20, ring(67), 60), "на 7: входим");
        assertTrue(Underground.decide(true, false, 0, 20, ring(63), 60), "на 3: остаёмся");
        assertFalse(Underground.decide(true, false, 0, 20, ring(62), 60), "на 2: выходим");
    }

    @Test
    void missingChunksFallBackToColumn() {
        int[] r = ring(Underground.MISSING);
        r[0] = 63;
        r[1] = 63;
        r[2] = 63;
        assertTrue(Underground.decide(false, false, 0, 12, r, 63), "три точки из восьми: решает свой столбец");
        r[3] = 63;
        assertFalse(Underground.decide(false, false, 0, 12, r, 63), "половина есть: решает кольцо");
    }

    @Test
    void holdDelaysSwitch() {
        Underground u = new Underground();
        assertFalse(u.update(false, 0, 30, ring(64), 34), "первая проверка: ещё ждём");
        assertTrue(u.update(false, 0, 30, ring(64), 34), "вторая подряд: переключились");
        assertTrue(u.update(true, 15, 30, ring(64), 34), "одна проверка с небом: держимся");
        assertTrue(u.update(false, 0, 30, ring(64), 34), "снова пещера: счёт сброшен");
        u.update(true, 15, 0, ring(64), 64);
        assertFalse(u.update(true, 15, 0, ring(64), 64), "две подряд с небом: поверхность");
    }
}
