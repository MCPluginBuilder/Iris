package art.arcane.iris.engine.platform.studio.generators;

import art.arcane.iris.engine.data.chunk.TerrainChunk;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.framework.WrongEngineBroException;
import art.arcane.iris.engine.platform.studio.StudioGenerator;
import art.arcane.iris.spi.PlatformBlockState;
import art.arcane.iris.util.common.data.B;

import java.util.Objects;

public final class StudioEntryChunkGenerator implements StudioGenerator {
    public static final int ENTRY_CHUNK_X = 0;
    public static final int ENTRY_CHUNK_Z = 0;
    public static final int BOOTSTRAP_RADIUS = 2;
    public static final int PREFERRED_PLATFORM_Y = 95;
    public static final int PREFERRED_ENTRY_Y = 96;
    public static final double ENTRY_X = 8.5D;
    public static final double ENTRY_Z = 8.5D;
    public static final String PLATFORM_BLOCK = "SMOOTH_STONE";
    public static final String PERIMETER_BLOCK = "POLISHED_DEEPSLATE";
    public static final String LIGHT_BLOCK = "SEA_LANTERN";

    private final PlatformBlockState platformBlock;
    private final PlatformBlockState perimeterBlock;
    private final PlatformBlockState lightBlock;

    public StudioEntryChunkGenerator() {
        this(
                B.getState(PLATFORM_BLOCK),
                B.getState(PERIMETER_BLOCK),
                B.getState(LIGHT_BLOCK));
    }

    StudioEntryChunkGenerator(
            PlatformBlockState platformBlock,
            PlatformBlockState perimeterBlock,
            PlatformBlockState lightBlock
    ) {
        this.platformBlock = Objects.requireNonNull(platformBlock, "Studio entry platform block");
        this.perimeterBlock = Objects.requireNonNull(perimeterBlock, "Studio entry perimeter block");
        this.lightBlock = Objects.requireNonNull(lightBlock, "Studio entry light block");
    }

    @Override
    public void generateChunk(Engine engine, TerrainChunk chunk, int x, int z) throws WrongEngineBroException {
        Objects.requireNonNull(chunk, "Studio entry chunk");
        if (!isBootstrapChunk(x, z)) {
            throw new IllegalArgumentException("Studio entry generator only supports its bootstrap lobby.");
        }
        int platformY = resolvePlatformY(chunk.getMinHeight(), chunk.getMaxHeight());
        int entryY = platformY + 1;
        chunk.setRegion(0, platformY, 0, 16, entryY, 16, platformBlock);
        if (z == ENTRY_CHUNK_Z - BOOTSTRAP_RADIUS) {
            chunk.setRegion(0, platformY, 0, 16, entryY, 1, perimeterBlock);
        }
        if (z == ENTRY_CHUNK_Z + BOOTSTRAP_RADIUS) {
            chunk.setRegion(0, platformY, 15, 16, entryY, 16, perimeterBlock);
        }
        if (x == ENTRY_CHUNK_X - BOOTSTRAP_RADIUS) {
            chunk.setRegion(0, platformY, 1, 1, entryY, 15, perimeterBlock);
        }
        if (x == ENTRY_CHUNK_X + BOOTSTRAP_RADIUS) {
            chunk.setRegion(15, platformY, 1, 16, entryY, 15, perimeterBlock);
        }
        chunk.setBlock(3, platformY, 3, lightBlock);
        chunk.setBlock(12, platformY, 3, lightBlock);
        chunk.setBlock(3, platformY, 12, lightBlock);
        chunk.setBlock(12, platformY, 12, lightBlock);
    }

    public static boolean isEntryChunk(int x, int z) {
        return x == ENTRY_CHUNK_X && z == ENTRY_CHUNK_Z;
    }

    public static boolean isBootstrapChunk(int x, int z) {
        return Math.abs(x - ENTRY_CHUNK_X) <= BOOTSTRAP_RADIUS
                && Math.abs(z - ENTRY_CHUNK_Z) <= BOOTSTRAP_RADIUS;
    }

    public static int resolveEntryY(int minHeight, int maxHeight) {
        int minEntryY = minHeight + 1;
        int maxEntryY = maxHeight - 2;
        return Math.max(minEntryY, Math.min(maxEntryY, PREFERRED_ENTRY_Y));
    }

    public static int resolvePlatformY(int minHeight, int maxHeight) {
        return resolveEntryY(minHeight, maxHeight) - 1;
    }
}
