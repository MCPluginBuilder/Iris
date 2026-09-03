package art.arcane.iris.engine.hydrology;

import art.arcane.iris.spi.IrisLogging;
import art.arcane.volmlib.util.cache.CacheKey;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

public final class HydrologyTileCache implements AutoCloseable {
    private static final int DEFAULT_MAXIMUM_ENTRIES = 64;
    private static final int MAXIMUM_COMPOSED_CHUNKS = 256;
    private static final int CHUNK_SIZE = 16;
    private static final int CHUNK_COLUMN_COUNT = CHUNK_SIZE * CHUNK_SIZE;

    private final HydrologyPlanner planner;
    private final int maximumEntries;
    private final Cache<HydrologyTileKey, HydrologyTile> tiles;
    private final Cache<Long, ChunkColumns> composedChunks;
    private final AtomicLong cacheEpoch;
    private final ThreadLocal<LocalChunkColumns> localChunkColumns;
    private final Executor prefetchExecutor;
    private final ConcurrentHashMap<HydrologyTileKey, CompletableFuture<HydrologyTile>> planning;
    private final BooleanSupplier waitingForbidden;

    public HydrologyTileCache(HydrologyPlanner planner) {
        this(planner, DEFAULT_MAXIMUM_ENTRIES);
    }

    public HydrologyTileCache(HydrologyPlanner planner, int maximumEntries) {
        this(planner, maximumEntries, null);
    }

    public HydrologyTileCache(HydrologyPlanner planner, int maximumEntries, Executor prefetchExecutor) {
        this(planner, maximumEntries, prefetchExecutor, null);
    }

