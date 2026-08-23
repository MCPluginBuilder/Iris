package art.arcane.iris.core.gui;

import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.render.IrisRenderer;
import art.arcane.iris.engine.framework.render.RenderType;
import org.junit.Test;

import java.awt.EventQueue;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;

public class VisionRenderControllerTest {
    @Test
    public void visibleTilesAreUniqueAndOrderedCenterFirst() {
        VisionRenderController.RenderSpec spec = spec(RenderType.BIOME, false, 7L, 0D, 0D, 2, 1440, 792);

        List<VisionRenderController.VisibleTile> tiles = VisionRenderController.visibleTiles(spec);

        assertEquals(96, tiles.size());
        Set<String> coordinates = new HashSet<>();
        double previousDistance = -1D;
        for (VisionRenderController.VisibleTile tile : tiles) {
            assertTrue(coordinates.add(tile.tileX() + ":" + tile.tileZ()));
            assertTrue(tile.distanceSquared() >= previousDistance);
            previousDistance = tile.distanceSquared();
        }
    }

    @Test
    public void negativeWorldCoordinatesUseFloorBasedTileOwnership() {
        VisionRenderController.RenderSpec spec = spec(RenderType.BIOME, false, 1L, -64D, -64D, 0, 1, 1);

        List<VisionRenderController.VisibleTile> tiles = VisionRenderController.visibleTiles(spec);

        assertEquals(1, tiles.size());
        assertEquals(-1L, tiles.get(0).tileX());
        assertEquals(-1L, tiles.get(0).tileZ());
    }

    @Test
    public void cacheIdentityIncludesRevisionModeZoomAndQuality() {
        VisionRenderController.TileKey baseline = key(4L, RenderType.BIOME, 2, false, 32, 88);

        assertNotEquals(baseline, key(5L, RenderType.BIOME, 2, false, 32, 88));
        assertNotEquals(baseline, key(4L, RenderType.RIVER, 2, false, 32, 88));
        assertNotEquals(baseline, key(4L, RenderType.BIOME, 3, false, 32, 88));
        assertNotEquals(baseline, key(4L, RenderType.BIOME, 2, true, 32, 88));
        assertNotEquals(baseline, key(4L, RenderType.BIOME, 2, false, 28, 72));
    }

    @Test
    public void zoomUsesFixedPowerOfTwoBlocksPerPixel() {
        for (int zoom = VisionRenderController.MINIMUM_ZOOM; zoom <= VisionRenderController.MAXIMUM_ZOOM; zoom++) {
            assertEquals((double) (1L << zoom), spec(RenderType.HEIGHT, false, 1L, 0D, 0D, zoom, 1, 1).blocksPerPixel(), 0D);
        }
    }

    @Test
    public void adaptiveSamplingKeepsDefaultAndFullscreenFramesWithinHardBudgets() {
        int defaultTiles = VisionRenderController.visibleTiles(
                spec(RenderType.BIOME, false, 1L, 0D, 0D, 2, 1440, 792)
        ).size();
        int fullscreenTiles = VisionRenderController.visibleTiles(
                spec(RenderType.BIOME, false, 1L, 0D, 0D, 2, 3840, 2160)
        ).size();

        assertBudget(defaultTiles, 2, RenderType.BIOME, false, VisionRenderController.HIGH_QUALITY_REFINED_SAMPLE_BUDGET);
        assertBudget(fullscreenTiles, 2, RenderType.BIOME, false, VisionRenderController.HIGH_QUALITY_REFINED_SAMPLE_BUDGET);
        assertBudget(defaultTiles, 2, RenderType.BIOME, true, VisionRenderController.LOW_QUALITY_REFINED_SAMPLE_BUDGET);
        assertBudget(fullscreenTiles, 2, RenderType.RIVER, false, VisionRenderController.RIVER_REFINED_SAMPLE_BUDGET);
        assertTrue(VisionRenderController.refinedPixels(RenderType.BIOME, false, defaultTiles, 2)
                > VisionRenderController.refinedPixels(RenderType.RIVER, false, defaultTiles, 2));
        assertEquals(8, VisionRenderController.previewPixels(defaultTiles, 2));
        assertEquals(24, VisionRenderController.refinedPixels(RenderType.BIOME, false, defaultTiles, 2));
        assertEquals(16, VisionRenderController.refinedPixels(RenderType.RIVER, false, defaultTiles, 2));
        assertEquals(4, VisionRenderController.previewPixels(defaultTiles, 0));
        assertEquals(8, VisionRenderController.refinedPixels(RenderType.BIOME, false, defaultTiles, 0));
    }

