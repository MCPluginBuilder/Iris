package art.arcane.iris.engine.hydrology;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.ForkJoinWorkerThread;

/**
 * Runs a batch of independent planning tasks in parallel without ever parking a pool worker on work
 * that lives in another pool. On a ForkJoin worker the tasks are forked into that worker's own pool
 * and joined with work helping, so a saturated pool still finishes them on the joining thread; on any
 * other thread they run on the fallback executor, or inline when there is none. Results keep the
 * task order and a task's unchecked exception is rethrown as is.
 */
public final class HydrologyForkJoin {
    private HydrologyForkJoin() {
    }

    public static <T> List<T> invokeAll(List<Callable<T>> tasks, Executor fallback) {
        Objects.requireNonNull(tasks, "tasks");
        if (tasks.size() < 2 || fallback == null && !(Thread.currentThread() instanceof ForkJoinWorkerThread)) {
            return runInline(tasks);
        }
        if (Thread.currentThread() instanceof ForkJoinWorkerThread) {
            return forkJoin(tasks);
        }
        return runOn(tasks, fallback);
    }

    private static <T> List<T> runInline(List<Callable<T>> tasks) {
        ArrayList<T> results = new ArrayList<>(tasks.size());
        for (Callable<T> task : tasks) {
            results.add(call(task));
        }
        return results;
    }

    private static <T> List<T> forkJoin(List<Callable<T>> tasks) {
        ArrayList<ForkJoinTask<T>> forked = new ArrayList<>(tasks.size());
        for (Callable<T> task : tasks) {
            forked.add(ForkJoinTask.adapt(task));
        }
        ForkJoinTask.invokeAll(forked);
        ArrayList<T> results = new ArrayList<>(tasks.size());
        for (ForkJoinTask<T> task : forked) {
            results.add(task.join());
        }
        return results;
    }

    private static <T> List<T> runOn(List<Callable<T>> tasks, Executor executor) {
        ArrayList<CompletableFuture<T>> futures = new ArrayList<>(tasks.size());
        for (Callable<T> task : tasks) {
            futures.add(CompletableFuture.supplyAsync(() -> call(task), executor));
        }
        ArrayList<T> results = new ArrayList<>(tasks.size());
        for (CompletableFuture<T> future : futures) {
            try {
                results.add(future.join());
            } catch (CompletionException failure) {
                throw unwrap(failure.getCause());
            }
        }
        return results;
    }

    private static <T> T call(Callable<T> task) {
        try {
            return task.call();
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Hydrology planning task failed.", failure);
        }
    }

    private static RuntimeException unwrap(Throwable cause) {
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("Hydrology planning task failed.", cause);
    }
}
