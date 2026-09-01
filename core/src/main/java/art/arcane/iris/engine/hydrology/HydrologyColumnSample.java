package art.arcane.iris.engine.hydrology;

import art.arcane.iris.engine.hydrology.cave.HydrologyCaveAction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record HydrologyColumnSample(
        int x,
        int z,
        int naturalHeight,
        int seaLevel,
        boolean ocean,
        String parentBiomeKey,
        List<HydrologyColumnLayer> layers
) {
    private static final Comparator<HydrologyColumnLayer> LAYER_ORDER = Comparator
            .comparingInt((HydrologyColumnLayer layer) -> layer.feature().type().renderPriority())
            .thenComparing(Comparator.comparingInt(HydrologyColumnLayer::fluidHeadY).reversed())
            .thenComparingLong((HydrologyColumnLayer layer) -> layer.feature().id());
    private static final Comparator<HydrologyColumnLayer> SURFACE_LAYER_ORDER = Comparator
            .comparingInt(HydrologyColumnSample::surfaceRolePriority)
            .thenComparingInt((HydrologyColumnLayer layer) -> layer.feature().type().renderPriority())
            .thenComparing(Comparator.comparingInt(HydrologyColumnLayer::fluidHeadY).reversed())
            .thenComparingInt(HydrologyColumnLayer::bedY)
            .thenComparingLong((HydrologyColumnLayer layer) -> layer.feature().id());

    public HydrologyColumnSample {
        if (parentBiomeKey == null || parentBiomeKey.isBlank()) {
            throw new IllegalArgumentException("parentBiomeKey must not be blank.");
        }
        parentBiomeKey = parentBiomeKey.trim();
        Objects.requireNonNull(layers, "layers");
        if (layers.size() < 2) {
            layers = List.copyOf(layers);
        } else {
            ArrayList<HydrologyColumnLayer> ordered = new ArrayList<>(layers);
            ordered.sort(LAYER_ORDER);
            layers = List.copyOf(ordered);
        }
        if (ocean) {
            for (HydrologyColumnLayer layer : layers) {
                if (layer.terrainOwned() || layer.fluidOwned() || layer.grading() || layer.shore()) {
                    throw new IllegalArgumentException("Ocean columns cannot contain river-owned writes or grading.");
                }
                if (layer.fluidHeadY() > seaLevel) {
                    throw new IllegalArgumentException("Ocean column fluid cannot be elevated above sea level.");
                }
            }
        }
        if (naturalHeight <= seaLevel) {
            for (HydrologyColumnLayer layer : layers) {
                if (layer.feature().type().isSurface()
                        && !layer.oceanApron()
                        && (layer.terrainOwned() || layer.fluidOwned() || layer.grading() || layer.shore())) {
                    throw new IllegalArgumentException(
                            "Naturally submerged columns cannot contain owned surface hydrology writes."
                    );
                }
            }
        }
        for (HydrologyColumnLayer layer : layers) {
            if (layer.feature().type().isSurface()
                    && !layer.oceanApron()
                    && layer.channel()
                    && layer.fluidOwned()
                    && layer.fluidHeadY() >= naturalHeight) {
                throw new IllegalArgumentException("Owned surface fluid must remain below natural terrain.");
            }
        }
    }

    public boolean present() {
        return !layers.isEmpty();
    }

    public boolean hasFeature(HydrologyFeatureType type) {
        Objects.requireNonNull(type, "type");
        for (HydrologyColumnLayer layer : layers) {
            if (layer.feature().type() == type) {
                return true;
            }
        }
        return false;
    }

    public boolean hasConnectedFluid() {
        for (HydrologyColumnLayer layer : layers) {
            if (layer.connectedFluid()) {
                return true;
            }
        }
        return false;
    }

    public Optional<HydrologyColumnLayer> primaryLayer() {
        return layers.isEmpty() ? Optional.empty() : Optional.of(layers.getFirst());
    }

    public Optional<HydrologyColumnLayer> primarySurfaceLayer() {
        return selectSurfaceLayer(false);
    }

    public Optional<HydrologyColumnLayer> primarySurfaceFluidLayer() {
        return selectSurfaceLayer(true);
    }

    public Optional<SurfacePublicationCell> surfacePublicationCellAt(int y) {
        if (y <= terrainHeight()) {
            return Optional.empty();
        }
        SurfacePublicationCell selected = null;
        for (HydrologyColumnLayer layer : layers) {
            if (!layer.publishesSurfaceFluid() || y <= layer.bedY() || y > layer.fluidHeadY()) {
                continue;
            }
            HydrologyCaveAction action = layer.fallingFluid() && y < layer.fluidHeadY()
                    ? HydrologyCaveAction.FALLING_FLUID
                    : HydrologyCaveAction.WET_SOURCE;
            SurfacePublicationCell candidate = new SurfacePublicationCell(layer, action);
            if (selected == null || surfaceActionPriority(action) < surfaceActionPriority(selected.action())) {
                selected = candidate;
            }
        }
        return Optional.ofNullable(selected);
    }

    public int terrainHeight() {
        Optional<HydrologyColumnLayer> layer = primarySurfaceLayer();
        if (layer.isEmpty()) {
            return naturalHeight;
        }
        HydrologyColumnLayer primary = layer.get();
        if (primary.channel()) {
            return primary.bedY();
        }
        int terrainHeight = primary.bedY();
        for (HydrologyColumnLayer candidate : layers) {
            if (candidate.feature().type().isSurface()
                    && candidate.terrainOwned()
                    && !candidate.channel()) {
                terrainHeight = Math.max(terrainHeight, candidate.bedY());
            }
        }
        return terrainHeight;
    }

    public HydrologyRenderSample renderSample() {
        ArrayList<HydrologyFeatureRef> features = new ArrayList<>(layers.size());
        for (HydrologyColumnLayer layer : layers) {
            features.add(layer.feature());
        }
        return new HydrologyRenderSample(x, z, features);
    }

    private Optional<HydrologyColumnLayer> selectSurfaceLayer(boolean fluidOnly) {
        HydrologyColumnLayer selected = null;
        for (HydrologyColumnLayer layer : layers) {
            if (layer.oceanApron() || !layer.feature().type().isSurface()) {
                continue;
            }
            if (fluidOnly) {
                if (!layer.channel() || !layer.connectedFluid() || !layer.fluidOwned()) {
                    continue;
                }
            } else if (!layer.terrainOwned()) {
                continue;
            }
            if (selected == null || SURFACE_LAYER_ORDER.compare(layer, selected) < 0) {
                selected = layer;
            }
        }
        return Optional.ofNullable(selected);
    }

    private static int surfaceRolePriority(HydrologyColumnLayer layer) {
        if (layer.channel()) {
            return 0;
        }
        return layer.shore() ? 1 : 2;
    }

    private static int surfaceActionPriority(HydrologyCaveAction action) {
        return action == HydrologyCaveAction.WET_SOURCE ? 0 : 1;
    }

    public record SurfacePublicationCell(
            HydrologyColumnLayer layer,
            HydrologyCaveAction action
    ) {
        public SurfacePublicationCell {
            Objects.requireNonNull(layer, "layer");
            if (action != HydrologyCaveAction.WET_SOURCE
                    && action != HydrologyCaveAction.FALLING_FLUID) {
                throw new IllegalArgumentException("Surface publication cells must contain fluid.");
            }
        }
    }
}
