package art.arcane.iris.engine.hydrology;

public interface HydrologyNaturalTerrainSampler extends HydrologyRoutingTerrainSampler {
    HydrologyTerrainSample sampleBasis(int blockX, int blockZ);

    default HydrologyTerrainSample sampleBasisWithoutSlope(int blockX, int blockZ) {
        return sampleBasis(blockX, blockZ);
    }
}
