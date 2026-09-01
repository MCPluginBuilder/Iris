package art.arcane.iris.util.project.stream;

import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.MeteredCache;
import art.arcane.iris.engine.framework.PreservationRegistry;
import art.arcane.iris.spi.IrisServices;
import art.arcane.iris.util.project.stream.interpolation.Interpolated;
import art.arcane.iris.util.project.stream.utility.CachedDoubleStream2D;
import art.arcane.iris.util.project.stream.utility.CachedStream2D;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;

public class ProceduralStreamCacheBypassTest {
    private PreservationRegistry previousRegistry;
    private Engine engine;

    @Before
    public void registerPreservationService() {
        previousRegistry = IrisServices.getOrNull(PreservationRegistry.class);
        IrisServices.register(PreservationRegistry.class, new NoOpPreservationRegistry());
        engine = mock(Engine.class);
    }

    @After
    public void restorePreservationService() {
        if (previousRegistry == null) {
            IrisServices.remove(PreservationRegistry.class);
        } else {
            IrisServices.register(PreservationRegistry.class, previousRegistry);
        }
    }

    @Test
    public void normalAccessCachesIntegerQuantizedValues() {
        AtomicInteger genericCalls = new AtomicInteger();
        ProceduralStream<Integer> genericSource = ProceduralStream.of(
                (x, z) -> genericCalls.incrementAndGet(),
                Interpolated.INT
        );
        CachedStream2D<Integer> genericCache = new CachedStream2D<>("generic", engine, genericSource, 8);

        assertEquals(Integer.valueOf(1), genericCache.get(12.9D, -3.9D));
        assertEquals(Integer.valueOf(1), genericCache.get(12.1D, -3.1D));
        assertEquals(1, genericCalls.get());
        assertEquals(256L, genericCache.getSize());

        AtomicInteger doubleCalls = new AtomicInteger();
        ProceduralStream<Double> doubleSource = ProceduralStream.ofDouble((x, z) -> {
            doubleCalls.incrementAndGet();
            return x * 31D + z;
        });
        CachedDoubleStream2D doubleCache = new CachedDoubleStream2D("double", engine, doubleSource, 8);

        assertEquals(-1_026D, doubleCache.getDouble(-33.9D, -3.9D), 0D);
        assertEquals(-1_026D, doubleCache.getDouble(-33.1D, -3.1D), 0D);
        assertEquals(1, doubleCalls.get());
        assertEquals(256L, doubleCache.getSize());
    }

    @Test
    public void bypassMatchesNestedTwoDimensionalSemanticsWithoutGrowingCaches() {
        DoubleChain baseline = new DoubleChain(engine);
        DoubleChain bypassed = new DoubleChain(engine);
        for (int index = 0; index < 32; index++) {
            double x = index * 64D + 4.875D;
            double z = index * -96D + 5.625D;
            double expected = baseline.output.get(x, z);
            double actual = ProceduralStream.bypass2DCaches(() -> bypassed.output.get(x, z));
            assertEquals(Double.doubleToLongBits(expected), Double.doubleToLongBits(actual));
        }

        assertEquals(2_048L, baseline.inner.getSize());
        assertEquals(2_048L, baseline.converted.getSize());
        assertEquals(2_048L, baseline.output.getSize());
        assertEquals(0L, bypassed.inner.getSize());
        assertEquals(0L, bypassed.converted.getSize());
        assertEquals(0L, bypassed.output.getSize());
    }

    @Test
    public void nestedScopesRestoreAfterExceptions() {
        DoubleChain chain = new DoubleChain(engine);

        ProceduralStream.bypass2DCaches(() -> {
            assertThrows(IllegalStateException.class, () -> ProceduralStream.bypass2DCaches(() -> {
                chain.output.getDouble(3_000.75D, -4_000.25D);
                throw new IllegalStateException("expected");
            }));
            chain.output.getDouble(5_000.75D, -6_000.25D);
            assertEquals(0L, chain.inner.getSize());
            assertEquals(0L, chain.converted.getSize());
            assertEquals(0L, chain.output.getSize());
            return null;
        });

        chain.output.getDouble(7_000.75D, -8_000.25D);
        assertEquals(256L, chain.inner.getSize());
        assertEquals(256L, chain.converted.getSize());
        assertEquals(256L, chain.output.getSize());
    }

    @Test
    public void bypassIsThreadLocal() {
        CachedDoubleStream2D cache = new CachedDoubleStream2D(
                "thread-local",
                engine,
                ProceduralStream.ofDouble((x, z) -> x * 17D + z),
                8
        );
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ProceduralStream.bypass2DCaches(() -> {
                CompletableFuture<Double> otherThread = CompletableFuture.supplyAsync(
                        () -> cache.getDouble(1_024.75D, 2_048.25D),
                        executor
                );
                assertEquals(19_456D, otherThread.join(), 0D);
                assertEquals(256L, cache.getSize());
                cache.getDouble(4_096.75D, 8_192.25D);
                assertEquals(256L, cache.getSize());
                return null;
            });
            cache.getDouble(12_288.75D, 16_384.25D);
            assertEquals(512L, cache.getSize());
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class DoubleChain {
        private final CachedDoubleStream2D inner;
        private final CachedStream2D<Double> converted;
        private final CachedDoubleStream2D output;

        private DoubleChain(Engine engine) {
            ProceduralStream<Double> source = ProceduralStream.ofDouble(
                    (x, z) -> x * x * 0.125D + z * z * 0.25D + x * z * 0.0625D
            );
            inner = new CachedDoubleStream2D("inner", engine, source, 8);
            ProceduralStream<Double> conversion = inner.convert(value -> value * 1.25D - 7D);
            converted = new CachedStream2D<>("converted", engine, conversion, 8);
            output = new CachedDoubleStream2D("output", engine, converted.slope(3), 8);
        }
    }

    private static final class NoOpPreservationRegistry implements PreservationRegistry {
        @Override
        public void register(Thread thread) {
        }

        @Override
        public void register(ExecutorService service) {
        }

        @Override
        public void registerCache(MeteredCache cache) {
        }

        @Override
        public void dereference() {
        }
    }
}
