package art.arcane.iris.core.runtime;

import art.arcane.iris.core.nms.INMS;
import art.arcane.iris.engine.framework.Engine;
import art.arcane.iris.engine.history.SavedTerrainChunk;
import art.arcane.iris.platform.bukkit.BukkitWorldBinding;
import org.bukkit.World;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

final class BukkitSavedTerrainCapture {
    private BukkitSavedTerrainCapture() {
    }

    static CompletableFuture<SavedTerrainChunk> capture(Engine engine, int chunkX, int chunkZ) {
        World world = BukkitWorldBinding.world(engine.getWorld());
        if (world == null) {
            return CompletableFuture.failedFuture(new IOException("The world is unavailable for terrain capture."));
        }
        return INMS.get().captureSavedTerrainChunk(world, chunkX, chunkZ, engine.getMinHeight(), engine.getHeight());
    }
}
