package art.arcane.iris.engine.mantle.components;

import art.arcane.iris.engine.IrisComplex;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.hydrology.HydrologyCaveVoxelViewFactory;
import art.arcane.iris.engine.hydrology.HydrologyColumnSample;
import art.arcane.iris.engine.hydrology.RiverFootprint;
import art.arcane.iris.engine.hydrology.cave.CavePosition;
import art.arcane.iris.engine.hydrology.cave.CaveVoxel;
import art.arcane.iris.engine.hydrology.cave.CaveVoxelView;
import art.arcane.iris.engine.mantle.EngineMantle;
import art.arcane.iris.engine.mantle.MantleComponent;
import art.arcane.iris.engine.mantle.MantleWriter;
import art.arcane.iris.engine.object.IrisProceduralBlocks;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.iris.util.project.context.ChunkContext;
import art.arcane.volmlib.util.function.Function2;
import art.arcane.volmlib.util.mantle.flag.ReservedFlag;
import art.arcane.volmlib.util.mantle.runtime.Mantle;
import art.arcane.volmlib.util.mantle.runtime.MantleChunk;
import art.arcane.volmlib.util.matter.Matter;
import art.arcane.volmlib.util.matter.MatterCavern;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.Objects;
import java.util.function.BiConsumer;

public final class MantleHydrologyCaveVoxelView implements CaveVoxelView {
    private static final int CLOSED_COLUMN = Integer.MAX_VALUE;
    private static final int CACHE_MISS = Integer.MIN_VALUE;

    private final Mantle<Matter> mantle;
    private final int worldHeight;
    private final Function2<Integer, Integer, Integer> surfaceHeight;
    private final BiConsumer<Integer, Integer> chunkLoader;
    private final LongOpenHashSet loadedChunks;
    private final Long2IntOpenHashMap openFloorCache;
    private final Long2IntOpenHashMap surfaceHeightCache;

    public MantleHydrologyCaveVoxelView(
            Engine engine,
            IrisComplex complex,
            RiverFootprint footprint
    ) {
        this(
                Objects.requireNonNull(engine).getMantle(),
                Objects.requireNonNull(complex),
                Objects.requireNonNull(footprint)
        );
    }

    public MantleHydrologyCaveVoxelView(
            Engine engine,
            IrisComplex complex,
            HydrologyCaveVoxelViewFactory.PlannedSurface plannedSurface
    ) {
        this(
                Objects.requireNonNull(engine).getMantle(),
                Objects.requireNonNull(complex),
                Objects.requireNonNull(plannedSurface)
        );
    }

    private MantleHydrologyCaveVoxelView(
            EngineMantle engineMantle,
            IrisComplex complex,
            RiverFootprint footprint
    ) {
        this(
                engineMantle.getMantle(),
                engineMantle.getMantle().getWorldHeight(),
                (x, z) -> plannedSurfaceHeight(complex, footprint, x, z),
                (chunkX, chunkZ) -> generateCarvingInput(engineMantle, complex, chunkX, chunkZ)
        );
    }

    private MantleHydrologyCaveVoxelView(
            EngineMantle engineMantle,
            IrisComplex complex,
            HydrologyCaveVoxelViewFactory.PlannedSurface plannedSurface
    ) {
        this(
                engineMantle.getMantle(),
                engineMantle.getMantle().getWorldHeight(),
                (x, z) -> plannedSurface.resolve(
                        x,
                        z,
                        (int) Math.round(complex.getNaturalHeightStream().getDouble(x, z))
                ),
                (chunkX, chunkZ) -> generateCarvingInput(engineMantle, complex, chunkX, chunkZ)
        );
    }

    MantleHydrologyCaveVoxelView(
            Mantle<Matter> mantle,
            int worldHeight,
            Function2<Integer, Integer, Integer> surfaceHeight,
            BiConsumer<Integer, Integer> chunkLoader
    ) {
        this.mantle = Objects.requireNonNull(mantle);
        if (worldHeight < 3) {
            throw new IllegalArgumentException("worldHeight must be at least three");
        }
        this.worldHeight = worldHeight;
        this.surfaceHeight = Objects.requireNonNull(surfaceHeight);
        this.chunkLoader = Objects.requireNonNull(chunkLoader);
        this.loadedChunks = new LongOpenHashSet();
        this.openFloorCache = new Long2IntOpenHashMap();
        this.openFloorCache.defaultReturnValue(CACHE_MISS);
        this.surfaceHeightCache = new Long2IntOpenHashMap();
        this.surfaceHeightCache.defaultReturnValue(CACHE_MISS);
    }

    @Override
    public boolean isInWorld(CavePosition position) {
        return position.y() > 0 && position.y() < worldHeight - 1;
    }

