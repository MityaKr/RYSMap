package works.repka.rysmap.hud;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import works.repka.rysmap.hud.LabelPlan.Mode;

class LabelPlanTest {
    private static List<Object> chosenKeys(LabelPlan plan) {
        List<Object> out = new ArrayList<>();
        for (int k = 0; k < plan.chosenCount(); k++) out.add(plan.key(plan.chosen(k)));
        return out;
    }

    @Test
    void modes() {
        // обычная точка рядом и далеко, с флажком «в мире всегда», закреплённая (цель, гибель)
        assertTrue(LabelPlan.visible(Mode.NEARBY, 100, 0, 512, false));
        assertFalse(LabelPlan.visible(Mode.NEARBY, 600, 0, 512, false), "за дальностью не видна");
        assertTrue(LabelPlan.visible(Mode.NEARBY, 5000, LabelPlan.PINNED, 512, false), "цель и гибель видны на любом расстоянии");
        assertFalse(LabelPlan.visible(Mode.GOAL_DEATH, 100, 0, 512, false), "«Цель и гибель»: обычной точки нет");
        assertTrue(LabelPlan.visible(Mode.GOAL_DEATH, 100, LabelPlan.ALWAYS, 512, false), "флажок «в мире всегда» уважается");
        assertFalse(LabelPlan.visible(Mode.GOAL_DEATH, 600, LabelPlan.ALWAYS, 512, false), "флажок в пределах дальности");
        assertTrue(LabelPlan.visible(Mode.GOAL_DEATH, 5000, LabelPlan.PINNED, 512, false));
        assertTrue(LabelPlan.visible(Mode.ALL, 5000, 0, 512, false), "«Все»: без предела дальности");
        assertTrue(LabelPlan.visible(Mode.GOAL_DEATH, 100, 0, 512, true), "клавиша показывает все рядом");
        assertFalse(LabelPlan.visible(Mode.GOAL_DEATH, 600, 0, 512, true), "клавиша не отменяет дальность");
        assertFalse(LabelPlan.visible(Mode.ALL, 1, LabelPlan.PINNED, 512, false), "на самой точке метки нет");
    }

    @Test
    void modeIds() {
        assertEquals(Mode.NEARBY, Mode.of(null));
        assertEquals(Mode.NEARBY, Mode.of("мусор"));
        assertEquals(Mode.GOAL_DEATH, Mode.of("goal"));
        assertEquals(Mode.ALL, Mode.of("ALL"));
    }

    @Test
    void limitKeepsPinnedAndNearest() {
        LabelPlan plan = new LabelPlan();
        plan.begin();
        for (int i = 0; i < 100; i++) plan.add("p" + i, 10 + i, 0);
        plan.add("goal", 3000, LabelPlan.PINNED);
        plan.add("death", 400, LabelPlan.PINNED);
        plan.choose(Mode.ALL, 512, 64, false);
        assertEquals(64, plan.chosenCount());
        List<Object> keys = chosenKeys(plan);
        assertTrue(keys.contains("goal") && keys.contains("death"), "закреплённые не вытесняются пределом");
        assertTrue(keys.contains("p0") && keys.contains("p61") && !keys.contains("p62"), "остальные ближайшие: " + keys);
        // по возрастанию расстояния: дальняя цель последней (рисуется первой, ближние сверху)
        assertEquals("goal", keys.get(keys.size() - 1));
        for (int k = 1; k < plan.chosenCount(); k++) assertTrue(plan.distance(plan.chosen(k - 1)) <= plan.distance(plan.chosen(k)));
    }

    @Test
    void rangeCutsInNearby() {
        LabelPlan plan = new LabelPlan();
        plan.begin();
        plan.add("near", 50, 0);
        plan.add("far", 900, 0);
        plan.add("far goal", 900, LabelPlan.PINNED);
        plan.choose(Mode.NEARBY, 512, 64, false);
        assertEquals(List.of("near", "far goal"), chosenKeys(plan));
    }

