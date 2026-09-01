package art.arcane.iris.engine.hydrology.surface;

import art.arcane.iris.engine.hydrology.HydrologyTerrainSample;

import java.util.Objects;

public record SurfaceColumn(
        int x,
        int z,
        HydrologyTerrainSample terrain,
        int station,
        SurfaceRole role,
        int height,
        int headY,
        boolean apron
) {
    public SurfaceColumn {
        Objects.requireNonNull(terrain, "terrain");
        Objects.requireNonNull(role, "role");
    }

    public SurfaceColumn withHeight(int replacementHeight) {
        return new SurfaceColumn(x, z, terrain, station, role, replacementHeight, replacementHeight, apron);
    }
}
