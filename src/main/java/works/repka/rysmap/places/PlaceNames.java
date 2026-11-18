package works.repka.rysmap.places;

import java.util.Locale;

/**
 * Имена мест (план Essentials 6): в FTB Essentials RepYS есть место {@code "boos apoth"} со словами через пробел, у игроков
 * бывают имена по-русски. Команда принимает имя словом или в кавычках (Brigadier {@code StringArgumentType.string()}),
 * подсказка и строка чата берут имя в кавычки, когда без них оно не читается. В окне имя показывается как есть.
 * Чистый класс, без игры.
 */
public final class PlaceNames {
    /** Длина имени места. */
    public static final int MAX = 32;

    private PlaceNames() {}

    /** Ключ для поиска: регистр не важен, пробелы по краям срезаны. */
    public static String key(String name) {
        return name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Имя после правки игрока: края срезаны, управляющие знаки и кавычки убраны, длина до {@link #MAX}. Пустая строка
     * значит «имя не годится».
     */
    public static String clean(String name) {
        if (name == null) return "";
        StringBuilder b = new StringBuilder();
        name.strip().codePoints().forEach(c -> {
            if (Character.isISOControl(c) || c == '"' || c == '\\' || c == '§') return;
            b.appendCodePoint(Character.isWhitespace(c) ? ' ' : c);
        });
        String s = b.toString().replaceAll(" {2,}", " ").strip();
        if (s.codePointCount(0, s.length()) > MAX) s = s.substring(0, s.offsetByCodePoints(0, MAX)).strip();
        return s;
    }

    /** Можно ли написать имя в команде без кавычек: как {@code StringReader.isAllowedInUnquotedString} у Brigadier. */
    public static boolean unquoted(String name) {
        if (name.isEmpty()) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = c >= '0' && c <= '9' || c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c == '_' || c == '-' || c == '.' || c == '+';
            if (!ok) return false;
        }
        return true;
    }

    /** Имя для команды и подсказки: словом, если можно, иначе в кавычках. */
    public static String quote(String name) {
        if (unquoted(name)) return name;
        return '"' + name.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
