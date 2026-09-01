package art.arcane.iris.engine.hydrology.surface;

import art.arcane.iris.engine.hydrology.HydrologyGeometrySampler;
import art.arcane.iris.engine.hydrology.HydrologyPlannerSettings;
import art.arcane.iris.engine.hydrology.HydrologyTerrainSample;
import art.arcane.iris.engine.hydrology.HydrologyTerrainSampler;

import java.util.Objects;

public final class ChannelProfileBuilder {
    private static final int SMOOTHING_RADIUS = 16;
    private static final int HEADWATER_TAPER = 48;
    private static final int MOUTH_FLARE = 32;

    private final HydrologyPlannerSettings.Surface surface;
    private final HydrologyTerrainSampler sampler;
    private final HydrologyGeometrySampler geometry;

    public ChannelProfileBuilder(
            HydrologyPlannerSettings.Surface surface,
            HydrologyTerrainSampler sampler,
            HydrologyGeometrySampler geometry
    ) {
        this.surface = Objects.requireNonNull(surface, "surface");
        this.sampler = Objects.requireNonNull(sampler, "sampler");
        this.geometry = Objects.requireNonNull(geometry, "geometry");
    }

    public ChannelProfile build(
            SurfaceCenterline centerline,
            String profileKey,
            boolean directOcean
    ) {
        int count = centerline.size();
        double[] width = new double[count];
        double[] depth = new double[count];
        double[] bank = new double[count];
        for (int station = 0; station < count; station++) {
            int stationX = centerline.x()[station];
            int stationZ = centerline.z()[station];
            int sampledWidth = geometry.sample(
                    HydrologyGeometrySampler.Field.SURFACE_WIDTH,
                    profileKey,
                    stationX,
                    stationZ,
                    0L,
                    surface.minimumWidth(),
                    surface.maximumWidth()
            );
            int sampledDepth = geometry.sample(
                    HydrologyGeometrySampler.Field.SURFACE_DEPTH,
                    profileKey,
                    stationX,
                    stationZ,
                    0L,
                    surface.minimumDepth(),
                    surface.maximumDepth()
            );
            HydrologyTerrainSample terrain = sampler.sample(stationX, stationZ);
            double widthMultiplier = terrain == null ? 1D : terrain.widthMultiplier();
            double depthMultiplier = terrain == null ? 1D : terrain.depthMultiplier();
            bank[station] = terrain == null ? 1D : terrain.bankMultiplier();
            width[station] = clamp(sampledWidth * widthMultiplier, surface.minimumWidth(), surface.maximumWidth() * 2D);
            depth[station] = clamp(sampledDepth * depthMultiplier, 1D, surface.maximumDepth() * 2D);
        }
        double[] smoothWidth = smooth(width);
        double[] smoothDepth = smooth(depth);
        int taper = Math.min(HEADWATER_TAPER, count / 2);
        for (int station = 0; station < taper; station++) {
            double progress = SurfaceNoise.smoothStep(station / (double) taper);
            smoothWidth[station] = 1D + (smoothWidth[station] - 1D) * progress;
            smoothDepth[station] = 1D + (smoothDepth[station] - 1D) * progress;
        }
        if (directOcean) {
            int flare = Math.min(MOUTH_FLARE, count / 3);
            int flareStart = count - flare;
            for (int station = Math.max(0, flareStart); station < count; station++) {
                double progress = SurfaceNoise.smoothStep((station - flareStart) / (double) Math.max(1, flare - 1));
                smoothWidth[station] *= 1D + (surface.banks().mouthFlareRatio() - 1D) * progress;
            }
        }
        double[] halfWidth = new double[count];
        for (int station = 0; station < count; station++) {
            halfWidth[station] = Math.max(0.5D, smoothWidth[station] / 2D);
            smoothDepth[station] = Math.max(1D, smoothDepth[station]);
        }
        return new ChannelProfile(halfWidth, smoothDepth, bank);
    }

    static double[] smooth(double[] values) {
        int count = values.length;
        double[] smoothed = new double[count];
        for (int station = 0; station < count; station++) {
            double total = 0D;
            double weight = 0D;
            for (int offset = -SMOOTHING_RADIUS; offset <= SMOOTHING_RADIUS; offset++) {
                int index = station + offset;
                if (index < 0 || index >= count) {
                    continue;
                }
                double factor = SMOOTHING_RADIUS + 1 - Math.abs(offset);
                total += values[index] * factor;
                weight += factor;
            }
            smoothed[station] = total / weight;
        }
        return smoothed;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