    @Test
    public void delayedRefinementWaitsForPreviewStageToDrain() throws Exception {
        IrisRenderer renderer = mock(IrisRenderer.class);
        CountDownLatch previewStarted = new CountDownLatch(1);
        CountDownLatch releasePreview = new CountDownLatch(1);
        CountDownLatch refinementObserved = new CountDownLatch(1);
        VisionRenderController.RenderSpec spec = new VisionRenderController.RenderSpec(
                renderer,
                RenderType.BIOME,
                false,
                1L,
                0D,
                0D,
                2,
                512,
                256
        );
        int tileCount = VisionRenderController.visibleTiles(spec).size();
        int previewPixels = VisionRenderController.previewPixels(tileCount, spec.zoom());
        when(renderer.renderStudio(
                anyDouble(),
                anyDouble(),
                anyDouble(),
                anyInt(),
                any(RenderType.class),
                any(BooleanSupplier.class)
        )).thenAnswer(invocation -> {
            int resolution = invocation.getArgument(3);
            if (resolution == previewPixels && previewStarted.getCount() > 0L) {
                previewStarted.countDown();
                releasePreview.await(2L, TimeUnit.SECONDS);
            } else if (resolution > previewPixels) {
                refinementObserved.countDown();
            }
            return new BufferedImage(resolution, resolution, BufferedImage.TYPE_INT_RGB);
        });

        VisionRenderController controller = new VisionRenderController(
                () -> {
                },
                new VisionRenderController.RuntimeOptions(1, 2, 15L, false)
        );
        try {
            VisionRenderController.Frame frame = controller.request(spec);
            assertTrue(previewStarted.await(1L, TimeUnit.SECONDS));
            assertTrue(await(() -> controller.refinementActive(frame), 1_000L));
            assertFalse(refinementObserved.await(50L, TimeUnit.MILLISECONDS));
            releasePreview.countDown();
            assertTrue(refinementObserved.await(2L, TimeUnit.SECONDS));
        } finally {
            releasePreview.countDown();
            controller.close();
        }
    }

