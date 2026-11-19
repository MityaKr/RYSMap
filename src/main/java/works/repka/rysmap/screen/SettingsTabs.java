package works.repka.rysmap.screen;

import java.util.ArrayList;
import java.util.List;

/** Вкладки страницы настроек карты (0.2.10): какие видны и какая открыта. Чистая часть без игры, проверяется модульным тестом. */
final class SettingsTabs {
    /** id вкладок: в настройке игрока {@code settingsTab} и в ключе подписи {@code rysmap.settings.tab.<id>}. */
    static final String MINIMAP = "minimap", WORLD = "world", SERVER = "server", PLACES = "places", KEYS = "keys";

    private SettingsTabs() {}

    /** Вкладки, которые видит игрок: «Сервер» и «Перемещения» только оператору. */
    static List<String> visible(boolean serverRules, boolean places) {
        List<String> out = new ArrayList<>(List.of(MINIMAP, WORLD));
        if (serverRules) out.add(SERVER);
        if (places) out.add(PLACES);
        out.add(KEYS);
        return out;
    }

    /** Открытая вкладка: запомненная, если она видна, иначе первая. */
    static String current(String saved, List<String> visible) {
        return visible.contains(saved) ? saved : visible.get(0);
    }
}
