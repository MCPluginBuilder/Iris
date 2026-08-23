package art.arcane.iris.engine.river.runtime;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.object.IrisBiome;
import art.arcane.iris.engine.object.IrisRegion;
import art.arcane.iris.engine.object.IrisRiverNetwork;
import art.arcane.iris.util.project.stream.ProceduralStream;

import java.util.Objects;

public record IrisRiverRuntimeContext(
        long seed,
        IrisRiverNetwork configuration,
        IrisData data,
        int fluidHeight,
        boolean caveHydrologyActive,
        ProceduralStream<Double> naturalHeight,
        ProceduralStream<Double> naturalSlope,
        ProceduralStream<IrisBiome> naturalBiome,
        ProceduralStream<IrisRegion> region
) {
    public IrisRiverRuntimeContext {
        Objects.requireNonNull(configuration);
        Objects.requireNonNull(data);
        Objects.requireNonNull(naturalHeight);
        Objects.requireNonNull(naturalSlope);
        Objects.requireNonNull(naturalBiome);
        Objects.requireNonNull(region);
    }
}
