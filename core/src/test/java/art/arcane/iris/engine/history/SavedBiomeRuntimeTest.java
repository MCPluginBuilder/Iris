package art.arcane.iris.engine.history;

import art.arcane.iris.core.IrisSettings;
import art.arcane.iris.engine.IrisEngine;
import art.arcane.iris.engine.framework.BiomeEnvironment;
import art.arcane.iris.engine.framework.PreservationRegistry;
import art.arcane.iris.spi.IrisServices;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SavedBiomeRuntimeTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final IrisEngine engine = mock(IrisEngine.class);
    private final GenerationHistory history = mock(GenerationHistory.class);
    private final SavedBiomeStore store = mock(SavedBiomeStore.class);
    private IrisSettings previousSettings;

    @Before
    public void prepareServices() throws Exception {
        previousSettings = IrisSettings.settings;
        IrisSettings.settings = new IrisSettings();
        IrisServices.register(PreservationRegistry.class, mock(PreservationRegistry.class));
        when(history.savedBiomes()).thenReturn(store);
        when(history.paths()).thenReturn(GenerationHistoryPaths.forDimension(temporaryFolder.newFolder().toPath()));
        String epochId = "a".repeat(64);
        GenerationManifest manifest = mock(GenerationManifest.class);
        GenerationEpoch epoch = mock(GenerationEpoch.class);
        when(history.manifest()).thenReturn(manifest);
        when(manifest.activation(1L)).thenReturn(Optional.of(GenerationActivation.initial(epochId, 1L)));
        when(manifest.activation(2L)).thenReturn(Optional.of(GenerationActivation.next(2L, epochId, 1L, 2L, 64)));
        when(manifest.epoch(epochId)).thenReturn(Optional.of(epoch));
        when(epoch.epochId()).thenReturn(epochId);
    }

    @After
    public void restoreServices() {
        IrisServices.clear();
        IrisSettings.settings = previousSettings;
    }

    @Test
    public void everyCallerReceivesLoadingWithoutWaitingForDisk() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(store.get(0, 0)).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5L, TimeUnit.SECONDS));
            return Optional.of(unresolvedChunk(1));
        });
        SavedBiomeRuntime runtime = new SavedBiomeRuntime(engine, history);
        try (ExecutorService caller = Executors.newSingleThreadExecutor()) {
            Future<SavedBiomeUnavailableException> response = caller.submit(() -> assertThrows(
                    SavedBiomeUnavailableException.class, () -> runtime.resolve(0, 0, 0, true)));
            assertTrue(entered.await(5L, TimeUnit.SECONDS));
            assertTrue(response.get(1L, TimeUnit.SECONDS).isLoading());
        } finally {
            release.countDown();
            runtime.close();
        }
    }

    @Test
    public void chunkReadinessDoesNotRequireEveryCellToHaveAResolvedIdentity() throws Exception {
        when(store.get(0, 0)).thenReturn(Optional.of(unresolvedChunk(1)));
        try (SavedBiomeRuntime runtime = new SavedBiomeRuntime(engine, history)) {
            assertTrue(assertThrows(SavedBiomeUnavailableException.class,
                    () -> runtime.prepareChunk(0, 0)).isLoading());
            awaitIdle(runtime);
            runtime.prepareChunk(0, 0);
            SavedBiomeUnavailableException unavailable = assertThrows(SavedBiomeUnavailableException.class,
                    () -> runtime.resolve(0, 0, 0, true));
            assertEquals(false, unavailable.isLoading());
        }
    }

    @Test
    public void closeWaitsForInvalidatedWorkersAndRestoresInterruptStatus() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SavedBiomeChunk chunk = unresolvedChunk(1);
        when(store.get(0, 0)).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5L, TimeUnit.SECONDS));
            return Optional.of(chunk);
        });
        SavedBiomeRuntime runtime = new SavedBiomeRuntime(engine, history);
        assertTrue(assertThrows(SavedBiomeUnavailableException.class,
                () -> runtime.resolve(0, 0, 0, true)).isLoading());
        assertTrue(entered.await(5L, TimeUnit.SECONDS));
        runtime.capture(chunk);
        assertEquals(1, runtime.pendingQueryCount());
        try (ExecutorService closer = Executors.newSingleThreadExecutor()) {
            Future<Boolean> closed = closer.submit(() -> {
                Thread.currentThread().interrupt();
                runtime.close();
                return Thread.currentThread().isInterrupted();
            });
            try {
                assertThrows(TimeoutException.class, () -> closed.get(100L, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            assertTrue(closed.get(5L, TimeUnit.SECONDS));
            assertEquals(0, runtime.pendingQueryCount());
            assertEquals(0, runtime.cachedQueryCount());
        } finally {
            release.countDown();
            runtime.close();
        }
    }

    @Test
    public void completedSnapshotCacheRespectsItsByteBudgetAndNegativeCacheIsBounded() throws Exception {
        when(store.get(anyInt(), anyInt())).thenReturn(Optional.of(unresolvedChunk(16)));
        try (SavedBiomeRuntime runtime = new SavedBiomeRuntime(engine, history)) {
            for (int chunkX = 0; chunkX < 110; chunkX++) {
                int blockX = chunkX * 16;
                assertTrue(assertThrows(SavedBiomeUnavailableException.class,
                        () -> runtime.resolve(blockX, 0, 0, true)).isLoading());
                awaitIdle(runtime);
                assertTrue(runtime.cachedQueryBytes() <= SavedBiomeChunk.MAXIMUM_ESTIMATED_BYTES);
            }
            assertTrue(runtime.cachedQueryCount() < 110);
        }
        when(store.get(anyInt(), anyInt())).thenReturn(Optional.empty());
        when(history.isActiveUnowned(anyInt(), anyInt())).thenReturn(true);
        when(history.semantics(anyInt(), anyInt())).thenReturn(Optional.empty());
        try (SavedBiomeRuntime runtime = new SavedBiomeRuntime(engine, history)) {
            for (int chunkX = 0; chunkX < 150; chunkX++) {
                int blockX = chunkX * 16;
                assertTrue(assertThrows(SavedBiomeUnavailableException.class,
                        () -> runtime.resolve(blockX, 0, 0, true)).isLoading());
                awaitIdle(runtime);
                assertEquals(Optional.empty(), runtime.resolve(blockX, 0, 0, true));
                assertTrue(runtime.cachedQueryCount() <= 128);
            }
        }
    }

    @Test
    public void preparedEnvironmentSurvivesLoaderEvictionWithoutReadingPackFilesAgain() throws Exception {
        String epochId = "a".repeat(64);
        Path pack = history.paths().packRoot(epochId);
        Files.createDirectories(pack.resolve("dimensions"));
        Files.createDirectories(pack.resolve("biomes"));
        Files.createDirectories(pack.resolve("regions"));
        Files.writeString(pack.resolve("dimensions/main.json"), "{}");
        Files.writeString(pack.resolve("biomes/forest.json"), "{}");
        Files.writeString(pack.resolve("regions/main.json"), "{}");
        GenerationManifest manifest = mock(GenerationManifest.class);
        GenerationEpoch epoch = mock(GenerationEpoch.class);
        GenerationEpoch.DimensionContract contract = mock(GenerationEpoch.DimensionContract.class);
        when(history.manifest()).thenReturn(manifest);
        when(history.packRoot(1L)).thenReturn(pack);
        when(history.packRoot(2L)).thenReturn(pack);
        when(manifest.activation(1L)).thenReturn(Optional.of(GenerationActivation.initial(epochId, 1L)));
        when(manifest.activation(2L)).thenReturn(Optional.of(GenerationActivation.next(2L, epochId, 1L, 2L, 64)));
        when(manifest.epoch(epochId)).thenReturn(Optional.of(epoch));
        when(epoch.epochId()).thenReturn(epochId);
        when(epoch.registryContract()).thenReturn(GenerationRegistryContract.empty());
        when(epoch.dimensionContract()).thenReturn(contract);
        when(contract.dimensionKey()).thenReturn("main");
        SavedBiomeChunk.Cell cell = new SavedBiomeChunk.Cell(1L, "forest", "main");
        SavedBiomeChunk.Cell repeatedActivation = new SavedBiomeChunk.Cell(2L, "forest", "main");
        SavedBiomeChunk.Cell removed = new SavedBiomeChunk.Cell(2L, "removed", "main");
        SavedBiomeChunk.Builder builder = SavedBiomeChunk.builder(new SavedBiomeChunk.Header(0, 0, 2L, -64, 384));
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                SavedBiomeChunk.Cell selected = x < 8 ? cell : x < 12 ? repeatedActivation : removed;
                builder.column(x, z, new SavedBiomeChunk.Column(selected, cell,
                        List.of(new SavedBiomeChunk.Span(-64, 320, selected))));
            }
        }
        when(store.get(0, 0)).thenReturn(Optional.of(builder.build()));
        try (SavedBiomeRuntime runtime = new SavedBiomeRuntime(engine, history)) {
            assertTrue(assertThrows(SavedBiomeUnavailableException.class,
                    () -> runtime.resolve(0, 0, 0, true)).isLoading());
            awaitIdle(runtime);
            runtime.prepareChunk(0, 0);
            BiomeEnvironment environment = runtime.resolve(0, 0, 0, true).orElseThrow();
            BiomeEnvironment repeated = runtime.resolve(8, 0, 0, true).orElseThrow();
            assertEquals(2L, repeated.activationId());
            assertSame(environment.data(), repeated.data());
            SavedBiomeUnavailableException missing = assertThrows(SavedBiomeUnavailableException.class,
                    () -> runtime.resolve(12, 0, 0, true));
            assertEquals(false, missing.isLoading());
            assertTrue(missing.getMessage().contains("removed"));
            assertSame(environment, runtime.resolveCaveBase(12, 0).orElseThrow());
            environment.data().getBiomeLoader().unload("forest");
            environment.data().getRegionLoader().unload("main");
            Files.delete(pack.resolve("biomes/forest.json"));
            Files.delete(pack.resolve("regions/main.json"));
            assertSame(environment, runtime.resolve(0, 0, 0, true).orElseThrow());
            assertEquals("forest", environment.biome().getLoadKey());
        }
    }

    private static void awaitIdle(SavedBiomeRuntime runtime) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (runtime.pendingQueryCount() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(1L);
        }
        assertEquals(0, runtime.pendingQueryCount());
    }

    private static SavedBiomeChunk unresolvedChunk(int runCount) {
        SavedBiomeChunk.Cell first = SavedBiomeChunk.Cell.unresolved(1L);
        SavedBiomeChunk.Cell second = SavedBiomeChunk.Cell.unresolved(2L);
        ArrayList<SavedBiomeChunk.Span> spans = new ArrayList<>(runCount);
        for (int index = 0; index < runCount; index++) {
            int start = -64 + index * 384 / runCount;
            int end = -64 + (index + 1) * 384 / runCount;
            spans.add(new SavedBiomeChunk.Span(start, end, (index & 1) == 0 ? first : second));
        }
        return chunk(new SavedBiomeChunk.Column(first, second, spans));
    }

    private static SavedBiomeChunk chunk(SavedBiomeChunk.Column column) {
        SavedBiomeChunk.Builder builder = SavedBiomeChunk.builder(new SavedBiomeChunk.Header(0, 0, 2L, -64, 384));
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                builder.column(x, z, column);
            }
        }
        return builder.build();
    }
}
