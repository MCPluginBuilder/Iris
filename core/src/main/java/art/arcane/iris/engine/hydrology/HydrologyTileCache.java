package art.arcane.iris.engine.hydrology;

import art.arcane.iris.spi.IrisLogging;
import art.arcane.iris.spi.IrisPlatforms;
import art.arcane.iris.util.common.parallel.MultiBurst;
import art.arcane.volmlib.util.cache.CacheKey;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

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
    private final Set<HydrologyTileKey> prefetching;

    public HydrologyTileCache(HydrologyPlanner planner) {
        this(planner, DEFAULT_MAXIMUM_ENTRIES);
    }

    public HydrologyTileCache(HydrologyPlanner planner, int maximumEntries) {
        this(planner, maximumEntries, null);
    }

    /**
     * @param prefetchExecutor runs neighbour tile planning ahead of generation, or null to plan
     *                         tiles only when a chunk needs them
     */
    public HydrologyTileCache(HydrologyPlanner planner, int maximumEntries, Executor prefetchExecutor) {
        this.planner = Objects.requireNonNull(planner, "planner");
        this.prefetchExecutor = prefetchExecutor;
        this.prefetching = ConcurrentHashMap.newKeySet();
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

    public HydrologyTile get(HydrologyTileKey key) {
        Objects.requireNonNull(key, "key");
        return tiles.get(key, this::planOrEmpty);
    }

    /**
     * A tile whose planning throws is published without rivers instead of failing the chunks that
     * asked for it: one bad column must not take the chunk system down. The failure is logged in
     * full so the cause can be traced, and the empty tile is cached like any other so the session
     * stays consistent.
     */
    private HydrologyTile planOrEmpty(HydrologyTileKey key) {
        try {
            return planner.plan(key);
        } catch (RuntimeException failure) {
            IrisLogging.error("Hydrology tile " + key.tileX() + "," + key.tileZ()
                    + " failed to plan and generates without rivers: " + failure.getMessage());
            IrisLogging.reportError(failure);
            return planner.emptyTile(key);
        }
    }

    public Optional<HydrologyColumnSample> columnAt(int blockX, int blockZ) {
        return chunkColumns(blockX, blockZ).columnAt(blockX, blockZ);
    }

    public void prepareChunkColumns(int blockX, int blockZ) {
        chunkColumns(blockX, blockZ);
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
        ChunkColumns columns = composedChunks.get(
                packedChunk,
                ignored -> composeChunkColumns(chunkX, chunkZ)
        );
        localChunkColumns.set(new LocalChunkColumns(epoch, columns));
        return columns;
    }

    private ChunkColumns composeChunkColumns(int chunkX, int chunkZ) {
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
        List<HydrologyTile> relevantTiles = loadTiles(relevantKeys);
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
        if (tiles.getIfPresent(key) != null || !prefetching.add(key)) {
            return;
        }
        try {
            prefetchExecutor.execute(() -> {
                try {
                    get(key);
                } catch (RuntimeException failure) {
                    IrisLogging.reportError(failure);
                } finally {
                    prefetching.remove(key);
                }
            });
        } catch (RuntimeException rejected) {
            prefetching.remove(key);
        }
    }

    private List<HydrologyTile> loadTiles(List<HydrologyTileKey> keys) {
        if (keys.size() < 2
                || !IrisPlatforms.isBound()) {
            ArrayList<HydrologyTile> loaded = new ArrayList<>(keys.size());
            for (HydrologyTileKey key : keys) {
                loaded.add(get(key));
            }
            return List.copyOf(loaded);
        }
        // Tiles already planned are answered in place; only a tile that still needs planning is
        // worth a round trip through the pool, and most chunks find every tile they need cached.
        HydrologyTile[] loaded = new HydrologyTile[keys.size()];
        ArrayList<CompletableFuture<HydrologyTile>> futures = null;
        for (int index = 0; index < loaded.length; index++) {
            HydrologyTileKey key = keys.get(index);
            HydrologyTile present = tiles.getIfPresent(key);
            if (present != null) {
                loaded[index] = present;
                continue;
            }
            if (futures == null) {
                futures = new ArrayList<>(loaded.length);
            }
            int slot = index;
            CompletableFuture<HydrologyTile> future = CompletableFuture.supplyAsync(() -> get(key), MultiBurst.hydrology);
            futures.add(future.thenApply(tile -> {
                loaded[slot] = tile;
                return tile;
            }));
        }
        if (futures != null) {
            for (CompletableFuture<HydrologyTile> future : futures) {
                awaitTile(future);
            }
        }
        return List.of(loaded);
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
        private Optional<HydrologyColumnSample> columnAt(int blockX, int blockZ) {
            int localX = Math.floorMod(blockX, CHUNK_SIZE);
            int localZ = Math.floorMod(blockZ, CHUNK_SIZE);
            return Optional.ofNullable(columns[localZ * CHUNK_SIZE + localX]);
        }
    }

    private record LocalChunkColumns(long epoch, ChunkColumns columns) {
    }
}
