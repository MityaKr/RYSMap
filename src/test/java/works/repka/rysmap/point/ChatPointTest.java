package works.repka.rysmap.point;

import static org.junit.jupiter.api.Assertions.*;

import java.util.regex.Matcher;
import org.junit.jupiter.api.Test;

class ChatPointTest {
    @Test
    void parsesRussianWithSpacesAndMinus() {
        Matcher m = ChatText.PATTERN.matcher("<Lena_K> вот: [Точка: Шахта · 1 388 · 64 · −434 · Верхний мир] заходи");
        assertTrue(m.find());
        ChatText.Parsed p = ChatText.parse(m);
        assertNotNull(p);
        assertEquals("Шахта", p.name());
        assertEquals(1388, p.x());
        assertEquals(64, p.y());
        assertEquals(-434, p.z());
        assertEquals("Верхний мир", p.dimension());
    }

    @Test
    void parsesEnglishWithoutDimension() {
        Matcher m = ChatText.PATTERN.matcher("[Point: Home base · -12 · 70 · 5]");
        assertTrue(m.find());
        ChatText.Parsed p = ChatText.parse(m);
        assertEquals("Home base", p.name());
        assertEquals(-12, p.x());
        assertNull(p.dimension());
    }

    @Test
    void ignoresOrdinaryBrackets() {
        assertFalse(ChatText.PATTERN.matcher("[Сервер] перезапуск через 5 минут").find());
    }
}