    /** Три метки на экране 640×400, прицел в центре. */
    private static LabelPlan screenPlan(float ax, float bx) {
        LabelPlan plan = new LabelPlan();
        plan.begin();
        int a = plan.add("a", 50, 0), b = plan.add("b", 60, 0), far = plan.add("off", 70, 0);
        plan.choose(Mode.NEARBY, 512, 64, false);
        plan.screen(a, ax, 200, true);
        plan.screen(b, bx, 200, true);
        plan.screen(far, 600, 30, true);
        return plan;
    }

    @Test
    void focusNearestToCrosshairWithinThreshold() {
        LabelPlan plan = screenPlan(330, 300);
        plan.focus(320, 200, 40);
        assertEquals("a", plan.key(plan.focused()), "ближайшая к прицелу");
        assertTrue(plan.expandedTarget(plan.focused()));

        plan = screenPlan(400, 250);
        plan.focus(320, 200, 40);
        assertEquals(-1, plan.focused(), "дальше порога не раскрывается");

        plan = screenPlan(330, 300);
        plan.focus(320, 200, 0);
        assertEquals(-1, plan.focused(), "порог 0 выключает раскрытие у прицела");
    }

    @Test
    void focusIgnoresPinnedAndOffscreen() {
        LabelPlan plan = new LabelPlan();
        plan.begin();
        int goal = plan.add("goal", 50, LabelPlan.PINNED), back = plan.add("back", 20, 0), side = plan.add("side", 30, 0);
        plan.choose(Mode.NEARBY, 512, 64, false);
        plan.screen(goal, 320, 200, true);
        plan.screen(back, 321, 200, false);
        plan.screen(side, 345, 200, true);
        plan.focus(320, 200, 40);
        assertEquals("side", plan.key(plan.focused()), "закреплённая и невидимая не забирают раскрытие");
        assertTrue(plan.expandedTarget(goal), "закреплённая раскрыта всегда");
        assertFalse(plan.expandedTarget(back));
    }

    @Test
    void hysteresisKeepsFocusBetweenNeighbours() {
        LabelPlan plan = new LabelPlan();
        // кадр 1: a ближе
        frame(plan, 325, 330);
        assertEquals("a", plan.key(plan.focused()));
        // кадр 2: b стала чуть ближе (на 2 единицы), раскрытие остаётся у a
        frame(plan, 327, 325);
        assertEquals("a", plan.key(plan.focused()), "соседняя метка не перехватывает раскрытие от малого сдвига");
        // кадр 3: b ближе заметно
        frame(plan, 336, 321);
        assertEquals("b", plan.key(plan.focused()));
    }

    private static void frame(LabelPlan plan, float ax, float bx) {
        plan.begin();
        int a = plan.add("a", 50, 0), b = plan.add("b", 60, 0);
        plan.choose(Mode.NEARBY, 512, 64, false);
        plan.screen(a, ax, 200, true);
        plan.screen(b, bx, 200, true);
        plan.focus(320, 200, 40);
        plan.animate(1 / 60f);
    }

    @Test
    void showAllExpandsEverything() {
        LabelPlan plan = new LabelPlan();
        plan.begin();
        int a = plan.add("a", 50, 0), b = plan.add("b", 60, 0);
        plan.choose(Mode.GOAL_DEATH, 512, 64, true);
        assertEquals(2, plan.chosenCount(), "клавиша показывает точки и в режиме «Цель и гибель»");
        plan.focus(320, 200, 40);
        assertTrue(plan.expandedTarget(a) && plan.expandedTarget(b));
    }

