package art.arcane.iris.engine.hydrology;

import java.util.Objects;

public record HydrologyDiagnosticCandidate(
        long id,
        HydrologyCandidateKind kind,
        HydrologyFeatureType projectedType,
        HydrologyPoint point,
        HydrologyCandidateRejection rejection
) {
    public HydrologyDiagnosticCandidate {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(projectedType, "projectedType");
        Objects.requireNonNull(point, "point");
        Objects.requireNonNull(rejection, "rejection");
    }
}
