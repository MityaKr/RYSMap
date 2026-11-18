package works.repka.rysmap.point;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/** Разбор строки точки в чате ({@link ChatPoint}) без классов игры: проверяется модульными тестами. */
public final class ChatText {
    /** Слово «Точка» на двух языках, оба минуса, разряды пробелом; измерение необязательно. */
    public static final Pattern PATTERN = Pattern.compile("\\[(?:Точка|Point): ([^\\[\\]·]{1,40}?) · ([−-]?\\d[\\d   ]*) · ([−-]?\\d[\\d   ]*) · ([−-]?\\d[\\d   ]*)(?: · ([^\\[\\]]{1,40}))?\\]");

    private ChatText() {}

    public record Parsed(String name, int x, int y, int z, @Nullable String dimension) {}

    static int number(String s) {
        return Integer.parseInt(s.replace("−", "-").replaceAll("[   ]", ""));
    }

    public static @Nullable Parsed parse(Matcher m) {
        try {
            return new Parsed(m.group(1).trim(), number(m.group(2)), number(m.group(3)), number(m.group(4)), m.group(5) == null ? null : m.group(5).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
