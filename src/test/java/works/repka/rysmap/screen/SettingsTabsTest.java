package works.repka.rysmap.screen;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class SettingsTabsTest {
    @Test
    void playerSeesNoServerTabs() {
        assertEquals(List.of("minimap", "world", "keys"), SettingsTabs.visible(false, false));
    }

    @Test
    void operatorSeesAll() {
        assertEquals(List.of("minimap", "world", "server", "places", "keys"), SettingsTabs.visible(true, true));
    }

    @Test
    void savedTabFallsBackWhenHidden() {
        List<String> player = SettingsTabs.visible(false, false);
        assertEquals("world", SettingsTabs.current("world", player), "запомненная вкладка открывается");
        assertEquals("minimap", SettingsTabs.current("server", player), "вкладка оператора у игрока: первая");
        assertEquals("minimap", SettingsTabs.current("что-то", player));
    }
}
