package art.arcane.iris.engine.hydrology;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class HydrologyForkJoinTest {
    @Test
    public void nestedFanOutCompletesOnASaturatedForkJoinPool() throws Exception {
        ForkJoinPool pool = new ForkJoinPool(1);
        try {
            List<Integer> results = pool.submit(() -> {
                List<Callable<Integer>> outer = new ArrayList<>();
                for (int index = 0; index < 3; index++) {
                    int value = index;
                    outer.add(() -> {
                        List<Callable<Integer>> inner = List.of(() -> value * 10, () -> value * 10 + 1);
                        List<Integer> nested = HydrologyForkJoin.invokeAll(inner, Runnable::run);
                        return nested.get(0) + nested.get(1);
                    });
                }
                return HydrologyForkJoin.invokeAll(outer, Runnable::run);
            }).get(10, TimeUnit.SECONDS);

            assertEquals(List.of(1, 21, 41), results);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void outsideAPoolTasksRunOnTheFallbackExecutorInOrder() throws Exception {
        List<String> threads = Collections.synchronizedList(new ArrayList<>());
        ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> new Thread(runnable, "fallback-executor"));
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                int value = index;
                tasks.add(() -> {
                    threads.add(Thread.currentThread().getName());
                    return value;
                });
            }

            List<Integer> results = HydrologyForkJoin.invokeAll(tasks, executor);

            assertEquals(List.of(0, 1, 2, 3), results);
            assertEquals(4, threads.size());
            assertTrue(threads.stream().allMatch("fallback-executor"::equals));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void withoutAFallbackTasksRunOnTheCaller() {
        String caller = Thread.currentThread().getName();
        List<Callable<String>> tasks = List.of(
                () -> Thread.currentThread().getName(),
                () -> Thread.currentThread().getName()
        );

        assertEquals(List.of(caller, caller), HydrologyForkJoin.invokeAll(tasks, null));
    }

    @Test
    public void aFailingTaskRethrowsItsExceptionOnEveryPath() throws Exception {
        List<Callable<Integer>> tasks = List.of(() -> 1, () -> {
            throw new IllegalStateException("planning failed");
        });

        IllegalStateException inline = assertThrows(IllegalStateException.class, () -> HydrologyForkJoin.invokeAll(tasks, null));
        assertEquals("planning failed", inline.getMessage());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            IllegalStateException pooled = assertThrows(IllegalStateException.class, () -> HydrologyForkJoin.invokeAll(tasks, executor));
            assertEquals("planning failed", pooled.getMessage());
        } finally {
            executor.shutdownNow();
        }

        ForkJoinPool pool = new ForkJoinPool(1);
        try {
            String message = pool.submit(() -> {
                try {
                    HydrologyForkJoin.invokeAll(tasks, Runnable::run);
                    return "no failure";
                } catch (IllegalStateException failure) {
                    return failure.getMessage();
                }
            }).get(10, TimeUnit.SECONDS);
            assertEquals("planning failed", message);
        } finally {
            pool.shutdownNow();
        }
    }
}
