package art.arcane.iris.engine.hydrology;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
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
                new HydrologyPlannerSettings.Routing(64, 16, 128, 64, 0, 0, 0D, 0D, 0D, 0D),
                new HydrologyPlannerSettings.Surface(false, disabled, 2, 4, 1, 2, 4, 1D,
                        HydrologyPlannerSettings.Banks.defaults()),
                new HydrologyPlannerSettings.Hydraulics(3),
                new HydrologyPlannerSettings.Underground(false, disabled, -32, 32, 2, 4, 1, 2, 3, 4, false),
                new HydrologyPlannerSettings.Outlets(
                        false,
                        new HydrologyPlannerSettings.Grotto(false, 2, 2, 2, 512),
                        new HydrologyPlannerSettings.Grotto(false, 2, 2, 2, 512),
                        false,
                        8,
                        16,
                        1,
                        2
                ),
                HydrologyPlannerSettings.Geometry.defaults(),
                List.of()
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
                new HydrologyPlannerSettings.Routing(64, 16, 128, 96, 16, 8, 0.5D, 12D, 0.5D, 0.1D),
                new HydrologyPlannerSettings.Surface(true, surfaceSources, 4, 8, 2, 3, 20, 1.5D, tileBoundedBanks()),
                new HydrologyPlannerSettings.Hydraulics(3),
                new HydrologyPlannerSettings.Underground(false, disabled, -32, 32, 2, 4, 1, 2, 3, 4, false),
                new HydrologyPlannerSettings.Outlets(
                        true,
                        new HydrologyPlannerSettings.Grotto(false, 2, 2, 2, 512),
                        new HydrologyPlannerSettings.Grotto(false, 2, 2, 2, 512),
                        false,
                        8,
                        16,
                        1,
                        2
                ),
                HydrologyPlannerSettings.Geometry.defaults(),
                List.of()
        );
    }

    // Small-tile settings need a blend envelope that fits inside the bounded cross-tile admission period.
    private static HydrologyPlannerSettings.Banks tileBoundedBanks() {
        return new HydrologyPlannerSettings.Banks(1, 1, 3D, 4, 4, 0.25D, 16, 2, 6, 1.6D, true);
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
                List.of("default")
        );
    }
}
