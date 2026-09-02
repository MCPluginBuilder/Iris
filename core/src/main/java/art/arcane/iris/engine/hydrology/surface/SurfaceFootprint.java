package art.arcane.iris.engine.hydrology.surface;

import java.util.List;
import java.util.Objects;

public record SurfaceFootprint(List<SurfaceLayerColumn> columns, int uncontainedWetCells) {
    public SurfaceFootprint {
        columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
    }

    public static SurfaceFootprint empty() {
        return new SurfaceFootprint(List.of(), 0);
    }

    public boolean isEmpty() {
        return columns.isEmpty();
    }
}
