package art.arcane.iris.engine.hydrology;

import java.util.List;
import java.util.Objects;

final class HydrologyOceanBoundaryRefiner {
    private HydrologyOceanBoundaryRefiner() {
    }

    static Result refine(
            List<HydrologyPoint> crossing,
            HydrologyTerrainSampler terrainSampler,
            HydrologyRoutingTerrainSampler routingSampler
    ) {
        Objects.requireNonNull(crossing, "crossing");
        Objects.requireNonNull(terrainSampler, "terrainSampler");
        Objects.requireNonNull(routingSampler, "routingSampler");
        if (crossing.size() < 2) {
            return null;
        }
        HydrologyPoint previous = crossing.getFirst();
        for (int index = 1; index < crossing.size(); index++) {
            HydrologyPoint point = crossing.get(index);
            HydrologyRoutingTerrainSampler.NaturalClassification classification =
                    routingSampler.classifyNatural(point.x(), point.z());
            if (classification == HydrologyRoutingTerrainSampler.NaturalClassification.UNAVAILABLE) {
                return null;
            }
            if (classification == HydrologyRoutingTerrainSampler.NaturalClassification.OCEAN) {
                HydrologyTerrainSample landwardTerrain = terrainSampler.sample(previous.x(), previous.z());
                if (landwardTerrain == null || landwardTerrain.ocean()) {
                    return null;
                }
                return new Result(previous, point, landwardTerrain);
            }
            previous = point;
        }
        return null;
    }

    record Result(
            HydrologyPoint landwardPoint,
            HydrologyPoint oceanPoint,
            HydrologyTerrainSample landwardTerrain
    ) {
    }
}
