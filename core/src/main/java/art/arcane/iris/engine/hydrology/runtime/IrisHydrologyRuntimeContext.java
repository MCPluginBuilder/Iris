package art.arcane.iris.engine.hydrology.runtime;

import art.arcane.iris.core.loader.IrisData;
import art.arcane.iris.engine.hydrology.HydrologyCaveVoxelViewFactory;
import art.arcane.iris.engine.object.IrisDimension;

import java.util.Objects;

public record IrisHydrologyRuntimeContext(
        long seed,
        int worldHeight,
        IrisDimension dimension,
        IrisData data,
        IrisHydrologyNaturalSampleProvider naturalSampleProvider,
        IrisHydrologyNaturalHeightProvider naturalHeightProvider,
        IrisHydrologyNaturalOceanClassifier naturalOceanClassifier,
        HydrologyCaveVoxelViewFactory caveViewFactory
) {
    public IrisHydrologyRuntimeContext {
        if (worldHeight < 3) {
            throw new IllegalArgumentException("worldHeight must be at least three");
        }
        Objects.requireNonNull(dimension);
        Objects.requireNonNull(data);
        Objects.requireNonNull(naturalSampleProvider);
        Objects.requireNonNull(naturalHeightProvider);
        Objects.requireNonNull(naturalOceanClassifier);
        Objects.requireNonNull(caveViewFactory);
    }
}