    @Test
    public void stalePublicationCannotConsumeCurrentCacheHitFrameSignal() throws Exception {
        CountDownLatch eventQueueBlocked = new CountDownLatch(1);
        CountDownLatch releaseEventQueue = new CountDownLatch(1);
        CountDownLatch publicationObserved = new CountDownLatch(1);
        EventQueue.invokeLater(() -> {
            eventQueueBlocked.countDown();
            try {
                releaseEventQueue.await(2L, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(eventQueueBlocked.await(1L, TimeUnit.SECONDS));

        IrisRenderer renderer = mock(IrisRenderer.class);
        when(renderer.renderStudio(
                anyDouble(),
                anyDouble(),
                anyDouble(),
                anyInt(),
                any(RenderType.class),
                any(BooleanSupplier.class)
        )).thenAnswer(invocation -> {
            int resolution = invocation.getArgument(3);
            return new BufferedImage(resolution, resolution, BufferedImage.TYPE_INT_RGB);
        });
        VisionRenderController.RenderSpec spec = new VisionRenderController.RenderSpec(
                renderer,
                RenderType.BIOME,
                false,
                1L,
                0D,
                0D,
                2,
                128,
                128
        );
        VisionRenderController controller = new VisionRenderController(
                publicationObserved::countDown,
                new VisionRenderController.RuntimeOptions(1, 2, 0L, false)
        );
        try {
            VisionRenderController.Frame first = controller.request(spec);
            assertTrue(await(() -> {
                VisionRenderController.Progress progress = controller.progress(first);
                return progress.refinedReady() == progress.total();
            }, 1_000L));

            VisionRenderController.Frame cached = controller.request(spec);
            assertEquals(controller.progress(cached).total(), controller.progress(cached).refinedReady());
            releaseEventQueue.countDown();

            assertTrue(publicationObserved.await(1L, TimeUnit.SECONDS));
        } finally {
            releaseEventQueue.countDown();
            controller.close();
        }
    }

    @Test
    public void cancelledRiverViewsDoNotParkRenderWorkersOnTheSingleFlightPermit() throws Exception {
        IrisRenderer renderer = mock(IrisRenderer.class);
        CountDownLatch riverStarted = new CountDownLatch(1);
        CountDownLatch releaseRiver = new CountDownLatch(1);
        CountDownLatch biomeObserved = new CountDownLatch(1);
        when(renderer.renderStudio(
                anyDouble(),
                anyDouble(),
                anyDouble(),
                anyInt(),
                any(RenderType.class),
                any(BooleanSupplier.class)
        )).thenAnswer(invocation -> {
            int resolution = invocation.getArgument(3);
            RenderType type = invocation.getArgument(4);
            if (type == RenderType.RIVER && riverStarted.getCount() > 0L) {
                riverStarted.countDown();
                releaseRiver.await(2L, TimeUnit.SECONDS);
            }
            if (type == RenderType.BIOME) {
                biomeObserved.countDown();
            }
            return new BufferedImage(resolution, resolution, BufferedImage.TYPE_INT_RGB);
        });
        VisionRenderController controller = new VisionRenderController(
                () -> {
                },
                new VisionRenderController.RuntimeOptions(2, 2, 1_000L, false)
        );
        try {
            controller.request(new VisionRenderController.RenderSpec(
                    renderer,
                    RenderType.RIVER,
                    false,
                    1L,
                    0D,
                    0D,
                    2,
                    128,
                    128
            ));
            assertTrue(riverStarted.await(1L, TimeUnit.SECONDS));
            controller.request(new VisionRenderController.RenderSpec(
                    renderer,
                    RenderType.RIVER,
                    false,
                    1L,
                    128D,
                    0D,
                    2,
                    128,
                    128
            ));
            controller.request(new VisionRenderController.RenderSpec(
                    renderer,
                    RenderType.BIOME,
                    false,
                    1L,
                    256D,
                    0D,
                    2,
                    128,
                    128
            ));

            assertTrue(biomeObserved.await(1L, TimeUnit.SECONDS));
        } finally {
            releaseRiver.countDown();
            controller.close();
        }
    }

    private static void assertBudget(int tileCount, int zoom, RenderType type, boolean lowQuality, long budget) {
        int previewPixels = VisionRenderController.previewPixels(tileCount, zoom);
        int refinedPixels = VisionRenderController.refinedPixels(type, lowQuality, tileCount, zoom);
        long previewSamples = VisionRenderController.sampleCount(tileCount, previewPixels);
        long refinedSamples = VisionRenderController.sampleCount(tileCount, refinedPixels);
        assertTrue(previewSamples <= VisionRenderController.PREVIEW_SAMPLE_BUDGET);
        assertTrue(refinedSamples <= budget);
        assertTrue(previewSamples + refinedSamples <= VisionRenderController.PREVIEW_SAMPLE_BUDGET + budget);
        assertTrue(previewSamples + refinedSamples < 1_000_000L);
        assertTrue(refinedPixels >= previewPixels);
    }

    private static VisionRenderController.TileKey key(
            long revision,
            RenderType type,
            int zoom,
            boolean lowQuality,
            int previewPixels,
            int refinedPixels
    ) {
        return new VisionRenderController.TileKey(
                revision,
                type,
                zoom,
                lowQuality,
                previewPixels,
                refinedPixels,
                3L,
                -6L
        );
    }

    private static boolean await(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(5L);
        }
        return condition.getAsBoolean();
    }

    private static VisionRenderController.RenderSpec spec(
            RenderType type,
            boolean lowQuality,
            long revision,
            double centerX,
            double centerZ,
            int zoom,
            int width,
            int height
    ) {
        Engine engine = mock(Engine.class);
        return new VisionRenderController.RenderSpec(
                new IrisRenderer(engine),
                type,
                lowQuality,
                revision,
                centerX,
                centerZ,
                zoom,
                width,
                height
        );
    }
}
