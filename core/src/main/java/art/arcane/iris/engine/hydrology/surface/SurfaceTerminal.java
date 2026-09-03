package art.arcane.iris.engine.hydrology.surface;

public enum SurfaceTerminal {
    OCEAN_MOUTH,
    SINKHOLE,
    COASTAL_GROTTO,
    /** The course ends on another surface course; the planner levels its tail to the stem's water at the junction. */
    TRIBUTARY
}
