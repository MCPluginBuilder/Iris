package art.arcane.iris.nativegen;

import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.IrisEngine;
import art.arcane.iris.engine.history.GenerationHistoryRuntimeRouter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;

public final class NativeGenerationWriteGuard {
    private NativeGenerationWriteGuard() {
    }

    public static boolean allowsDecoration(Engine engine, WorldGenLevel region, ChunkPos center) {
        int radius = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES).blockStateWriteRadius();
        for (int offsetX = -radius; offsetX <= radius; offsetX++) {
            for (int offsetZ = -radius; offsetZ <= radius; offsetZ++) {
                int chunkX = Math.addExact(center.x(), offsetX);
                int chunkZ = Math.addExact(center.z(), offsetZ);
                ChunkAccess target = region.getChunk(chunkX, chunkZ);
                if (target.getPersistedStatus().isOrAfter(ChunkStatus.FULL)
                        && !engine.getComplex().allowsMantleChunkWrite(chunkX, chunkZ)) {
                    return false;
                }
            }
        }
        return true;
    }

    public static boolean isHistoricalStructure(Engine engine, long activation) {
        if (activation <= 0 || !(engine instanceof IrisEngine irisEngine)) {
            return false;
        }
        GenerationHistoryRuntimeRouter router = irisEngine.getGenerationHistoryRuntimeRouter().orElse(null);
        return router != null && activation < router.history().activeActivation().activationId();
    }

    public static boolean allowsPendingStage(Engine engine, ChunkAccess chunk, ChunkStatus stage) {
        return stage.isOrAfter(ChunkStatus.FEATURES)
                && chunk.getPersistedStatus().isOrAfter(ChunkStatus.NOISE)
                && !chunk.getPersistedStatus().isOrAfter(stage)
                && !engine.getComplex().allowsMantleChunkWrite(chunk.getPos().x(), chunk.getPos().z());
    }
}
