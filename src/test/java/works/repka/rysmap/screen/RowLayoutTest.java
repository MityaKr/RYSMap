package works.repka.rysmap.screen;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RowLayoutTest {
    @Test
    void fitsInline() {
        RowLayout.Place p = RowLayout.place(80, 150, 90, 300);
        assertFalse(p.below());
        assertEquals(0, p.controlDy());
        assertTrue(p.height() >= RowLayout.CONTROL_H + 3, "строка не ниже переключателя");
    }

    @Test
    void wideControlGoesBelow() {
        // «Чужие игроки на карте»: пояснение 250, четыре сегмента 230, строка 300 — раньше кнопки наезжали на текст
        RowLayout.Place p = RowLayout.place(110, 250, 230, 300);
        assertTrue(p.below());
        assertTrue(p.controlDy() >= RowLayout.LABEL_BLOCK, "кнопки ниже пояснения");
        assertEquals(1, p.noteLines());
        assertTrue(p.height() >= p.controlDy() + RowLayout.CONTROL_H, "высота по содержимому");
    }

    @Test
    void longNoteWraps() {
        RowLayout.Place p = RowLayout.place(110, 420, 100, 300);
        assertTrue(p.below());
        assertEquals(2, p.noteLines());
        RowLayout.Place one = RowLayout.place(110, 250, 230, 300);
        assertTrue(p.controlDy() > one.controlDy(), "две строки пояснения опускают кнопки");
    }

    @Test
    void labelOnlyBelow() {
        RowLayout.Place p = RowLayout.place(200, 0, 150, 300);
        assertTrue(p.below());
        assertEquals(0, p.noteLines());
        assertEquals(RowLayout.LABEL_ONLY + 3, p.controlDy());
    }
}
