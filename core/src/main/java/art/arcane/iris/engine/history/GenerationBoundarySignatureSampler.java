package art.arcane.iris.engine.history;

import art.arcane.iris.engine.IrisEngine;

import java.io.IOException;

@FunctionalInterface
public interface GenerationBoundarySignatureSampler {
    TerrainBoundarySignature sample(IrisEngine engine, int blockX, int blockZ) throws IOException;
}
