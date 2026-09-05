package art.arcane.iris.modded.mixin;

import art.arcane.iris.modded.NativeTerrainReceiptHolder;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ChunkAccess.class)
public abstract class ChunkTerrainReceiptMixin implements NativeTerrainReceiptHolder {
    @Unique
    private byte[] iris$naturalTerrain;
    @Unique
    private long iris$structureActivation;

    @Override
    public long iris$getStructureActivation() {
        return iris$structureActivation;
    }

    @Override
    public void iris$setStructureActivation(long activation) {
        if (activation < 0) {
            throw new IllegalArgumentException("Native structure activation cannot be negative");
        }
        iris$structureActivation = activation;
    }

    @Override
    public byte[] iris$getNaturalTerrain() {
        return iris$naturalTerrain == null ? null : iris$naturalTerrain.clone();
    }

    @Override
    public void iris$setNaturalTerrain(byte[] receipt) {
        iris$naturalTerrain = receipt == null ? null : receipt.clone();
    }
}
