package art.arcane.iris.engine.hydrology;

import java.util.List;

public record HydrologyPlannerSettings(
        int seaLevel,
        Routing routing,
        Surface surface,
        Hydraulics hydraulics,
        Underground underground,
        Outlets outlets,
        Geometry geometry,
        List<DeepFluid> deepFluids
) {
    private static final long PLAN_FORMAT_REVISION = 6L;
    private static final int MAXIMUM_CROSS_TILE_COLOR_PERIOD = 4;

    public HydrologyPlannerSettings {
        if (seaLevel < -2048 || seaLevel > 2048) {
            throw new IllegalArgumentException("seaLevel is outside the supported planning range.");
        }
        if (routing == null || surface == null || hydraulics == null || underground == null
                || outlets == null || geometry == null) {
            throw new IllegalArgumentException("Hydrology planner settings cannot contain null sections.");
        }
        deepFluids = deepFluids == null ? List.of() : List.copyOf(deepFluids);
        int publicationRadius = publicationRadius(routing, surface, underground, outlets, geometry, deepFluids);
        if (crossTileColorPeriod(publicationRadius, routing.tileSize()) > MAXIMUM_CROSS_TILE_COLOR_PERIOD) {
            throw new IllegalArgumentException("Hydrology publication envelope exceeds the bounded cross-tile admission period.");
        }
    }

    public static HydrologyPlannerSettings defaults() {
        Source surfaceSources = new Source(true, 0.5D, 88, 0, 1, 384);
        Source undergroundSources = new Source(true, 0.25D, Integer.MIN_VALUE, 0, 1, 512);
        return new HydrologyPlannerSettings(
                63,
                new Routing(2048, 64, 8192, 8192, 384, 192, 1.5D, 24D, 2D, 0.2D, 1D),
                new Surface(true, surfaceSources, 4, 8, 2, 4, 10, 1.5D, Banks.defaults()),
                new Hydraulics(8),
                new Underground(true, undergroundSources, -48, 72, 3, 8, 1, 3, 6, 14, true),
                new Outlets(
                        true,
                        new Grotto(true, 18, 8, 8, 32768),
                        new Grotto(true, 18, 8, 8, 32768),
                        false,
                        12,
                        64,
                        8,
                        12
                ),
                Geometry.defaults(),
                List.of()
        );
    }

    public long fingerprint() {
        long hash = HydrologyHash.mix(
                0x485944524f4c4f47L,
                PLAN_FORMAT_REVISION,
                routing.hashCode(),
                surface.hashCode(),
                hydraulics.hashCode(),
                underground.hashCode(),
                outlets.hashCode(),
                geometry.hashCode()
        );
        for (DeepFluid deepFluid : deepFluids) {
            hash = HydrologyHash.mix(hash, deepFluid.hashCode(), HydrologyHash.text(deepFluid.id()));
        }
        return hash;
    }

    public int publicationRadius() {
        return publicationRadius(routing, surface, underground, outlets, geometry, deepFluids);
    }

    int crossTileColorPeriod() {
        return crossTileColorPeriod(publicationRadius(), routing.tileSize());
    }

    private static int publicationRadius(
            Routing routing,
            Surface surface,
            Underground underground,
            Outlets outlets,
            Geometry geometry,
            List<DeepFluid> deepFluids
    ) {
        int alignedHalo = Math.floorDiv(
                Math.min(
                        routing.maximumRouteLength(),
                        Math.multiplyExact(routing.sampleSpacing(), 2)
                ),
                routing.sampleSpacing()
        ) * routing.sampleSpacing();
        int radius = 0;
        int routeDisplacement = Math.multiplyExact(routing.sampleSpacing(), 3);
        if (surface.enabled() && surface.sources().enabled()) {
            int blendWidth = surface.banks().maximumBlendWidth();
            int surfaceRadius = (int) StrictMath.ceil(
                    surface.maximumWidth() / 2D + surface.shoreWidth() + blendWidth
            );
            surfaceRadius = Math.max(
                    surfaceRadius,
                    (int) StrictMath.ceil(geometry.drops().basinWidth(
                            geometry.drops().flowWidth(surface.maximumWidth())
                    ) / 2D + surface.shoreWidth() + blendWidth)
            );
            surfaceRadius = Math.max(surfaceRadius, outlets.coastalGrotto().horizontalRadius());
            surfaceRadius = Math.max(surfaceRadius, outlets.inlandGrotto().horizontalRadius());
            radius = Math.max(radius, Math.addExact(alignedHalo, Math.addExact(surfaceRadius, routeDisplacement)));
        }
        if (underground.enabled() && underground.sources().enabled()) {
            int undergroundRadius = (int) StrictMath.ceil(underground.maximumWidth() / 2D);
            undergroundRadius = Math.max(
                    undergroundRadius,
                    (int) StrictMath.ceil(geometry.drops().basinWidth(
                            geometry.drops().flowWidth(underground.maximumWidth())
                    ) / 2D)
            );
            undergroundRadius = Math.max(undergroundRadius, outlets.coastalGrotto().horizontalRadius());
            undergroundRadius = Math.max(undergroundRadius, outlets.inlandGrotto().horizontalRadius());
            radius = Math.max(radius, Math.addExact(alignedHalo, Math.addExact(undergroundRadius, routeDisplacement)));
        }
        for (DeepFluid deepFluid : deepFluids) {
            if (!deepFluid.enabled() || deepFluid.maximumPerTile() == 0) {
                continue;
            }
            int poolReach = deepFluid.containedPools() ? deepFluid.maximumHorizontalRadius() : 0;
            int channelReach = deepFluid.shortChannels()
                    ? Math.addExact(deepFluid.maximumChannelLength(), (int) StrictMath.ceil(deepFluid.channelWidth() / 2D))
                    : 0;
            radius = Math.max(radius, Math.max(poolReach, channelReach));
        }
        return radius;
    }

    private static int crossTileColorPeriod(int publicationRadius, int tileSize) {
        long actionReach = Math.addExact((long) publicationRadius, 1L);
        return Math.toIntExact(Math.addExact(Math.floorDiv(Math.multiplyExact(2L, actionReach), tileSize), 2L));
    }

    public record Routing(
            int tileSize,
            int sampleSpacing,
            int maximumRouteNodes,
            int maximumRouteLength,
            int minimumSurfaceCourseLength,
            int minimumUndergroundCourseLength,
            double valleyPreference,
            double uphillPenalty,
            double slopePenalty,
            double confluenceAttraction,
            double lengthPreference
    ) {
        public Routing {
            if (tileSize < 32 || sampleSpacing < 4 || tileSize % sampleSpacing != 0) {
                throw new IllegalArgumentException("Routing sizes must form an exact bounded tile lattice.");
            }
            int latticeWidth = tileSize / sampleSpacing + 1;
            int halo = Math.min(maximumRouteLength, Math.multiplyExact(sampleSpacing, 2));
            int alignedHalo = Math.floorDiv(halo, sampleSpacing) * sampleSpacing;
            int expandedWidth = latticeWidth + alignedHalo * 2 / sampleSpacing;
            long latticeNodes = (long) expandedWidth * expandedWidth;
            if (maximumRouteNodes < latticeNodes || maximumRouteNodes > 1_000_000 || maximumRouteLength < 1) {
                throw new IllegalArgumentException("maximumRouteNodes must contain the complete lattice and remain bounded.");
            }
            if (minimumSurfaceCourseLength < 0 || minimumSurfaceCourseLength > 32_768
                    || minimumUndergroundCourseLength < 0 || minimumUndergroundCourseLength > 32_768) {
                throw new IllegalArgumentException("Hydrology course lengths are invalid.");
            }
            if (minimumSurfaceCourseLength > maximumRouteLength
                    || minimumUndergroundCourseLength > maximumRouteLength) {
                throw new IllegalArgumentException("Minimum course lengths cannot exceed maximum route length.");
            }
            requireFiniteNonNegative(valleyPreference, "valleyPreference");
            requireFiniteNonNegative(uphillPenalty, "uphillPenalty");
            requireFiniteNonNegative(slopePenalty, "slopePenalty");
            requireFiniteNonNegative(confluenceAttraction, "confluenceAttraction");
            requireFiniteNonNegative(lengthPreference, "lengthPreference");
        }

        // Route refinement is derived from the lattice, never authored.
        public static int refinementSpacing(int sampleSpacing) {
            if (sampleSpacing % 4 == 0) {
                return 4;
            }
            return sampleSpacing % 2 == 0 ? 2 : 1;
        }

        public int refinementSpacing() {
            return refinementSpacing(sampleSpacing);
        }

        public int minimumCourseLength(boolean surface) {
            return surface ? minimumSurfaceCourseLength : minimumUndergroundCourseLength;
        }
    }

    public record Source(
            boolean enabled,
            double density,
            int minimumElevation,
            int minimumPerTile,
            int maximumPerTile,
            int minimumSpacing
    ) {
        public Source {
            if (!Double.isFinite(density) || density < 0D || density > 64D) {
                throw new IllegalArgumentException("Source density must be between 0 and 64 expected sources per tile.");
            }
            if (minimumPerTile < 0 || maximumPerTile < minimumPerTile || maximumPerTile > 4096) {
                throw new IllegalArgumentException("Source tile quotas are invalid.");
            }
            if (minimumSpacing < 0) {
                throw new IllegalArgumentException("Source spacing cannot be negative.");
            }
        }
    }

    public record Surface(
            boolean enabled,
            Source sources,
            int minimumWidth,
            int maximumWidth,
            int minimumDepth,
            int maximumDepth,
            int maximumIncision,
            double shoreWidth,
            Banks banks
    ) {
        public Surface {
            if (sources == null || banks == null) {
                throw new IllegalArgumentException("Surface source and bank settings are required.");
            }
            requireRange(minimumWidth, maximumWidth, 1, 256, "surface width");
            requireRange(minimumDepth, maximumDepth, 1, 64, "surface depth");
            if (maximumIncision < 0 || !Double.isFinite(shoreWidth) || shoreWidth < 0D || shoreWidth > 32D) {
                throw new IllegalArgumentException("Surface incision and shore width are invalid.");
            }
        }
    }

    public record Banks(
            int inset,
            int freeboard,
            double blendSlope,
            int minimumBlendWidth,
            int maximumBlendWidth,
            double roughness,
            int roughnessWavelength,
            int cascadeRun,
            int waterfallMinimumDrop,
            double mouthFlareRatio,
            double springWidthRatio,
            int springLength,
            boolean exposeCutStrata
    ) {
        // Structural invariants only; authoring bounds live in the pack validator.
        public Banks {
            if (inset < 0 || freeboard < 0
                    || !Double.isFinite(blendSlope) || blendSlope <= 0D
                    || minimumBlendWidth < 1 || maximumBlendWidth < minimumBlendWidth
                    || !Double.isFinite(roughness) || roughness < 0D || roughness > 1D
                    || roughnessWavelength < 1
                    || cascadeRun < 1
                    || waterfallMinimumDrop < 1
                    || !Double.isFinite(mouthFlareRatio) || mouthFlareRatio < 1D
                    || !Double.isFinite(springWidthRatio) || springWidthRatio < 1D
                    || springLength < 1) {
                throw new IllegalArgumentException("Surface bank settings are invalid.");
            }
        }

        public static Banks defaults() {
            return new Banks(1, 1, 3D, 4, 32, 0.25D, 16, 2, 6, 1.6D, 2.5D, 24, true);
        }
    }

    // The routing gates read one hydraulic threshold: the drop that makes a reach a waterfall.
    public record Hydraulics(int waterfallMinimumDrop) {
        public Hydraulics {
            if (waterfallMinimumDrop < 1) {
                throw new IllegalArgumentException("waterfallMinimumDrop must be at least one block.");
            }
        }
    }

    public record Underground(
            boolean enabled,
            Source sources,
            int minimumFluidY,
            int maximumFluidY,
            int minimumWidth,
            int maximumWidth,
            int minimumDepth,
            int maximumDepth,
            int minimumHeadroom,
            int maximumHeadroom,
            boolean connectToExistingCaves
    ) {
        public Underground {
            if (sources == null || minimumFluidY > maximumFluidY) {
                throw new IllegalArgumentException("Underground source and height settings are invalid.");
            }
            requireRange(minimumWidth, maximumWidth, 1, 256, "underground width");
            requireRange(minimumDepth, maximumDepth, 1, 64, "underground depth");
            requireRange(minimumHeadroom, maximumHeadroom, 1, 128, "underground headroom");
        }
    }

    public record Outlets(
            boolean oceanEnabled,
            Grotto coastalGrotto,
            Grotto inlandGrotto,
            boolean surfaceSinkholesEnabled,
            int coastalCliffMinimumHeight,
            int mouthLevelingDistance,
            int maximumOceanApron,
            int maximumPerTile
    ) {
        public Outlets {
            if (coastalGrotto == null || inlandGrotto == null) {
                throw new IllegalArgumentException("Coastal and inland grotto settings are required.");
            }
            if (surfaceSinkholesEnabled && !inlandGrotto.enabled()) {
                throw new IllegalArgumentException("Surface sinkholes require inland grotto outlets.");
            }
            if (coastalCliffMinimumHeight < 0 || maximumOceanApron < 0 || maximumOceanApron > 64
                    || mouthLevelingDistance < 0 || maximumPerTile < 1 || maximumPerTile > 256) {
                throw new IllegalArgumentException("Outlet bounds are invalid.");
            }
        }
    }

    public record Grotto(
            boolean enabled,
            int horizontalRadius,
            int verticalRadius,
            int headroom,
            int maximumVolume
    ) {
        public Grotto {
            if (horizontalRadius < 1 || horizontalRadius > 128 || verticalRadius < 1 || verticalRadius > 128
                    || headroom < 1 || headroom > 128 || maximumVolume < 1 || maximumVolume > 1_048_576) {
                throw new IllegalArgumentException("Grotto bounds are invalid.");
            }
        }
    }

    public record Geometry(
            Meanders meanders,
            ChannelShape surface,
            ChannelShape underground,
            ChannelShape grottos,
            Drops drops
    ) {
        public Geometry {
            if (meanders == null || surface == null || underground == null || grottos == null || drops == null) {
                throw new IllegalArgumentException("Hydrology geometry settings cannot contain null sections.");
            }
        }

        public static Geometry defaults() {
            ChannelShape channel = new ChannelShape(2.4D, 0.28D, 0.24D, 11);
            return new Geometry(
                    new Meanders(64, 12, 0.34D, 0.42D, 0.48D, 1, 82D),
                    channel,
                    channel,
                    channel,
                    new Drops(2, 1.4D, 2, 0.45D, 2, 1.8D, 8)
            );
        }
    }

    public record Meanders(
            int primaryWavelength,
            int detailWavelength,
            double primaryStrength,
            double detailStrength,
            double maximumOffsetRatio,
            int smoothingPasses,
            double maximumTurnDegrees
    ) {
        public Meanders {
            if (primaryWavelength < 8 || primaryWavelength > 512
                    || detailWavelength < 4 || detailWavelength > 128
                    || detailWavelength > primaryWavelength
                    || smoothingPasses < 0 || smoothingPasses > 4
                    || !Double.isFinite(primaryStrength) || primaryStrength < 0D || primaryStrength > 2D
                    || !Double.isFinite(detailStrength) || detailStrength < 0D || detailStrength > 2D
                    || !Double.isFinite(maximumOffsetRatio) || maximumOffsetRatio < 0D || maximumOffsetRatio > 1D
                    || !Double.isFinite(maximumTurnDegrees)
                    || maximumTurnDegrees < 10D || maximumTurnDegrees > 150D) {
                throw new IllegalArgumentException("Hydrology meander geometry is invalid.");
            }
        }
    }

    public record ChannelShape(
            double bedRoundness,
            double bedRoughness,
            double wallRoughness,
            int roughnessWavelength
    ) {
        public ChannelShape {
            if (!Double.isFinite(bedRoundness) || bedRoundness < 1D || bedRoundness > 6D
                    || !Double.isFinite(bedRoughness) || bedRoughness < 0D || bedRoughness > 1D
                    || !Double.isFinite(wallRoughness) || wallRoughness < 0D || wallRoughness > 1D
                    || roughnessWavelength < 3 || roughnessWavelength > 128) {
                throw new IllegalArgumentException("Hydrology channel shape is invalid.");
            }
        }
    }

    public record Drops(
            int cascadeRunPerBlock,
            double cascadeExponent,
            int maximumCascadeStep,
            double flowWidthRatio,
            int maximumFlowDepth,
            double basinWidthRatio,
            int maximumBasinDepth
    ) {
        public Drops {
            if (cascadeRunPerBlock < 1 || cascadeRunPerBlock > 16
                    || !Double.isFinite(cascadeExponent) || cascadeExponent < 0.25D || cascadeExponent > 6D
                    || maximumCascadeStep < 1 || maximumCascadeStep > 4
                    || !Double.isFinite(flowWidthRatio) || flowWidthRatio < 0.25D || flowWidthRatio > 1D
                    || maximumFlowDepth < 1 || maximumFlowDepth > 16
                    || !Double.isFinite(basinWidthRatio) || basinWidthRatio < 1D || basinWidthRatio > 4D
                    || maximumBasinDepth < maximumFlowDepth || maximumBasinDepth > 32) {
                throw new IllegalArgumentException("Hydrology drop geometry is invalid.");
            }
        }

        public int flowWidth(int channelWidth) {
            return Math.max(1, Math.min(channelWidth, (int) StrictMath.ceil(channelWidth * flowWidthRatio)));
        }

        public int stepLimit(HydrologyFeatureType type) {
            return type.isSurface() ? 1 : maximumCascadeStep;
        }

        public int flowDepth(int channelDepth) {
            return Math.max(1, Math.min(channelDepth, maximumFlowDepth));
        }

        public int basinWidth(int flowWidth) {
            return Math.max(flowWidth, (int) StrictMath.ceil(flowWidth * basinWidthRatio));
        }

        public int basinDepth(int flowDepth, int drop) {
            int erodedDepth = Math.addExact(flowDepth, Math.max(1, (int) StrictMath.ceil(StrictMath.sqrt(drop))));
            return Math.max(flowDepth, Math.min(maximumBasinDepth, erodedDepth));
        }
    }

    public record DeepFluid(
            String id,
            boolean enabled,
            double density,
            int spacing,
            int minimumY,
            int maximumY,
            int minimumHorizontalRadius,
            int maximumHorizontalRadius,
            int minimumVerticalRadius,
            int maximumVerticalRadius,
            int minimumChannelLength,
            int maximumChannelLength,
            int channelWidth,
            int channelDepth,
            int headroom,
            int maximumVolume,
            int maximumPerTile,
            boolean containedPools,
            boolean shortChannels
    ) {
        public DeepFluid {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Deep-fluid id must not be blank.");
            }
            id = id.trim();
            if (!Double.isFinite(density) || density < 0D || density > 64D || spacing < 16 || minimumY > maximumY) {
                throw new IllegalArgumentException("Deep-fluid density or height range is invalid.");
            }
            requireRange(minimumHorizontalRadius, maximumHorizontalRadius, 1, 128, "deep-fluid horizontal radius");
            requireRange(minimumVerticalRadius, maximumVerticalRadius, 1, 128, "deep-fluid vertical radius");
            requireRange(minimumChannelLength, maximumChannelLength, 0, 32_768, "deep-fluid channel length");
            if (channelWidth < 1 || channelWidth > 256 || channelDepth < 1 || channelDepth > 64 || headroom < 1
                    || maximumVolume < 64 || maximumVolume > 1_048_576
                    || maximumPerTile < 0 || maximumPerTile > 1024) {
                throw new IllegalArgumentException("Deep-fluid channel bounds are invalid.");
            }
            long poolEnvelope = ellipsoidVolume(
                    maximumHorizontalRadius,
                    Math.max(channelDepth, maximumVerticalRadius),
                    headroom
            );
            if (enabled && containedPools && poolEnvelope > maximumVolume) {
                throw new IllegalArgumentException("Deep-fluid maximumVolume cannot contain its configured pool ellipsoid.");
            }
            if (enabled && !containedPools && !shortChannels) {
                throw new IllegalArgumentException("An enabled deep fluid requires a pool or channel.");
            }
            if (enabled && shortChannels && minimumChannelLength < 1) {
                throw new IllegalArgumentException("Enabled deep-fluid channels require a positive minimum length.");
            }
        }
    }

    private static void requireRange(int minimum, int maximum, int lower, int upper, String name) {
        if (minimum < lower || maximum < minimum || maximum > upper) {
            throw new IllegalArgumentException(name + " range is invalid.");
        }
    }

    private static void requireFiniteNonNegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0D) {
            throw new IllegalArgumentException(name + " must be finite and non-negative.");
        }
    }

    static long ellipsoidVolume(int radius, int lowerExtent, int upperExtent) {
        long volume = 0L;
        for (int deltaZ = -radius; deltaZ <= radius; deltaZ++) {
            for (int deltaX = -radius; deltaX <= radius; deltaX++) {
                double distance = StrictMath.hypot(deltaX, deltaZ);
                if (distance > radius + 0.25D) {
                    continue;
                }
                double normalized = Math.min(1D, distance / Math.max(1D, radius));
                double scale = StrictMath.sqrt(Math.max(0D, 1D - normalized * normalized));
                int lower = (int) StrictMath.ceil(lowerExtent * scale);
                int upper = (int) StrictMath.floor(upperExtent * scale);
                volume += lower + upper + 1L;
            }
        }
        return volume;
    }
}
