package art.arcane.iris.engine.hydrology.surface;

import art.arcane.iris.engine.hydrology.HydrologyCandidateRejection;

public record ValleyProfile(
        int[] head,
        int[] crossMin,
        int[] crossMax,
        int[] centerNatural,
        int exposedStations,
        HydrologyCandidateRejection rejection
) {
    public boolean accepted() {
        return rejection == null;
    }

    public static ValleyProfile rejected(HydrologyCandidateRejection rejection) {
        return new ValleyProfile(new int[0], new int[0], new int[0], new int[0], 0, rejection);
    }
}
