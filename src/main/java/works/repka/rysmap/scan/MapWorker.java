package works.repka.rysmap.scan;

import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Единственный рабочий поток карты: разбор снимков чанков, пекарь, чтение и запись регионов, позже переезд с FTB.
 * Пула нет (правило памяти плана, раздел 12). Приоритет низкий, ошибка задачи пишется в лог и не останавливает поток.
 */
public final class MapWorker {
    private final LinkedBlockingDeque<Runnable> tasks = new LinkedBlockingDeque<>();
    private final Thread thread;
    private final Consumer<Throwable> errors;
    private volatile boolean running = true;

    public MapWorker(String name, Consumer<Throwable> errors) {
        this.errors = errors;
        this.thread = new Thread(this::loop, name);
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 2);
        thread.start();
    }

    public void submit(Runnable task) {
        if (running) tasks.addLast(task);
    }

    /** Вне очереди: нужное прямо сейчас (тайлы для открытого окна). */
    public void submitFirst(Runnable task) {
        if (running) tasks.addFirst(task);
    }

    public int queued() {
        return tasks.size();
    }

    public boolean onWorker() {
        return Thread.currentThread() == thread;
    }

    /** Выполняет оставшиеся задачи и последнюю, потом останавливается. Ждёт не дольше {@code millis}. */
    public void stop(Runnable last, long millis) {
        tasks.addLast(last);
        tasks.addLast(() -> running = false);
        try {
            thread.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        running = false;
    }

    private void loop() {
        while (running || !tasks.isEmpty()) {
            Runnable task;
            try {
                task = tasks.pollFirst(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (task == null) continue;
            try {
                task.run();
            } catch (Throwable t) {
                errors.accept(t);
            }
        }
    }
}
