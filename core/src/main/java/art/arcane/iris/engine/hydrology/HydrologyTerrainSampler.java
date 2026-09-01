package art.arcane.iris.engine.hydrology;

@FunctionalInterface
public interface HydrologyTerrainSampler {
    HydrologyTerrainSample sample(int blockX, int blockZ);
}
