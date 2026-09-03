package art.arcane.iris.engine.hydrology;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class HydrologyTileCacheTest {
    @Test
    public void chunkPreparationUsesHeightFillColumnOrder() {
        HydrologyPlanner planner = mock(HydrologyPlanner.class);
        HydrologyTile tile = mock(HydrologyTile.class);
        ArrayList<Long> visitedColumns = new ArrayList<>();
        when(planner.settings()).thenReturn(emptySettings());
        when(planner.plan(any(HydrologyTileKey.class))).thenReturn(tile);
        doAnswer(invocation -> {
            int blockX = invocation.getArgument(0);
            int blockZ = invocation.getArgument(1);
            visitedColumns.add(RiverFootprint.pack(blockX, blockZ));
            return Optional.empty();
        }).when(tile).columnAt(anyInt(), anyInt());
        HydrologyTileCache cache = new HydrologyTileCache(planner, 4);

        cache.prepareChunkColumns(32, 48);

        assertEquals(256, visitedColumns.size());
        int index = 0;
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                assertEquals(
                        RiverFootprint.pack(32 + localX, 48 + localZ),
                        visitedColumns.get(index).longValue()
                );
                index++;
            }
        }
        verify(planner, times(1)).plan(new HydrologyTileKey(0, 0));
    }

    @Test
    public void concurrentSameKeyRequestsPlanOnlyOnce() throws Exception {
        AtomicInteger planningStarts = new AtomicInteger();
        HydrologyTerrainSampler sampler = (int x, int z) -> HydrologyTerrainSample.openLand(90, 0D, "parent");
        HydrologyPlanner planner = new HydrologyPlanner(
                41L,
                emptySettings(),
                sampler,
                -4096,
                footprint -> {
                    planningStarts.incrementAndGet();
                    return new HydrologyTerrainCaveVoxelView(sampler, 63, -4096, 4096);
                }
        );
        HydrologyTileCache cache = new HydrologyTileCache(planner, 4);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            ArrayList<Callable<HydrologyTile>> tasks = new ArrayList<>();
            for (int index = 0; index < 32; index++) {
                tasks.add(() -> cache.get(new HydrologyTileKey(0, 0)));
            }
            List<Future<HydrologyTile>> futures = executor.invokeAll(tasks);
            HydrologyTile expected = futures.getFirst().get(10L, TimeUnit.SECONDS);
            for (Future<HydrologyTile> future : futures) {
                assertSame(expected, future.get(10L, TimeUnit.SECONDS));
            }
            assertEquals(1, planningStarts.get());
            assertEquals(1, cache.size());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10L, TimeUnit.SECONDS));
        }
    }

    @Test
    public void completedTilesAreEvictedAtTheConfiguredBound() {
        HydrologyPlanner planner = new HydrologyPlanner(
                73L,
                emptySettings(),
                (int x, int z) -> HydrologyTerrainSample.openLand(90, 0D, "parent")
        );
        HydrologyTileCache cache = new HydrologyTileCache(planner, 2);

        for (int tileX = 0; tileX < 8; tileX++) {
            cache.get(new HydrologyTileKey(tileX, 0));
            assertTrue(cache.size() <= 2);
        }

        assertEquals(2, cache.maximumEntries());
        assertEquals(2, cache.size());
    }

    @Test
    public void compositePublicationIsIndependentOfTileAndColumnQueryOrder() {
        HydrologyPlanner planner = new HydrologyPlanner(811L, featureSettings(), this::featureTerrain);
        HydrologyTile origin = planner.plan(new HydrologyTileKey(0, 0));
        HydrologyColumnSample neighboringColumn = null;
        HydrologyColumnSample ownerColumn = null;
        for (HydrologyColumnSample column : origin.footprint().columns().values()) {
            if (new HydrologyTileKey(0, 0).contains(column.x(), column.z(), origin.tileSize())) {
                ownerColumn = column;
            } else if (!column.ocean()) {
                neighboringColumn = column;
            }
            if (ownerColumn != null && neighboringColumn != null) {
                break;
            }
        }
        assertTrue(origin.footprint().columns().values().stream().anyMatch(
                (HydrologyColumnSample column) -> !new HydrologyTileKey(0, 0)
                        .contains(column.x(), column.z(), origin.tileSize())));
        assertTrue(ownerColumn != null && neighboringColumn != null);
        HydrologyTileKey neighboringKey = HydrologyTileKey.fromBlock(
                neighboringColumn.x(),
                neighboringColumn.z(),
                origin.tileSize()
        );
        assertFalse(planner.plan(neighboringKey)
                .columnAt(neighboringColumn.x(), neighboringColumn.z())
                .isPresent());

        HydrologyTileCache firstOrder = new HydrologyTileCache(planner, 8);
        HydrologyColumnSample firstNeighbor = firstOrder
                .columnAt(neighboringColumn.x(), neighboringColumn.z())
                .orElseThrow();
        HydrologyColumnSample firstOwner = firstOrder.columnAt(ownerColumn.x(), ownerColumn.z()).orElseThrow();
        HydrologyTileCache reverseOrder = new HydrologyTileCache(planner, 8);
        HydrologyColumnSample secondOwner = reverseOrder.columnAt(ownerColumn.x(), ownerColumn.z()).orElseThrow();
        HydrologyColumnSample secondNeighbor = reverseOrder
                .columnAt(neighboringColumn.x(), neighboringColumn.z())
                .orElseThrow();

        assertEquals(firstNeighbor, secondNeighbor);
        assertEquals(firstOwner, secondOwner);
        assertEquals(firstNeighbor.renderSample(), reverseOrder.renderAt(neighboringColumn.x(), neighboringColumn.z()));
        assertTrue(firstNeighbor.layers().containsAll(neighboringColumn.layers()));
    }

    @Test
    public void realPlannedFeatureIdsAndTypesReachTheRendererAtExactCoordinates() {
        HydrologyPlanner planner = new HydrologyPlanner(811L, featureSettings(), this::featureTerrain);
        HydrologyTileCache cache = new HydrologyTileCache(planner, 8);
        HydrologyTile planned = cache.get(new HydrologyTileKey(0, 0));
        HydrologyColumnSample plannedColumn = planned.footprint().columns().values().stream()
                .filter(HydrologyColumnSample::present)
                .findFirst()
                .orElseThrow();
        HydrologyColumnSample accepted = cache.columnAt(plannedColumn.x(), plannedColumn.z()).orElseThrow();
        HydrologyRenderSample rendered = cache.renderAt(plannedColumn.x(), plannedColumn.z());
        Map<Long, HydrologyFeatureType> acceptedFeatures = new HashMap<>();
        Map<Long, HydrologyFeatureType> renderedFeatures = new HashMap<>();
        for (HydrologyColumnLayer layer : accepted.layers()) {
            acceptedFeatures.put(layer.feature().id(), layer.feature().type());
        }
        for (HydrologyFeatureRef feature : rendered.features()) {
            renderedFeatures.put(feature.id(), feature.type());
        }

        assertFalse(acceptedFeatures.isEmpty());
        assertEquals(plannedColumn.x(), rendered.x());
        assertEquals(plannedColumn.z(), rendered.z());
        assertEquals(acceptedFeatures, renderedFeatures);
        for (HydrologyColumnLayer layer : plannedColumn.layers()) {
            assertEquals(layer.feature().type(), renderedFeatures.get(layer.feature().id()));
        }
    }

    @Test
    public void concurrentDifferentTileAndCompositeQueriesRemainDeterministic() throws Exception {
        HydrologyPlanner planner = new HydrologyPlanner(1211L, featureSettings(), this::featureTerrain);
        HydrologyTileCache cache = new HydrologyTileCache(planner, 16);
        List<HydrologyTileKey> keys = List.of(
                new HydrologyTileKey(-1, 0),
                new HydrologyTileKey(0, 0),
                new HydrologyTileKey(1, 0)
        );
        ExecutorService executor = Executors.newFixedThreadPool(12);
        try {
            ArrayList<Callable<HydrologyTile>> tileTasks = new ArrayList<>();
            for (int index = 0; index < 36; index++) {
                HydrologyTileKey key = keys.get(index % keys.size());
                tileTasks.add(() -> cache.get(key));
            }
            List<Future<HydrologyTile>> futures = executor.invokeAll(tileTasks);
            Map<HydrologyTileKey, HydrologyTile> firstByKey = new HashMap<>();
            for (int index = 0; index < futures.size(); index++) {
                HydrologyTileKey key = keys.get(index % keys.size());
                HydrologyTile tile = futures.get(index).get(10L, TimeUnit.SECONDS);
                HydrologyTile existing = firstByKey.putIfAbsent(key, tile);
                if (existing != null) {
                    assertSame(existing, tile);
                }
                assertEquals(planner.plan(key), tile);
            }
            HydrologyColumnSample outside = planner.plan(new HydrologyTileKey(0, 0)).footprint().columns().values()
                    .stream()
                    .filter((HydrologyColumnSample column) -> column.x() >= 64 && !column.ocean())
                    .findFirst()
                    .orElseThrow();
            HydrologyColumnSample expected = cache.columnAt(outside.x(), outside.z()).orElseThrow();
            ArrayList<Callable<HydrologyColumnSample>> columnTasks = new ArrayList<>();
            for (int index = 0; index < 24; index++) {
                columnTasks.add(() -> cache.columnAt(outside.x(), outside.z()).orElseThrow());
            }
            for (Future<HydrologyColumnSample> future : executor.invokeAll(columnTasks)) {
                assertEquals(expected, future.get(10L, TimeUnit.SECONDS));
                assertEquals(expected.renderSample(), future.get(10L, TimeUnit.SECONDS).renderSample());
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10L, TimeUnit.SECONDS));
        }
    }

    private HydrologyPlannerSettings emptySettings() {
        HydrologyPlannerSettings.Source disabled = new HydrologyPlannerSettings.Source(
                false,
                0D,
                Integer.MIN_VALUE,
                0,
                0,
                0
        );
        return new HydrologyPlannerSettings(
                63,
                new HydrologyPlannerSettings.Routing(64, 16, 128, 64, 0, 0, 0D, 0D, 0D, 0D, 1D, 0),
                new HydrologyPlannerSettings.Surface(false, disabled, 2, 4, 1, 2, 4, 1D,
                        HydrologyPlannerSettings.Banks.defaults()),
                new HydrologyPlannerSettings.Hydraulics(3),
                new HydrologyPlannerSettings.Underground(false, disabled, -32, 32, 2, 4, 1, 2, 3, 4, false, 0),
                new HydrologyPlannerSettings.Outlets(
                        false,
                        new HydrologyPlannerSettings.Grotto(false, 2, 2, 2, 512),
                        new HydrologyPlannerSettings.Grotto(false, 2, 2, 2, 512),
                        false,
                        8,
                        16,
                        1,
                        2,
                        2
                ),
                HydrologyPlannerSettings.Geometry.defaults(),
                List.of(), List.of(),
                0D,
                HydrologyPlannerSettings.SeaCaves.disabled()
        );
    }

    private HydrologyPlannerSettings featureSettings() {
        HydrologyPlannerSettings.Source surfaceSources = new HydrologyPlannerSettings.Source(
                true,
                1D,
                80,
                0,
                1,
                16
        );
        HydrologyPlannerSettings.Source disabled = new HydrologyPlannerSettings.Source(
                false,
                0D,
                Integer.MIN_VALUE,
                0,
                0,
                0
        );
        return new HydrologyPlannerSettings(
                63,
                new HydrologyPlannerSettings.Routing(64, 16, 128, 96, 16, 8, 0.5D, 12D, 0.5D, 0.1D, 1D, 0),
                new HydrologyPlannerSettings.Surface(true, surfaceSources, 4, 8, 2, 3, 20, 1.5D, tileBoundedBanks()),
                new HydrologyPlannerSettings.Hydraulics(3),
                new HydrologyPlannerSettings.Underground(false, disabled, -32, 32, 2, 4, 1, 2, 3, 4, false, 0),
                new HydrologyPlannerSettings.Outlets(
                        true,
                        new HydrologyPlannerSettings.Grotto(false, 2, 2, 2, 512),
                        new HydrologyPlannerSettings.Grotto(false, 2, 2, 2, 512),
                        false,
                        8,
                        16,
                        1,
                        2,
                        2
                ),
                HydrologyPlannerSettings.Geometry.defaults(),
                List.of(), List.of(),
                0D,
                HydrologyPlannerSettings.SeaCaves.disabled()
        );
    }

    // Small-tile settings need a blend envelope that fits inside the bounded cross-tile admission period.
    private static HydrologyPlannerSettings.Banks tileBoundedBanks() {
        return new HydrologyPlannerSettings.Banks(0, 3D, 4, 4, 0.25D, 16, 2, 6, 1.6D, HydrologyPlannerSettings.Inlet.none(), 2.5D, 24, true,
                HydrologyPlannerSettings.Erosion.defaults(), HydrologyPlannerSettings.Ponds.defaults());
    }

    private HydrologyTerrainSample featureTerrain(int x, int z) {
        if (x >= 80) {
            return HydrologyTerrainSample.ocean(54, "ocean_parent");
        }
        boolean source = x >= 0 && x <= 16;
        return new HydrologyTerrainSample(
                104 - Math.floorDiv(x, 8),
                1D,
                false,
                false,
                40,
                42,
                true,
                true,
                source,
                source,
                false,
                false,
                0D,
                source ? 1D : 0D,
                0D,
                1D,
                1D,
                1D,
                1D,
                1D,
                "parent",
                "surface",
                "mouth",
                "shore",
                "dry",
                "flooded",
                List.of("default"), List.of(),
                Double.NaN,
                null
        );
    }

    @Test
    public void planningFailureFallsBackToTheEmptyTileAndIsCached() {
        HydrologyPlanner planner = mock(HydrologyPlanner.class);
        HydrologyTileKey key = new HydrologyTileKey(3, -2);
        HydrologyTile empty = new HydrologyTile(key, 7L, 11L, 1024, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), RiverFootprint.empty());
        when(planner.settings()).thenReturn(emptySettings());
        when(planner.plan(key)).thenThrow(new IllegalStateException("Hydrology natural height was not finite at -66,-641"));
        when(planner.emptyTile(key)).thenReturn(empty);
        HydrologyTileCache cache = new HydrologyTileCache(planner, 4);

        assertSame(empty, cache.get(key));
        assertSame(empty, cache.get(key));

        verify(planner, times(1)).plan(key);
        assertTrue(cache.get(key).courses().isEmpty());
    }

    @Test
    public void plannerEmptyTileCarriesTheTileIdentityAndNoContent() {
        HydrologyPlanner planner = new HydrologyPlanner(811L, featureSettings(), this::featureTerrain);
        HydrologyTileKey key = new HydrologyTileKey(-4, 9);

        HydrologyTile tile = planner.emptyTile(key);

        assertEquals(key, tile.key());
        assertEquals(featureSettings().routing().tileSize(), tile.tileSize());
        assertTrue(tile.courses().isEmpty());
        assertTrue(tile.outlets().isEmpty());
        assertTrue(tile.columnAt(0, 0).isEmpty());
    }

    @Test
    public void chunkPreparationPrefetchesTheRingOfTilesAroundTheOnesItNeeded() {
        HydrologyPlanner planner = mock(HydrologyPlanner.class);
        HydrologyTile tile = mock(HydrologyTile.class);
        HydrologyPlannerSettings settings = emptySettings();
        when(planner.settings()).thenReturn(settings);
        when(planner.plan(any(HydrologyTileKey.class))).thenReturn(tile);
        when(tile.columnAt(anyInt(), anyInt())).thenReturn(Optional.empty());
        HydrologyTileCache eager = new HydrologyTileCache(planner, 64, Runnable::run);
        HydrologyTileCache lazy = new HydrologyTileCache(planner, 64);

        eager.prepareChunkColumns(32, 48);
        lazy.prepareChunkColumns(32, 48);

        int tileSize = settings.routing().tileSize();
        int radius = settings.publicationRadius();
        int width = Math.floorDiv(32 + 15 + radius, tileSize) - Math.floorDiv(32 - radius, tileSize) + 1;
        int height = Math.floorDiv(48 + 15 + radius, tileSize) - Math.floorDiv(48 - radius, tileSize) + 1;
        assertEquals(width * height, lazy.size());
        assertEquals((width + 2) * (height + 2), eager.size());
    }

    @Test
    public void areaPrefetchPlansEveryTouchingTileNearestTheCentreFirst() {
        HydrologyPlanner planner = mock(HydrologyPlanner.class);
        HydrologyTile tile = mock(HydrologyTile.class);
        HydrologyPlannerSettings settings = emptySettings();
        when(planner.settings()).thenReturn(settings);
        when(planner.plan(any(HydrologyTileKey.class))).thenReturn(tile);
        List<HydrologyTileKey> order = new java.util.ArrayList<>();
        HydrologyTileCache cache = new HydrologyTileCache(planner, 256, Runnable::run);
        org.mockito.Mockito.doAnswer(invocation -> {
            order.add(invocation.getArgument(0));
            return tile;
        }).when(planner).plan(any(HydrologyTileKey.class));

        int tileSize = settings.routing().tileSize();
        cache.prefetchArea(0, 0, tileSize * 2 - 1, tileSize * 2 - 1, tileSize, tileSize);

        int radius = settings.publicationRadius();
        int extra = radius > 0 ? 1 : 0;
        int span = 2 + 2 * extra;
        assertEquals(span * span, order.size());
        assertEquals(new HydrologyTileKey(1, 1), order.getFirst());
        assertTrue(order.subList(0, 4).containsAll(List.of(new HydrologyTileKey(0, 0), new HydrologyTileKey(1, 0), new HydrologyTileKey(0, 1), new HydrologyTileKey(1, 1))));
    }
    @Test
    public void tileBatchesPlanMissingTilesConcurrentlyOnThePrefetchExecutor() throws Exception {
        HydrologyPlanner planner = mock(HydrologyPlanner.class);
        HydrologyTile tile = mock(HydrologyTile.class);
        when(planner.settings()).thenReturn(emptySettings());
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch allStarted = new CountDownLatch(4);
        doAnswer(invocation -> {
            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            allStarted.countDown();
            assertTrue("every batch member should plan at the same time", allStarted.await(5, TimeUnit.SECONDS));
            inFlight.decrementAndGet();
            return tile;
        }).when(planner).plan(any(HydrologyTileKey.class));
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            HydrologyTileCache cache = new HydrologyTileCache(planner, 64, executor);
            List<HydrologyTileKey> keys = List.of(
                    new HydrologyTileKey(0, 0),
                    new HydrologyTileKey(1, 0),
                    new HydrologyTileKey(2, 0),
                    new HydrologyTileKey(3, 0)
            );

            List<HydrologyTile> tiles = cache.tiles(keys);

            assertEquals(4, tiles.size());
            assertEquals(4, peak.get());
            verify(planner, times(4)).plan(any(HydrologyTileKey.class));
            assertEquals(4, cache.size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void tileBatchesNeverKeepMoreThanHalfTheCacheBoundInFlight() throws Exception {
        HydrologyPlanner planner = mock(HydrologyPlanner.class);
        HydrologyTile tile = mock(HydrologyTile.class);
        when(planner.settings()).thenReturn(emptySettings());
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        List<HydrologyTileKey> planned = java.util.Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> {
            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            Thread.sleep(20L);
            planned.add(invocation.getArgument(0));
            inFlight.decrementAndGet();
            return tile;
        }).when(planner).plan(any(HydrologyTileKey.class));
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            HydrologyTileCache cache = new HydrologyTileCache(planner, 4, executor);
            ArrayList<HydrologyTileKey> keys = new ArrayList<>();
            for (int tileX = 0; tileX < 6; tileX++) {
                keys.add(new HydrologyTileKey(tileX, 0));
            }

            List<HydrologyTile> tiles = cache.tiles(keys);

            assertEquals(6, tiles.size());
            assertEquals(6, planned.size());
            assertTrue("in flight " + peak.get(), peak.get() <= 2);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void tileBatchesWithoutAPrefetchExecutorPlanInlineInKeyOrder() {
        HydrologyPlanner planner = mock(HydrologyPlanner.class);
        when(planner.settings()).thenReturn(emptySettings());
        List<HydrologyTileKey> planned = new ArrayList<>();
        doAnswer(invocation -> {
            planned.add(invocation.getArgument(0));
            return mock(HydrologyTile.class);
        }).when(planner).plan(any(HydrologyTileKey.class));
        HydrologyTileCache cache = new HydrologyTileCache(planner, 8);
        List<HydrologyTileKey> keys = List.of(new HydrologyTileKey(2, 1), new HydrologyTileKey(0, 0), new HydrologyTileKey(1, 1));

        List<HydrologyTile> tiles = cache.tiles(keys);

        assertEquals(keys, planned);
        assertEquals(3, tiles.size());
        assertSame(cache.get(keys.get(1)), tiles.get(1));
    }

    @Test
    public void tileBatchesJoinAPlanAlreadyInFlightInsteadOfQueueingAnother() throws Exception {
        HydrologyPlanner planner = mock(HydrologyPlanner.class);
        HydrologyTile tile = mock(HydrologyTile.class);
        when(planner.settings()).thenReturn(emptySettings());
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger plans = new AtomicInteger();
        doAnswer(invocation -> {
            plans.incrementAndGet();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return tile;
        }).when(planner).plan(any(HydrologyTileKey.class));
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            HydrologyTileCache cache = new HydrologyTileCache(planner, 64, executor);
            HydrologyTileKey key = new HydrologyTileKey(5, 5);
            ExecutorService callers = Executors.newFixedThreadPool(3);
            try {
                List<Future<List<HydrologyTile>>> results = new ArrayList<>();
                for (int caller = 0; caller < 3; caller++) {
                    results.add(callers.submit(() -> cache.tiles(List.of(key))));
                }
                Thread.sleep(100L);
                release.countDown();
                for (Future<List<HydrologyTile>> result : results) {
                    assertSame(tile, result.get(5, TimeUnit.SECONDS).getFirst());
                }
            } finally {
                callers.shutdownNow();
            }
            assertEquals(1, plans.get());
        } finally {
            executor.shutdownNow();
        }
    }
    @Test
    public void interruptedPlanningIsNotPublishedAsAnEmptyTile() {
        HydrologyPlanner planner = mock(HydrologyPlanner.class);
        HydrologyTile tile = mock(HydrologyTile.class);
        when(planner.settings()).thenReturn(emptySettings());
        AtomicInteger plans = new AtomicInteger();
        doAnswer(invocation -> {
            if (plans.incrementAndGet() == 1) {
                throw new IllegalStateException("Interrupted", new InterruptedException());
            }
            return tile;
        }).when(planner).plan(any(HydrologyTileKey.class));
        HydrologyTileCache cache = new HydrologyTileCache(planner, 8);
        HydrologyTileKey key = new HydrologyTileKey(3, 4);

        IllegalStateException interrupted = org.junit.Assert.assertThrows(IllegalStateException.class, () -> cache.get(key));

        assertEquals("Interrupted", interrupted.getMessage());
        assertEquals(0, cache.size());
        assertSame(tile, cache.get(key));
        assertEquals(2, plans.get());
        verify(planner, org.mockito.Mockito.never()).emptyTile(any(HydrologyTileKey.class));
    }

    @Test
    public void directTileRequestsPlanOnThePrefetchExecutorInsteadOfTheCaller() throws Exception {
        HydrologyPlanner planner = mock(HydrologyPlanner.class);
        HydrologyTile tile = mock(HydrologyTile.class);
        when(planner.settings()).thenReturn(emptySettings());
        List<String> planningThreads = java.util.Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> {
            planningThreads.add(Thread.currentThread().getName());
            return tile;
        }).when(planner).plan(any(HydrologyTileKey.class));
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "hydrology-planning-executor"));
        try {
            HydrologyTileCache cache = new HydrologyTileCache(planner, 8, executor);

            assertSame(tile, cache.get(new HydrologyTileKey(7, 7)));

            assertEquals(List.of("hydrology-planning-executor"), planningThreads);
        } finally {
            executor.shutdownNow();
        }
    }
    @Test
    public void forkJoinWorkersPlanOnTheirOwnThreadInsteadOfTheExecutor() throws Exception {
        HydrologyPlanner planner = mock(HydrologyPlanner.class);
        HydrologyTile tile = mock(HydrologyTile.class);
        when(planner.settings()).thenReturn(emptySettings());
        List<String> planningThreads = java.util.Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> {
            planningThreads.add(Thread.currentThread().getName());
            return tile;
        }).when(planner).plan(any(HydrologyTileKey.class));
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "hydrology-planning-executor"));
        java.util.concurrent.ForkJoinPool pool = new java.util.concurrent.ForkJoinPool(1);
        try {
            HydrologyTileCache cache = new HydrologyTileCache(planner, 8, executor);

            HydrologyTile planned = pool.submit(() -> cache.get(new HydrologyTileKey(9, 9))).get(5, TimeUnit.SECONDS);
            List<HydrologyTile> batch = pool.submit(() -> cache.tiles(List.of(new HydrologyTileKey(10, 9), new HydrologyTileKey(11, 9)))).get(5, TimeUnit.SECONDS);

            assertSame(tile, planned);
            assertEquals(2, batch.size());
            assertEquals(3, planningThreads.size());
            assertTrue(planningThreads.toString(), planningThreads.stream().allMatch(name -> name.startsWith("ForkJoinPool-")));
        } finally {
            pool.shutdownNow();
            executor.shutdownNow();
        }
    }
    @Test
    public void plannedCheckNeverPlansItselfButAsksThePrefetchExecutorForMissingTiles() {
        HydrologyPlanner planner = mock(HydrologyPlanner.class);
        HydrologyTile tile = mock(HydrologyTile.class);
        when(planner.settings()).thenReturn(emptySettings());
        when(planner.plan(any(HydrologyTileKey.class))).thenReturn(tile);
        when(tile.columnAt(anyInt(), anyInt())).thenReturn(Optional.empty());
        List<Runnable> queued = new ArrayList<>();
        HydrologyTileCache cache = new HydrologyTileCache(planner, 64, queued::add);

        assertFalse(cache.isPlanned(40, 40));
        assertFalse(queued.isEmpty());
        verify(planner, org.mockito.Mockito.never()).plan(any(HydrologyTileKey.class));

        for (Runnable task : List.copyOf(queued)) {
            task.run();
        }

        assertTrue(cache.isPlanned(40, 40));
        assertTrue(cache.columnAt(40, 40).isEmpty());
    }

    @Test
    public void plannedCheckWithoutAnExecutorReportsTheCacheOnly() {
        HydrologyPlanner planner = mock(HydrologyPlanner.class);
        HydrologyTile tile = mock(HydrologyTile.class);
        when(planner.settings()).thenReturn(emptySettings());
        when(planner.plan(any(HydrologyTileKey.class))).thenReturn(tile);
        when(tile.columnAt(anyInt(), anyInt())).thenReturn(Optional.empty());
        HydrologyTileCache cache = new HydrologyTileCache(planner, 64);

        assertFalse(cache.isPlanned(40, 40));
        cache.columnAt(40, 40);
        assertTrue(cache.isPlanned(40, 40));
    }
}
