package art.arcane.iris.engine.hydrology.surface;

import art.arcane.iris.engine.hydrology.RiverFootprint;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.Objects;

public final class ErosionField {
    private final Long2ObjectOpenHashMap<SurfaceColumn> columns;
    private final int uncontainedWetCells;

    ErosionField(Long2ObjectOpenHashMap<SurfaceColumn> columns, int uncontainedWetCells) {
        this.columns = Objects.requireNonNull(columns, "columns");
        this.uncontainedWetCells = uncontainedWetCells;
    }

    public Long2ObjectOpenHashMap<SurfaceColumn> columns() {
        return columns;
    }

    public SurfaceColumn column(int x, int z) {
        return columns.get(RiverFootprint.pack(x, z));
    }

    public int uncontainedWetCells() {
        return uncontainedWetCells;
    }

    public int size() {
        return columns.size();
    }
}
