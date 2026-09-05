package art.arcane.iris.engine.modifier;

import art.arcane.iris.engine.IrisEngine;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.object.IrisDepositGenerator;
import art.arcane.iris.spi.IrisPlatform;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.iris.spi.PlatformRegistries;
import art.arcane.iris.util.common.parallel.BurstExecutor;
import art.arcane.iris.util.common.parallel.MultiBurst;
import art.arcane.iris.util.project.context.ChunkContext;
import art.arcane.iris.util.project.context.IrisContext;
import art.arcane.iris.util.project.hunk.Hunk;
import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.mantle.runtime.Mantle;
import art.arcane.volmlib.util.mantle.runtime.MantleChunk;
import art.arcane.volmlib.util.matter.Matter;
import org.junit.Test;
import org.junit.AfterClass;
import org.junit.BeforeClass;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class IrisDepositModifierContextTest {
    @BeforeClass
    public static void bindPlatform() {
        IrisPlatforms.unbind();
        PlatformBlockState block = mock(PlatformBlockState.class);
        PlatformRegistries registries = mock(PlatformRegistries.class);
        when(registries.block(anyString())).thenReturn(block);
        IrisPlatform platform = mock(IrisPlatform.class);
        when(platform.registries()).thenReturn(registries);
        IrisPlatforms.bind(platform);
    }

    @AfterClass
    public static void unbindPlatform() {
        IrisPlatforms.unbind();
    }

    @Test
    public void workerDepositsRetainRuntimeAndChunkContextUntilCompletion() throws Exception {
        IrisEngine engine = mock(IrisEngine.class, RETURNS_DEEP_STUBS);
        try (Fixture fixture = new Fixture(engine, true)) {
            fixture.generateAndCheckCleanup();
            verify(engine, times(3)).captureGenerationRuntimeBinding();
            verify(engine, times(3)).openGenerationRuntimeScope(fixture.binding);
            assertEquals(3, fixture.closedScopes.get());
        }
    }

    @Test
    public void unscopedDepositCallsDoNotInventARuntimeBinding() throws Exception {
        IrisEngine engine = mock(IrisEngine.class, RETURNS_DEEP_STUBS);
        try (Fixture fixture = new Fixture(engine, false)) {
            fixture.generateAndCheckCleanup();
            verify(engine, never()).captureGenerationRuntimeBinding();
            verify(engine, never()).openGenerationRuntimeScope(any());
        }
    }

    @Test
    public void nonIrisEngineWorkersStillReceiveTheirChunkContext() throws Exception {
        try (Fixture fixture = new Fixture(mock(Engine.class, RETURNS_DEEP_STUBS), false)) {
            fixture.generateAndCheckCleanup();
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final Engine engine;
        private final ChunkContext context = mock(ChunkContext.class, RETURNS_DEEP_STUBS);
        private final IrisEngine.GenerationRuntimeBinding binding = mock(IrisEngine.GenerationRuntimeBinding.class);
        private final ThreadLocal<Boolean> workerRuntime = ThreadLocal.withInitial(() -> false);
        private final AtomicInteger completed = new AtomicInteger();
        private final AtomicInteger closedScopes = new AtomicInteger();
        private final IrisDepositModifier modifier;
        private final MantleChunk<Matter> chunk;

        @SuppressWarnings("unchecked")
        private Fixture(Engine engine, boolean scoped) {
            this.engine = engine;
            Thread caller = Thread.currentThread();
            when(context.getGenerationSessionId()).thenReturn(71L);
            MultiBurst pool = mock(MultiBurst.class);
            BurstExecutor burst = new BurstExecutor(executor, 3);
            burst.setMulticore(true);
            when(engine.burst()).thenReturn(pool);
            when(pool.burst(true)).thenReturn(burst);
            IrisDepositGenerator dimensionDeposit = mock(IrisDepositGenerator.class);
            IrisDepositGenerator regionDeposit = mock(IrisDepositGenerator.class);
            IrisDepositGenerator biomeDeposit = mock(IrisDepositGenerator.class);
            when(engine.getDimension().getDeposits()).thenReturn(new KList<>(dimensionDeposit));
            when(context.getRegion().get(7, 7).getDeposits())
                    .thenReturn(new KList<>(regionDeposit));
            when(context.getBiome().get(7, 7).getDeposits())
                    .thenReturn(new KList<>(biomeDeposit));
            chunk = mock(MantleChunk.class);
            Mantle<Matter> mantle = engine.getMantle().getMantle();
            doReturn(chunk).when(mantle).getChunk(2, -3);
            when(chunk.use()).thenReturn(chunk);
            if (engine instanceof IrisEngine irisEngine) {
                when(irisEngine.hasGenerationRuntimeScope()).thenReturn(scoped);
                when(irisEngine.captureGenerationRuntimeBinding()).thenReturn(binding);
                IrisEngine.GenerationRuntimeScope scope = mock(IrisEngine.GenerationRuntimeScope.class);
                when(irisEngine.openGenerationRuntimeScope(binding)).thenAnswer(invocation -> {
                    assertNotSame(caller, Thread.currentThread());
                    workerRuntime.set(true);
                    return scope;
                });
                doAnswer(invocation -> {
                    workerRuntime.remove();
                    closedScopes.incrementAndGet();
                    return null;
                }).when(scope).close();
            }
            modifier = spy(new IrisDepositModifier(engine));
            doAnswer(invocation -> {
                assertNotSame(caller, Thread.currentThread());
                assertEquals(scoped, workerRuntime.get());
                assertSame(engine, IrisContext.require().getEngine());
                assertSame(context, IrisContext.require().getChunkContext());
                assertEquals(71L, IrisContext.require().getGenerationSessionId());
                completed.incrementAndGet();
                return null;
            }).when(modifier).generate(any(), same(chunk), any(), any(), eq(2), eq(-3), eq(false), same(context));
            doAnswer(invocation -> {
                assertEquals(3, completed.get());
                assertEquals(scoped ? 3 : 0, closedScopes.get());
                return null;
            }).when(chunk).release();
        }

        private void generateAndCheckCleanup() throws Exception {
            ChunkContext callerContext = mock(ChunkContext.class);
            try (IrisContext.Scope ignored = IrisContext.open(engine, 19L, callerContext)) {
                modifier.generateDeposits(Hunk.<PlatformBlockState>newArrayHunk(16, 16, 16),
                        2, -3, true, context);
                assertSame(callerContext, IrisContext.require().getChunkContext());
                assertEquals(19L, IrisContext.require().getGenerationSessionId());
            }
            assertEquals(3, completed.get());
            verify(chunk).release();
            executor.submit(() -> {
                assertNull(IrisContext.get());
                assertFalse(workerRuntime.get());
            }).get(10L, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws Exception {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10L, TimeUnit.SECONDS));
        }
    }
}
