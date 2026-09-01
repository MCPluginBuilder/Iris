package art.arcane.iris.engine.hydrology.runtime;

import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisRegion;

public record IrisHydrologyNaturalSample(
        double naturalHeight,
        boolean ocean,
        IrisBiome biome,
        IrisRegion region
) {
}
