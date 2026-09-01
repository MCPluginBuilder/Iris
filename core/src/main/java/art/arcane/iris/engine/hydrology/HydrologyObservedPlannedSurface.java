package art.arcane.iris.engine.hydrology;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class HydrologyObservedPlannedSurface implements HydrologyCaveVoxelViewFactory.PlannedSurface {
    private final HydrologyCaveVoxelViewFactory.PlannedSurface delegate;
    private final Long2ObjectOpenHashMap<ArrayList<Observation>> observations;

    public HydrologyObservedPlannedSurface(HydrologyCaveVoxelViewFactory.PlannedSurface delegate) {
        this.delegate = Objects.requireNonNull(delegate);
        this.observations = new Long2ObjectOpenHashMap<>();
    }

    @Override
    public int resolve(int x, int z, int naturalHeight) {
        int resolvedHeight = delegate.resolve(x, z, naturalHeight);
        long key = RiverFootprint.pack(x, z);
        ArrayList<Observation> column = observations.get(key);
        if (column == null) {
            column = new ArrayList<>(1);
            observations.put(key, column);
        }
        for (Observation observation : column) {
            if (observation.naturalHeight() == naturalHeight) {
                return resolvedHeight;
            }
        }
        column.add(new Observation(x, z, naturalHeight, resolvedHeight));
        return resolvedHeight;
    }

    public List<Observation> observationsAt(int x, int z) {
        ArrayList<Observation> column = observations.get(RiverFootprint.pack(x, z));
        return column == null ? List.of() : List.copyOf(column);
    }

    public record Observation(
            int x,
            int z,
            int naturalHeight,
            int resolvedHeight
    ) {
    }
}