    @Override
    public CaveVoxel voxelAt(CavePosition position) {
        Objects.requireNonNull(position);
        MatterCavern cavern = dataIfPresent(position, MatterCavern.class);
        if (cavern != null) {
            if (cavern.isLava()) {
                return CaveVoxel.LAVA;
            }
            if (cavern.getLiquid() == 1) {
                return CaveVoxel.COMPATIBLE_FLUID;
            }
            return CaveVoxel.CAVE_AIR;
        }
        PlatformBlockState block = dataIfPresent(position, PlatformBlockState.class);
        if (block != null) {
            if (!block.isFluid()) {
                return CaveVoxel.SOLID;
            }
            return IrisProceduralBlocks.materialKey(block).endsWith(":lava")
                    ? CaveVoxel.LAVA
                    : CaveVoxel.INCOMPATIBLE_FLUID;
        }
        return position.y() > surfaceY(position.x(), position.z())
                ? CaveVoxel.CAVE_AIR
                : CaveVoxel.SOLID;
    }

    @Override
    public boolean isOpenToSurface(CavePosition position) {
        if (!isInWorld(position) || voxelAt(position) == CaveVoxel.SOLID) {
            return false;
        }
        if (isAboveTerrainSurface(position)) {
            return true;
        }
        long key = RiverFootprint.pack(position.x(), position.z());
        int openFloor = openFloorCache.get(key);
        if (openFloor == CACHE_MISS) {
            openFloor = resolveOpenFloor(position.x(), position.z());
            openFloorCache.put(key, openFloor);
        }
        return openFloor != CLOSED_COLUMN && position.y() >= openFloor;
    }

    @Override
    public boolean isAboveTerrainSurface(CavePosition position) {
        return position.y() > surfaceY(position.x(), position.z());
    }

    @Override
    public boolean hasAboveTerrainSurface(int x, int z, int minimumY, int maximumY) {
        int firstY = Math.max(minimumY, 1);
        int lastY = Math.min(maximumY, worldHeight - 2);
        return firstY <= lastY && lastY > surfaceY(x, z);
    }

    private int resolveOpenFloor(int x, int z) {
        int top = surfaceY(x, z);
        if (voxelAt(new CavePosition(x, top, z)) == CaveVoxel.SOLID) {
            return CLOSED_COLUMN;
        }
        int y = top;
        while (y > 0 && voxelAt(new CavePosition(x, y - 1, z)) != CaveVoxel.SOLID) {
            y--;
        }
        return y;
    }

    private int surfaceY(int x, int z) {
        long key = RiverFootprint.pack(x, z);
        int cached = surfaceHeightCache.get(key);
        if (cached != CACHE_MISS) {
            return cached;
        }
        int resolved = Math.max(1, Math.min(worldHeight - 2, surfaceHeight.apply(x, z)));
        surfaceHeightCache.put(key, resolved);
        return resolved;
    }

    private <T> T dataIfPresent(CavePosition position, Class<T> type) {
        int chunkX = position.x() >> 4;
        int chunkZ = position.z() >> 4;
        long chunkKey = Mantle.key(chunkX, chunkZ);
        if (loadedChunks.add(chunkKey)) {
            chunkLoader.accept(chunkX, chunkZ);
        }
        return mantle.get(position.x(), position.y(), position.z(), type);
    }

    private static int plannedSurfaceHeight(
            IrisComplex complex,
            RiverFootprint footprint,
            int x,
            int z
    ) {
        HydrologyColumnSample sample = footprint.sample(x, z).orElse(null);
        return sample == null
                ? (int) Math.round(complex.getNaturalHeightStream().getDouble(x, z))
                : sample.terrainHeight();
    }

    static void generateCarvingInput(
            EngineMantle engineMantle,
            IrisComplex complex,
            int chunkX,
            int chunkZ
    ) {
        MantleComponent carving = engineMantle.getRegisteredComponents().get(ReservedFlag.CARVED);
        if (carving == null || !carving.isEnabled()) {
            throw new IllegalStateException("Hydrology containment requires the carving component");
        }
        if (!requiresCarvingInput(engineMantle.getMantle(), chunkX, chunkZ)) {
            return;
        }
        ChunkContext context = new ChunkContext(
                chunkX << 4,
                chunkZ << 4,
                complex,
                false,
                ChunkContext.PrefillPlan.NONE,
                null
        );
        try (MantleWriter writer = new MantleWriter(
                engineMantle,
                engineMantle.getMantle(),
                chunkX,
                chunkZ,
                0,
                false
        )) {
            MantleChunk<Matter> chunk = writer.acquireChunk(chunkX, chunkZ);
            if (chunk == null) {
                throw new IllegalStateException("Hydrology containment could not acquire carving input at "
                        + chunkX + "," + chunkZ);
            }
            chunk.raiseFlagSuspend(
                    ReservedFlag.CARVED,
                    () -> carving.generateLayer(writer, chunkX, chunkZ, context)
            );
        }
    }

    static boolean requiresCarvingInput(Mantle<Matter> mantle, int chunkX, int chunkZ) {
        return !mantle.hasFlag(chunkX, chunkZ, ReservedFlag.CARVED);
    }
}