    @Test
    void animationCrossfadesWithoutJumps() {
        LabelPlan plan = new LabelPlan();
        float dt = 1 / 60f;
        // a у прицела раскрывается за DURATION
        for (int f = 0; f < 30; f++) frame(plan, 322, 360);
        int a = find(plan, "a"), b = find(plan, "b");
        assertEquals(1f, plan.anim(a).expand, 1e-4);
        assertEquals(0f, plan.anim(b).expand, 1e-4);
        assertEquals(1f, plan.anim(a).appear, 1e-4, "появление закончилось");
        // прицел переходит к b: a сворачивается, b раскрывается одновременно, шаг за кадр не больше dt/DURATION
        float prevA = 1, prevB = 0, maxStep = dt / LabelPlan.DURATION + 1e-4f;
        for (int f = 0; f < 30; f++) {
            frame(plan, 380, 321);
            a = find(plan, "a");
            b = find(plan, "b");
            float ea = plan.anim(a).expand, eb = plan.anim(b).expand;
            assertTrue(ea <= prevA && prevA - ea <= maxStep, "a сворачивается плавно");
            assertTrue(eb >= prevB && eb - prevB <= maxStep, "b раскрывается плавно");
            prevA = ea;
            prevB = eb;
        }
        assertEquals(0f, prevA, 1e-4);
        assertEquals(1f, prevB, 1e-4);
    }

    @Test
    void newLabelFadesIn() {
        LabelPlan plan = new LabelPlan();
        plan.begin();
        int a = plan.add("a", 50, LabelPlan.PINNED);
        plan.choose(Mode.NEARBY, 512, 64, false);
        plan.animate(0);
        assertEquals(0f, plan.anim(a).appear, 1e-6, "новая метка начинает прозрачной");
        assertEquals(0f, plan.anim(a).expand, 1e-6);
        plan.begin();
        a = plan.add("a", 50, LabelPlan.PINNED);
        plan.choose(Mode.NEARBY, 512, 64, false);
        plan.animate(LabelPlan.DURATION / 2);
        assertEquals(0.5f, plan.anim(a).appear, 1e-4);
        assertEquals(0.5f, plan.anim(a).expand, 1e-4, "закреплённая раскрывается сама");
    }

    @Test
    void stateSurvivesManyFramesWithoutGrowth() {
        LabelPlan plan = new LabelPlan();
        for (int f = 0; f < 500; f++) {
            plan.begin();
            for (int i = 0; i < 200; i++) plan.add(i, 10 + i, 0);
            plan.choose(Mode.ALL, 512, 64, false);
            for (int k = 0; k < plan.chosenCount(); k++) plan.screen(plan.chosen(k), 320, 200, true);
            plan.focus(320, 200, 40);
            plan.animate(1 / 60f);
        }
        assertEquals(64, plan.chosenCount());
        assertEquals(0, plan.key(plan.focused()), "при равенстве у прицела раскрыта ближняя к игроку");
    }

    @Test
    void sizeAndFadeByDistance() {
        assertEquals(1f, LabelPlan.scale(10, 512), 1e-6);
        assertEquals(LabelPlan.FAR_SCALE, LabelPlan.scale(512, 512), 1e-6);
        assertEquals(LabelPlan.FAR_SCALE, LabelPlan.scale(5000, 512), 1e-6, "дальше дальности не мельче");
        assertTrue(LabelPlan.scale(100, 512) > LabelPlan.scale(300, 512));
        assertEquals(1f, LabelPlan.fade(LabelPlan.NEAR, 512), 1e-6);
        assertEquals(LabelPlan.FAR_ALPHA, LabelPlan.fade(1000, 512), 1e-6);
        assertEquals(0f, LabelPlan.ease(-1), 1e-6);
        assertEquals(1f, LabelPlan.ease(2), 1e-6);
        assertEquals(0.5f, LabelPlan.ease(0.5f), 1e-6);
    }

    private static int find(LabelPlan plan, Object key) {
        for (int k = 0; k < plan.chosenCount(); k++) if (plan.key(plan.chosen(k)).equals(key)) return plan.chosen(k);
        return -1;
    }
}