    /**
     * @param prefetchExecutor runs neighbour tile planning ahead of generation, or null to plan
     *                         tiles only when a chunk needs them
     * @param waitingForbidden true on a thread that must never wait for a cold plan, so a sample
     *                         from it is answered as "no hydrology here" while the tiles it needs
     *                         are handed to the prefetch executor; null lets every caller wait
     */
    public HydrologyTileCache(
            HydrologyPlanner planner,
            int maximumEntries,
            Executor prefetchExecutor,
            BooleanSupplier waitingForbidden
    ) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.prefetchExecutor = prefetchExecutor;
        this.waitingForbidden = waitingForbidden;
        this.planning = new ConcurrentHashMap<>();
        if (maximumEntries < 1) {
            throw new IllegalArgumentException("maximumEntries must be positive.");
        }
        this.maximumEntries = maximumEntries;
        this.tiles = Caffeine.newBuilder()
                .maximumSize(maximumEntries)
                .build();
        this.composedChunks = Caffeine.newBuilder()
                .maximumSize(MAXIMUM_COMPOSED_CHUNKS)
                .build();
        this.cacheEpoch = new AtomicLong();
        this.localChunkColumns = new ThreadLocal<>();
    }

    /**
     * The tile, planned on the prefetch executor when there is one so that a caller that gets
     * interrupted or cancelled (a map render, a command) never aborts a plan every other caller is
     * waiting for; without an executor the tile is planned on the caller.
     */
    public HydrologyTile get(HydrologyTileKey key) {
        Objects.requireNonNull(key, "key");
        if (prefetchExecutor == null || plansInline()) {
            return planInline(key);
        }
        return awaitTile(planAsync(key));
    }

    /**
     * A pool worker plans on its own thread. Parking the workers of one pool on tasks of another is how
     * two saturated pools deadlock each other (generation workers waiting for planning that waits for
     * generation workers), and a ForkJoin worker keeps the planner's nested fan-out alive by helping
     * with its own forks instead of waiting for them.
     */
    private static boolean plansInline() {
        return Thread.currentThread() instanceof ForkJoinWorkerThread;
    }

    private HydrologyTile planInline(HydrologyTileKey key) {
        if (tiles.getIfPresent(key) == null && planning.containsKey(key)) {
            IrisLogging.debug("Hydrology tile %d,%d awaited by %s while the planning pool plans it",
                    key.tileX(), key.tileZ(), Thread.currentThread().getName());
        }
        return tiles.get(key, this::planOrEmpty);
    }

    /**
     * A tile whose planning throws is published without rivers instead of failing the chunks that
     * asked for it: one bad column must not take the chunk system down. The failure is logged in
     * full so the cause can be traced, and the empty tile is cached like any other so the session
     * stays consistent. A plan that was interrupted is not a bad tile: it is rethrown without being
     * cached so the next request plans it again.
     */
    private HydrologyTile planOrEmpty(HydrologyTileKey key) {
        try {
            return planner.plan(key);
        } catch (RuntimeException failure) {
            if (interrupted(failure)) {
                throw failure;
            }
            IrisLogging.error("Hydrology tile " + key.tileX() + "," + key.tileZ()
                    + " failed to plan and generates without rivers: " + failure.getMessage());
            IrisLogging.reportError(failure);
            return planner.emptyTile(key);
        }
    }

    private static boolean interrupted(Throwable failure) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException || cause instanceof java.io.InterruptedIOException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    public Optional<HydrologyColumnSample> columnAt(int blockX, int blockZ) {
        return chunkColumns(blockX, blockZ).columnAt(blockX, blockZ);
    }

    public void prepareChunkColumns(int blockX, int blockZ) {
        chunkColumns(blockX, blockZ);
    }

    /**
     * Whether every tile the column's chunk composes from is already planned. A caller that must not
     * wait (the server thread answering a height or biome query) uses this before sampling: when the
     * answer is false the missing tiles are handed to the prefetch executor so a later query finds
     * them, and nothing is planned on the caller.
     */
    public boolean isPlanned(int blockX, int blockZ) {
        int chunkX = Math.floorDiv(blockX, CHUNK_SIZE);
        int chunkZ = Math.floorDiv(blockZ, CHUNK_SIZE);
        if (composedChunks.getIfPresent(CacheKey.mix(RiverFootprint.pack(chunkX, chunkZ))) != null) {
            return true;
        }
        return !requestUnplanned(chunkX, chunkZ);
    }

    /**
     * Hands every tile the chunk needs and does not have to the prefetch executor and says whether
     * any was missing. Nothing is planned on the caller, so this is safe from a thread that must
     * never wait for a cold plan.
     */
    private boolean requestUnplanned(int chunkX, int chunkZ) {
        boolean missing = false;
        for (HydrologyTileKey key : relevantKeys(chunkX, chunkZ)) {
            if (tiles.getIfPresent(key) != null) {
                continue;
            }
            missing = true;
            if (prefetchExecutor != null) {
                prefetch(key);
            }
        }
        return missing;
    }

    /** Whether the calling thread must never wait for a cold hydrology plan. */
    private boolean waitsForbidden() {
        return waitingForbidden != null && waitingForbidden.getAsBoolean();
    }

    public HydrologyRenderSample renderAt(int blockX, int blockZ) {
        return columnAt(blockX, blockZ)
                .map(HydrologyColumnSample::renderSample)
                .orElseGet(() -> new HydrologyRenderSample(blockX, blockZ, List.of()));
    }

    public void invalidate(HydrologyTileKey key) {
        Objects.requireNonNull(key, "key");
        clear();
    }

    public void clear() {
        cacheEpoch.incrementAndGet();
        planner.clearOwnerDrafts();
        tiles.invalidateAll();
        composedChunks.invalidateAll();
        localChunkColumns.remove();
    }

    public int size() {
        tiles.cleanUp();
        return Math.toIntExact(tiles.estimatedSize());
    }

    public int maximumEntries() {
        return maximumEntries;
    }

    @Override
    public void close() {
        clear();
    }

    private ChunkColumns chunkColumns(int blockX, int blockZ) {
        int chunkX = Math.floorDiv(blockX, CHUNK_SIZE);
        int chunkZ = Math.floorDiv(blockZ, CHUNK_SIZE);
        long epoch = cacheEpoch.get();
        LocalChunkColumns local = localChunkColumns.get();
        if (local != null && local.epoch() == epoch
                && local.columns().chunkX() == chunkX
                && local.columns().chunkZ() == chunkZ) {
            return local.columns();
        }
        long packedChunk = CacheKey.mix(RiverFootprint.pack(chunkX, chunkZ));
        if (composedChunks.getIfPresent(packedChunk) == null
                && waitsForbidden()
                && requestUnplanned(chunkX, chunkZ)) {
            // A thread that must never wait (the server thread answering a query) gets a riverless
            // answer while the tiles it would have waited for are planned on the prefetch executor.
            // The empty result is never cached, so the next query composes the real columns.
            return ChunkColumns.empty(chunkX, chunkZ);
        }
        ChunkColumns columns = composedChunks.get(
                packedChunk,
                ignored -> composeChunkColumns(chunkX, chunkZ)
        );
        localChunkColumns.set(new LocalChunkColumns(epoch, columns));
        return columns;
    }

    /** The tiles a chunk's columns compose from: every tile within the publication radius of the chunk. */
    private ArrayList<HydrologyTileKey> relevantKeys(int chunkX, int chunkZ) {
        int minimumBlockX = Math.multiplyExact(chunkX, CHUNK_SIZE);
        int minimumBlockZ = Math.multiplyExact(chunkZ, CHUNK_SIZE);
        int tileSize = planner.settings().routing().tileSize();
        int publicationRadius = planner.settings().publicationRadius();
        int minimumTileX = tileCoordinate((long) minimumBlockX - publicationRadius, tileSize);
        int maximumTileX = tileCoordinate((long) minimumBlockX + CHUNK_SIZE - 1L + publicationRadius, tileSize);
        int minimumTileZ = tileCoordinate((long) minimumBlockZ - publicationRadius, tileSize);
        int maximumTileZ = tileCoordinate((long) minimumBlockZ + CHUNK_SIZE - 1L + publicationRadius, tileSize);
        ArrayList<HydrologyTileKey> relevantKeys = new ArrayList<>(
                Math.multiplyExact(maximumTileX - minimumTileX + 1, maximumTileZ - minimumTileZ + 1)
        );
        for (int tileZ = minimumTileZ; tileZ <= maximumTileZ; tileZ++) {
            for (int tileX = minimumTileX; tileX <= maximumTileX; tileX++) {
                relevantKeys.add(new HydrologyTileKey(tileX, tileZ));
            }
        }
        return relevantKeys;
    }

    private ChunkColumns composeChunkColumns(int chunkX, int chunkZ) {
        int minimumBlockX = Math.multiplyExact(chunkX, CHUNK_SIZE);
        int minimumBlockZ = Math.multiplyExact(chunkZ, CHUNK_SIZE);
        ArrayList<HydrologyTileKey> relevantKeys = relevantKeys(chunkX, chunkZ);
        int minimumTileX = relevantKeys.getFirst().tileX();
        int maximumTileX = relevantKeys.getLast().tileX();
        int minimumTileZ = relevantKeys.getFirst().tileZ();
        int maximumTileZ = relevantKeys.getLast().tileZ();
        List<HydrologyTile> relevantTiles = tiles(relevantKeys);
        prefetchNeighbours(minimumTileX, maximumTileX, minimumTileZ, maximumTileZ);
        HydrologyColumnSample[] columns = new HydrologyColumnSample[CHUNK_COLUMN_COUNT];
        for (int localZ = 0; localZ < CHUNK_SIZE; localZ++) {
            for (int localX = 0; localX < CHUNK_SIZE; localX++) {
                int blockX = minimumBlockX + localX;
                int blockZ = minimumBlockZ + localZ;
                columns[localZ * CHUNK_SIZE + localX] = composeColumn(
                        blockX,
                        blockZ,
                        relevantKeys,
                        relevantTiles
                )
                        .orElse(null);
            }
        }
        return new ChunkColumns(chunkX, chunkZ, columns);
    }

    /**
     * Plans the ring of tiles around the ones a chunk just needed, on the prefetch executor, so a
     * generation front walking outward finds its next tiles already planned instead of stalling on
     * a cold plan. Tiles already cached or already being prefetched are skipped; a tile a chunk
     * needs before its prefetch finishes simply joins that computation.
     */
    private void prefetchNeighbours(int minimumTileX, int maximumTileX, int minimumTileZ, int maximumTileZ) {
        if (prefetchExecutor == null) {
            return;
        }
        for (int tileZ = minimumTileZ - 1; tileZ <= maximumTileZ + 1; tileZ++) {
            for (int tileX = minimumTileX - 1; tileX <= maximumTileX + 1; tileX++) {
                if (tileX >= minimumTileX && tileX <= maximumTileX && tileZ >= minimumTileZ && tileZ <= maximumTileZ) {
                    continue;
                }
                prefetch(new HydrologyTileKey(tileX, tileZ));
            }
        }
    }

    /**
     * Plans every tile that touches the block area, nearest to the centre first, on the prefetch
     * executor. A pregeneration calls this once up front so its spiral never waits on a cold plan
     * once the first tiles are in; nothing happens without a prefetch executor.
     */
    public void prefetchArea(int minimumBlockX, int minimumBlockZ, int maximumBlockX, int maximumBlockZ, int centreBlockX, int centreBlockZ) {
        if (prefetchExecutor == null) {
            return;
        }
        int tileSize = planner.settings().routing().tileSize();
        int publicationRadius = planner.settings().publicationRadius();
        int minimumTileX = tileCoordinate((long) minimumBlockX - publicationRadius, tileSize);
        int maximumTileX = tileCoordinate((long) maximumBlockX + publicationRadius, tileSize);
        int minimumTileZ = tileCoordinate((long) minimumBlockZ - publicationRadius, tileSize);
        int maximumTileZ = tileCoordinate((long) maximumBlockZ + publicationRadius, tileSize);
        int centreTileX = tileCoordinate(centreBlockX, tileSize);
        int centreTileZ = tileCoordinate(centreBlockZ, tileSize);
        ArrayList<HydrologyTileKey> keys = new ArrayList<>();
        for (int tileZ = minimumTileZ; tileZ <= maximumTileZ; tileZ++) {
            for (int tileX = minimumTileX; tileX <= maximumTileX; tileX++) {
                keys.add(new HydrologyTileKey(tileX, tileZ));
            }
        }
        keys.sort(java.util.Comparator.comparingInt((HydrologyTileKey key) -> Math.max(Math.abs(key.tileX() - centreTileX), Math.abs(key.tileZ() - centreTileZ)))
                .thenComparingInt(HydrologyTileKey::tileZ)
                .thenComparingInt(HydrologyTileKey::tileX));
        for (HydrologyTileKey key : keys) {
            prefetch(key);
        }
    }

    private void prefetch(HydrologyTileKey key) {
        planAsync(key);
    }

    /**
     * Plans every tile that is not cached yet on the prefetch executor and returns all of them in key
     * order. At most half the cache bound is in flight at once so a wide request cannot evict tiles
     * before the caller has read them; a tile another caller is already planning is joined rather than
     * planned twice. Without a prefetch executor the tiles are planned inline on the caller.
     */
    public List<HydrologyTile> tiles(List<HydrologyTileKey> keys) {
        Objects.requireNonNull(keys, "keys");
        HydrologyTile[] loaded = new HydrologyTile[keys.size()];
        if (prefetchExecutor == null || plansInline()) {
            for (int index = 0; index < loaded.length; index++) {
                loaded[index] = planInline(keys.get(index));
            }
            return List.of(loaded);
        }
        int batch = Math.max(1, maximumEntries / 2);
        ArrayList<CompletableFuture<HydrologyTile>> futures = new ArrayList<>(Math.min(batch, loaded.length));
        for (int start = 0; start < loaded.length; start += batch) {
            int end = Math.min(loaded.length, start + batch);
            futures.clear();
            for (int index = start; index < end; index++) {
                futures.add(planAsync(keys.get(index)));
            }
            for (int index = start; index < end; index++) {
                loaded[index] = awaitTile(futures.get(index - start));
            }
        }
        return List.of(loaded);
    }

    /**
     * The tile, planned on the prefetch executor unless it is cached or already being planned, in which
     * case the caller shares that plan instead of occupying a second executor thread with it.
     */
    private CompletableFuture<HydrologyTile> planAsync(HydrologyTileKey key) {
        HydrologyTile present = tiles.getIfPresent(key);
        if (present != null) {
            return CompletableFuture.completedFuture(present);
        }
        CompletableFuture<HydrologyTile> future = new CompletableFuture<>();
        CompletableFuture<HydrologyTile> existing = planning.putIfAbsent(key, future);
        if (existing != null) {
            return existing;
        }
        try {
            prefetchExecutor.execute(() -> {
                try {
                    future.complete(planInline(key));
                } catch (Throwable failure) {
                    future.completeExceptionally(failure);
                    IrisLogging.reportError(failure);
                } finally {
                    planning.remove(key, future);
                }
            });
        } catch (RuntimeException rejected) {
            planning.remove(key, future);
            future.completeExceptionally(rejected);
        }
        return future;
    }

    private static HydrologyTile awaitTile(CompletableFuture<HydrologyTile> future) {
        try {
            return future.join();
        } catch (CompletionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Hydrology tile planning failed.", cause);
        }
    }

    private Optional<HydrologyColumnSample> composeColumn(
            int blockX,
            int blockZ,
            List<HydrologyTileKey> relevantKeys,
            List<HydrologyTile> relevantTiles
    ) {
        ArrayList<HydrologyColumnLayer> layers = new ArrayList<>();
        HydrologyColumnSample template = null;
        int tileSize = planner.settings().routing().tileSize();
        int publicationRadius = planner.settings().publicationRadius();
        int minimumTileX = tileCoordinate((long) blockX - publicationRadius, tileSize);
        int maximumTileX = tileCoordinate((long) blockX + publicationRadius, tileSize);
        int minimumTileZ = tileCoordinate((long) blockZ - publicationRadius, tileSize);
        int maximumTileZ = tileCoordinate((long) blockZ + publicationRadius, tileSize);
        for (int tileIndex = 0; tileIndex < relevantTiles.size(); tileIndex++) {
            HydrologyTileKey key = relevantKeys.get(tileIndex);
            if (key.tileX() < minimumTileX || key.tileX() > maximumTileX
                    || key.tileZ() < minimumTileZ || key.tileZ() > maximumTileZ) {
                continue;
            }
            HydrologyTile tile = relevantTiles.get(tileIndex);
            Optional<HydrologyColumnSample> candidate = tile.columnAt(blockX, blockZ);
            if (candidate.isEmpty()) {
                continue;
            }
            HydrologyColumnSample sample = candidate.get();
            if (template == null) {
                template = sample;
            } else {
                requireMatchingTerrain(template, sample);
            }
            layers.addAll(sample.layers());
        }
        if (template == null) {
            return Optional.empty();
        }
        LinkedHashMap<Long, HydrologyColumnLayer> unique = new LinkedHashMap<>();
        for (HydrologyColumnLayer layer : layers) {
            HydrologyColumnLayer existing = unique.putIfAbsent(layer.feature().id(), layer);
            if (existing != null && !existing.equals(layer)) {
                throw new IllegalStateException("Hydrology feature id collision at " + blockX + "," + blockZ + ".");
            }
        }
        return Optional.of(new HydrologyColumnSample(
                blockX,
                blockZ,
                template.naturalHeight(),
                template.seaLevel(),
                template.ocean(),
                template.parentBiomeKey(),
                new ArrayList<>(unique.values())
        ));
    }

    private static int tileCoordinate(long blockCoordinate, int tileSize) {
        return Math.toIntExact(Math.floorDiv(blockCoordinate, tileSize));
    }

    private static void requireMatchingTerrain(
            HydrologyColumnSample expected,
            HydrologyColumnSample actual
    ) {
        if (expected.naturalHeight() != actual.naturalHeight()
                || expected.seaLevel() != actual.seaLevel()
                || expected.ocean() != actual.ocean()
                || !expected.parentBiomeKey().equals(actual.parentBiomeKey())) {
            throw new IllegalStateException(
                    "Hydrology plans disagree on terrain metadata at " + expected.x() + "," + expected.z() + "."
            );
        }
    }

    private record ChunkColumns(int chunkX, int chunkZ, HydrologyColumnSample[] columns) {
        /** A chunk with no hydrology at all, the answer for a caller that may not wait for its plan. */
        private static ChunkColumns empty(int chunkX, int chunkZ) {
            return new ChunkColumns(chunkX, chunkZ, new HydrologyColumnSample[CHUNK_COLUMN_COUNT]);
        }

        private Optional<HydrologyColumnSample> columnAt(int blockX, int blockZ) {
            int localX = Math.floorMod(blockX, CHUNK_SIZE);
            int localZ = Math.floorMod(blockZ, CHUNK_SIZE);
            return Optional.ofNullable(columns[localZ * CHUNK_SIZE + localX]);
        }
    }

    private record LocalChunkColumns(long epoch, ChunkColumns columns) {
    }
}
