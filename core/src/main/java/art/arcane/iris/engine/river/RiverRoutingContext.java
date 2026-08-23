package art.arcane.iris.engine.river;

import java.util.Objects;

public record RiverRoutingContext(RiverEdgeId edgeId, RiverNode from, RiverNode to, RiverPolyline polyline) {
    public RiverRoutingContext {
        Objects.requireNonNull(edgeId);
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        Objects.requireNonNull(polyline);
    }

    public int midpointX() {
        return (int) StrictMath.max(
                Integer.MIN_VALUE,
                StrictMath.min(Integer.MAX_VALUE, StrictMath.round((from.x() + to.x()) * 0.5))
        );
    }

    public int midpointZ() {
        return (int) StrictMath.max(
                Integer.MIN_VALUE,
                StrictMath.min(Integer.MAX_VALUE, StrictMath.round((from.z() + to.z()) * 0.5))
        );
    }
}
