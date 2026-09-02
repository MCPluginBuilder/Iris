package art.arcane.iris.engine.hydrology.surface;

import art.arcane.iris.engine.hydrology.HydraulicSegment;
import art.arcane.iris.engine.hydrology.HydrologyCandidateRejection;
import art.arcane.iris.engine.hydrology.HydrologyPoint;

import java.util.List;
import java.util.Objects;

public record SurfaceCourseResult(
        List<HydraulicSegment> segments,
        int lastHead,
        int lastWidth,
        int lastDepth,
        HydrologyPoint pathEnd,
        HydrologyCandidateRejection rejection,
        int rejectionDetail
) {
    public SurfaceCourseResult {
        segments = List.copyOf(Objects.requireNonNull(segments, "segments"));
    }

    public boolean accepted() {
        return rejection == null;
    }

    public static SurfaceCourseResult rejected(HydrologyCandidateRejection rejection, int detail) {
        return new SurfaceCourseResult(List.of(), 0, 0, 0, null, Objects.requireNonNull(rejection, "rejection"), detail);
    }
}
