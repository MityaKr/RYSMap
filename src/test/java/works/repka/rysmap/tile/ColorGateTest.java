package works.repka.rysmap.tile;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Баг 19.11: регионы вокруг игрока, испечённые до готовности таблицы цветов, оставались серыми со штриховкой,
 * потому что первая смена версии таблицы не перепекала тайлы.
 */
class ColorGateTest {
    @Test
    void holdsUntilTableBuilt() {
        ColorGate g = new ColorGate();
        assertEquals(ColorGate.Step.HOLD, g.update(false, 0), "таблицы нет: тайлы не заказывать");
        assertEquals(ColorGate.Step.HOLD, g.update(false, 1), "биомы мира подняли версию, таблицы всё ещё нет");
    }

    @Test
    void firstBuildDropsTiles() {
        ColorGate g = new ColorGate();
        g.update(false, 1);
        assertEquals(ColorGate.Step.DROP, g.update(true, 2), "первая готовность таблицы тоже перепекает");
        assertEquals(ColorGate.Step.KEEP, g.update(true, 2));
    }

    @Test
    void firstBuildDropsEvenAtVersionZero() {
        // старая сессия начинала с -1 и пропускала первую смену; версия могла быть любой, и 0 тоже
        ColorGate g = new ColorGate();
        assertEquals(ColorGate.Step.DROP, g.update(true, 0));
        assertEquals(ColorGate.Step.KEEP, g.update(true, 0));
    }

    @Test
    void laterChangesDrop() {
        ColorGate g = new ColorGate();
        g.update(true, 5);
        assertEquals(ColorGate.Step.KEEP, g.update(true, 5));
        assertEquals(ColorGate.Step.DROP, g.update(true, 6), "смена темы или ресурспака");
        assertEquals(ColorGate.Step.KEEP, g.update(true, 6));
    }

    @Test
    void rebuildKeepsOldTable() {
        // смена ресурспаков: ready() падает, но старая таблица служит пекарю, ждать не нужно
        ColorGate g = new ColorGate();
        g.update(true, 3);
        assertEquals(ColorGate.Step.KEEP, g.update(true, 3));
    }
}
