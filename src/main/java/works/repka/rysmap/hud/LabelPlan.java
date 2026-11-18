package works.repka.rysmap.hud;

import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.ints.IntComparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * Чистая часть подписей точек в мире ({@link WorldLabels}): какие метки рисовать, какая раскрыта, ход анимации.
 * Без Minecraft, проверяется модульными тестами.
 *
 * <p>Кадр: {@link #begin()}, {@link #add} на каждого кандидата, {@link #choose}, затем рисующий кладёт место на экране
 * ({@link #screen}) выбранным, {@link #focus} выбирает раскрытую у прицела, {@link #animate} двигает анимацию.
 *
 * <p>Правила (решение автора 18.11):
 * <ul>
 *   <li>{@link #PINNED} (цель пути, последнее место гибели): видна в любом режиме и на любом расстоянии, раскрыта всегда;</li>
 *   <li>{@link #ALWAYS} (флажок точки «в мире всегда»): видна в любом режиме, но в пределах дальности;</li>
 *   <li>{@link Mode#NEARBY}: все точки в пределах дальности; {@link Mode#ALL}: все точки измерения на любом расстоянии;
 *       {@link Mode#GOAL_DEATH}: только закреплённые и с флажком. Пока зажата клавиша «показать все точки», режим не
 *       ниже {@code NEARBY} и раскрыты все;</li>
 *   <li>не больше {@code limit} меток: закреплённые всегда, остальные ближайшие;</li>
 *   <li>раскрыта одна метка, ближайшая к прицелу в пределах порога; чтобы две соседние не перехватывали раскрытие
 *       друг у друга, новая забирает его, только если ближе к прицелу на {@link #HYSTERESIS}.</li>
 * </ul>
 *
 * <p>Память: массивы растут только вместе с числом точек, состояние анимации заводится при появлении метки; в кадре
 * выделений на точку нет.
 */
public final class LabelPlan {
    /** Какие точки видны в мире: настройка {@code worldLabels} в {@code client.json}. */
    public enum Mode {
        GOAL_DEATH("goal"), NEARBY("nearby"), ALL("all");

        public final String id;

        Mode(String id) {
            this.id = id;
        }

        public static Mode of(@Nullable String id) {
            if (id != null) for (Mode m : values()) if (m.id.equals(id.toLowerCase(Locale.ROOT))) return m;
            return NEARBY;
        }
    }

    /** Цель пути и последнее место гибели: видна всегда, раскрыта всегда. */
    public static final int PINNED = 1;
    /** Флажок точки «в мире всегда»: видна в любом режиме (в пределах дальности). */
    public static final int ALWAYS = 2;
    /** Ближе этого метка не рисуется: игрок стоит на точке. */
    public static final double MIN_DISTANCE = 2;
    /** Ближе этого значок в полный размер и яркость. */
    public static final double NEAR = 24;
    /** На сколько единиц новая метка должна быть ближе к прицелу, чтобы забрать раскрытие у прежней. */
    public static final float HYSTERESIS = 4f;
    /** Полный ход раскрытия и появления, секунды. */
    public static final float DURATION = 0.14f;
    /** Самый мелкий и самый бледный дальний значок. */
    public static final float FAR_SCALE = 0.6f, FAR_ALPHA = 0.5f;

    /** Состояние метки между кадрами и кэш её строк: строки строятся заново, только когда меняются. */
    public static final class Anim {
        public float expand, appear;
        int seen;
        /** Кэш рисующего: исходное имя, готовая строка, ключ расстояния и строка расстояния. */
        public @Nullable Object source;
        public @Nullable String name, distText;
        public float nameWidth, distWidth;
        public int distKey = Integer.MIN_VALUE;
    }

    private Object[] keys = new Object[64];
    private double[] distance = new double[64];
    private int[] flags = new int[64];
    private float[] sx = new float[64], sy = new float[64];
    private boolean[] on = new boolean[64];
    private Anim[] anim = new Anim[64];
    private int count;

    private int[] chosen = new int[64];
    private int chosenCount;
    private final IntComparator byDistance = (a, b) -> Double.compare(distance[a], distance[b]);

    private int focused = -1;
    private @Nullable Object focusKey;
    private boolean showAll;
    private final Map<Object, Anim> anims = new HashMap<>();
    private int frame;

    /** Новый кадр: кандидаты прошлого кадра забываются, состояние анимации остаётся. */
    public void begin() {
        count = 0;
        chosenCount = 0;
        focused = -1;
        frame++;
    }

    /** Кандидат: ключ (id точки), расстояние до игрока в блоках, флаги {@link #PINNED}, {@link #ALWAYS}. Номер кандидата. */
    public int add(Object key, double blocks, int flag) {
        if (count == keys.length) grow();
        keys[count] = key;
        distance[count] = blocks;
        flags[count] = flag;
        on[count] = false;
        anim[count] = null;
        return count++;
    }

    private void grow() {
        int n = keys.length * 2;
        keys = java.util.Arrays.copyOf(keys, n);
        distance = java.util.Arrays.copyOf(distance, n);
        flags = java.util.Arrays.copyOf(flags, n);
        sx = java.util.Arrays.copyOf(sx, n);
        sy = java.util.Arrays.copyOf(sy, n);
        on = java.util.Arrays.copyOf(on, n);
        anim = java.util.Arrays.copyOf(anim, n);
        chosen = java.util.Arrays.copyOf(chosen, n);
    }

    /** Видна ли метка при этих настройках (без предела числа). */
    public static boolean visible(Mode mode, double blocks, int flag, double range, boolean showAll) {
        if (blocks < MIN_DISTANCE) return false;
        if ((flag & PINNED) != 0) return true;
        Mode m = showAll && mode == Mode.GOAL_DEATH ? Mode.NEARBY : mode;
        if (m == Mode.ALL) return true;
        if (blocks > range) return false;
        return m == Mode.NEARBY || (flag & ALWAYS) != 0;
    }

    /**
     * Отбор: видимые по режиму и дальности, не больше {@code limit} (закреплённые всегда, остальные ближайшие),
     * по возрастанию расстояния.
     */
    public void choose(Mode mode, double range, int limit, boolean showAllHeld) {
        showAll = showAllHeld;
        int n = 0, pinned = 0;
        for (int i = 0; i < count; i++) {
            if (!visible(mode, distance[i], flags[i], range, showAll)) continue;
            chosen[n++] = i;
            if ((flags[i] & PINNED) != 0) pinned++;
        }
        IntArrays.quickSort(chosen, 0, n, byDistance);
        int others = Math.max(0, limit - pinned), kept = 0, out = 0;
        for (int k = 0; k < n; k++) {
            int i = chosen[k];
            boolean pin = (flags[i] & PINNED) != 0;
            if (!pin && kept >= others) continue;
            if (!pin) kept++;
            chosen[out++] = i;
        }
        chosenCount = out;
    }

    public int chosenCount() {
        return chosenCount;
    }

    /** {@code k}-я выбранная метка по возрастанию расстояния: номер кандидата. */
    public int chosen(int k) {
        return chosen[k];
    }

    public double distance(int i) {
        return distance[i];
    }

    public boolean pinned(int i) {
        return (flags[i] & PINNED) != 0;
    }

    public Object key(int i) {
        return keys[i];
    }

    /** Место выбранной метки на экране в единицах интерфейса; {@code visible} ложно, если точка за спиной или за краем. */
    public void screen(int i, float x, float y, boolean visible) {
        sx[i] = x;
        sy[i] = y;
        on[i] = visible;
    }

    public float x(int i) {
        return sx[i];
    }

    public float y(int i) {
        return sy[i];
    }

    public boolean onScreen(int i) {
        return on[i];
    }

    /**
     * Раскрытая у прицела: ближайшая к {@code cx, cy} незакреплённая метка на экране в пределах {@code threshold}.
     * Прежняя раскрытая держится, пока она в пределах порога и никто не ближе неё на {@link #HYSTERESIS}.
     */
    public void focus(float cx, float cy, float threshold) {
        focused = -1;
        if (threshold <= 0) {
            focusKey = null;
            return;
        }
        float limit = threshold * threshold;
        int best = -1, previous = -1;
        float bestD = Float.MAX_VALUE, previousD = Float.MAX_VALUE;
        for (int k = 0; k < chosenCount; k++) {
            int i = chosen[k];
            if (!on[i] || (flags[i] & PINNED) != 0) continue;
            float dx = sx[i] - cx, dy = sy[i] - cy, d = dx * dx + dy * dy;
            if (d > limit) continue;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
            if (focusKey != null && focusKey.equals(keys[i])) {
                previous = i;
                previousD = d;
            }
        }
        if (previous >= 0 && best != previous && (float) Math.sqrt(previousD) - (float) Math.sqrt(bestD) < HYSTERESIS) best = previous;
        focused = best;
        focusKey = best >= 0 ? keys[best] : null;
    }

    /** Номер раскрытой у прицела метки или −1. */
    public int focused() {
        return focused;
    }

    /** Куда идёт раскрытие метки: закреплённая, зажатая клавиша или раскрытая у прицела. */
    public boolean expandedTarget(int i) {
        return (flags[i] & PINNED) != 0 || showAll || i == focused;
    }

    /** Ход анимации на {@code seconds}: раскрытие к цели, появление к единице. Новая метка начинает свёрнутой и прозрачной. */
    public void animate(float seconds) {
        float step = Math.max(0, seconds) / DURATION;
        for (int k = 0; k < chosenCount; k++) {
            int i = chosen[k];
            Anim a = anims.get(keys[i]);
            if (a == null) {
                a = new Anim();
                anims.put(keys[i], a);
            }
            a.seen = frame;
            a.appear = Math.min(1, a.appear + step);
            float target = expandedTarget(i) ? 1 : 0;
            a.expand = target > a.expand ? Math.min(target, a.expand + step) : Math.max(target, a.expand - step);
            anim[i] = a;
        }
        // метки, пропавшие из отбора, забываются раз в 120 кадров: вернувшись, появятся снова плавно
        if (frame % 120 == 0) anims.values().removeIf(a -> a.seen != frame);
    }

    /** Состояние выбранной метки после {@link #animate}. */
    public Anim anim(int i) {
        return anim[i];
    }

    /** Сглаженный ход 0…1. */
    public static float ease(float t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    /** Насколько метка дальняя: 0 ближе {@link #NEAR}, 1 на дальности и дальше. */
    public static float farness(double blocks, double range) {
        if (range <= NEAR) return blocks <= NEAR ? 0 : 1;
        return (float) Math.max(0, Math.min(1, (blocks - NEAR) / (range - NEAR)));
    }

    /** Размер значка: 1 рядом, {@link #FAR_SCALE} на дальности. */
    public static float scale(double blocks, double range) {
        return 1 - (1 - FAR_SCALE) * farness(blocks, range);
    }

    /** Яркость значка: 1 рядом, {@link #FAR_ALPHA} на дальности. */
    public static float fade(double blocks, double range) {
        return 1 - (1 - FAR_ALPHA) * farness(blocks, range);
    }
}
