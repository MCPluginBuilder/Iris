/*
 * Iris is a World Generator for Minecraft Servers
 * Copyright (c) 2026 Arcane Arts (Volmit Software)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package art.arcane.iris.core.gui;

import art.arcane.iris.engine.framework.PreservationRegistry;
import art.arcane.iris.engine.framework.render.IrisRenderer;
import art.arcane.iris.engine.framework.render.RenderType;
import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.IrisServices;

import java.awt.EventQueue;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

final class VisionRenderController implements AutoCloseable {
    static final int TILE_PIXELS = 128;
    static final int PREVIEW_PIXELS = 8;
    static final int LOW_QUALITY_PIXELS = 16;
    static final int HIGH_QUALITY_PIXELS = 24;
    static final int RIVER_PIXELS = 16;
    static final int MINIMUM_ZOOM = 0;
    static final int MAXIMUM_ZOOM = 12;
    static final long PREVIEW_SAMPLE_BUDGET = 6_144L;
    static final long HIGH_QUALITY_REFINED_SAMPLE_BUDGET = 55_296L;
    static final long LOW_QUALITY_REFINED_SAMPLE_BUDGET = 24_576L;
    static final long RIVER_REFINED_SAMPLE_BUDGET = 24_576L;

    private static final int MAXIMUM_WORKERS = 3;
    private static final int MAXIMUM_VISIBLE_TILES = 8_192;
    private static final int RENDER_QUEUE_CAPACITY = 768;
    private static final int PROBE_QUEUE_CAPACITY = 1;
    private static final long REFINEMENT_DELAY_MILLIS = 180L;
    private static final long CACHE_BYTES = 64L * 1024L * 1024L;

    private final Runnable listener;
    private final ThreadPoolExecutor renderExecutor;
    private final ScheduledThreadPoolExecutor refinementExecutor;
    private final ThreadPoolExecutor probeExecutor;
    private final Semaphore riverPermit;
    private final WeightedTileCache cache;
    private final AtomicLong viewSequence;
    private final AtomicLong probeSequence;
    private final AtomicBoolean closed;
    private final AtomicBoolean publicationQueued;
    private final AtomicBoolean publicationDirty;
    private final long refinementDelayMillis;
    private volatile Frame currentFrame;
    private volatile WorkState currentWork;
    private volatile CancellationToken currentToken;
    private volatile Future<?> refinementFuture;
    private volatile long refinementRevision;

    VisionRenderController(Runnable listener) {
        this(listener, RuntimeOptions.production());
    }

    VisionRenderController(Runnable listener, RuntimeOptions options) {
        this.listener = Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(options, "options");
        AtomicInteger threadSequence = new AtomicInteger();
        ThreadFactory renderFactory = daemonFactory("Iris Vision Render", Thread.NORM_PRIORITY, threadSequence);
        ThreadFactory refinementFactory = daemonFactory("Iris Vision Refine", Thread.MIN_PRIORITY, threadSequence);
        ThreadFactory probeFactory = daemonFactory("Iris Vision Probe", Thread.MIN_PRIORITY, threadSequence);
        this.renderExecutor = new ThreadPoolExecutor(
                options.workers(),
                options.workers(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(options.renderQueueCapacity()),
                renderFactory,
                new ThreadPoolExecutor.AbortPolicy()
        );
        this.refinementExecutor = new ScheduledThreadPoolExecutor(1, refinementFactory);
        this.refinementExecutor.setRemoveOnCancelPolicy(true);
        this.probeExecutor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(PROBE_QUEUE_CAPACITY),
                probeFactory,
                new ThreadPoolExecutor.DiscardOldestPolicy()
        );
        this.riverPermit = new Semaphore(1, true);
        this.cache = new WeightedTileCache(CACHE_BYTES);
        this.viewSequence = new AtomicLong();
        this.probeSequence = new AtomicLong();
        this.closed = new AtomicBoolean();
        this.publicationQueued = new AtomicBoolean();
        this.publicationDirty = new AtomicBoolean();
        this.refinementDelayMillis = options.refinementDelayMillis();
        if (options.registerPreservation()) {
            PreservationRegistry preservation = IrisServices.getOrNull(PreservationRegistry.class);
            if (preservation != null) {
                preservation.register(renderExecutor);
                preservation.register(refinementExecutor);
                preservation.register(probeExecutor);
            }
        }
    }

    synchronized Frame request(RenderSpec spec) {
        Objects.requireNonNull(spec, "spec");
        if (closed.get()) {
            throw new IllegalStateException("Vision render controller is closed");
        }

        CancellationToken previousToken = currentToken;
        if (previousToken != null) {
            previousToken.cancel();
        }
        Future<?> previousRefinement = refinementFuture;
        if (previousRefinement != null) {
            previousRefinement.cancel(false);
        }
        renderExecutor.getQueue().clear();
        refinementRevision = 0L;
        probeSequence.incrementAndGet();
        probeExecutor.getQueue().clear();

        long viewRevision = viewSequence.incrementAndGet();
        List<VisibleTile> tiles = visibleTiles(spec);
        int previewPixels = previewPixels(tiles.size(), spec.zoom());
        int refinedPixels = refinedPixels(spec.type(), spec.lowQuality(), tiles.size(), spec.zoom());
        Frame frame = new Frame(viewRevision, spec, tiles, previewPixels, refinedPixels);
        CancellationToken token = new CancellationToken();
        currentFrame = frame;
        currentToken = token;
        WorkState work = new WorkState(frame, token);
        currentWork = work;
        schedule(work, RenderStage.PREVIEW);
        refinementFuture = frame.refinementRequired()
                ? refinementExecutor.schedule(
                        () -> activateRefinement(work),
                        refinementDelayMillis,
                        TimeUnit.MILLISECONDS
                )
                : null;
        publish(frame, token);
        return frame;
    }

    Frame currentFrame() {
        return currentFrame;
    }

    boolean refinementActive(Frame frame) {
        return frame != null && currentFrame == frame && refinementRevision == frame.viewRevision();
    }

    BufferedImage image(Frame frame, VisibleTile tile) {
        if (frame == null || tile == null) {
            return null;
        }
        TileImages images = cache.get(frame.key(tile));
        if (images == null) {
            return null;
        }
        return images.refined() == null ? images.preview() : images.refined();
    }

    boolean isRefined(Frame frame, VisibleTile tile) {
        if (frame == null || tile == null) {
            return false;
        }
        TileImages images = cache.get(frame.key(tile));
        return images != null && images.refined() != null;
    }

    Progress progress(Frame frame) {
        if (frame == null) {
            return new Progress(0, 0, 0, 0, 0);
        }
        int previewReady = 0;
        int refinedReady = 0;
        for (VisibleTile tile : frame.tiles()) {
            TileImages images = cache.get(frame.key(tile));
            if (images == null) {
                continue;
            }
            if (images.preview() != null || images.refined() != null) {
                previewReady++;
            }
            if (images.refined() != null) {
                refinedReady++;
            }
        }
        return new Progress(
                frame.tiles().size(),
                previewReady,
                refinedReady,
                renderExecutor.getActiveCount(),
                renderExecutor.getQueue().size()
        );
    }

    <T> void submitProbe(Frame frame, Callable<T> probe, Consumer<T> consumer) {
        Objects.requireNonNull(probe, "probe");
        Objects.requireNonNull(consumer, "consumer");
        if (frame == null || closed.get() || currentFrame != frame) {
            return;
        }
        long probeRevision = probeSequence.incrementAndGet();
        probeExecutor.getQueue().clear();
        try {
            probeExecutor.execute(() -> runProbe(frame, probeRevision, probe, consumer));
        } catch (RejectedExecutionException ignored) {
            probeExecutor.getQueue().clear();
        }
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        CancellationToken token = currentToken;
        if (token != null) {
            token.cancel();
        }
        Future<?> future = refinementFuture;
        if (future != null) {
            future.cancel(false);
        }
        currentWork = null;
        refinementRevision = 0L;
        renderExecutor.getQueue().clear();
        probeExecutor.getQueue().clear();
        refinementExecutor.shutdownNow();
        renderExecutor.shutdownNow();
        probeExecutor.shutdownNow();
        cache.clear();
    }

    static List<VisibleTile> visibleTiles(RenderSpec spec) {
        Objects.requireNonNull(spec, "spec");
        double blocksPerPixel = spec.blocksPerPixel();
        double tileSpan = TILE_PIXELS * blocksPerPixel;
        double halfWidth = spec.width() * blocksPerPixel * 0.5D;
        double halfHeight = spec.height() * blocksPerPixel * 0.5D;
        long minimumX = floorTile(spec.centerX() - halfWidth, tileSpan);
        long maximumX = floorTile(Math.nextDown(spec.centerX() + halfWidth), tileSpan);
        long minimumZ = floorTile(spec.centerZ() - halfHeight, tileSpan);
        long maximumZ = floorTile(Math.nextDown(spec.centerZ() + halfHeight), tileSpan);
        long tileCount = Math.multiplyExact(maximumX - minimumX + 1L, maximumZ - minimumZ + 1L);
        if (tileCount > MAXIMUM_VISIBLE_TILES) {
            throw new IllegalArgumentException("Vision viewport contains too many tiles");
        }

        ArrayList<VisibleTile> tiles = new ArrayList<>((int) tileCount);
        double centerTileX = spec.centerX() / tileSpan;
        double centerTileZ = spec.centerZ() / tileSpan;
        for (long tileZ = minimumZ; ; tileZ++) {
            for (long tileX = minimumX; ; tileX++) {
                int screenX = (int) Math.round(spec.width() * 0.5D + (tileX * tileSpan - spec.centerX()) / blocksPerPixel);
                int screenY = (int) Math.round(spec.height() * 0.5D + (tileZ * tileSpan - spec.centerZ()) / blocksPerPixel);
                double deltaX = tileX + 0.5D - centerTileX;
                double deltaZ = tileZ + 0.5D - centerTileZ;
                tiles.add(new VisibleTile(tileX, tileZ, screenX, screenY, deltaX * deltaX + deltaZ * deltaZ));
                if (tileX == maximumX) {
                    break;
                }
            }
            if (tileZ == maximumZ) {
                break;
            }
        }
        tiles.sort(Comparator.comparingDouble(VisibleTile::distanceSquared)
                .thenComparingLong(VisibleTile::tileZ)
                .thenComparingLong(VisibleTile::tileX));
        return List.copyOf(tiles);
    }

    static int previewPixels(int tileCount, int zoom) {
        int cacheSafeMaximum = Math.min(PREVIEW_PIXELS, Math.max(4, 2 << zoom));
        return budgetedResolution(PREVIEW_SAMPLE_BUDGET, tileCount, cacheSafeMaximum);
    }

    static int refinedPixels(RenderType type, boolean lowQuality, int tileCount, int zoom) {
        Objects.requireNonNull(type, "type");
        long budget = type == RenderType.RIVER
                ? RIVER_REFINED_SAMPLE_BUDGET
                : lowQuality ? LOW_QUALITY_REFINED_SAMPLE_BUDGET : HIGH_QUALITY_REFINED_SAMPLE_BUDGET;
        int qualityMaximum = type == RenderType.RIVER
                ? RIVER_PIXELS
                : lowQuality ? LOW_QUALITY_PIXELS : HIGH_QUALITY_PIXELS;
        int cacheSafeMaximum = Math.min(qualityMaximum, Math.max(8, 8 << zoom));
        return Math.max(previewPixels(tileCount, zoom), budgetedResolution(budget, tileCount, cacheSafeMaximum));
    }

    static long sampleCount(int tileCount, int resolution) {
        if (tileCount < 1 || resolution < 1) {
            throw new IllegalArgumentException("Vision sample dimensions must be positive");
        }
        return Math.multiplyExact((long) tileCount, Math.multiplyExact((long) resolution, resolution));
    }

    private static int budgetedResolution(long budget, int tileCount, int maximum) {
        if (tileCount < 1 || maximum < 1) {
            throw new IllegalArgumentException("Vision sample dimensions must be positive");
        }
        long samplesPerTile = Math.max(1L, budget / tileCount);
        int resolution = Math.max(1, Math.min(maximum, (int) Math.floor(Math.sqrt(samplesPerTile))));
        if (resolution >= 16) {
            resolution -= resolution % 4;
        }
        return Math.max(1, resolution);
    }

    private static long floorTile(double coordinate, double tileSpan) {
        double tile = Math.floor(coordinate / tileSpan);
        if (tile < Long.MIN_VALUE || tile > Long.MAX_VALUE) {
            throw new IllegalArgumentException("Vision viewport exceeds tile coordinate range");
        }
        return (long) tile;
    }

    private static ThreadFactory daemonFactory(String name, int priority, AtomicInteger sequence) {
        return (Runnable runnable) -> {
            Thread thread = new Thread(runnable, name + " " + sequence.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(priority);
            thread.setUncaughtExceptionHandler((Thread failedThread, Throwable error) -> IrisLogging.reportError(error));
            return thread;
        };
    }

    private void activateRefinement(WorkState work) {
        synchronized (work) {
            if (!isCurrent(work) || !work.frame().refinementRequired()) {
                return;
            }
            work.activateRefinement();
            refinementRevision = work.frame().viewRevision();
        }
        schedule(work, RenderStage.REFINED);
    }

    private void schedule(WorkState work, RenderStage stage) {
        synchronized (work) {
            if (!isCurrent(work)) {
                return;
            }
            if (stage == RenderStage.REFINED
                    && (!work.refinementActivated() || !work.complete(RenderStage.PREVIEW))) {
                return;
            }
            int admissionLimit = work.frame().spec().type() == RenderType.RIVER
                    ? 1
                    : renderExecutor.getCorePoolSize();
            while (work.inFlight(stage) < admissionLimit) {
                int previousIndex = work.index(stage);
                ScheduledTile scheduled = nextTile(work, stage);
                if (scheduled == null) {
                    return;
                }
                boolean riverAcquired = work.frame().spec().type() == RenderType.RIVER;
                if (riverAcquired && !riverPermit.tryAcquire()) {
                    work.setIndex(stage, previousIndex);
                    return;
                }
                work.incrementInFlight(stage);
                try {
                    renderExecutor.execute(() -> render(work, scheduled.tile(), scheduled.request(), riverAcquired));
                } catch (RejectedExecutionException ignored) {
                    work.setIndex(stage, previousIndex);
                    work.decrementInFlight(stage);
                    if (riverAcquired) {
                        riverPermit.release();
                    }
                    return;
                }
            }
        }
    }

    private ScheduledTile nextTile(WorkState work, RenderStage stage) {
        Frame frame = work.frame();
        while (work.index(stage) < frame.tiles().size()) {
            VisibleTile tile = frame.tiles().get(work.index(stage));
            work.setIndex(stage, work.index(stage) + 1);
            TileKey tileKey = frame.key(tile);
            TileImages images = cache.get(tileKey);
            if (stage == RenderStage.PREVIEW
                    && images != null
                    && (images.preview() != null || images.refined() != null)) {
                continue;
            }
            if (stage == RenderStage.REFINED && images != null && images.refined() != null) {
                continue;
            }
            return new ScheduledTile(tile, new RenderRequest(tileKey, stage));
        }
        return null;
    }

    private void render(WorkState work, VisibleTile tile, RenderRequest request, boolean riverAcquired) {
        Frame frame = work.frame();
        CancellationToken token = work.token();
        try {
            if (!isCurrent(frame, token)) {
                return;
            }
            int resolution = request.stage() == RenderStage.PREVIEW
                    ? frame.previewPixels()
                    : frame.refinedPixels();
            double tileSpan = TILE_PIXELS * frame.spec().blocksPerPixel();
            BufferedImage image = frame.spec().renderer().renderStudio(
                    tile.tileX() * tileSpan,
                    tile.tileZ() * tileSpan,
                    tileSpan,
                    resolution,
                    frame.spec().type(),
                    () -> !isCurrent(frame, token)
            );
            if (!isCurrent(frame, token)) {
                return;
            }
            cache.put(request.tileKey(), request.stage(), image);
            publish(frame, token);
        } catch (CancellationException ignored) {
        } catch (Throwable error) {
            IrisLogging.debug("Vision tile render failed: " + error.getClass().getSimpleName() + ": " + error.getMessage());
        } finally {
            if (riverAcquired) {
                riverPermit.release();
                WorkState active = currentWork;
                if (active != null && active != work) {
                    schedule(active, RenderStage.PREVIEW);
                    schedule(active, RenderStage.REFINED);
                }
            }
            complete(work, request.stage());
        }
    }

    private void complete(WorkState work, RenderStage stage) {
        boolean current;
        synchronized (work) {
            work.decrementInFlight(stage);
            current = isCurrent(work);
        }
        if (!current) {
            return;
        }
        schedule(work, stage);
        if (stage == RenderStage.PREVIEW) {
            schedule(work, RenderStage.REFINED);
        }
    }

    private <T> void runProbe(Frame frame, long probeRevision, Callable<T> probe, Consumer<T> consumer) {
        try {
            if (!isProbeCurrent(frame, probeRevision)) {
                return;
            }
            T result = probe.call();
            if (!isProbeCurrent(frame, probeRevision)) {
                return;
            }
            EventQueue.invokeLater(() -> {
                if (isProbeCurrent(frame, probeRevision)) {
                    consumer.accept(result);
                }
            });
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Throwable error) {
            IrisLogging.debug("Vision probe failed: " + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    private boolean isProbeCurrent(Frame frame, long probeRevision) {
        return !closed.get() && currentFrame == frame && probeSequence.get() == probeRevision;
    }

    private boolean isCurrent(Frame frame, CancellationToken token) {
        return !closed.get() && !token.cancelled() && currentFrame == frame;
    }

    private boolean isCurrent(WorkState work) {
        return currentWork == work && isCurrent(work.frame(), work.token());
    }

    private void publish(Frame frame, CancellationToken token) {
        if (!isCurrent(frame, token)) {
            return;
        }
        publicationDirty.set(true);
        queuePublication();
    }

    private void queuePublication() {
        if (!publicationQueued.compareAndSet(false, true)) {
            return;
        }
        EventQueue.invokeLater(() -> {
            try {
                if (publicationDirty.getAndSet(false) && !closed.get()) {
                    listener.run();
                }
            } finally {
                publicationQueued.set(false);
                if (publicationDirty.get() && !closed.get()) {
                    queuePublication();
                }
            }
        });
    }

    record RenderSpec(
            IrisRenderer renderer,
            RenderType type,
            boolean lowQuality,
            long contentRevision,
            double centerX,
            double centerZ,
            int zoom,
            int width,
            int height
    ) {
        RenderSpec {
            Objects.requireNonNull(renderer, "renderer");
            Objects.requireNonNull(type, "type");
            if (!Double.isFinite(centerX) || !Double.isFinite(centerZ)) {
                throw new IllegalArgumentException("Vision center must be finite");
            }
            if (zoom < MINIMUM_ZOOM || zoom > MAXIMUM_ZOOM) {
                throw new IllegalArgumentException("Vision zoom is outside the supported range");
            }
            if (width < 1 || height < 1) {
                throw new IllegalArgumentException("Vision viewport must be positive");
            }
        }

        double blocksPerPixel() {
            return 1L << zoom;
        }
    }

    record Frame(
            long viewRevision,
            RenderSpec spec,
            List<VisibleTile> tiles,
            int previewPixels,
            int refinedPixels
    ) {
        Frame {
            Objects.requireNonNull(spec, "spec");
            tiles = List.copyOf(tiles);
            if (previewPixels < 1 || refinedPixels < previewPixels) {
                throw new IllegalArgumentException("Vision frame sample resolutions are invalid");
            }
        }

        boolean refinementRequired() {
            return refinedPixels > previewPixels;
        }

        TileKey key(VisibleTile tile) {
            return new TileKey(
                    spec.contentRevision(),
                    spec.type(),
                    spec.zoom(),
                    spec.lowQuality(),
                    previewPixels,
                    refinedPixels,
                    tile.tileX(),
                    tile.tileZ()
            );
        }
    }

    record VisibleTile(long tileX, long tileZ, int screenX, int screenY, double distanceSquared) {
    }

    record TileKey(
            long contentRevision,
            RenderType type,
            int zoom,
            boolean lowQuality,
            int previewPixels,
            int refinedPixels,
            long tileX,
            long tileZ
    ) {
        TileKey {
            Objects.requireNonNull(type, "type");
            if (previewPixels < 1 || refinedPixels < previewPixels) {
                throw new IllegalArgumentException("Vision cache sample resolutions are invalid");
            }
        }
    }

    record Progress(int total, int previewReady, int refinedReady, int active, int queued) {
        double completion() {
            if (total == 0) {
                return 1D;
            }
            return Math.min(1D, (previewReady + refinedReady) / (double) (total * 2));
        }
    }

    record RuntimeOptions(
            int workers,
            int renderQueueCapacity,
            long refinementDelayMillis,
            boolean registerPreservation
    ) {
        RuntimeOptions {
            if (workers < 1 || renderQueueCapacity < 1 || refinementDelayMillis < 0L) {
                throw new IllegalArgumentException("Vision render runtime options are invalid");
            }
        }

        static RuntimeOptions production() {
            int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
            int workers = Math.max(1, Math.min(MAXIMUM_WORKERS, processors));
            return new RuntimeOptions(workers, RENDER_QUEUE_CAPACITY, REFINEMENT_DELAY_MILLIS, true);
        }
    }

    enum RenderStage {
        PREVIEW,
        REFINED
    }

    private record RenderRequest(TileKey tileKey, RenderStage stage) {
    }

    private record ScheduledTile(VisibleTile tile, RenderRequest request) {
    }

    private record TileImages(BufferedImage preview, BufferedImage refined, long bytes) {
    }

    private static final class CancellationToken {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        void cancel() {
            cancelled.set(true);
        }

        boolean cancelled() {
            return cancelled.get();
        }
    }

    private static final class WorkState {
        private final Frame frame;
        private final CancellationToken token;
        private int previewIndex;
        private int refinedIndex;
        private int previewInFlight;
        private int refinedInFlight;
        private boolean refinementActivated;

        private WorkState(Frame frame, CancellationToken token) {
            this.frame = frame;
            this.token = token;
        }

        Frame frame() {
            return frame;
        }

        CancellationToken token() {
            return token;
        }

        int index(RenderStage stage) {
            return stage == RenderStage.PREVIEW ? previewIndex : refinedIndex;
        }

        void setIndex(RenderStage stage, int index) {
            if (stage == RenderStage.PREVIEW) {
                previewIndex = index;
            } else {
                refinedIndex = index;
            }
        }

        int inFlight(RenderStage stage) {
            return stage == RenderStage.PREVIEW ? previewInFlight : refinedInFlight;
        }

        void incrementInFlight(RenderStage stage) {
            if (stage == RenderStage.PREVIEW) {
                previewInFlight++;
            } else {
                refinedInFlight++;
            }
        }

        void decrementInFlight(RenderStage stage) {
            if (stage == RenderStage.PREVIEW) {
                previewInFlight--;
            } else {
                refinedInFlight--;
            }
        }

        boolean complete(RenderStage stage) {
            return index(stage) >= frame.tiles().size() && inFlight(stage) == 0;
        }

        void activateRefinement() {
            refinementActivated = true;
        }

        boolean refinementActivated() {
            return refinementActivated;
        }
    }

    private static final class WeightedTileCache {
        private final long maximumBytes;
        private final LinkedHashMap<TileKey, TileImages> entries;
        private long bytes;

        private WeightedTileCache(long maximumBytes) {
            this.maximumBytes = maximumBytes;
            this.entries = new LinkedHashMap<>(128, 0.75F, true);
        }

        synchronized TileImages get(TileKey key) {
            return entries.get(key);
        }

        synchronized void put(TileKey key, RenderStage stage, BufferedImage image) {
            TileImages previous = entries.get(key);
            BufferedImage preview = stage == RenderStage.PREVIEW
                    ? image
                    : previous == null ? null : previous.preview();
            BufferedImage refined = stage == RenderStage.REFINED
                    ? image
                    : previous == null ? null : previous.refined();
            long nextBytes = imageBytes(preview) + imageBytes(refined);
            if (previous != null) {
                bytes -= previous.bytes();
            }
            entries.put(key, new TileImages(preview, refined, nextBytes));
            bytes += nextBytes;
            while (bytes > maximumBytes && !entries.isEmpty()) {
                Iterator<Map.Entry<TileKey, TileImages>> iterator = entries.entrySet().iterator();
                Map.Entry<TileKey, TileImages> eldest = iterator.next();
                bytes -= eldest.getValue().bytes();
                iterator.remove();
            }
        }

        synchronized void clear() {
            entries.clear();
            bytes = 0L;
        }

        private static long imageBytes(BufferedImage image) {
            return image == null ? 0L : (long) image.getWidth() * image.getHeight() * Integer.BYTES;
        }
    }
}
